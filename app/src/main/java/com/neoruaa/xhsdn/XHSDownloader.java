package com.neoruaa.xhsdn;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class XHSDownloader {
    private static final String TAG = "XHSDownloader";
    private static final String USER_AGENT_XHS_ANDROID = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 xiaohongshu";
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_BACKOFF_MS = 350L;
    private Context context;
    private OkHttpClient httpClient;
    private FileDownloader fileDownloader;
    private List<String> downloadUrls;
    private boolean cacheDestinationMode = false;
    private File cacheDestinationDir;
    // Thread-safe: populated concurrently from the download executor thread pool.
    private final List<CachedMediaFile> cachedMediaFiles = Collections.synchronizedList(new ArrayList<>());
    
    // Regex patterns for URL matching
    private static final Pattern XHS_LINK_PATTERN = Pattern.compile("(?:https?://)?www\\.xiaohongshu\\.com/explore/\\S+");
    private static final Pattern XHS_USER_PATTERN = Pattern.compile("(?:https?://)?www\\.xiaohongshu\\.com/user/profile/[a-z0-9]+/\\S+");
    private static final Pattern XHS_SHARE_PATTERN = Pattern.compile("(?:https?://)?www\\.xiaohongshu\\.com/discovery/item/\\S+");
    private static final Pattern XHS_SHORT_PATTERN = Pattern.compile("(?:https?://)?xhslink\\.(?:com|cn)/[^\\s\\\"<>\\\\\\^`{|}，。；！？、【】《》]+");
    
    private DownloadCallback downloadCallback;
    // Map to store the relationship between transformed URLs and original URLs for fallback
    private java.util.Map<String, String> urlMapping = new java.util.HashMap<>();
    
    // Track successful downloads for the overall download result.
    // Atomic because it is incremented from concurrent download callbacks.
    private final AtomicInteger successfulDownloads = new AtomicInteger(0);

    // Store live photo pairs to distinguish them from regular videos
    private List<LivePhotoPair> livePhotoPairs = new ArrayList<>();
    private NoteMetadata currentNoteMetadata;
    private boolean customNamingEnabled = false;
    private String customFormatTemplate;
    private String sessionTimestamp;
    private long sessionDownloadEpochSeconds;
    private static final java.util.regex.Pattern NAMING_PLACEHOLDER_PATTERN = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
    private static final long MIN_VALID_EPOCH_MS = 946684800000L; // 2000-01-01

    // Flag to track if videos have been detected in the current download
    private boolean videosDetected = false;
    // Flag to track if video warning has already been shown to avoid repeated warnings
    private boolean videoWarningShown = true;
    // Flag to control if download should stop when video is detected
    private volatile boolean shouldStopOnVideo = false;
    // Flag to indicate if download should be stopped
    private volatile boolean shouldStopDownload = false;
    private volatile okhttp3.Call activeCall;

    public XHSDownloader(Context context) {
        this(context, null);
    }

    public XHSDownloader(Context context, DownloadCallback callback) {
        this.context = context;
        this.httpClient = FileDownloader.getSharedHttpClient();
        this.downloadUrls = new ArrayList<>();
        // Wrap the original callback to track successful downloads
        if (callback != null) {
            this.downloadCallback = new DownloadCallback() {
                @Override
                public void onFileDownloaded(String filePath) {
                    successfulDownloads.incrementAndGet(); // Increment counter when a file is successfully downloaded
                    callback.onFileDownloaded(filePath);
                }

                @Override
                public void onDownloadError(String status, String originalUrl) {
                    callback.onDownloadError(status, originalUrl);
                }

                @Override
                public void onDownloadProgress(String status) {
                    callback.onDownloadProgress(status);
                }

                @Override
                public void onDownloadProgressUpdate(long downloaded, long total) {
                    callback.onDownloadProgressUpdate(downloaded, total);
                }

                @Override
                public void onVideoDetected() {
                    callback.onVideoDetected();
                }

                @Override
                public boolean isCancelled() {
                    return callback.isCancelled();
                }
            };
        } else {
            this.downloadCallback = callback;
        }
        this.fileDownloader = new FileDownloader(this.context, this.downloadCallback);
    }

    public static class CachedMediaFile {
        public final String path;
        public final String displayName;

        public CachedMediaFile(String path, String displayName) {
            this.path = path;
            this.displayName = displayName;
        }
    }

    public static class SelectiveDownloadResult {
        public final boolean success;
        public final String noteUrl;
        public final List<CachedMediaFile> files;

        public SelectiveDownloadResult(boolean success, String noteUrl, List<CachedMediaFile> files) {
            this.success = success;
            this.noteUrl = noteUrl;
            this.files = new ArrayList<>(files);
        }
    }
    
    public boolean downloadFile(String url, String filename) {
        String timestamp = new java.text.SimpleDateFormat("yyMMdd", java.util.Locale.getDefault()).format(new java.util.Date());
        return downloadFileWithRetries(url, filename, timestamp);
    }

    public boolean downloadFile(String url, String filename, String timestamp) {
        return downloadFileWithRetries(url, filename, timestamp);
    }
    
    public boolean downloadContent(String inputUrl) {
        // Reset successful downloads counter for this download session
        this.successfulDownloads.set(0);
        boolean hasErrors = false; // Track if any errors occurred
        boolean hasContent = false; // Track if we found any content to download
        try {
            // Check if download should stop
            if (shouldStop()) {
                Log.d(TAG, "Download stopped by user request");
                return false;
            }

            // Extract all valid XHS URLs from the input
            List<String> urls = extractLinks(inputUrl);

            if (urls.isEmpty()) {
                Log.e(TAG, "No valid XHS URLs found");
                return false;
            }

            Log.d(TAG, "Found " + urls.size() + " XHS URLs to process");

            java.util.Date currentDate = new java.util.Date();
            String sessionTimestamp = new java.text.SimpleDateFormat("yyMMdd", java.util.Locale.getDefault()).format(currentDate);
            this.sessionTimestamp = sessionTimestamp;
            this.sessionDownloadEpochSeconds = currentDate.getTime() / 1000L;
            this.customNamingEnabled = shouldUseCustomNamingFormat();
            this.customFormatTemplate = getCustomNamingTemplate();

            for (String url : urls) {
                // Check if download should stop
                try {
                    checkForStop();
                } catch (InterruptedException e) {
                    Log.d(TAG, "Download stopped by user request during URL processing");
                    return false;
                }

                // Get the post ID from the URL
                String postId = extractPostId(url);

                if (postId != null) {
                    this.currentNoteMetadata = null;
                    // Fetch the post details
                    String postDetails = fetchPostDetails(url);


                    // 将postDetails保存到download目录下的postId.txt文件中
//                    if (postDetails != null && !postDetails.isEmpty()) {
//                        // 获取应用的外部存储目录
//                        File externalStorageDir = context.getExternalFilesDir(null);
//                        if (externalStorageDir != null) {
//                            // 构建目标目录路径
//                            File downloadDir = new File(externalStorageDir.getParentFile(), "Download");
//                            // 创建目录（如果不存在）
//                            if (!downloadDir.exists()) {
//                                downloadDir.mkdirs();
//                            }
//
//                            // 创建文件对象
//                            File postDetailsFile = new File(downloadDir, postId + ".txt");
//
//                            // 写入文件
//                            try {
//                                java.io.FileWriter writer = new java.io.FileWriter(postDetailsFile);
//                                writer.write(postDetails);
//                                writer.close();
//                                Log.d(TAG, "Saved post details to: " + postDetailsFile.getAbsolutePath());
//                            } catch (java.io.IOException e) {
//                                Log.e(TAG, "Failed to save post details to file: " + e.getMessage());
//                            }
//                        }
//                    }
                    
                    if (postDetails != null) {
                        // Parse the post details to extract media URLs
                        List<String> mediaUrls = parsePostDetails(postDetails);
                        
                        if (!mediaUrls.isEmpty()) {
                            hasContent = true; // We found media to download
                            Log.d(TAG, "Found " + mediaUrls.size() + " media URLs in post: " + postId);

                            // Check if download should stop
                            try {
                                checkForStop();
                            } catch (InterruptedException e) {
                                Log.d(TAG, "Download stopped by user request after parsing media URLs");
                                return false;
                            }

                            // Check if we should create live photos
                            boolean createLivePhotos = shouldCreateLivePhotos();

                            // Separate images and videos for potential live photo creation
                            List<String> imageUrls = new ArrayList<>();
                            List<String> videoUrls = new ArrayList<>();

                            for (String mediaUrl : mediaUrls) {
                                if (isVideoUrl(mediaUrl)) {
                                    videoUrls.add(mediaUrl);
                                } else {
                                    imageUrls.add(mediaUrl);
                                }
                            }
                            
                            boolean postHasErrors = false;
                            
                            // Check if we should create live photos
                            // Only create live photos if the setting is enabled AND we have both images and videos
                            // (the createLivePhotos method will handle the actual pairing logic)
                            if (createLivePhotos && imageUrls.size() > 0 && videoUrls.size() > 0) {
                                // Create live photos for image-video pairs using the original mediaUrls list
                                // which has the correct order from parsePostDetails
                                Log.d(TAG, "Creating live photos for post: " + postId);
                                postHasErrors = createLivePhotos(postId, mediaUrls, sessionTimestamp);
                                if (postHasErrors) {
                                    hasErrors = true;
                                }
                            } else {
                                // Download files separately as before
                                List<String> allMediaUrls = new ArrayList<>();
                                allMediaUrls.addAll(imageUrls);
                                allMediaUrls.addAll(videoUrls);
                                
                                // Download each media file with unique names using concurrent threads for better performance
                                
                                // Use executor service for concurrent downloads if multiple files exist
                                if (allMediaUrls.size() > 1) {
                                    // For posts with multiple files, use concurrent downloads
                                    ExecutorService executor = Executors.newFixedThreadPool(Math.min(allMediaUrls.size(), 4)); // Max 4 concurrent downloads
                                    List<Future<Boolean>> futures = new ArrayList<>();
                                    
                                    for (int i = 0; i < allMediaUrls.size(); i++) {
                                        final int index = i;
                                        final String mediaUrl = allMediaUrls.get(i);
                                        Future<Boolean> future = executor.submit(() -> {
                                            String baseFileName = buildFileBaseName(postId, index + 1);

                                            // Determine file extension based on URL content
                                            String fileExtension = determineFileExtension(mediaUrl);
                                            String fileNameWithExtension = baseFileName + "." + fileExtension;

                                            // Use the session timestamp to maintain consistency across the download session
                                            return downloadFile(mediaUrl, fileNameWithExtension, sessionTimestamp);
                                        });
                                        futures.add(future);
                                    }
                                    
                                    // Wait for all downloads to complete and collect results
                                    for (int i = 0; i < futures.size(); i++) {
                                        // Check if download should stop
                                        try {
                                            checkForStop();
                                        } catch (InterruptedException e) {
                                            Log.d(TAG, "Download stopped by user request during concurrent download");
                                            executor.shutdownNow(); // Stop all running tasks
                                            return false;
                                        }

                                        try {
                                            boolean success = futures.get(i).get();
                                            String mediaUrl = allMediaUrls.get(i);
                                            if (!success) {
                                                Log.e(TAG, "Failed to download: " + mediaUrl);
                                                postHasErrors = true;
                                                hasErrors = true;
                                            } else {
                                                Log.d(TAG, "Successfully downloaded: " + mediaUrl);
                                            }
                                        } catch (Exception e) {
                                            String mediaUrl = allMediaUrls.get(i);
                                            Log.e(TAG, "Exception during concurrent download: " + e.getMessage());
                                            if (downloadCallback != null) {
                                                String originalUrl = urlMapping.get(mediaUrl);
                                                if (originalUrl != null) {
                                                    downloadCallback.onDownloadError("Exception downloading: " + mediaUrl, originalUrl);
                                                } else {
                                                    downloadCallback.onDownloadError("Exception downloading: " + mediaUrl, mediaUrl);
                                                }
                                            }
                                            postHasErrors = true;
                                            hasErrors = true;
                                        }
                                    }
                                    
                                    executor.shutdown();
                                } else {
                                    // Single file download - keep existing behavior
                                    for (int i = 0; i < allMediaUrls.size(); i++) {
                                        // Check if download should stop
                                        try {
                                            checkForStop();
                                        } catch (InterruptedException e) {
                                            Log.d(TAG, "Download stopped by user request during single file download");
                                            return false;
                                        }

                                        String mediaUrl = allMediaUrls.get(i);
                                        String baseFileName = buildFileBaseName(postId, i + 1);

                                        // Determine file extension based on URL content
                                        String fileExtension = determineFileExtension(mediaUrl);
                                        String fileNameWithExtension = baseFileName + "." + fileExtension;

                                        boolean success = downloadFile(mediaUrl, fileNameWithExtension, sessionTimestamp);
                                        if (!success) {
                                            Log.e(TAG, "Failed to download: " + mediaUrl);
                                            postHasErrors = true;
                                            hasErrors = true;
                                        } else {
                                            Log.d(TAG, "Successfully downloaded: " + mediaUrl);
                                        }
                                    }
                                }
                            }
                            
                            // If the post had download errors, consider it a partial failure
                            if (postHasErrors) {
                                hasErrors = true;
                            }
                        } else {
                            Log.e(TAG, "No media URLs found in post: " + url);
                            // Notify the callback about this issue
                            if (downloadCallback != null) {
                                downloadCallback.onDownloadError("No media URLs found in post: " + postId, url);
                                // Show web crawl option if JSON parsing failed
                                if (context instanceof MainActivity) {
                                    ((MainActivity) context).showWebCrawlOption();
                                }
                            }
                            hasErrors = true; // Consider this an error condition
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch post details for: " + url);
                        // Notify the callback about this issue
                        if (downloadCallback != null) {
                            downloadCallback.onDownloadError("Failed to fetch post details for: " + url, url);
                            // Show web crawl option if we couldn't fetch post details
                            if (context instanceof MainActivity) {
                                ((MainActivity) context).showWebCrawlOption();
                            }
                        }
                        hasErrors = true;
                    }
                } else {
                    Log.e(TAG, "Could not extract post ID from URL: " + url);
                    // Notify the callback about this issue
                    if (downloadCallback != null) {
                        downloadCallback.onDownloadError("Could not extract post ID from URL: " + url, url);
                        // Show web crawl option if we couldn't extract post ID
                        if (context instanceof MainActivity) {
                            ((MainActivity) context).showWebCrawlOption();
                        }
                    }
                    hasErrors = true;
                }
            }
            
            // Clear state variables to prevent issues in subsequent downloads
            downloadUrls.clear();
            urlMapping.clear();
            
            // Return true if we successfully downloaded at least one file, even if some had errors
            // For the overall process, return success if successfulDownloads > 0
            int successCount = successfulDownloads.get();
            boolean overallSuccess = successCount > 0;
            
            Log.d(TAG, "Download process completed with " + successCount + " successful downloads, hasErrors: " + hasErrors);
            
            return overallSuccess;
        } catch (Exception e) {
            Log.e(TAG, "Error in downloadContent: " + e.getMessage());
            e.printStackTrace();
            // Show web crawl option if there's a general error
            if (context instanceof MainActivity) {
                ((MainActivity) context).showWebCrawlOption();
            }
            // Clear state variables in case of exception as well
            downloadUrls.clear();
            urlMapping.clear();
            return false;
        }
    }

    public SelectiveDownloadResult downloadContentToCache(String inputUrl, File cacheDir) {
        cacheDestinationMode = true;
        cacheDestinationDir = cacheDir;
        cachedMediaFiles.clear();
        if (cacheDestinationDir != null && !cacheDestinationDir.exists()) {
            cacheDestinationDir.mkdirs();
        }
        try {
            boolean success = downloadContent(inputUrl);
            return new SelectiveDownloadResult(success, inputUrl, cachedMediaFiles);
        } finally {
            cacheDestinationMode = false;
            cacheDestinationDir = null;
        }
    }

    private boolean downloadFileWithRetries(String mediaUrl, String filename, String timestamp) {
        String originalUrl = urlMapping.get(mediaUrl);
        if (TextUtils.isEmpty(originalUrl)) {
            originalUrl = mediaUrl;
        }

        List<String> candidateUrls = buildDownloadCandidateUrls(mediaUrl, originalUrl);

        for (String candidateUrl : candidateUrls) {
            for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
                try {
                    checkForStop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }

                boolean success;
                if (cacheDestinationMode) {
                    File cachedFile = fileDownloader.downloadFileToDirectory(candidateUrl, filename, timestamp, cacheDestinationDir);
                    success = cachedFile != null && cachedFile.exists();
                    if (success) {
                        cachedMediaFiles.add(new CachedMediaFile(cachedFile.getAbsolutePath(), cachedFile.getName()));
                    }
                } else {
                    success = fileDownloader.downloadFile(candidateUrl, filename, timestamp, false);
                }
                if (success) {
                    if (!candidateUrl.equals(mediaUrl)) {
                        Log.d(TAG, "Download succeeded via fallback URL: " + candidateUrl);
                    }
                    return true;
                }

                Log.w(TAG, "Download attempt " + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS + " failed for " + candidateUrl);
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(DOWNLOAD_RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        if (downloadCallback != null) {
            downloadCallback.onDownloadError(
                    "Failed to download after " + (candidateUrls.size() * MAX_DOWNLOAD_ATTEMPTS) + " attempts",
                    originalUrl
            );
        }
        return false;
    }

    private List<String> buildDownloadCandidateUrls(String mediaUrl, String originalUrl) {
        Set<String> candidateUrls = new LinkedHashSet<>();
        String transformedMediaUrl = transformXhsCdnUrl(mediaUrl);
        if (isLikelyMediaDownloadUrl(transformedMediaUrl) && !transformedMediaUrl.equals(mediaUrl)) {
            addDownloadCandidate(candidateUrls, transformedMediaUrl);
        }
        if (isLikelyMediaDownloadUrl(mediaUrl)) {
            addDownloadCandidate(candidateUrls, mediaUrl);
        }
        if (isLikelyMediaDownloadUrl(originalUrl)) {
            addDownloadCandidate(candidateUrls, originalUrl);
            String transformedOriginalUrl = transformXhsCdnUrl(originalUrl);
            if (isLikelyMediaDownloadUrl(transformedOriginalUrl)) {
                addDownloadCandidate(candidateUrls, transformedOriginalUrl);
            }
        }
        return new ArrayList<>(candidateUrls);
    }

    private void addDownloadCandidate(Set<String> candidateUrls, String candidateUrl) {
        if (!TextUtils.isEmpty(candidateUrl)) {
            candidateUrls.add(candidateUrl);
        }
    }

    private boolean isLikelyMediaDownloadUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }

        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.contains("xhscdn.com")
                || normalized.contains("ci.xiaohongshu.com")
                || normalized.contains(".jpg")
                || normalized.contains(".jpeg")
                || normalized.contains(".png")
                || normalized.contains(".webp")
                || normalized.contains(".gif")
                || normalized.contains(".mp4")
                || normalized.contains(".mov")
                || normalized.contains("imageview2")
                || normalized.contains("sns-video")
                || normalized.contains("/spectrum/");
    }
    
    /**
     * Determine the appropriate file extension based on the URL
     * @param url The URL to check
     * @return The appropriate file extension (png, jpg, mp4, etc.)
     */
    private String determineFileExtension(String url) {
        if (url != null) {
            // Check for common image extensions in the URL
            if (url.toLowerCase().contains(".jpg") || url.toLowerCase().contains(".jpeg")) {
                return "jpg";
            } else if (url.toLowerCase().contains(".png")) {
                return "png";
            } else if (url.toLowerCase().contains(".gif")) {
                return "gif";
            } else if (url.toLowerCase().contains(".webp")) {
                return "webp";
            } else if (url.toLowerCase().contains(".mp4") || url.contains("video") || 
                       url.contains("masterUrl") || url.contains("stream")) {
                // If it's a video URL or appears to be a Live Photo stream URL
                return "mp4";
            } else if (url.contains("xhscdn.com")) {
                // For xhscdn URLs which could be images or videos, default to image unless specified otherwise
                // However, if we know it's a Live Photo stream URL, it should be mp4
                if (url.contains("h264") || url.contains("stream")) {
                    return "mp4";
                } else {
                    // Default to image format for xhscdn URLs that don't indicate video
                    return "jpg";
                }
            }
        }
        
        // Default fallback
        return "jpg";
    }
    
    public List<String> extractLinks(String input) {
        List<String> urls = new ArrayList<>();

        // Check if input is null or empty
        if (input == null || input.isEmpty()) {
            return urls; // Return empty list if input is null or empty
        }

        // 按空格分割输入，模仿原Python项目的逻辑
        String[] parts = input.split("\\s+");
        
        for (String part : parts) {
            // 确保部分不为空
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            
            String processedPart = part;
            
            // 检查短链接格式 (xhslink.com / xhslink.cn)
            Matcher shortMatcher = XHS_SHORT_PATTERN.matcher(part);
            if (shortMatcher.find()) {
                String shortUrl = part.substring(shortMatcher.start(), shortMatcher.end());
                
                // 原Python代码会对短链接进行重定向获取真实URL
                // 实现类似功能，发起请求以获取重定向后的真实URL
                String resolvedUrl = resolveShortUrl(shortUrl);
                processedPart = resolvedUrl != null ? resolvedUrl : shortUrl;
                
                // 将处理后的URL添加到列表
                urls.add(processedPart);
                continue;  // 找到匹配后跳过其他检查
            }
            
            // 如果不是短链接，则检查其他格式
            // 检查分享格式
            Matcher shareMatcher = XHS_SHARE_PATTERN.matcher(processedPart);
            if (shareMatcher.find()) {
                urls.add(processedPart.substring(shareMatcher.start(), shareMatcher.end()));
                continue;
            }
            
            // 检查常规链接格式
            Matcher linkMatcher = XHS_LINK_PATTERN.matcher(processedPart);
            if (linkMatcher.find()) {
                urls.add(processedPart.substring(linkMatcher.start(), linkMatcher.end()));
                continue;
            }
            
            // 检查用户资料格式
            Matcher userMatcher = XHS_USER_PATTERN.matcher(processedPart);
            if (userMatcher.find()) {
                urls.add(processedPart.substring(userMatcher.start(), userMatcher.end()));
            }
        }
        
        return urls;
    }
    
    public String extractPostId(String url) {
        // Pattern to extract the post ID from various URL formats
        // After redirection, the URL should be in standard format
        Pattern idPattern = Pattern.compile("(?:explore|item)/([a-zA-Z0-9_\\-]+)/?(?:\\?|$)"); // Added support for underscores and hyphens
        Pattern idUserPattern = Pattern.compile("user/profile/[a-z0-9]+/([a-zA-Z0-9_\\-]+)/?(?:\\?|$)"); // Added support for underscores and hyphens
        
        Matcher matcher = idPattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        matcher = idUserPattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // 如果标准模式匹配失败，尝试从xhslink短链接格式中提取
        // xhslink.com / xhslink.cn 路径格式，ID通常在路径的最后一部分
        if (url.contains("xhslink.com/") || url.contains("xhslink.cn/")) {
            String[] parts = url.split("/");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                // 移除查询参数部分
                if (lastPart.contains("?")) {
                    lastPart = lastPart.split("\\?")[0];
                }
                // 如果提取出的ID不为空，返回它
                if (!lastPart.isEmpty() && !lastPart.equals("o")) {
                    return lastPart;
                }
                // 如果最后部分是"o"，我们需要取前一部分
                else if (parts.length > 1) {
                    String secondToLast = parts[parts.length - 2];
                    if (secondToLast.contains("?")) {
                        secondToLast = secondToLast.split("\\?")[0];
                    }
                    if (!secondToLast.isEmpty()) {
                        return secondToLast;
                    }
                }
            }
        }
        
        return null;
    }
    
    public String fetchPostDetails(String url) {
        try {
            // Create a request to fetch the post details
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT_XHS_ANDROID)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=1.0,image/avif,image/webp,image/apng,*/*;q=1.0")
                    .build();
            
            activeCall = httpClient.newCall(request);
            Response response = activeCall.execute();
            
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                Log.e(TAG, "Failed to fetch post details. Response code: " + response.code());
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error fetching post details: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static boolean isJsIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Extract the first JS object literal (starting with '{') from a larger JS snippet.
     * This helps when the script contains extra JS after the state object.
     */
    private static String extractFirstJsObjectLiteral(String jsSnippet) {
        if (jsSnippet == null) return null;

        boolean inString = false;
        char quote = 0;
        boolean escape = false;
        int depth = 0;
        int start = -1;

        for (int i = 0; i < jsSnippet.length(); i++) {
            char c = jsSnippet.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == quote) {
                    inString = false;
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                continue;
            }

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start != -1) {
                        return jsSnippet.substring(start, i + 1);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Convert a JS-ish object literal to strict JSON by replacing bare `undefined` tokens with `null`.
     * Only replaces tokens outside of quoted strings.
     */
    private static String replaceJsUndefinedWithNull(String input) {
        if (input == null || !input.contains("undefined")) return input;

        StringBuilder out = new StringBuilder(input.length());
        boolean inString = false;
        char quote = 0;
        boolean escape = false;

        for (int i = 0; i < input.length(); ) {
            char c = input.charAt(i);

            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                    i++;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                } else if (c == quote) {
                    inString = false;
                    quote = 0;
                }
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out.append(c);
                i++;
                continue;
            }

            if (i + "undefined".length() <= input.length() && input.startsWith("undefined", i)) {
                char prev = (i > 0) ? input.charAt(i - 1) : '\0';
                char next = (i + "undefined".length() < input.length()) ? input.charAt(i + "undefined".length()) : '\0';
                boolean prevOk = (i == 0) || !isJsIdentifierChar(prev);
                boolean nextOk = (i + "undefined".length() == input.length()) || !isJsIdentifierChar(next);
                if (prevOk && nextOk) {
                    out.append("null");
                    i += "undefined".length();
                    continue;
                }
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    private JSONObject parseInitialStateRootFromHtml(String html) {
        if (html == null) return null;

        int startIndex = html.indexOf("window.__INITIAL_STATE__");
        if (startIndex == -1) return null;

        int endIndex = html.indexOf("</script>", startIndex);
        if (endIndex == -1) return null;

        String scriptContent = html.substring(startIndex, endIndex);
        int equalsIndex = scriptContent.indexOf("=");
        if (equalsIndex == -1) return null;

        String afterEquals = scriptContent.substring(equalsIndex + 1).trim();

        // The script may contain extra JS after the object. Extract the first balanced object literal.
        String jsObject = extractFirstJsObjectLiteral(afterEquals);
        if (jsObject == null) {
            // Fallback: try parsing whatever remains (might already be pure JSON).
            jsObject = afterEquals;
        }

        jsObject = jsObject.trim();
        if (jsObject.endsWith(";")) {
            jsObject = jsObject.substring(0, jsObject.length() - 1).trim();
        }

        jsObject = replaceJsUndefinedWithNull(jsObject);

        try {
            return new JSONObject(jsObject);
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing __INITIAL_STATE__ JSON: " + e.getMessage());
            try {
                Log.d(TAG, "__INITIAL_STATE__ (first 500 chars): " + jsObject.substring(0, Math.min(500, jsObject.length())));
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static boolean isLikelyNoteObject(JSONObject obj) {
        if (obj == null) return false;

        try {
            if (obj.has("imageList") && obj.get("imageList") instanceof JSONArray) {
                JSONArray list = obj.getJSONArray("imageList");
                if (list.length() > 0 && list.get(0) instanceof JSONObject) {
                    JSONObject first = list.getJSONObject(0);
                    return first.has("urlDefault") || first.has("url") || first.has("traceId") || first.has("infoList");
                }
            }

            if (obj.has("images") && obj.get("images") instanceof JSONArray) {
                JSONArray list = obj.getJSONArray("images");
                if (list.length() > 0 && list.get(0) instanceof JSONObject) {
                    JSONObject first = list.getJSONObject(0);
                    return first.has("urlDefault") || first.has("url") || first.has("traceId") || first.has("infoList");
                }
            }

            if (obj.has("video") && obj.get("video") instanceof JSONObject) {
                JSONObject video = obj.getJSONObject("video");
                return video.has("consumer") || video.has("media");
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private static void addNoteCandidate(JSONObject note, List<JSONObject> notes, Set<String> seenNoteIds) {
        if (note == null) return;

        // Avoid empty placeholder objects (these break extraction and block better candidates).
        JSONArray names = note.names();
        if (names == null || names.length() == 0) return;

        String noteId = note.optString("noteId", "");
        if (!TextUtils.isEmpty(noteId)) {
            if (!seenNoteIds.add(noteId)) return;
        }
        notes.add(note);
    }

    private List<JSONObject> findNoteObjects(JSONObject root) {
        List<JSONObject> notes = new ArrayList<>();
        Set<String> seenNoteIds = new HashSet<>();

        try {
            // 1) Legacy: root.note.noteDetailMap[*].note
            if (root.has("note") && root.get("note") instanceof JSONObject) {
                JSONObject noteRoot = root.getJSONObject("note");

                if (noteRoot.has("noteDetailMap") && noteRoot.get("noteDetailMap") instanceof JSONObject) {
                    JSONObject noteDetailMap = noteRoot.getJSONObject("noteDetailMap");
                    JSONArray keys = noteDetailMap.names();
                    if (keys != null) {
                        for (int i = 0; i < keys.length(); i++) {
                            String key = keys.getString(i);
                            JSONObject noteData = noteDetailMap.optJSONObject(key);
                            if (noteData != null) {
                                JSONObject note = noteData.optJSONObject("note");
                                addNoteCandidate(note, notes, seenNoteIds);
                            }
                        }
                    }
                } else if (noteRoot.has("note") && noteRoot.get("note") instanceof JSONObject) {
                    addNoteCandidate(noteRoot.getJSONObject("note"), notes, seenNoteIds);
                } else if (noteRoot.has("feed") && noteRoot.get("feed") instanceof JSONObject) {
                    JSONObject feed = noteRoot.getJSONObject("feed");
                    JSONArray items = feed.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject note = items.optJSONObject(i);
                            addNoteCandidate(note, notes, seenNoteIds);
                        }
                    }
                } else {
                    // Some pages embed the note directly under root.note
                    addNoteCandidate(noteRoot, notes, seenNoteIds);
                }
            }

            // 2) Legacy: root.feed.items[*]
            if (root.has("feed") && root.get("feed") instanceof JSONObject) {
                JSONObject feed = root.getJSONObject("feed");
                JSONArray items = feed.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject note = items.optJSONObject(i);
                        addNoteCandidate(note, notes, seenNoteIds);
                    }
                }
            }

            // 3) Newer: root.noteData.data.noteData
            if (root.has("noteData") && root.get("noteData") instanceof JSONObject) {
                JSONObject noteDataRoot = root.getJSONObject("noteData");
                JSONObject data = noteDataRoot.optJSONObject("data");
                if (data != null) {
                    JSONObject note = data.optJSONObject("noteData");
                    if (note == null) {
                        // Some variants may use a different key under data.
                        note = data.optJSONObject("note");
                    }
                    addNoteCandidate(note, notes, seenNoteIds);
                }
            }

            // 4) Last resort: deep scan for a note-like object anywhere.
            boolean hasLikely = false;
            for (JSONObject n : notes) {
                if (isLikelyNoteObject(n) || n.has("imageList") || n.has("images") || n.has("video")) {
                    hasLikely = true;
                    break;
                }
            }

            if (notes.isEmpty() || !hasLikely) {
                Deque<Object> stack = new ArrayDeque<>();
                stack.push(root);
                int visited = 0;
                int maxVisited = 50000;
                int maxNotes = 5;

                while (!stack.isEmpty() && visited < maxVisited && notes.size() < maxNotes) {
                    Object current = stack.pop();
                    visited++;

                    if (current instanceof JSONObject) {
                        JSONObject obj = (JSONObject) current;

                        // Prefer unwrapping { note: {...} } when present.
                        JSONObject innerNote = obj.optJSONObject("note");
                        if (innerNote != null) {
                            stack.push(innerNote);
                        }

                        if (isLikelyNoteObject(obj)) {
                            addNoteCandidate(obj, notes, seenNoteIds);
                            if (notes.size() >= maxNotes) break;
                        }

                        JSONArray names = obj.names();
                        if (names != null) {
                            for (int i = 0; i < names.length(); i++) {
                                Object v = obj.opt(names.getString(i));
                                if (v instanceof JSONObject || v instanceof JSONArray) {
                                    stack.push(v);
                                }
                            }
                        }
                    } else if (current instanceof JSONArray) {
                        JSONArray arr = (JSONArray) current;
                        for (int i = 0; i < arr.length(); i++) {
                            Object v = arr.opt(i);
                            if (v instanceof JSONObject || v instanceof JSONArray) {
                                stack.push(v);
                            }
                        }
                    }
                }

                if (!notes.isEmpty()) {
                    Log.d(TAG, "Deep scan found " + notes.size() + " note candidate(s)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding note objects: " + e.getMessage());
        }

        return notes;
    }
    
    public List<String> parsePostDetails(String html) {
        List<String> mediaUrls = new ArrayList<>();
        // Create pairs of images and their corresponding live photo videos
        List<MediaPair> mediaPairs = new ArrayList<>();

        // Look for JSON data in the HTML that contains media information
        // This is a simplified approach - in a real implementation, you'd need to parse the actual structure

        // Look for JSON data containing media URLs
        JSONObject root = parseInitialStateRootFromHtml(html);
        if (root != null) {
            List<JSONObject> notes = findNoteObjects(root);
            for (JSONObject note : notes) {
                mediaUrls.addAll(extractMediaUrlsFromNote(note, mediaPairs));
            }
        } else {
            // If structured JSON isn't available, try to extract URLs directly from HTML
            mediaUrls.addAll(extractUrlsFromHtml(html));
        }

        // Clear the URL mapping before processing new URLs
        urlMapping.clear();

        // If we have media pairs, process them and add to main mediaUrls
        // But preserve any existing mediaUrls (like videos from note.video section)
        List<String> existingMediaUrls = new ArrayList<>(mediaUrls); // Preserve existing URLs

        // Transform URLs for all media pairs first to maintain proper pairing
        for (MediaPair pair : mediaPairs) {
            if (pair.originalImageUrl != null) {
                pair.imageUrl = transformXhsCdnUrl(pair.originalImageUrl);
                // Store mapping for original to transformed URL
                urlMapping.put(pair.imageUrl, pair.originalImageUrl);
                Log.d(TAG, "Transformed image URL: " + pair.originalImageUrl + " -> " + pair.imageUrl);
            }
            if (pair.originalVideoUrl != null) {
                pair.videoUrl = transformXhsCdnUrl(pair.originalVideoUrl);
                // Store mapping for original to transformed URL
                urlMapping.put(pair.videoUrl, pair.originalVideoUrl);
                Log.d(TAG, "Transformed video URL: " + pair.originalVideoUrl + " -> " + pair.videoUrl);
            }
        }

        // Create a new media URL list that properly handles live photo pairs separately
        List<String> newMediaUrls = new ArrayList<>();
        List<LivePhotoPair> livePhotoPairs = new ArrayList<>();

        // Add the properly paired live photos to livePhotoPairs and their URLs to newMediaUrls
        for (MediaPair pair : mediaPairs) {
            if (pair.isLivePhoto) {
                // Add both image and video for live photo (in correct order)
                livePhotoPairs.add(new LivePhotoPair(pair.imageUrl, pair.videoUrl));
                newMediaUrls.add(pair.imageUrl);
                if (pair.videoUrl != null) {
                    newMediaUrls.add(pair.videoUrl);
                }
            } else {
                // Add just the image
                newMediaUrls.add(pair.imageUrl);
            }
        }

        // Add remaining media that weren't part of live photo pairs
        for (String existingUrl : existingMediaUrls) {
            // Only add if not already in the new list (avoiding duplication)
            if (!newMediaUrls.contains(existingUrl)) {
                newMediaUrls.add(existingUrl);
            }
        }

        // Store live photo pairs for use in createLivePhotos method
        this.livePhotoPairs = livePhotoPairs;
        mediaUrls = newMediaUrls; // Replace the list

        // Log the final media URLs for debugging
        for (String url : mediaUrls) {
            Log.d(TAG, "Original URL: " + url);
        }

        Log.d(TAG, "Found " + mediaUrls.size() + " media URLs: " + mediaUrls);
        return mediaUrls;
    }
    
    /**
     * Extract media URLs from a note object
     * @param note The note JSON object to extract media from
     * @param mediaPairs List to store image-video pairs for live photos
     * @return A list of media URLs found in the note
     */
    private List<String> extractMediaUrlsFromNote(JSONObject note, List<MediaPair> mediaPairs) {
        List<String> mediaUrls = new ArrayList<>();
        
        try {
            captureNoteMetadata(note);
            // Debug logging to trace execution path
            Log.d(TAG, "Processing note object");
            Log.d(TAG, "Note object keys: " + note.names());
            
            // Check if it's a video post
            if (note.has("video")) {
                Log.d(TAG, "Found video field in note");
                JSONObject video = note.getJSONObject("video");
                Log.d(TAG, "Video object keys: " + video.names());
                
                // 从consumer.originVideoKey构建视频URL（模仿Python代码）
                if (video.has("consumer") && video.getJSONObject("consumer").has("originVideoKey")) {
                    Log.d(TAG, "Found consumer.originVideoKey");
                    String originVideoKey = video.getJSONObject("consumer").getString("originVideoKey");
                    String videoUrl = "https://sns-video-bd.xhscdn.com/" + originVideoKey;
                    Log.d(TAG, "Extracted video URL: " + videoUrl);
                    mediaUrls.add(videoUrl);
                    // Mark that videos have been detected
                    videosDetected = true;
                    if (shouldStopOnVideo && downloadCallback != null && !videoWarningShown) {
                        videoWarningShown = true;
                        downloadCallback.onVideoDetected();
                    }
                }
                // 备用方案：检查media.stream.h265
                else if (video.has("media")) {
                    Log.d(TAG, "Found media field in video");
                    JSONObject media = video.getJSONObject("media");
                    if (media.has("stream")) {
                        JSONObject stream = media.getJSONObject("stream");
                        if (stream.has("h265")) {
                            JSONArray h265Array = stream.getJSONArray("h265");
                            for (int j = 0; j < h265Array.length(); j++) {
                                Object h265Obj = h265Array.get(j);
                                // 如果是字符串，直接使用；如果是JSON对象，提取URL字段
                                if (h265Obj instanceof String) {
                                    String url = (String) h265Obj;
                                    // 确保这是一个有效的URL而不是JSON字符串
                                    if (url.startsWith("http")) {
                                        Log.d(TAG, "Extracted video URL from h265 string: " + url);
                                        mediaUrls.add(url);
                                        // Mark that videos have been detected
                                        videosDetected = true;
                                        if (shouldStopOnVideo && downloadCallback != null && !videoWarningShown) {
                                            videoWarningShown = true;
                                            downloadCallback.onVideoDetected();
                                        }
                                    }
                                } else if (h265Obj instanceof JSONObject) {
                                    JSONObject h265Json = (JSONObject) h265Obj;
                                    // 尝试获取URL字段
                                    if (h265Json.has("url")) {
                                        Log.d(TAG, "Extracted video URL from h265.url: " + h265Json.getString("url"));
                                        mediaUrls.add(h265Json.getString("url"));
                                        // Mark that videos have been detected
                                        videosDetected = true;
                                        if (shouldStopOnVideo && downloadCallback != null && !videoWarningShown) {
                                            videoWarningShown = true;
                                            downloadCallback.onVideoDetected();
                                        }
                                    } else if (h265Json.has("masterUrl")) {
                                        Log.d(TAG, "Extracted video URL from h265.masterUrl: " + h265Json.getString("masterUrl"));
                                        mediaUrls.add(h265Json.getString("masterUrl"));
                                        // Mark that videos have been detected
                                        videosDetected = true;
                                        if (shouldStopOnVideo && downloadCallback != null && !videoWarningShown) {
                                            videoWarningShown = true;
                                            downloadCallback.onVideoDetected();
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "Video object doesn't have consumer or media field");
                }
            } else {
                Log.d(TAG, "Note doesn't have video field");
            }
            
            // Check if it's an image post - try multiple possible fields
            JSONArray imageList = null;
            if (note.has("imageList")) {
                imageList = note.getJSONArray("imageList");
                Log.d(TAG, "Found imageList with " + imageList.length() + " images");
            } 
            // Additional fields that might contain images
            else if (note.has("images")) {
                imageList = note.getJSONArray("images");
                Log.d(TAG, "Found images array with " + imageList.length() + " images");
            }
            // Some notes might have a single image field
            else if (note.has("image")) {
                JSONObject singleImage = note.getJSONObject("image");
                JSONArray singleImageArray = new JSONArray();
                singleImageArray.put(singleImage);
                imageList = singleImageArray;
                Log.d(TAG, "Found single image object");
            }
            
            if (imageList != null) {
                for (int j = 0; j < imageList.length(); j++) {
                    JSONObject image = imageList.getJSONObject(j);
                    
                    // Store image URL - check multiple possible fields
                    String imageUrl = null;
                    if (image.has("urlDefault")) {
                        imageUrl = image.getString("urlDefault");
                    } else if (image.has("url")) {
                        imageUrl = image.getString("url");
                    } else if (image.has("traceId")) {
                        // Construct URL from traceId if needed
                        String traceId = image.getString("traceId");
                        imageUrl = "https://sns-img-qc.xhscdn.com/" + traceId;
                    } else if (image.has("infoList")) {
                        // Some images have infoList with different formats
                        JSONArray infoList = image.getJSONArray("infoList");
                        for (int k = 0; k < infoList.length(); k++) {
                            JSONObject info = infoList.getJSONObject(k);
                            if (info.has("url")) {
                                imageUrl = info.getString("url");
                                break; // Take the first available URL
                            }
                        }
                    }
                    
                    // Check for Live Photo video stream
                    String livePhotoVideoUrl = null;
                    if (image.has("stream")) {
                        JSONObject stream = image.getJSONObject("stream");
                        if (stream.has("h264") && stream.getJSONArray("h264").length() > 0) {
                            Object h264Obj = stream.getJSONArray("h264").get(0);
                            if (h264Obj instanceof JSONObject) {
                                JSONObject h264Json = (JSONObject) h264Obj;
                                if (h264Json.has("masterUrl")) {
                                    livePhotoVideoUrl = h264Json.getString("masterUrl");
                                } else if (h264Json.has("url")) {
                                    livePhotoVideoUrl = h264Json.getString("url");
                                }
                            }
                        }
                    }
                    
                    // Add to media pairs - either Live Photo pair or single image
                    if (imageUrl != null) {
                        if (livePhotoVideoUrl != null) {
                            // Has stream field = Live Photo
                            Log.d(TAG, "Live Photo detected: image=" + imageUrl + ", video=" + livePhotoVideoUrl);
                            mediaPairs.add(new MediaPair(imageUrl, livePhotoVideoUrl, true));
                            videosDetected = true;
                        } else {
                            mediaPairs.add(new MediaPair(imageUrl, null, false)); // single image
                        }
                    }
                }
            } else {
                Log.d(TAG, "Note doesn't have imageList field");
                
                // Additional check: some posts might have media in other fields
                // Check for any URL that might be media related
                JSONArray names = note.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String fieldName = names.getString(i);
                        Object fieldValue = note.get(fieldName);
                        
                        // Check if this field contains potential media URLs
                        if (fieldValue instanceof String) {
                            String value = (String) fieldValue;
                            if (value.contains("xhscdn.com") || value.contains(".mp4") || value.contains(".jpg") || value.contains(".png")) {
                                Log.d(TAG, "Found potential media URL in field " + fieldName + ": " + value);
                                mediaUrls.add(value);
                            }
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error extracting media from note: " + e.getMessage());
        }
        
        return mediaUrls;
    }
    
    /**
     * Extracts the description from a note object
     * @param note The note JSON object to extract description from
     * @return The description text or null if not found
     */
    private String extractNoteDescription(JSONObject note) {
        try {
            String desc = "";
            if (note.has("title")) {
                desc += note.getString("title");
            }
            if (note.has("desc")) {
                desc += note.getString("desc");
            }
            return desc;
        } catch (JSONException e) {
            Log.e(TAG, "Error extracting description from note: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Extracts complete note information including description
     * @param note The note JSON object to extract info from
     * @return A JSONObject containing note information
     */
    private JSONObject extractNoteInfo(JSONObject note) {
        JSONObject noteInfo = new JSONObject();
        try {
            // Extract description
            String desc = extractNoteDescription(note);
            if (desc != null) {
                noteInfo.put("desc", desc);
            }
            
            // Extract title if different from description
            if (note.has("title") && !note.getString("title").equals(desc)) {
                noteInfo.put("title", note.getString("title"));
            }
            
            // Extract other useful fields
            if (note.has("noteId")) {
                noteInfo.put("noteId", note.getString("noteId"));
            }
            
            if (note.has("user")) {
                JSONObject user = note.getJSONObject("user");
                if (user.has("nickname")) {
                    noteInfo.put("author", user.getString("nickname"));
                }
            }
            
            if (note.has("interactInfo")) {
                JSONObject interactInfo = note.getJSONObject("interactInfo");
                if (interactInfo.has("likedCount")) {
                    noteInfo.put("likes", interactInfo.getString("likedCount"));
                }
                if (interactInfo.has("commentCount")) {
                    noteInfo.put("comments", interactInfo.getString("commentCount"));
                }
            }
            
            if (note.has("tagList")) {
                JSONArray tags = note.getJSONArray("tagList");
                JSONArray tagNames = new JSONArray();
                for (int i = 0; i < tags.length(); i++) {
                    JSONObject tag = tags.getJSONObject(i);
                    if (tag.has("name")) {
                        tagNames.put(tag.getString("name"));
                    }
                }
                noteInfo.put("tags", tagNames);
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Error extracting note info: " + e.getMessage());
        }
        return noteInfo;
    }
    
    /**
     * Class to hold image-video pairs for live photos
     */
    private static class MediaPair {
        String originalImageUrl;
        String originalVideoUrl;
        String imageUrl;
        String videoUrl;
        boolean isLivePhoto;

        MediaPair(String originalImageUrl, String originalVideoUrl, boolean isLivePhoto) {
            this.originalImageUrl = originalImageUrl;
            this.originalVideoUrl = originalVideoUrl;
            this.imageUrl = originalImageUrl;  // Initialize with original, will be transformed later
            this.videoUrl = originalVideoUrl;  // Initialize with original, will be transformed later
            this.isLivePhoto = isLivePhoto;
        }
    }

    /**
     * Class to hold confirmed live photo pairs
     */
    private static class LivePhotoPair {
        String imageUrl;
        String videoUrl;

        LivePhotoPair(String imageUrl, String videoUrl) {
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            LivePhotoPair that = (LivePhotoPair) obj;
            return java.util.Objects.equals(imageUrl, that.imageUrl) &&
                   java.util.Objects.equals(videoUrl, that.videoUrl);
        }
    }

    private static class NoteMetadata {
        final String userName;
        final String userId;
        final String title;
        final String publishTime;

        NoteMetadata(String userName, String userId, String title, String publishTime) {
            this.userName = userName;
            this.userId = userId;
            this.title = title;
            this.publishTime = publishTime;
        }

        boolean hasRequiredFields() {
            return !TextUtils.isEmpty(userName) && !TextUtils.isEmpty(userId);
        }
    }
    
    private List<String> extractUrlsFromHtml(String html) {
        List<String> urls = new ArrayList<>();
        
        // Look for image URLs in the HTML
        Pattern imgPattern = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");
        Matcher imgMatcher = imgPattern.matcher(html);
        while (imgMatcher.find()) {
            String url = imgMatcher.group(1);
            if (isValidMediaUrl(url)) {
                urls.add(url);
            }
        }
        
        // Look for other potential media URLs
        Pattern urlPattern = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+\\.(jpg|jpeg|png|gif|mp4|avi|mov|webm|wmv|flv|f4v|swf|avi|mpg|mpeg|asf|3gp|3g2|mkv|webp|heic|heif)");
        Matcher urlMatcher = urlPattern.matcher(html);
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            if (!urls.contains(url) && isValidMediaUrl(url)) {
                urls.add(url);
                // Check if this is a video URL and mark it if so
                if (isVideoUrl(url)) {
                    videosDetected = true;
                    if (shouldStopOnVideo && downloadCallback != null && !videoWarningShown) {
                        videoWarningShown = true;
                        downloadCallback.onVideoDetected();
                    }
                }
            }
        }
        
        return urls;
    }
    
    private boolean isValidMediaUrl(String url) {
        return url != null && 
               (url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") || 
                url.contains(".gif") || url.contains(".mp4") || url.contains(".webm") ||
                url.contains("xhscdn.com") || url.contains("xiaohongshu.com"));
    }
    
    /**
     * Checks if live photo creation is enabled in settings
     * @return true if live photos should be created, false otherwise
     */
    private boolean shouldCreateLivePhotos() {
        SharedPreferences prefs = context.getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        return prefs.getBoolean("create_live_photos", true); // Default to true - live photos enabled by default
    }

    private boolean shouldUseCustomNamingFormat() {
        SharedPreferences prefs = context.getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        if (prefs.contains("use_custom_naming_format")) {
            return prefs.getBoolean("use_custom_naming_format", false);
        }
        return prefs.getBoolean("use_metadata_file_names", false);
    }

    private String getCustomNamingTemplate() {
        SharedPreferences prefs = context.getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        String template = prefs.getString("custom_naming_template", NamingFormat.DEFAULT_TEMPLATE);
        if (template != null) {
            template = template.trim();
        }
        if (TextUtils.isEmpty(template)) {
            return NamingFormat.DEFAULT_TEMPLATE;
        }
        return template;
    }

    private void captureNoteMetadata(JSONObject note) {
        if (!customNamingEnabled || note == null) {
            return;
        }
        if (currentNoteMetadata != null && currentNoteMetadata.hasRequiredFields()) {
            return;
        }
        NoteMetadata metadata = buildNoteMetadata(note);
        if (metadata != null && metadata.hasRequiredFields()) {
            currentNoteMetadata = metadata;
        }
    }

    private NoteMetadata buildNoteMetadata(JSONObject note) {
        try {
            if (note == null) {
                return null;
            }

            JSONObject user = null;
            String nickname = null;
            String redId = null;

            // Try to get user from direct "user" field
            if (note.has("user")) {
                user = note.optJSONObject("user");
            }

            // If not found, try alternative locations that might be used in newer structures
            if (user == null && note.has("user_info")) {
                user = note.optJSONObject("user_info");
            }

            if (user != null) {
                nickname = firstNonEmpty(
                        user.optString("nickname", null),
                        user.optString("name", null),
                        user.optString("userName", null),
                        user.optString("nickName", null)); // Some structures use camelCase
                redId = firstNonEmpty(
                        user.optString("redId", null),
                        user.optString("red_id", null),
                        user.optString("userId", null),
                        user.optString("userid", null),
                        user.optString("user_id", null),
                        user.optString("id", null)); // Some structures might use generic "id"
            }

            // If still not found, try to get from note level (backup)
            if (TextUtils.isEmpty(redId)) {
                redId = firstNonEmpty(
                        note.optString("userId", null),
                        note.optString("uid", null),
                        note.optString("user_id", null));
            }

            if (TextUtils.isEmpty(nickname)) {
                nickname = firstNonEmpty(
                        note.optString("author", null),
                        note.optString("userName", null));
            }

            String title = firstNonEmpty(
                    note.optString("title", null),
                    note.optString("desc", null),
                    note.optString("description", null));
            if (TextUtils.isEmpty(title)) {
                title = note.optString("noteId", null);
            }

            String publishTime = extractPublishTime(note);

            String sanitizedUser = sanitizeForFilename(nickname, 40);
            String sanitizedRedId = sanitizeForFilename(redId, 40);
            String sanitizedTitle = sanitizeForFilename(title, 80);
            String sanitizedPublishTime = sanitizeForFilename(publishTime, 60);

            if (TextUtils.isEmpty(sanitizedUser) || TextUtils.isEmpty(sanitizedRedId)) {
                return null;
            }

            return new NoteMetadata(sanitizedUser, sanitizedRedId, sanitizedTitle, sanitizedPublishTime);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting note metadata: " + e.getMessage());
            return null;
        }
    }

    private String extractPublishTime(JSONObject note) {
        if (note == null) {
            return null;
        }
        String textual = firstNonEmpty(
                note.optString("time", null),
                note.optString("timeText", null),
                note.optString("displayTime", null),
                note.optString("publishTime", null),
                note.optString("publish_time", null),
                note.optString("createTime", null));

        String normalized = normalizeExplicitDate(textual);
        if (!TextUtils.isEmpty(normalized)) {
            return normalized;
        }

        long epoch = extractEpochFromNote(note,
                "time", "publishTime", "publish_time", "createTime", "timestamp", "timeStamp");
        if (epoch > 0) {
            return formatEpochAsDate(epoch);
        }

        return textual;
    }

    private String normalizeExplicitDate(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                java.util.Date parsed = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .parse(value.substring(0, 10));
                if (parsed != null) {
                    return formatEpochAsDate(parsed.getTime());
                }
            } catch (java.text.ParseException ignore) {
            }
        }
        if (value.matches("\\d{2}-\\d{2}-\\d{2}.*")) {
            return value.substring(0, Math.min(value.length(), 8));
        }
        String digitsOnly = value.replaceAll("[^0-9]", "");
        if (digitsOnly.length() >= 8) {
            String firstEight = digitsOnly.substring(0, 8);
            try {
                java.util.Date parsed = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                        .parse(firstEight);
                if (parsed != null) {
                    return formatEpochAsDate(parsed.getTime());
                }
            } catch (java.text.ParseException ignore) {
            }
        }
        return null;
    }

    private long extractEpochFromNote(JSONObject note, String... keys) {
        if (note == null || keys == null) {
            return -1;
        }
        for (String key : keys) {
            if (note.has(key)) {
                try {
                    Object raw = note.get(key);
                    long value = -1;
                    if (raw instanceof Number) {
                        value = ((Number) raw).longValue();
                    } else {
                        String rawString = note.optString(key, null);
                        if (!TextUtils.isEmpty(rawString)) {
                            value = Long.parseLong(rawString.replaceAll("[^0-9]", ""));
                        }
                    }
                    if (value > 0) {
                        if (value < 1_000_000_000L) {
                            continue;
                        }
                        if (value < 1_000_000_000_000L) {
                            value *= 1000L;
                        }
                        if (value < MIN_VALID_EPOCH_MS) {
                            continue;
                        }
                        return value;
                    }
                } catch (Exception ignore) {
                    // Ignore parse errors for fallback fields
                }
            }
        }
        return -1;
    }

    private String formatEpochAsDate(long epochMs) {
        if (epochMs < MIN_VALID_EPOCH_MS) {
            return null;
        }
        return new java.text.SimpleDateFormat("yy-MM-dd", java.util.Locale.getDefault())
                .format(new java.util.Date(epochMs));
    }

    private String sanitizeForFilename(String value, int maxLength) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String sanitized = value.replaceAll("[\\/:*?\"<>|]", "_");
        sanitized = sanitized.replaceAll("[\\p{Cntrl}]", "");
        sanitized = sanitized.trim();
        sanitized = sanitized.replaceAll("\\s+", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+", "");
        sanitized = sanitized.replaceAll("_+$", "");
        if (maxLength > 0 && sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
        }
        return sanitized.isEmpty() ? null : sanitized;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private String buildFileBaseName(String fallbackPostId, int mediaIndex) {
        String indexPart = String.format(Locale.getDefault(), "%02d", Math.max(mediaIndex, 1));
        if (customNamingEnabled && !TextUtils.isEmpty(customFormatTemplate)) {
            String customName = applyCustomTemplate(customFormatTemplate, fallbackPostId, mediaIndex, indexPart);
            if (!TextUtils.isEmpty(customName)) {
                Log.d(TAG, "on buildFileBaseName: customNamingEnabled - " + customName);
                return customName;
            }
        }
        Log.d(TAG, "on buildFileBaseName: fallbackPostId - " + fallbackPostId + "_" + indexPart);
        return fallbackPostId + "_" + indexPart;
    }

    private String applyCustomTemplate(String template, String fallbackPostId, int mediaIndex, String indexPart) {
        if (TextUtils.isEmpty(template)) {
            return null;
        }

        // Count how many times each token appears in the template
        int titleTokenCount = countOccurrences(template, NamingFormat.buildPlaceholder(NamingFormat.TOKEN_TITLE));

        String result;
        if (titleTokenCount > 0) {
            // If the template contains the title token, we need to estimate available space for the final filename
            // We'll use a dynamic approach: calculate the base length and determine space for title

            // Get the title text first to ensure we have it for calculations
            String titleText = (currentNoteMetadata != null && currentNoteMetadata.title != null)
                             ? currentNoteMetadata.title
                             : "";

            // Calculate the base length without the title content - using the actual values that will be used
            // First, create the template with a placeholder for title to measure base length
            String templateWithPlaceholder = template;
            for (int i = 0; i < titleTokenCount; i++) {
                templateWithPlaceholder = templateWithPlaceholder.replaceFirst(
                    java.util.regex.Pattern.quote(NamingFormat.buildPlaceholder(NamingFormat.TOKEN_TITLE)),
                    "PLACEHOLDER_FOR_CALCULATION");
            }

            // Calculate the base length using the same logic that will be used for final result
            java.util.regex.Matcher baseMatcher = NAMING_PLACEHOLDER_PATTERN.matcher(templateWithPlaceholder);
            StringBuffer baseBuffer = new StringBuffer();
            while (baseMatcher.find()) {
                String key = baseMatcher.group(1);
                String replacement = resolveTemplateValue(key, fallbackPostId, mediaIndex, indexPart);
                if (replacement == null) {
                    replacement = "";
                }
                baseMatcher.appendReplacement(baseBuffer, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            baseMatcher.appendTail(baseBuffer);
            String baseResult = baseBuffer.toString();

            // Sanitize the base result (using 0 for no length limit during calculation)
            String sanitizedBase = sanitizeForFilename(baseResult, 0);
            if (TextUtils.isEmpty(sanitizedBase)) {
                sanitizedBase = fallbackPostId + "_" + indexPart;
            }

            // Add index suffix if needed (this happens after the template is processed)
            String indexSuffix = !containsIndexToken(template) ? "_" + indexPart : "";
            String baseWithSuffix = sanitizedBase + indexSuffix;

            // Calculate available space for title content
            // The final filename in FileDownloader will be "xhs_" + baseFileName + "." + fileExtension
            // We need to account for the "xhs_" prefix (4 chars), file extension (~10 chars), potential duplicate suffixes like " (1)" (~20 chars for safety), and extra buffer (~20 chars for safety)
            int baseLength = baseWithSuffix.length();
            int theoreticalAvailableForTitle = Math.max(1, 255 - 4 - 10 - 20 - 20 - baseLength); // At least 1 char, 4 for "xhs_" prefix, 10 for extension, 20 for duplicate suffix, 20 extra buffer

            // To ensure truncation happens regardless of calculation, enforce a strict limit on title length
            // This ensures that even if the base part is small, the title won't be excessively long
            int maxAllowedTitleLength = 50; // Strict limit to ensure filename stays well under limit
            int finalAvailableForTitle = Math.min(theoreticalAvailableForTitle, maxAllowedTitleLength);
            String titleValue = safeTokenValue(titleText, finalAvailableForTitle);

            // Now apply the template with the appropriately sized title
            String finalTemplate = template;
            for (int i = 0; i < titleTokenCount; i++) {
                finalTemplate = finalTemplate.replaceFirst(
                    java.util.regex.Pattern.quote(NamingFormat.buildPlaceholder(NamingFormat.TOKEN_TITLE)),
                    java.util.regex.Matcher.quoteReplacement(titleValue != null ? titleValue : ""));
            }

            // Process the template with all remaining tokens (the title is already properly sized)
            java.util.regex.Matcher finalMatcher = NAMING_PLACEHOLDER_PATTERN.matcher(finalTemplate);
            StringBuffer finalBuffer = new StringBuffer();
            while (finalMatcher.find()) {
                String key = finalMatcher.group(1);
                String replacement = resolveTemplateValue(key, fallbackPostId, mediaIndex, indexPart);
                if (replacement == null) {
                    replacement = "";
                }
                finalMatcher.appendReplacement(finalBuffer, java.util.regex.Matcher.quoteReplacement(replacement != null ? replacement : ""));
            }
            finalMatcher.appendTail(finalBuffer);
            result = finalBuffer.toString();
        } else {
            // Template doesn't contain title, apply normally
            java.util.regex.Matcher matcher = NAMING_PLACEHOLDER_PATTERN.matcher(template);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                String key = matcher.group(1);
                String replacement = resolveTemplateValue(key, fallbackPostId, mediaIndex, indexPart);
                if (replacement == null) {
                    replacement = "";
                }
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(replacement != null ? replacement : ""));
            }
            matcher.appendTail(buffer);
            result = buffer.toString();
        }

        String sanitized = sanitizeForFilename(result, 0); // No length limit here since we handled it above
        if (TextUtils.isEmpty(sanitized)) {
            return null;
        }
        if (!containsIndexToken(template)) {
            sanitized = sanitized + "_" + indexPart;
        }
        return sanitized;
    }

    // Helper method to count occurrences of a substring
    private int countOccurrences(String str, String substr) {
        if (str == null || substr == null || substr.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(substr, idx)) != -1) {
            count++;
            idx += substr.length();
        }
        return count;
    }

    private boolean containsIndexToken(String template) {
        if (TextUtils.isEmpty(template)) {
            return false;
        }
        return template.contains(NamingFormat.buildPlaceholder(NamingFormat.TOKEN_INDEX)) ||
               template.contains(NamingFormat.buildPlaceholder(NamingFormat.TOKEN_INDEX_PADDED));
    }

    private String resolveTemplateValue(String key, String fallbackPostId, int mediaIndex, String indexPart) {
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        switch (key) {
            case NamingFormat.TOKEN_USERNAME:
                return safeTokenValue(currentNoteMetadata != null ? currentNoteMetadata.userName : null, 60);
            case NamingFormat.TOKEN_USER_ID:
                return safeTokenValue(currentNoteMetadata != null ? currentNoteMetadata.userId : null, 60);
            case NamingFormat.TOKEN_TITLE:
                return safeTokenValue(currentNoteMetadata != null ? currentNoteMetadata.title : null, 80);
            case NamingFormat.TOKEN_POST_ID:
                return safeTokenValue(fallbackPostId, 60);
            case NamingFormat.TOKEN_PUBLISH_TIME:
                return safeTokenValue(currentNoteMetadata != null ? currentNoteMetadata.publishTime : null, 60);
            case NamingFormat.TOKEN_INDEX:
                return String.valueOf(Math.max(mediaIndex, 1));
            case NamingFormat.TOKEN_INDEX_PADDED:
                return indexPart;
            case NamingFormat.TOKEN_DOWNLOAD_TIMESTAMP:
                long epochSeconds = sessionDownloadEpochSeconds > 0
                        ? sessionDownloadEpochSeconds
                        : System.currentTimeMillis() / 1000L;
                return String.valueOf(epochSeconds);
            default:
                return "";
        }
    }

    private String safeTokenValue(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        // First sanitize the value (remove invalid characters)
        String sanitized = sanitizeForFilename(value, 0); // No length limit during sanitization

        if (sanitized == null) {
            return null;
        }

        // Now apply length limit with ellipsis if needed
        if (maxLength > 0 && sanitized.length() > maxLength) {
            // Reserve space for ellipsis
            int availableLength = maxLength - 3; // 3 for "..."
            if (availableLength > 0) {
                sanitized = sanitized.substring(0, availableLength) + "...";
            } else {
                // If maxLength is too small for ellipsis, just truncate to maxLength
                sanitized = sanitized.substring(0, maxLength);
            }
        }

        return sanitized.isEmpty() ? null : sanitized;
    }

    
    /**
     * Creates live photos by combining images and videos
     * @param postId The post ID for naming
     * @param mediaUrls List of media URLs where live photos are properly paired as [image, video, image, video, ...]
     * @param timestamp The timestamp to use in file names for this download session
     * @return true if there were errors, false otherwise
     */
    private boolean createLivePhotos(String postId, List<String> mediaUrls, String timestamp) {
        boolean hasErrors = false;

        // Process media URLs in pairs: [image, video, image, video, ...] for live photos
        // Regular images/videos that are not part of live photos are handled separately
        int livePhotoIndex = 0; // Track the live photo number separately to ensure correct pairing

        // Process only confirmed live photo pairs that were identified during parsing
        for (LivePhotoPair livePhotoPair : this.livePhotoPairs) {
            livePhotoIndex++; // Increment the live photo index for this pair

            try {
                String imageUrl = livePhotoPair.imageUrl;
                String videoUrl = livePhotoPair.videoUrl;

                Log.d(TAG, "Creating live photo " + livePhotoIndex + " for post: " + postId);
                Log.d(TAG, "Image URL: " + imageUrl);
                Log.d(TAG, "Video URL: " + videoUrl);

                // Create a temporary downloader that downloads to the app's internal storage
                FileDownloader tempDownloader = new FileDownloader(context, null); // No callback to avoid premature notification

                // Download the image to a temporary location (app's internal storage)
                String baseName = buildFileBaseName(postId, livePhotoIndex);
                String imageFileName = baseName + "_img." + determineFileExtension(imageUrl);
                boolean imageDownloaded = tempDownloader.downloadFileToInternalStorage(imageUrl, imageFileName, timestamp);
                if (!imageDownloaded) {
                    Log.e(TAG, "Failed to download image for live photo: " + imageUrl);
                    hasErrors = true;
                    continue; // Skip to next live photo pair
                }

                // Download the video to a temporary location (app's internal storage)
                String videoFileName = baseName + "_vid." + determineFileExtension(videoUrl);
                boolean videoDownloaded = tempDownloader.downloadFileToInternalStorage(videoUrl, videoFileName, timestamp);
                if (!videoDownloaded) {
                    Log.e(TAG, "Failed to download video for live photo: " + videoUrl);
                    hasErrors = true;
                    // Clean up the already downloaded image file
                    File alreadyDownloadedImage = new File(context.getExternalFilesDir(null), "xhs_" + timestamp + "_" + imageFileName);
                    if (alreadyDownloadedImage.exists()) {
                        alreadyDownloadedImage.delete();
                    }
                    continue; // Skip to next live photo pair
                }

                // The files are downloaded to internal storage with "xhs_" prefix
                File actualTempImageFile = new File(context.getExternalFilesDir(null), "xhs_" + timestamp + "_" + imageFileName);
                File actualTempVideoFile = new File(context.getExternalFilesDir(null), "xhs_" + timestamp + "_" + videoFileName);

                Log.d(TAG, "Image file downloaded to: " + actualTempImageFile.getAbsolutePath());
                Log.d(TAG, "Video file downloaded to: " + actualTempVideoFile.getAbsolutePath());

                if (!actualTempImageFile.exists() || !actualTempVideoFile.exists()) {
                    Log.e(TAG, "Downloaded temporary files do not exist. Image: " + actualTempImageFile.exists() + ", Video: " + actualTempVideoFile.exists());
                    hasErrors = true;
                    continue; // Skip to next live photo pair
                }

                File destinationDir;
                if (cacheDestinationMode && cacheDestinationDir != null) {
                    destinationDir = cacheDestinationDir;
                } else {
                    // Always use MediaStore directory with "xhsdn" subfolder for consistent location
                    File publicPicturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES);
                    if (publicPicturesDir != null) {
                        destinationDir = new File(publicPicturesDir, "xhsdn");
                    } else {
                        destinationDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                    }
                }

                if (!destinationDir.exists()) {
                    destinationDir.mkdirs();
                }

                // Create the live photo in the final destination
                String livePhotoFileName = baseName + "_live.jpg";
                File livePhotoFile = new File(destinationDir, "xhs_" + livePhotoFileName);

                Log.d(TAG, "Creating live photo with image: " + actualTempImageFile.getAbsolutePath() +
                       " and video: " + actualTempVideoFile.getAbsolutePath() +
                       " -> output: " + livePhotoFile.getAbsolutePath());

                boolean livePhotoCreated = LivePhotoCreator.createLivePhoto(actualTempImageFile, actualTempVideoFile, livePhotoFile, context);

                if (livePhotoCreated) {
                    // Additional check: verify if the created live photo file can be opened by checking if it was created properly
                    if (livePhotoFile.exists() && livePhotoFile.length() > 0) {
                        // Notify the callback that the live photo has been downloaded
                        if (downloadCallback != null) {
                            downloadCallback.onFileDownloaded(livePhotoFile.getAbsolutePath());
                        }
                        if (cacheDestinationMode) {
                            cachedMediaFiles.add(new CachedMediaFile(livePhotoFile.getAbsolutePath(), livePhotoFile.getName()));
                        }
                        Log.d(TAG, "Successfully created live photo: " + livePhotoFile.getAbsolutePath());

                        // Clean up temporary files
                        if (actualTempImageFile.exists()) {
                            actualTempImageFile.delete();
                        }
                        if (actualTempVideoFile.exists()) {
                            actualTempVideoFile.delete();
                        }
                    } else {
                        // Live photo file is invalid, treat as failure
                        Log.e(TAG, "Live photo file was created but is invalid (zero size or doesn't exist)");
                        livePhotoCreated = false;

                        // Delete the invalid live photo file to prevent corrupted files from remaining
                        if (livePhotoFile.exists()) {
                            boolean deleted = livePhotoFile.delete();
                            Log.d(TAG, "Deleted invalid live photo file: " + livePhotoFile.getAbsolutePath() +
                                   ", deletion result: " + deleted);
                        }
                    }
                } else {
                    // LivePhotoCreator returned false, meaning creation failed
                    Log.e(TAG, "LivePhotoCreator failed to create live photo");

                    // Delete the failed live photo file if it exists to prevent corrupted files from remaining
                    if (livePhotoFile.exists()) {
                        boolean deleted = livePhotoFile.delete();
                        Log.d(TAG, "Deleted failed live photo file: " + livePhotoFile.getAbsolutePath() +
                               ", deletion result: " + deleted);
                    }
                }

                if (!livePhotoCreated) {
                    Log.e(TAG, "Failed to create live photo from image: " + actualTempImageFile.getAbsolutePath() +
                           " and video: " + actualTempVideoFile.getAbsolutePath() +
                           " -> output: " + livePhotoFile.getAbsolutePath() +
                           ". Falling back to separate files.");
                    hasErrors = true;

                    // Notify the callback about live photo creation failure with i18n message
                    if (downloadCallback != null) {
                        String fallbackMessage = "Live photo creation failed for post " + postId + ", index " + livePhotoIndex +
                            ". Falling back to downloading separate image and video files.";
                        downloadCallback.onDownloadProgress(fallbackMessage);
                    }

                    // Only download separately if the downloadFile calls were successful
                    boolean imageDownloadedFallback = downloadFile(imageUrl, imageFileName, timestamp);
                    boolean videoDownloadedFallback = downloadFile(videoUrl, videoFileName, timestamp);

                    Log.d(TAG, "Fallback download - Image: " + (imageDownloadedFallback ? "Success" : "Failed") +
                           ", Video: " + (videoDownloadedFallback ? "Success" : "Failed"));

                    boolean anySeparateFilesDownloaded = imageDownloadedFallback || videoDownloadedFallback;
                    if (downloadCallback != null && !anySeparateFilesDownloaded) {
                        // If neither separate file downloaded successfully, notify about the failure
                        downloadCallback.onDownloadError(
                            "Both image and video failed to download separately after live photo creation failure",
                            "Post " + postId + ", item " + livePhotoIndex
                        );
                    }

                    // Clean up temporary files
                    if (actualTempImageFile.exists()) {
                        actualTempImageFile.delete();
                    }
                    if (actualTempVideoFile.exists()) {
                        actualTempVideoFile.delete();
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error creating live photo: " + e.getMessage());
                e.printStackTrace();
                hasErrors = true;
            }
        }

        // Now handle the remaining media that are not part of live photo pairs
        // These include standalone images and standalone videos (like post videos)
        int mediaIndex = livePhotoIndex; // Continue numbering after live photo pairs
        for (String mediaUrl : mediaUrls) {
            boolean isPartOfLivePhoto = false;
            // Check if this URL is part of any live photo pair
            for (LivePhotoPair pair : this.livePhotoPairs) {
                if (mediaUrl.equals(pair.imageUrl) || mediaUrl.equals(pair.videoUrl)) {
                    isPartOfLivePhoto = true;
                    break;
                }
            }

            if (!isPartOfLivePhoto) {
                // This media is not part of a live photo pair, download separately
                mediaIndex++; // Increment for each standalone media to avoid overwriting files
                String baseFileName = buildFileBaseName(postId, mediaIndex);
                String uniqueFileName = baseFileName + "_" + (isVideoUrl(mediaUrl) ? "video" : "image");
                String fileExtension = determineFileExtension(mediaUrl);
                String fileNameWithExtension = uniqueFileName + "." + fileExtension;

                boolean success = downloadFile(mediaUrl, fileNameWithExtension, timestamp);
                if (!success) {
                    Log.e(TAG, "Failed to download media separately: " + mediaUrl);
                    hasErrors = true;
                } else {
                    Log.d(TAG, "Successfully downloaded media separately: " + mediaUrl);
                }
            }
        }

        return hasErrors;
    }
    
    /**
     * Checks if a URL is a video URL
     * @param url The URL to check
     * @return true if it's a video URL, false otherwise
     */
    private boolean isVideoUrl(String url) {
        return url != null &&
               (url.contains(".mp4") || url.contains(".mov") || url.contains(".avi") ||
                url.contains(".webm") || url.contains("video") || url.contains("masterUrl") ||
                url.contains("stream") || url.contains("sns-video") || url.contains("/spectrum/"));
    }

    /**
     * Checks if a URL is a main post video URL (not a live photo video)
     * @param url The URL to check
     * @return true if it's a main post video URL, false otherwise
     */
    private boolean isMainPostVideoUrl(String url) {
        return url != null && url.contains("sns-video-bd") && (url.contains("pre_post") || url.contains("originVideoKey"));
    }
    
    /**
     * 解析短链接，获取重定向后的真实URL
     * @param shortUrl 短链接
     * @return 重定向后的完整URL，如果失败则返回null
     */
    private String resolveShortUrl(String shortUrl) {
        try {
            // 创建一个GET请求来获取重定向的URL（GET请求通常会自动跟踪重定向）
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(shortUrl)
                    .addHeader("User-Agent", USER_AGENT_XHS_ANDROID)
                    .build();
            
            // 同步执行请求
            activeCall = httpClient.newCall(request);
            okhttp3.Response response = activeCall.execute();
            
            if (response.isSuccessful()) {
                // 获取重定向后的最终URL
                String finalUrl = response.request().url().toString();
                response.close();
                return finalUrl;
            } else {
                response.close();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving short URL: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Convert xhscdn.com URLs to the new format using the identifier
     * Convert from format like http://sns-webpic-qc.xhscdn.com/202404121854/a7e6fa93538d17fa5da39ed6195557d7/{{token}}!nd_dft_wlteh_webp_3
     * to format like https://ci.xiaohongshu.com/{{token}}?imageView2/format/png (based on original Python project)
     * Skip video URLs as they should not be transformed
     * @param originalUrl The original URL to transform
     * @return The transformed URL, or the original if transformation is not applicable
     */
    public String transformXhsCdnUrl(String originalUrl) {
        // Skip transformation for video URLs, only transform image URLs
        if (originalUrl != null && originalUrl.contains("xhscdn.com")) {
            // Don't transform video URLs
            if (originalUrl.contains("video") || originalUrl.contains("sns-video")) {
                return originalUrl;
            }
            
            // extract from 5th part onwards, and split by "!"
            String[] parts = originalUrl.split("/");
            if (parts.length > 5) {
                // Get everything from the 5th index onwards
                StringBuilder tokenBuilder = new StringBuilder();
                for (int i = 5; i < parts.length; i++) {
                    if (i > 5) tokenBuilder.append("/");
                    tokenBuilder.append(parts[i]);
                }
                String fullToken = tokenBuilder.toString();
                
                // Remove anything after "!" or "?"
                String token = fullToken.split("[!?]")[0];
                
                // Use ci.xiaohongshu.com endpoint like the original Python project for more reliable image serving
                return "https://ci.xiaohongshu.com/" + token;
            }
        }
        
        // Return the original URL if no transformation is needed
        return originalUrl;
    }

    /**
     * Gets the media count from a XHS URL
     * @param inputUrl The URL to get media count for
     * @return The number of media items or 0 if not found
     */
    public int getMediaCount(String inputUrl) {
        try {
            // Extract all valid XHS URLs from the input
            List<String> urls = extractLinks(inputUrl);

            if (urls.isEmpty()) {
                Log.e(TAG, "No valid XHS URLs found for media count");
                return 0;
            }

            for (String url : urls) {
                // Get the post ID from the URL
                String postId = extractPostId(url);

                if (postId != null) {
                    // Fetch the post details
                    String postDetails = fetchPostDetails(url);

                    if (postDetails != null) {
                        // Parse the post details to extract media URLs
                        List<String> mediaUrls = parsePostDetails(postDetails);
                        int count = mediaUrls.size();
                        // 如果启用了 Live Photo 合成，则每对图+视频最终只生成一个文件，进度应按合成后的数量计算
                        if (shouldCreateLivePhotos() && this.livePhotoPairs != null && !this.livePhotoPairs.isEmpty()) {
                            count -= this.livePhotoPairs.size(); // 每对减少一次计数
                            if (count < 0) count = 0;
                        }
                        return count; // Return number of resulting media items
                    } else {
                        Log.e(TAG, "Failed to fetch post details for media count: " + url);
                    }
                } else {
                    Log.e(TAG, "Could not extract post ID from URL for media count: " + url);
                }
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error in getMediaCount: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Gets the description of a note from its URL
     * @param inputUrl The URL of the note to get description for
     * @return The description text or null if not found
     */
    public String getNoteDescription(String inputUrl) {
        try {
            // Extract all valid XHS URLs from the input
            List<String> urls = extractLinks(inputUrl);
            
            if (urls.isEmpty()) {
                Log.e(TAG, "No valid XHS URLs found");
                return null;
            }
            
            Log.d(TAG, "Found " + urls.size() + " XHS URLs to process");
            
            for (String url : urls) {
                // Get the post ID from the URL
                String postId = extractPostId(url);
                
                if (postId != null) {
                    // Fetch the post details
                    String postDetails = fetchPostDetails(url);
                    
                    if (postDetails != null) {
                        JSONObject root = parseInitialStateRootFromHtml(postDetails);
                        if (root != null) {
                            List<JSONObject> notes = findNoteObjects(root);
                            for (JSONObject note : notes) {
                                String desc = extractNoteDescription(note);
                                if (desc != null && !desc.isEmpty()) {
                                    Log.d(TAG, "Found description: " + desc);
                                    return desc;
                                }
                            }
                        }
                    }
                }
            }
            Log.e(TAG, "No description found in any note");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error in getNoteDescription: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public boolean hasVideosDetected() {
        return videosDetected;
    }

    public void resetVideosDetected() {
        videosDetected = false;
        videoWarningShown = false;
    }

    public void setShouldStopOnVideo(boolean shouldStop) {
        this.shouldStopOnVideo = shouldStop;
    }

    public void stopDownload() {
        this.shouldStopDownload = true;
        if (activeCall != null) {
            activeCall.cancel();
        }
        if (fileDownloader != null) {
            fileDownloader.cancel();
        }
    }

    public boolean shouldStopDownload() {
        return shouldStopDownload;
    }

    public void resetStopDownload() {
        this.shouldStopDownload = false;
    }

    protected void checkForStop() throws InterruptedException {
        if (shouldStopDownload) {
            throw new InterruptedException("Download stopped by user request");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Download thread was interrupted");
        }
    }

    protected boolean shouldStop() {
        return shouldStopDownload || Thread.currentThread().isInterrupted();
    }


}
