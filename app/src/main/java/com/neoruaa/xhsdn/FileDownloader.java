package com.neoruaa.xhsdn;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class FileDownloader {
    private static final String TAG = "FileDownloader";
    private OkHttpClient httpClient;
    private Context context;
    private DownloadCallback callback;
    
    public FileDownloader(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient();
        this.callback = null;
    }
    
    public FileDownloader(Context context, DownloadCallback callback) {
        this.context = context;
        // Configure OkHttpClient with performance optimizations
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)  // 30-second connection timeout
                .readTimeout(60, TimeUnit.SECONDS)     // 60-second read timeout
                .writeTimeout(30, TimeUnit.SECONDS)    // 30-second write timeout
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))  // Connection pooling
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)) // Enable HTTP/2
                .build();
        this.callback = callback;
    }
    
    public boolean downloadFile(String url, String fileName) {
        try {
            // Create the request
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=1.0,image/avif,image/webp,image/apng,*/*;q=1.0")
                    .addHeader("Referer", "https://www.xiaohongshu.com/")
                    .build();
            
            // Execute the request
            Response response = httpClient.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                // Get the file extension from the URL or Content-Type header
                String fileExtension = getFileExtension(response, url);
                String fullFileName = "xhs_" + fileName;
                
                // Check for custom save path in preferences
                SharedPreferences prefs = context.getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
                String customSavePath = prefs.getString("custom_save_path", null);
                
                File destinationDir;
                if (customSavePath != null && !customSavePath.isEmpty()) {
                    // Use custom save path
                    destinationDir = new File(customSavePath);
                    Log.d(TAG, "Using custom save path: " + customSavePath);
                } else {
                    // Try to use public Downloads directory first (requires permissions)
                    File publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (publicDownloadsDir != null) {
                        destinationDir = new File(publicDownloadsDir, "xhs");
                    } else {
                        destinationDir = null;
                    }
                    
                    // Check if we have permission to write to public directory
                    boolean canWriteToPublic = false;
                    if (destinationDir != null) {
                        try {
                            // Try to create the directory to test write permission
                            if (!destinationDir.exists()) {
                                canWriteToPublic = destinationDir.mkdirs();
                            } else {
                                // Try to create a temporary file to test write permission
                                File testFile = new File(destinationDir, ".test_permission");
                                canWriteToPublic = testFile.createNewFile();
                                if (canWriteToPublic) {
                                    testFile.delete();
                                }
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "Cannot write to public directory: " + e.getMessage());
                            canWriteToPublic = false;
                        }
                    }
                    
                    // If we can't write to public directory, fall back to app's private directory
                    if (!canWriteToPublic) {
                        Log.d(TAG, "Falling back to app's private directory");
                        destinationDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "xhs");
                    } else {
                        Log.d(TAG, "Using public Downloads directory");
                    }
                }
                
                // Ensure the directory exists
                if (!destinationDir.exists()) {
                    boolean dirCreated = destinationDir.mkdirs();
                    Log.d(TAG, "Directory creation result: " + dirCreated + " for " + destinationDir.getAbsolutePath());
                }
                
                // Create the destination file
                File destinationFile = new File(destinationDir, fullFileName);
                
                // Write the response body to the file
                ResponseBody body = response.body();
                if (body != null) {
                    InputStream inputStream = body.byteStream();
                    OutputStream outputStream = new FileOutputStream(destinationFile);
                    
                    // Increased buffer size for better throughput (64KB instead of 4KB)
                    byte[] buffer = new byte[65536]; // 64KB buffer
                    int bytesRead;
                    long totalBytesRead = 0;
                    long contentLength = body.contentLength();
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        
                        // Report progress updates less frequently to avoid UI thread contention
                        // Update every 1MB instead of every 256KB for better performance
                        if (callback != null && contentLength > 0) {
                            // Only report progress if we have a content length and it's not 0
                            // Limit progress updates to once per 256KB to avoid excessive callbacks
                            if (totalBytesRead % 262144 == 0 || totalBytesRead == contentLength) { // 256KB = 262144 bytes
                                callback.onDownloadProgressUpdate(totalBytesRead, contentLength);
                            }
                        }
                    }
                    
                    inputStream.close();
                    outputStream.close();
                    
                    Log.d(TAG, "Downloaded file: " + destinationFile.getAbsolutePath());
                    Log.d(TAG, "Total bytes: " + totalBytesRead);
                    Log.d(TAG, "File exists: " + destinationFile.exists());
                    Log.d(TAG, "File size: " + destinationFile.length());
                    
                    // 通知回调下载完成
                    if (callback != null) {
                        callback.onFileDownloaded(destinationFile.getAbsolutePath());
                    }
                    
                    return true;
                }
            } else {
                Log.e(TAG, "Download failed. Response code: " + response.code());
                if (response.body() != null) {
                    response.body().close();
                }
                
                // Notify the callback about the download error with the original URL
                if (callback != null) {
                    callback.onDownloadError("Download failed. Response code: " + response.code(), url);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error downloading file: " + e.getMessage());
            e.printStackTrace();
            
            // Notify the callback about the download error with the original URL
            if (callback != null) {
                callback.onDownloadError("IO Error downloading file: " + e.getMessage(), url);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception while downloading file: " + e.getMessage());
            e.printStackTrace();
            
            // Notify the callback about the download error with the original URL
            if (callback != null) {
                callback.onDownloadError("Security exception while downloading file: " + e.getMessage(), url);
            }
        }
        
        return false;
    }
    
    private String getFileExtension(Response response, String url) {
        // First try to get extension from Content-Type header
        String contentType = response.header("Content-Type");
        if (contentType != null) {
            if (contentType.contains("video")) {
                if (contentType.contains("mp4")) return "mp4";
                else if (contentType.contains("quicktime")) return "mov";
                else return "mp4"; // default video format
            } else if (contentType.contains("image")) {
                if (contentType.contains("jpeg")) return "jpg";
                else if (contentType.contains("png")) return "png";
                else if (contentType.contains("webp")) return "webp";
                else if (contentType.contains("gif")) return "gif";
                else return "jpg"; // default image format
            }
        }
        
        // If Content-Type doesn't help, try to extract from URL
        if (url.toLowerCase().contains(".jpg") || url.toLowerCase().contains(".jpeg")) {
            return "jpg";
        } else if (url.toLowerCase().contains(".png")) {
            return "png";
        } else if (url.toLowerCase().contains(".gif")) {
            return "gif";
        } else if (url.toLowerCase().contains(".mp4")) {
            return "mp4";
        } else if (url.toLowerCase().contains(".mov")) {
            return "mov";
        } else if (url.toLowerCase().contains(".webp")) {
            return "webp";
        }
        
        // Default extensions based on URL patterns
        if (url.contains("sns-img")) {
            return "jpg"; // Images from XHS typically
        } else if (url.contains("video")) {
            return "mp4"; // Videos from XHS typically
        }
        
        // If all else fails, try to guess based on URL structure
        return "jpg"; // Default to image format
    }
}