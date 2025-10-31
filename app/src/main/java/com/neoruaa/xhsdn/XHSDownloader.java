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

    public XHSDownloader(Context context) {
        this(context, null);
    }

    public XHSDownloader(Context context, DownloadCallback callback) {
        this.context = context;
        this.httpClient = new OkHttpClient();
        this.downloadUrls = new ArrayList<>();
        this.downloadCallback = callback;
    }
    
    public boolean downloadFile(String url, String filename) {
        // Use the FileDownloader class to handle the actual download
        FileDownloader downloader = new FileDownloader(this.context, this.downloadCallback);
        return downloader.downloadFile(url, filename);
    }
    
    public boolean downloadContent(String inputUrl) {
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
            
            for (String url : urls) {
                // Get the post ID from the URL
                String postId = extractPostId(url);
                
                if (postId != null) {
                    // Fetch the post details
                    String postDetails = fetchPostDetails(url);
                    
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
                                postHasErrors = createLivePhotos(postId, mediaUrls);
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
                                            
                                            return downloadFile(mediaUrl, fileNameWithExtension);
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
                                        
                                        boolean success = downloadFile(mediaUrl, fileNameWithExtension);
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
                            Log.e(TAG, "No media URLs found in post: " + postId);
                            // Notify the callback about this issue
                            if (downloadCallback != null) {
                                downloadCallback.onDownloadError("No media URLs found in post: " + postId, url);
                            }
                            hasErrors = true; // Consider this an error condition
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch post details for: " + url);
                        // Notify the callback about this issue
                        if (downloadCallback != null) {
                            downloadCallback.onDownloadError("Failed to fetch post details for: " + url, url);
                        }
                        hasErrors = true;
                    }
                } else {
                    Log.e(TAG, "Could not extract post ID from URL: " + url);
                    // Notify the callback about this issue
                    if (downloadCallback != null) {
                        downloadCallback.onDownloadError("Could not extract post ID from URL: " + url, url);
                    }
                    hasErrors = true;
                }
            }
            
            // Return true only if we processed everything without errors
            // If we found content and had no errors, that's success
            // If we found no content or had errors, that's failure
            return !hasErrors;
        } catch (Exception e) {
            Log.e(TAG, "Error in downloadContent: " + e.getMessage());
            e.printStackTrace();
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
                        if (root.has("note") && root.getJSONObject("note").has("noteDetailMap")) {
                            JSONObject noteDetailMap = root.getJSONObject("note").getJSONObject("noteDetailMap");
                            
                            // Process each note in the map
                            for (int i = 0; i < noteDetailMap.names().length(); i++) {
                                String key = noteDetailMap.names().getString(i);
                                JSONObject noteData = noteDetailMap.getJSONObject(key);
                                
                                if (noteData.has("note")) {
                                    JSONObject note = noteData.getJSONObject("note");
                                    
                                    // Debug logging to trace execution path
                                    Log.d(TAG, "Processing note object");
                                    
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
                                    
                                    // Check if it's an image post
                                    if (note.has("imageList")) {
                                        JSONArray imageList = note.getJSONArray("imageList");
                                        for (int j = 0; j < imageList.length(); j++) {
                                            JSONObject image = imageList.getJSONObject(j);
                                            
                                            // Store image URL
                                            String imageUrl = null;
                                            if (image.has("urlDefault")) {
                                                imageUrl = image.getString("urlDefault");
                                            } else if (image.has("traceId")) {
                                                // Construct URL from traceId if needed
                                                String traceId = image.getString("traceId");
                                                imageUrl = "https://sns-img-qc.xhscdn.com/" + traceId;
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
                                                    mediaPairs.add(new MediaPair(imageUrl, livePhotoVideoUrl, true)); // paired live photo
                                                } else {
                                                    mediaPairs.add(new MediaPair(imageUrl, null, false)); // single image
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing JSON: " + e.getMessage());
                        
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
        
        // If we have media pairs, process them and add to main mediaUrls
        // But preserve any existing mediaUrls (like videos from note.video section)
        List<String> existingMediaUrls = new ArrayList<>(mediaUrls); // Preserve existing URLs
        if (!mediaPairs.isEmpty()) {
            mediaUrls.clear(); // Clear the existing mediaUrls to start fresh with properly paired items
            for (MediaPair pair : mediaPairs) {
                if (pair.isLivePhoto) {
                    // Add both image and video for live photo
                    mediaUrls.add(pair.imageUrl);
                    if (pair.videoUrl != null) {
                        mediaUrls.add(pair.videoUrl);
                    }
                } else {
                    // Add just the image
                    mediaUrls.add(pair.imageUrl);
                }
            }
            // Add back any existing media URLs that weren't part of the pairs
            for (String existingUrl : existingMediaUrls) {
                if (!mediaUrls.contains(existingUrl)) {
                    mediaUrls.add(existingUrl);
                }
            }
        }
        
        // Clear the URL mapping before processing new URLs
        urlMapping.clear();
        
        // Process media URLs to transform xhscdn.com URLs to the new format
        for (int i = 0; i < mediaUrls.size(); i++) {
            String originalUrl = mediaUrls.get(i);
            Log.d(TAG, "Original URL: " + originalUrl);
            String transformedUrl = transformXhsCdnUrl(originalUrl);
            // Store the mapping between transformed URL and original URL
            urlMapping.put(transformedUrl, originalUrl);
            mediaUrls.set(i, transformedUrl);
        }
        
        Log.d(TAG, "Found " + mediaUrls);
        return mediaUrls;
    }
    
    /**
     * Class to hold image-video pairs for live photos
     */
    private static class MediaPair {
        String imageUrl;
        String videoUrl;
        boolean isLivePhoto;
        
        MediaPair(String imageUrl, String videoUrl, boolean isLivePhoto) {
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.isLivePhoto = isLivePhoto;
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
     * @return true if there were errors, false otherwise
     */
    private boolean createLivePhotos(String postId, List<String> mediaUrls) {
        boolean hasErrors = false;
        
        // Process media URLs in pairs: [image, video, image, video, ...] for live photos
        // Regular images/videos that are not part of live photos are handled separately
        for (int i = 0; i < mediaUrls.size(); i++) {
            String currentUrl = mediaUrls.get(i);
            
            if (isVideoUrl(currentUrl)) {
                // If current is a video and there's no preceding image in a pair, download separately
                String uniqueFileName = postId + "_video_" + (i + 1);
                String fileExtension = determineFileExtension(currentUrl);
                String fileNameWithExtension = uniqueFileName + "." + fileExtension;
                
                boolean success = downloadFile(currentUrl, fileNameWithExtension);
                if (!success) {
                    Log.e(TAG, "Failed to download video: " + currentUrl);
                    hasErrors = true;
                }
            } else {
                // Current is an image - check if next item is a video (for live photo pair)
                if (i + 1 < mediaUrls.size() && isVideoUrl(mediaUrls.get(i + 1))) {
                    // This is a live photo pair (image + video)
                    String imageUrl = currentUrl;
                    String videoUrl = mediaUrls.get(i + 1);
                    
                    try {
                        // Create a temporary downloader that downloads to the app's internal storage
                        FileDownloader tempDownloader = new FileDownloader(context, null); // No callback to avoid premature notification
                        
                        // Download the image to a temporary location (app's internal storage)
                        String imageFileName = postId + "_img_" + ((i/2) + 1) + "." + determineFileExtension(imageUrl);
                        boolean imageDownloaded = tempDownloader.downloadFileToInternalStorage(imageUrl, imageFileName);
                        if (!imageDownloaded) {
                            Log.e(TAG, "Failed to download image for live photo: " + imageUrl);
                            hasErrors = true;
                            // Skip video download too
                            i++; // Skip the paired video since image failed
                            continue;
                        }
                        
                        // Download the video to a temporary location (app's internal storage)
                        String videoFileName = postId + "_vid_" + ((i/2) + 1) + "." + determineFileExtension(videoUrl);
                        boolean videoDownloaded = tempDownloader.downloadFileToInternalStorage(videoUrl, videoFileName);
                        if (!videoDownloaded) {
                            Log.e(TAG, "Failed to download video for live photo: " + videoUrl);
                            hasErrors = true;
                            // Clean up the already downloaded image file
                            File alreadyDownloadedImage = new File(context.getExternalFilesDir(null), "xhs_" + imageFileName);
                            if (alreadyDownloadedImage.exists()) {
                                alreadyDownloadedImage.delete();
                            }
                            i++; // Skip the paired video since it failed
                            continue;
                        }
                        
                        // The files are downloaded to internal storage with "xhs_" prefix
                        File actualTempImageFile = new File(context.getExternalFilesDir(null), "xhs_" + imageFileName);
                        File actualTempVideoFile = new File(context.getExternalFilesDir(null), "xhs_" + videoFileName);
                        
                        if (!actualTempImageFile.exists() || !actualTempVideoFile.exists()) {
                            Log.e(TAG, "Downloaded temporary files do not exist. Image: " + actualTempImageFile.exists() + ", Video: " + actualTempVideoFile.exists());
                            hasErrors = true;
                            i++; // Skip the paired video
                            continue;
                        }
                        
                        // Get the destination directory for final save
                        SharedPreferences prefs = context.getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
                        String customSavePath = prefs.getString("custom_save_path", null);
                        
                        File destinationDir;
                        if (customSavePath != null && !customSavePath.isEmpty()) {
                            destinationDir = new File(customSavePath);
                        } else {
                            File publicDownloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                            if (publicDownloadsDir != null) {
                                destinationDir = new File(publicDownloadsDir, "xhs");
                            } else {
                                destinationDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                            }
                        }
                        
                        if (!destinationDir.exists()) {
                            destinationDir.mkdirs();
                        }
                        
                        // Create the live photo in the final destination
                        String livePhotoFileName = postId + "_live_" + ((i/2) + 1) + ".jpg";
                        File livePhotoFile = new File(destinationDir, "xhs_" + livePhotoFileName);
                        
                        boolean livePhotoCreated = LivePhotoCreator.createLivePhoto(actualTempImageFile, actualTempVideoFile, livePhotoFile);
                        
                        if (livePhotoCreated) {
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
                            Log.e(TAG, "Failed to create live photo from image: " + actualTempImageFile.getAbsolutePath() + 
                                   " and video: " + actualTempVideoFile.getAbsolutePath());
                            hasErrors = true;
                            
                            // If live photo creation failed, download the files separately to final location
                            // by using the main downloadFile method which handles the callback
                            downloadFile(imageUrl, imageFileName.replace("xhs_", ""));
                            downloadFile(videoUrl, videoFileName.replace("xhs_", ""));
                            
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
                    
                    i++; // Skip the next item since it was paired with current image
                } else {
                    // Single image without corresponding video - download separately
                    String uniqueFileName = postId + "_image_" + ((i+1) / 2 + 1);
                    String fileExtension = determineFileExtension(currentUrl);
                    String fileNameWithExtension = uniqueFileName + "." + fileExtension;
                    
                    boolean success = downloadFile(currentUrl, fileNameWithExtension);
                    if (!success) {
                        Log.e(TAG, "Failed to download image: " + currentUrl);
                        hasErrors = true;
                    }
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
     * 解析短链接，获取重定向后的真实URL
     * @param shortUrl 短链接
     * @return 重定向后的完整URL，如果失败则返回null
     */
    private String resolveShortUrl(String shortUrl) {
        try {
            // 创建一个GET请求来获取重定向的URL（GET请求通常会自动跟踪重定向）
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(shortUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
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
    private String transformXhsCdnUrl(String originalUrl) {
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
}