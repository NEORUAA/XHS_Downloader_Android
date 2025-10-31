package com.neoruaa.xhsdn;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    private static final Pattern XHS_LINK_PATTERN = Pattern.compile("(?:https?://)?www\\\\.xiaohongshu\\\\.com/explore/\\\\S+");
    private static final Pattern XHS_USER_PATTERN = Pattern.compile("(?:https?://)?www\\\\.xiaohongshu\\\\.com/user/profile/[a-z0-9]+/\\\\S+");
    private static final Pattern XHS_SHARE_PATTERN = Pattern.compile("(?:https?://)?www\\\\.xiaohongshu\\\\.com/discovery/item/\\\\S+");
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
                            Log.d(TAG, "Found " + mediaUrls);
                            // Download each media file with unique names
                            for (int i = 0; i < mediaUrls.size(); i++) {
                                String mediaUrl = mediaUrls.get(i);
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
                                } else {
                                    Log.d(TAG, "Successfully downloaded: " + mediaUrl);
                                }
                            }
                        } else {
                            Log.e(TAG, "No media URLs found in post: " + postId);
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch post details for: " + url);
                    }
                } else {
                    Log.e(TAG, "Could not extract post ID from URL: " + url);
                }
            }
            
            return true;
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
        List<String> livePhotoUrls = new ArrayList<>(); // 存储Live Photo视频链接
        
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
                                    
                                    // Check if it's a video post
                                    if (note.has("video")) {
                                        JSONObject video = note.getJSONObject("video");
                                        // 从consumer.originVideoKey构建视频URL（模仿Python代码）
                                        if (video.has("consumer") && video.getJSONObject("consumer").has("originVideoKey")) {
                                            String originVideoKey = video.getJSONObject("consumer").getString("originVideoKey");
                                            String videoUrl = "https://sns-video-bd.xhscdn.com/" + originVideoKey;
                                            mediaUrls.add(videoUrl);
                                        }
                                        // 备用方案：检查media.stream.h264
                                        else if (video.has("media")) {
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
                                                                mediaUrls.add(url);
                                                            }
                                                        } else if (h264Obj instanceof JSONObject) {
                                                            JSONObject h264Json = (JSONObject) h264Obj;
                                                            // 尝试获取URL字段
                                                            if (h264Json.has("url")) {
                                                                mediaUrls.add(h264Json.getString("url"));
                                                            } else if (h264Json.has("masterUrl")) {
                                                                mediaUrls.add(h264Json.getString("masterUrl"));
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    // Check if it's an image post
                                    if (note.has("imageList")) {
                                        JSONArray imageList = note.getJSONArray("imageList");
                                        for (int j = 0; j < imageList.length(); j++) {
                                            JSONObject image = imageList.getJSONObject(j);
                                            
                                            // 添加Live Photo（动图）视频链接
                                            if (image.has("stream")) {
                                                JSONObject stream = image.getJSONObject("stream");
                                                if (stream.has("h264") && stream.getJSONArray("h264").length() > 0) {
                                                    Object h264Obj = stream.getJSONArray("h264").get(0);
                                                    if (h264Obj instanceof JSONObject) {
                                                        JSONObject h264Json = (JSONObject) h264Obj;
                                                        if (h264Json.has("masterUrl")) {
                                                            String livePhotoUrl = h264Json.getString("masterUrl");
                                                            if (livePhotoUrl.startsWith("http")) {
                                                                livePhotoUrls.add(livePhotoUrl);
                                                            }
                                                        } else if (h264Json.has("url")) {
                                                            String livePhotoUrl = h264Json.getString("url");
                                                            if (livePhotoUrl.startsWith("http")) {
                                                                livePhotoUrls.add(livePhotoUrl);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            if (image.has("urlDefault")) {
                                                mediaUrls.add(image.getString("urlDefault"));
                                            } else if (image.has("traceId")) {
                                                // Construct URL from traceId if needed
                                                String traceId = image.getString("traceId");
                                                // This is a simplified example - actual URLs may vary
                                                mediaUrls.add("https://sns-img-qc.xhscdn.com/" + traceId);
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
        
        // Process and add live photo URLs to the main list, maintaining mapping
        for (String livePhotoUrl : livePhotoUrls) {
            Log.d(TAG, "Original URL: " + livePhotoUrl);
            String transformedUrl = transformXhsCdnUrl(livePhotoUrl);
            // Store the mapping between transformed URL and original URL
            urlMapping.put(transformedUrl, livePhotoUrl);
            mediaUrls.add(transformedUrl);
        }
        
        Log.d(TAG, "Found " + mediaUrls);
        return mediaUrls;
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