package com.neoruaa.xhsdn;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class XHSDownloader {
    private static final String TAG = "XHSDownloader";
    private Context context;
    private OkHttpClient httpClient;
    private List<String> downloadUrls;
    
    // Regex patterns for URL matching
    private static final Pattern XHS_LINK_PATTERN = Pattern.compile("(?:https?://)?www\\.xiaohongshu\\.com/explore/\\S+");
    private static final Pattern XHS_USER_PATTERN = Pattern.compile("(?:https?://)?www\\.xiaohongshu\\.com/user/profile/[a-z0-9]+/\\S+");
    private static final Pattern XHS_SHARE_PATTERN = Pattern.compile("(?:https?://)?www\\.xiaohongshu\\.com/discovery/item/\\S+");
    private static final Pattern XHS_SHORT_PATTERN = Pattern.compile("(?:https?://)?xhslink\\.com/[^\\s\\\"<>\\\\\\^`{|}，。；！？、【】《》]+");
    
    private DownloadCallback downloadCallback;
    // Map to store the relationship between transformed URLs and original URLs for fallback
    private java.util.Map<String, String> urlMapping = new java.util.HashMap<>();
    
    // Track successful downloads for the overall download result
    private int successfulDownloads = 0;

    // Store live photo pairs to distinguish them from regular videos
    private List<LivePhotoPair> livePhotoPairs = new ArrayList<>();

    public XHSDownloader(Context context) {
        this(context, null);
    }

    public XHSDownloader(Context context, DownloadCallback callback) {
        this.context = context;
        this.httpClient = new OkHttpClient();
        this.downloadUrls = new ArrayList<>();
        // Wrap the original callback to track successful downloads
        if (callback != null) {
            this.downloadCallback = new DownloadCallback() {
                @Override
                public void onFileDownloaded(String filePath) {
                    successfulDownloads++; // Increment counter when a file is successfully downloaded
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
            };
        } else {
            this.downloadCallback = callback;
        }
    }
    
    public boolean downloadFile(String url, String filename) {
        // Use the FileDownloader class to handle the actual download
        FileDownloader downloader = new FileDownloader(this.context, this.downloadCallback);
        return downloader.downloadFile(url, filename);
    }

    public boolean downloadFile(String url, String filename, String timestamp) {
        // Use the FileDownloader class to handle the actual download with timestamp
        FileDownloader downloader = new FileDownloader(this.context, this.downloadCallback);
        return downloader.downloadFile(url, filename, timestamp);
    }
    
    public boolean downloadContent(String inputUrl) {
        // Reset successful downloads counter for this download session
        this.successfulDownloads = 0;
        boolean hasErrors = false; // Track if any errors occurred
        boolean hasContent = false; // Track if we found any content to download
        try {
            // Extract all valid XHS URLs from the input
            List<String> urls = extractLinks(inputUrl);

            if (urls.isEmpty()) {
                Log.e(TAG, "No valid XHS URLs found");
                return false;
            }

            Log.d(TAG, "Found " + urls.size() + " XHS URLs to process");

            // Generate a single date-based timestamp for the entire download session (YYMMDD format)
            String sessionTimestamp = new java.text.SimpleDateFormat("yyMMdd", java.util.Locale.getDefault()).format(new java.util.Date());

            for (String url : urls) {
                // Get the post ID from the URL
                String postId = extractPostId(url);

                if (postId != null) {
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
                                            String uniqueFileName = postId + "_" + (index + 1); // Use index to create unique name

                                            // Determine file extension based on URL content
                                            String fileExtension = determineFileExtension(mediaUrl);
                                            String fileNameWithExtension = uniqueFileName + "." + fileExtension;

                                            // Use the session timestamp to maintain consistency across the download session
                                            return downloadFile(mediaUrl, fileNameWithExtension, sessionTimestamp);
                                        });
                                        futures.add(future);
                                    }
                                    
                                    // Wait for all downloads to complete and collect results
                                    for (int i = 0; i < futures.size(); i++) {
                                        try {
                                            boolean success = futures.get(i).get();
                                            String mediaUrl = allMediaUrls.get(i);
                                            if (!success) {
                                                Log.e(TAG, "Failed to download: " + mediaUrl);
                                                // Notify the callback about the download error with the original URL
                                                if (downloadCallback != null) {
                                                    // Look up the original URL in the mapping
                                                    String originalUrl = urlMapping.get(mediaUrl);
                                                    if (originalUrl != null) {
                                                        downloadCallback.onDownloadError("Failed to download: " + mediaUrl, originalUrl);
                                                    } else {
                                                        // If no mapping exists, use the URL as is
                                                        downloadCallback.onDownloadError("Failed to download: " + mediaUrl, mediaUrl);
                                                    }
                                                }
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
                                        String mediaUrl = allMediaUrls.get(i);
                                        String uniqueFileName = postId + "_" + (i + 1); // Use index to create unique name
                                        
                                        // Determine file extension based on URL content
                                        String fileExtension = determineFileExtension(mediaUrl);
                                        String fileNameWithExtension = uniqueFileName + "." + fileExtension;
                                        
                                        boolean success = downloadFile(mediaUrl, fileNameWithExtension, sessionTimestamp);
                                        if (!success) {
                                            Log.e(TAG, "Failed to download: " + mediaUrl);
                                            // Notify the callback about the download error with the original URL
                                            if (downloadCallback != null) {
                                                // Look up the original URL in the mapping
                                                String originalUrl = urlMapping.get(mediaUrl);
                                                if (originalUrl != null) {
                                                    downloadCallback.onDownloadError("Failed to download: " + mediaUrl, originalUrl);
                                                } else {
                                                    // If no mapping exists, use the URL as is
                                                    downloadCallback.onDownloadError("Failed to download: " + mediaUrl, mediaUrl);
                                                }
                                            }
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
            boolean overallSuccess = successfulDownloads > 0;
            
            Log.d(TAG, "Download process completed with " + successfulDownloads + " successful downloads, hasErrors: " + hasErrors);
            
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
        
        // 按空格分割输入，模仿原Python项目的逻辑
        String[] parts = input.split("\\s+");
        
        for (String part : parts) {
            // 确保部分不为空
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            
            String processedPart = part;
            
            // 检查短链接格式 (xhslink.com)
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
        // xhslink.com/路径格式，ID通常在路径的最后一部分
        if (url.contains("xhslink.com/")) {
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
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=1.0,image/avif,image/webp,image/apng,*/*;q=1.0")
                    .build();
            
            Response response = httpClient.newCall(request).execute();
            
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
    
    public List<String> parsePostDetails(String html) {
        List<String> mediaUrls = new ArrayList<>();
        // Create pairs of images and their corresponding live photo videos
        List<MediaPair> mediaPairs = new ArrayList<>();

        // Look for JSON data in the HTML that contains media information
        // This is a simplified approach - in a real implementation, you'd need to parse the actual structure

        // Look for JSON data containing media URLs
        int startIndex = html.indexOf("window.__INITIAL_STATE__=");
        if (startIndex != -1) {
            int endIndex = html.indexOf("</script>", startIndex);
            if (endIndex != -1) {
                String scriptContent = html.substring(startIndex, endIndex);

                // Extract the JSON part
                int jsonStart = scriptContent.indexOf("=");
                if (jsonStart != -1) {
                    String jsonData = scriptContent.substring(jsonStart + 1).trim();

                    try {
                        JSONObject root = new JSONObject(jsonData);

                        // Navigate through the JSON structure to find media URLs
                        // Try multiple approaches to handle different JSON structures
                        if (root.has("note")) {
                            JSONObject noteRoot = root.getJSONObject("note");

                            // Try to get from noteDetailMap first (most common structure)
                            if (noteRoot.has("noteDetailMap")) {
                                JSONObject noteDetailMap = noteRoot.getJSONObject("noteDetailMap");

                                // Get all the keys in the noteDetailMap
                                JSONArray keys = noteDetailMap.names();
                                if (keys != null && keys.length() > 0) {
                                    // Process each note in the map - there might be multiple notes or just one
                                    for (int i = 0; i < keys.length(); i++) {
                                        String key = keys.getString(i);
                                        JSONObject noteData = noteDetailMap.getJSONObject(key);

                                        if (noteData.has("note")) {
                                            // Found the actual note data
                                            JSONObject note = noteData.getJSONObject("note");
                                            mediaUrls.addAll(extractMediaUrlsFromNote(note, mediaPairs));
                                        }
                                    }
                                }
                            } else {
                                // Alternative structures - try different approaches
                                // 1. Direct note in noteRoot
                                if (noteRoot.has("note")) {
                                    JSONObject note = noteRoot.getJSONObject("note");
                                    mediaUrls.addAll(extractMediaUrlsFromNote(note, mediaPairs));
                                }
                                // 2. Feed structure
                                else if (noteRoot.has("feed")) {
                                    JSONObject feed = noteRoot.getJSONObject("feed");
                                    if (feed.has("items") && feed.getJSONArray("items").length() > 0) {
                                        JSONArray items = feed.getJSONArray("items");
                                        for (int i = 0; i < items.length(); i++) {
                                            JSONObject note = items.getJSONObject(i);
                                            mediaUrls.addAll(extractMediaUrlsFromNote(note, mediaPairs));
                                        }
                                    }
                                }
                                // 3. Try as a direct note structure (fallback)
                                else {
                                    mediaUrls.addAll(extractMediaUrlsFromNote(noteRoot, mediaPairs));
                                }
                            }
                        }
                        // Fallback: Check if root itself contains the note data directly
                        else if (root.has("feed")) {
                            JSONObject feed = root.getJSONObject("feed");
                            if (feed.has("items") && feed.getJSONArray("items").length() > 0) {
                                JSONArray items = feed.getJSONArray("items");
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject note = items.getJSONObject(i);
                                    mediaUrls.addAll(extractMediaUrlsFromNote(note, mediaPairs));
                                }
                            }
                        }
                        // Another fallback - could be in different top-level structure
                        else {
                            // Try to find any note-like object in the root
                            JSONArray names = root.names();
                            if (names != null) {
                                for (int i = 0; i < names.length(); i++) {
                                    String key = names.getString(i);
                                    Object value = root.get(key);
                                    if (value instanceof JSONObject) {
                                        JSONObject obj = (JSONObject) value;
                                        if (obj.has("note") || obj.has("imageList") || obj.has("video")) {
                                            mediaUrls.addAll(extractMediaUrlsFromNote(obj, mediaPairs));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing JSON: " + e.getMessage());

                        // Add more detailed logging to understand what's in the JSON
                        try {
                            // Log the first 500 characters of JSON to see structure
                            Log.d(TAG, "Full JSON data (first 500 chars): " + jsonData.substring(0, Math.min(500, jsonData.length())));
                        } catch (Exception ex) {
                            Log.e(TAG, "Error logging JSON: " + ex.getMessage());
                        }

                        // Fallback - try to extract URLs directly from HTML
                        // This handles cases where the structured JSON isn't available
                        mediaUrls.addAll(extractUrlsFromHtml(html));
                    }
                }
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
                }
                // 备用方案：检查media.stream.h264
                else if (video.has("media")) {
                    Log.d(TAG, "Found media field in video");
                    JSONObject media = video.getJSONObject("media");
                    if (media.has("stream")) {
                        JSONObject stream = media.getJSONObject("stream");
                        if (stream.has("h264")) {
                            JSONArray h264Array = stream.getJSONArray("h264");
                            for (int j = 0; j < h264Array.length(); j++) {
                                Object h264Obj = h264Array.get(j);
                                // 如果是字符串，直接使用；如果是JSON对象，提取URL字段
                                if (h264Obj instanceof String) {
                                    String url = (String) h264Obj;
                                    // 确保这是一个有效的URL而不是JSON字符串
                                    if (url.startsWith("http")) {
                                        Log.d(TAG, "Extracted video URL from h264 string: " + url);
                                        mediaUrls.add(url);
                                    }
                                } else if (h264Obj instanceof JSONObject) {
                                    JSONObject h264Json = (JSONObject) h264Obj;
                                    // 尝试获取URL字段
                                    if (h264Json.has("url")) {
                                        Log.d(TAG, "Extracted video URL from h264.url: " + h264Json.getString("url"));
                                        mediaUrls.add(h264Json.getString("url"));
                                    } else if (h264Json.has("masterUrl")) {
                                        Log.d(TAG, "Extracted video URL from h264.masterUrl: " + h264Json.getString("masterUrl"));
                                        mediaUrls.add(h264Json.getString("masterUrl"));
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
                    
                    // Check for corresponding Live Photo video
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
                    
                    // Add to media pairs - either paired or as single image
                    if (imageUrl != null) {
                        if (livePhotoVideoUrl != null) {
                            Log.d(TAG, "Matched live photo: image=" + imageUrl + ", video=" + livePhotoVideoUrl);
                            mediaPairs.add(new MediaPair(imageUrl, livePhotoVideoUrl, true)); // paired live photo with original URLs
                        } else {
                            mediaPairs.add(new MediaPair(imageUrl, null, false)); // single image with original URL
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
            if (note.has("desc")) {
                return note.getString("desc");
            } else if (note.has("description")) {
                return note.getString("description");
            } else if (note.has("title")) {
                // If desc not found, return title as fallback
                return note.getString("title");
            }
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
                String imageFileName = postId + "_img_" + livePhotoIndex + "." + determineFileExtension(imageUrl);
                boolean imageDownloaded = tempDownloader.downloadFileToInternalStorage(imageUrl, imageFileName, timestamp);
                if (!imageDownloaded) {
                    Log.e(TAG, "Failed to download image for live photo: " + imageUrl);
                    hasErrors = true;
                    continue; // Skip to next live photo pair
                }

                // Download the video to a temporary location (app's internal storage)
                String videoFileName = postId + "_vid_" + livePhotoIndex + "." + determineFileExtension(videoUrl);
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

                // Always use MediaStore directory with "xhs" subfolder for consistent location
                File destinationDir;
                File publicPicturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES);
                if (publicPicturesDir != null) {
                    destinationDir = new File(publicPicturesDir, "xhs");
                } else {
                    destinationDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                }

                if (!destinationDir.exists()) {
                    destinationDir.mkdirs();
                }

                // Create the live photo in the final destination
                String livePhotoFileName = postId + "_live_" + livePhotoIndex + ".jpg";
                File livePhotoFile = new File(destinationDir, "xhs_" + timestamp + "_" + livePhotoFileName);

                Log.d(TAG, "Creating live photo with image: " + actualTempImageFile.getAbsolutePath() +
                       " and video: " + actualTempVideoFile.getAbsolutePath() +
                       " -> output: " + livePhotoFile.getAbsolutePath());

                boolean livePhotoCreated = LivePhotoCreator.createLivePhoto(actualTempImageFile, actualTempVideoFile, livePhotoFile);

                if (livePhotoCreated) {
                    // Additional check: verify if the created live photo file can be opened by checking if it was created properly
                    if (livePhotoFile.exists() && livePhotoFile.length() > 0) {
                        // Notify the callback that the live photo has been downloaded
                        if (downloadCallback != null) {
                            downloadCallback.onFileDownloaded(livePhotoFile.getAbsolutePath());
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
                        downloadCallback.onDownloadError(fallbackMessage,
                            "Live photo creation for " + postId + " (item " + livePhotoIndex + ")");
                    }

                    // Only download separately if the downloadFile calls were successful
                    boolean imageDownloadedFallback = downloadFile(imageUrl, imageFileName.replace("xhs_", ""), timestamp);
                    boolean videoDownloadedFallback = downloadFile(videoUrl, videoFileName.replace("xhs_", ""), timestamp);

                    Log.d(TAG, "Fallback download - Image: " + (imageDownloadedFallback ? "Success" : "Failed") +
                           ", Video: " + (videoDownloadedFallback ? "Success" : "Failed"));

                    // Notify the callback if the separate downloads were successful
                    // Only notify once per live photo pair that couldn't be merged
                    boolean anySeparateFilesDownloaded = imageDownloadedFallback || videoDownloadedFallback;
                    if (downloadCallback != null && anySeparateFilesDownloaded) {
                        if (imageDownloadedFallback) {
                            File separateImageFile = new File(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                                "xhs_" + timestamp + "_" + imageFileName.replace("xhs_", "")
                            );
                            if (separateImageFile.exists()) {
                                downloadCallback.onFileDownloaded(separateImageFile.getAbsolutePath());
                            }
                        }
                        if (videoDownloadedFallback) {
                            File separateVideoFile = new File(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                                "xhs_" + timestamp + "_" + videoFileName.replace("xhs_", "")
                            );
                            if (separateVideoFile.exists()) {
                                downloadCallback.onFileDownloaded(separateVideoFile.getAbsolutePath());
                            }
                        }
                    } else if (downloadCallback != null && !anySeparateFilesDownloaded) {
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
                String uniqueFileName = postId + "_" + (isVideoUrl(mediaUrl) ? "video" : "image") + "_" +
                    (livePhotoIndex + 1); // Use an index that accounts for already processed live photos
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
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 xiaohongshu")
                    .build();
            
            // 同步执行请求
            okhttp3.Response response = httpClient.newCall(request).execute();
            
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
                        return mediaUrls.size(); // Return number of media items found
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
                        // Navigate through the JSON structure to find the description
                        JSONObject root = null;
                        // Look for JSON data in the HTML that contains media information
                        int startIndex = postDetails.indexOf("window.__INITIAL_STATE__=");
                        if (startIndex != -1) {
                            int endIndex = postDetails.indexOf("</script>", startIndex);
                            if (endIndex != -1) {
                                String scriptContent = postDetails.substring(startIndex, endIndex);
                                
                                // Extract the JSON part
                                int jsonStart = scriptContent.indexOf("=");
                                if (jsonStart != -1) {
                                    String jsonData = scriptContent.substring(jsonStart + 1).trim();
                                    
                                    try {
                                        root = new JSONObject(jsonData);
                                        
                                        // Try to get from different possible structure paths (similar to parsePostDetails)
                                        if (root.has("note")) {
                                            JSONObject noteRoot = root.getJSONObject("note");
                                            
                                            // Try to get from noteDetailMap first (most common structure)
                                            if (noteRoot.has("noteDetailMap")) {
                                                JSONObject noteDetailMap = noteRoot.getJSONObject("noteDetailMap");
                                                
                                                // Get all the keys in the noteDetailMap
                                                JSONArray keys = noteDetailMap.names();
                                                if (keys != null && keys.length() > 0) {
                                                    // Process each note in the map - there might be multiple notes or just one
                                                    for (int i = 0; i < keys.length(); i++) {
                                                        String key = keys.getString(i);
                                                        JSONObject noteData = noteDetailMap.getJSONObject(key);
                                                        
                                                        if (noteData.has("note")) {
                                                            // Found the actual note data
                                                            JSONObject note = noteData.getJSONObject("note");
                                                            String desc = extractNoteDescription(note);
                                                            if (desc != null && !desc.isEmpty()) {
                                                                Log.d(TAG, "Found description: " + desc);
                                                                return desc;
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                // Alternative structures - try different approaches
                                                // 1. Direct note in noteRoot
                                                if (noteRoot.has("note")) {
                                                    JSONObject note = noteRoot.getJSONObject("note");
                                                    String desc = extractNoteDescription(note);
                                                    if (desc != null && !desc.isEmpty()) {
                                                        Log.d(TAG, "Found description: " + desc);
                                                        return desc;
                                                    }
                                                } 
                                                // 2. Feed structure
                                                else if (noteRoot.has("feed")) {
                                                    JSONObject feed = noteRoot.getJSONObject("feed");
                                                    if (feed.has("items") && feed.getJSONArray("items").length() > 0) {
                                                        JSONArray items = feed.getJSONArray("items");
                                                        for (int i = 0; i < items.length(); i++) {
                                                            JSONObject note = items.getJSONObject(i);
                                                            String desc = extractNoteDescription(note);
                                                            if (desc != null && !desc.isEmpty()) {
                                                                Log.d(TAG, "Found description: " + desc);
                                                                return desc;
                                                            }
                                                        }
                                                    }
                                                }
                                                // 3. Try as a direct note structure (fallback)
                                                else {
                                                    String desc = extractNoteDescription(noteRoot);
                                                    if (desc != null && !desc.isEmpty()) {
                                                        Log.d(TAG, "Found description: " + desc);
                                                        return desc;
                                                    }
                                                }
                                            }
                                        }
                                        // Fallback: Check if root itself contains the note data directly
                                        else if (root.has("feed")) {
                                            JSONObject feed = root.getJSONObject("feed");
                                            if (feed.has("items") && feed.getJSONArray("items").length() > 0) {
                                                JSONArray items = feed.getJSONArray("items");
                                                for (int i = 0; i < items.length(); i++) {
                                                    JSONObject note = items.getJSONObject(i);
                                                    String desc = extractNoteDescription(note);
                                                    if (desc != null && !desc.isEmpty()) {
                                                        Log.d(TAG, "Found description: " + desc);
                                                        return desc;
                                                    }
                                                }
                                            }
                                        }
                                        // Another fallback - could be in different top-level structure
                                        else {
                                            // Try to find any note-like object in the root
                                            JSONArray names = root.names();
                                            if (names != null) {
                                                for (int i = 0; i < names.length(); i++) {
                                                    String key = names.getString(i);
                                                    Object value = root.get(key);
                                                    if (value instanceof JSONObject) {
                                                        JSONObject obj = (JSONObject) value;
                                                        if (obj.has("note") || obj.has("imageList") || obj.has("video")) {
                                                            String desc = extractNoteDescription(obj);
                                                            if (desc != null && !desc.isEmpty()) {
                                                                Log.d(TAG, "Found description: " + desc);
                                                                return desc;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (JSONException e) {
                                        Log.e(TAG, "Error parsing JSON: " + e.getMessage());
                                    }
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
}