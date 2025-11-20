package com.neoruaa.xhsdn;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;

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
        // Use current date timestamp when no timestamp is provided
        String timestamp = getTimestampForFilename(); // 日期格式时间戳，如 251114
        return downloadFile(url, fileName, timestamp);
    }

    public boolean downloadFile(String url, String fileName, String timestamp) {
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
                // 优先从响应中获取文件扩展名 (Content-Type header)
                String fileExtension = getFileExtension(response, url);

                // 从原始文件名中提取基础名称（去掉扩展名）
                String baseFileName = fileName;
                int lastDotIndex = fileName.lastIndexOf('.');
                if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
                    baseFileName = fileName.substring(0, lastDotIndex);
                    Log.d(TAG, "Original filename has extension: " + fileName.substring(lastDotIndex + 1).toLowerCase() + 
                          ", but using Content-Type based extension: " + fileExtension);
                }

                String fullFileName = "xhs_" + baseFileName + "." + fileExtension;

                File destinationFile = null;

                // For Android 10+ use MediaStore to ensure gallery visibility
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    destinationFile = saveToMediaStore(fullFileName, response.body(), fileExtension);
                }

                // If MediaStore save failed or we're on older Android, fall back to file-based save
                if (destinationFile == null) {
                    destinationFile = saveToFileSystem(url, fullFileName, response.body());
                }

                if (destinationFile != null && destinationFile.exists()) {
                    Log.d(TAG, "Downloaded file: " + destinationFile.getAbsolutePath());
                    Log.d(TAG, "Total bytes: " + (response.body() != null ? response.body().contentLength() : 0));
                    Log.d(TAG, "File exists: " + destinationFile.exists());
                    Log.d(TAG, "File size: " + destinationFile.length());

                    // For files that aren't already in MediaStore (like those saved to app's private directory),
                    // we still need to notify MediaStore
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isFileInPrivateDirectory(destinationFile)) {
                        notifyMediaStore(destinationFile);
                    }

                    // 通知回调下载完成
                    if (callback != null) {
                        callback.onFileDownloaded(destinationFile.getAbsolutePath());
                    }

                    if (response.body() != null) {
                        response.body().close();
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
    
    /**
     * Save file directly to MediaStore (Android 10+ with scoped storage support)
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private File saveToMediaStore(String fileName, ResponseBody body, String fileExtension) {
        try {
            ContentResolver contentResolver = context.getContentResolver();
            
            // Determine the collection based on file type and use MediaStore directory + xhs subfolder
            Uri collectionUri;
            String relativePath;
            if (isImageFile(fileExtension)) {
                collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                relativePath = Environment.DIRECTORY_PICTURES + File.separator + "xhs";
            } else if (isVideoFile(fileExtension)) {
                collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                relativePath = Environment.DIRECTORY_MOVIES + File.separator + "xhs";
            } else {
                collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + "xhs";
            }
            
            // 删除已存在的同名文件，以避免重复文件
            deleteExistingFilesInMediaStore(contentResolver, collectionUri, fileName, relativePath);
            
            // 准备插入新文件 (we can now use the original filename since duplicates have been removed)
            ContentValues values = new ContentValues();
            String mimeType = getMimeTypeForFileExtension(fileExtension);
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            
            Uri uri = contentResolver.insert(collectionUri, values);
            
            if (uri != null) {
                try (OutputStream outputStream = contentResolver.openOutputStream(uri)) {
                    if (outputStream != null && body != null) {
                        // Write the response body to the content URI
                        byte[] buffer = new byte[65536]; // 64KB buffer
                        int bytesRead;
                        long totalBytesRead = 0;
                        long contentLength = body.contentLength();
                        
                        InputStream inputStream = body.byteStream();
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            totalBytesRead += bytesRead;
                            
                            // Report progress updates less frequently to avoid UI thread contention
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
                        
                        // File is now in MediaStore, find the actual file path
                        return getFileFromUri(uri);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error writing to MediaStore URI: " + e.getMessage());
                    // Try to delete the partially created entry
                    try {
                        contentResolver.delete(uri, null, null);
                    } catch (Exception deleteEx) {
                        Log.e(TAG, "Error deleting partial MediaStore entry: " + deleteEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving to MediaStore: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null; // Return null if MediaStore save failed
    }
    
    /**
     * Save file to filesystem (fallback for older Android versions or MediaStore failures)
     */
    private File saveToFileSystem(String url, String fileName, ResponseBody body) throws IOException {
        // Always use public directory with "xhs" subfolder
        File destinationDir;
        // Try to use public Pictures directory first (requires permissions)
        File publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        if (publicPicturesDir != null) {
            destinationDir = new File(publicPicturesDir, "xhs");
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
            destinationDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "xhs");
        } else {
            Log.d(TAG, "Using public Pictures directory");
        }
        
        // Ensure the directory exists
        if (!destinationDir.exists()) {
            boolean dirCreated = destinationDir.mkdirs();
            Log.d(TAG, "Directory creation result: " + dirCreated + " for " + destinationDir.getAbsolutePath());
        }
        
        // 检查是否有同名文件，如果有则删除
        File existingFile = new File(destinationDir, fileName);
        if (existingFile.exists()) {
            boolean deleted = existingFile.delete();
            Log.d(TAG, "Deleted existing file: " + existingFile.getAbsolutePath() + ", success: " + deleted);
        }
        
        File destinationFile = new File(destinationDir, fileName);
        
        Log.d(TAG, "Saving file to: " + destinationFile.getAbsolutePath());
        
        // Write the response body to the file
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
        }
        
        return destinationFile;
    }
    
    /**
     * Helper method to get file extension MIME type
     */
    private String getMimeTypeForFileExtension(String fileExtension) {
        if (fileExtension == null) return "application/octet-stream";
        
        String ext = fileExtension.toLowerCase();
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "mp4":
                return "video/mp4";
            case "mov":
                return "video/quicktime";
            case "webp":
                return "image/webp";
            default:
                return "application/octet-stream";
        }
    }
    
    /**
     * Helper method to check if file extension is for an image
     */
    private boolean isImageFile(String fileExtension) {
        if (fileExtension == null) return false;
        
        String ext = fileExtension.toLowerCase();
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || 
               ext.equals("gif") || ext.equals("webp");
    }
    
    /**
     * Helper method to check if file extension is for a video
     */
    private boolean isVideoFile(String fileExtension) {
        if (fileExtension == null) return false;
        
        String ext = fileExtension.toLowerCase();
        return ext.equals("mp4") || ext.equals("mov");
    }
    
    /**
     * Helper method to check if file is in app's private directory
     */
    private boolean isFileInPrivateDirectory(File file) {
        String appPrivateDir = context.getExternalFilesDir(null).getAbsolutePath();
        return file.getAbsolutePath().startsWith(appPrivateDir);
    }
    
    /**
     * Helper method to get actual file from MediaStore URI
     */
    private File getFileFromUri(Uri uri) {
        try {
            String[] projection = {MediaStore.MediaColumns.DATA};
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    if (columnIndex != -1) {
                        String filePath = cursor.getString(columnIndex);
                        if (filePath != null) {
                            return new File(filePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file path from URI: " + e.getMessage());
        }
        
        // If we can't get the file path from URI, return a dummy file with the display name
        try {
            String displayName = getDisplayNameFromUri(uri);
            if (displayName != null) {
                // Return a file object that just represents the URI
                return new File(context.getExternalFilesDir(null), displayName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting display name from URI: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Helper method to get display name from MediaStore URI
     */
    private String getDisplayNameFromUri(Uri uri) {
        try {
            String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting display name from URI: " + e.getMessage());
        }
        return null;
    }

    /**
     * Downloads a file directly to internal app storage (for temporary processing) with timestamp
     * @param url The URL to download from
     * @param fileName The name of the file to save
     * @param timestamp The timestamp to use in the file name
     * @return true if download was successful, false otherwise
     */
    public boolean downloadFileToInternalStorage(String url, String fileName, String timestamp) {
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
                String fullFileName = "xhs_" + timestamp + "_" + fileName;

                // 生成唯一文件名（如果文件已存在，使用 xxx_(1).jpg 格式）
                File internalDir = context.getExternalFilesDir(null);
                String uniqueFileName = getUniqueFileName(internalDir, fullFileName);
                File destinationFile = new File(internalDir, uniqueFileName);

                Log.d(TAG, "Saving file to internal storage: " + destinationFile.getAbsolutePath());

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
                    }

                    inputStream.close();
                    outputStream.close();

                    Log.d(TAG, "Downloaded file to internal storage: " + destinationFile.getAbsolutePath());
                    Log.d(TAG, "Total bytes: " + totalBytesRead);
                    Log.d(TAG, "File exists: " + destinationFile.exists());
                    Log.d(TAG, "File size: " + destinationFile.length());

                    return true;
                }
            } else {
                Log.e(TAG, "Download failed. Response code: " + response.code());
                if (response.body() != null) {
                    response.body().close();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error downloading file: " + e.getMessage());
            e.printStackTrace();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception while downloading file: " + e.getMessage());
            e.printStackTrace();
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
    
    /**
     * Notify MediaStore about the new file so it appears in gallery immediately
     * @param file The file to register with MediaStore
     */
    private void notifyMediaStore(File file) {
        // Check if the file is in a public directory (e.g., Downloads, Pictures)
        // Only files in public directories will appear in gallery and other apps
        String filePath = file.getAbsolutePath();
        String publicPicturesPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath();
        String publicDownloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        
        if (filePath.startsWith(publicPicturesPath) || filePath.startsWith(publicDownloadsPath)) {
            // File is in a public directory, so we should make it visible to MediaStore
            
            // For Android 10+ (API 29+), use MediaStore.insert() for better reliability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentResolver contentResolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    
                    // Determine if it's an image or video file
                    String mimeType = getMimeTypeForFile(file);
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, getRelativePathForFile(file, publicPicturesPath, publicDownloadsPath));
                    
                    Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    
                    if (uri != null) {
                        // Successfully inserted into MediaStore
                        try (OutputStream out = contentResolver.openOutputStream(uri)) {
                            // File already exists, so we don't need to copy it again
                            Log.d(TAG, "File inserted into MediaStore via direct method: " + file.getAbsolutePath());
                        } catch (IOException e) {
                            Log.e(TAG, "Error writing to content URI: " + e.getMessage());
                        }
                    } else {
                        // Fallback to MediaScannerConnection
                        fallbackMediaScan(file);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error inserting file into MediaStore: " + e.getMessage());
                    // Fallback to MediaScannerConnection
                    fallbackMediaScan(file);
                }
            } else {
                // For older Android versions, use MediaScannerConnection and broadcast
                fallbackMediaScan(file);
            }
        } else {
            // File is in app's private directory, no need to notify MediaStore
            Log.d(TAG, "File is in private directory, no MediaStore notification needed: " + filePath);
        }
    }
    
    /**
     * Fallback method using MediaScannerConnection and broadcast for older Android versions
     */
    private void fallbackMediaScan(File file) {
        // Use MediaScannerConnection to scan the file
        MediaScannerConnection.scanFile(
            context,
            new String[]{file.getAbsolutePath()},
            null,
            new MediaScannerConnection.OnScanCompletedListener() {
                @Override
                public void onScanCompleted(String path, Uri uri) {
                    Log.d(TAG, "MediaScanner scanned file: " + path + ", URI: " + uri);
                }
            }
        );
        
        // For older versions, also send a broadcast
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Send broadcast to refresh media store on older Android versions
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(file);
            mediaScanIntent.setData(contentUri);
            context.sendBroadcast(mediaScanIntent);
        }
    }
    
    /**
     * Get the MIME type for a file based on its extension
     */
    private String getMimeTypeForFile(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        } else if (fileName.endsWith(".mov")) {
            return "video/quicktime";
        } else if (fileName.endsWith(".webp")) {
            return "image/webp";
        } else {
            // Default to image/jpeg if unknown
            return "image/jpeg";
        }
    }
    
    /**
     * Get the relative path for MediaStore insertion
     */
    private String getRelativePathForFile(File file, String publicPicturesPath, String publicDownloadsPath) {
        String filePath = file.getAbsolutePath();
        
        if (filePath.startsWith(publicPicturesPath)) {
            // Remove the public pictures path and add to MediaStore.Images.Media.DIRECTORY
            String subPath = filePath.substring(publicPicturesPath.length());
            return Environment.DIRECTORY_PICTURES + subPath.substring(0, subPath.lastIndexOf('/'));
        } else if (filePath.startsWith(publicDownloadsPath)) {
            // Remove the public downloads path and add to MediaStore.Downloads.DIRECTORY
            String subPath = filePath.substring(publicDownloadsPath.length());
            return Environment.DIRECTORY_DOWNLOADS + subPath.substring(0, subPath.lastIndexOf('/'));
        }
        
        // Default to Pictures
        return Environment.DIRECTORY_PICTURES;
    }
    
    /**
     * Get a unique file name in MediaStore to avoid duplicate file names
     * If file exists, generate name like: xxx_(1).jpg, xxx_(2).jpg, etc.
     * @param contentResolver ContentResolver instance
     * @param collectionUri The MediaStore collection URI
     * @param fileName The original file name
     * @param relativePath The relative path where the file is located
     * @return A unique file name
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private String getUniqueFileNameInMediaStore(ContentResolver contentResolver, Uri collectionUri, 
                                                  String fileName, String relativePath) {
        try {
            // 分离文件名和扩展名
            String baseName = fileName;
            String extension = "";
            int lastDotIndex = fileName.lastIndexOf('.');
            if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
                baseName = fileName.substring(0, lastDotIndex);
                extension = fileName.substring(lastDotIndex); // 包含点号，如 ".jpg"
            }
            
            // 检查原始文件名是否存在
            if (!fileExistsInMediaStore(contentResolver, collectionUri, fileName, relativePath)) {
                Log.d(TAG, "File doesn't exist, using original name: " + fileName);
                return fileName;
            }
            
            // 如果存在，生成 xxx_(1).jpg, xxx_(2).jpg 等格式
            int counter = 1;
            String newFileName;
            while (counter < 1000) { // 限制最多尝试1000次
                newFileName = baseName + "_(" + counter + ")" + extension;
                if (!fileExistsInMediaStore(contentResolver, collectionUri, newFileName, relativePath)) {
                    Log.d(TAG, "Generated unique file name: " + newFileName);
                    return newFileName;
                }
                counter++;
            }
            
            // 如果尝试1000次都失败，使用时间戳
            newFileName = baseName + "_" + System.currentTimeMillis() + extension;
            Log.d(TAG, "Using timestamp-based file name: " + newFileName);
            return newFileName;
            
        } catch (Exception e) {
            Log.e(TAG, "Error generating unique file name: " + e.getMessage());
            // 如果出错，返回原始文件名
            return fileName;
        }
    }
    
    /**
     * Check if a file exists in MediaStore
     * @param contentResolver ContentResolver instance
     * @param collectionUri The MediaStore collection URI
     * @param fileName The file name to check
     * @param relativePath The relative path where the file is located
     * @return true if file exists, false otherwise
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private boolean fileExistsInMediaStore(ContentResolver contentResolver, Uri collectionUri, 
                                           String fileName, String relativePath) {
        try {
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + 
                              MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            String[] selectionArgs = new String[]{fileName, relativePath + File.separator};
            
            String[] projection = {MediaStore.MediaColumns._ID};
            
            android.database.Cursor cursor = contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                null
            );
            
            if (cursor != null) {
                try {
                    boolean exists = cursor.getCount() > 0;
                    return exists;
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking file existence: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Delete existing files in MediaStore with the same name
     * @param contentResolver ContentResolver instance
     * @param collectionUri The MediaStore collection URI
     * @param fileName The file name to check
     * @param relativePath The relative path where the file is located
     * @return Number of files deleted
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private int deleteExistingFilesInMediaStore(ContentResolver contentResolver, Uri collectionUri, 
                                                String fileName, String relativePath) {
        try {
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + 
                              MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            String[] selectionArgs = new String[]{fileName, relativePath + File.separator};
            
            // Query for existing files to get their IDs
            String[] projection = {MediaStore.MediaColumns._ID};
            
            android.database.Cursor cursor = contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                null
            );
            
            int deletedCount = 0;
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        int idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                        if (idColumn != -1) {
                            long id = cursor.getLong(idColumn);
                            Uri fileUri = Uri.withAppendedPath(collectionUri, String.valueOf(id));
                            
                            // Delete the existing file
                            int deleted = contentResolver.delete(fileUri, null, null);
                            if (deleted > 0) {
                                deletedCount++;
                                Log.d(TAG, "Deleted existing file with ID: " + id);
                            }
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            return deletedCount;
        } catch (Exception e) {
            Log.e(TAG, "Error deleting existing files: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get a unique file name in file system to avoid duplicate file names
     * If file exists, generate name like: xxx_(1).jpg, xxx_(2).jpg, etc.
     * @param directory The directory where the file will be saved
     * @param fileName The original file name
     * @return A unique file name
     */
    private String getUniqueFileName(File directory, String fileName) {
        try {
            File file = new File(directory, fileName);
            
            // 如果文件不存在，直接返回原始文件名
            if (!file.exists()) {
                Log.d(TAG, "File doesn't exist, using original name: " + fileName);
                return fileName;
            }
            
            // 分离文件名和扩展名
            String baseName = fileName;
            String extension = "";
            int lastDotIndex = fileName.lastIndexOf('.');
            if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
                baseName = fileName.substring(0, lastDotIndex);
                extension = fileName.substring(lastDotIndex); // 包含点号，如 ".jpg"
            }
            
            // 生成 xxx_(1).jpg, xxx_(2).jpg 等格式
            int counter = 1;
            String newFileName;
            while (counter < 1000) { // 限制最多尝试1000次
                newFileName = baseName + "_(" + counter + ")" + extension;
                file = new File(directory, newFileName);
                if (!file.exists()) {
                    Log.d(TAG, "Generated unique file name: " + newFileName);
                    return newFileName;
                }
                counter++;
            }
            
            // 如果尝试1000次都失败，使用时间戳
            newFileName = baseName + "_" + System.currentTimeMillis() + extension;
            Log.d(TAG, "Using timestamp-based file name: " + newFileName);
            return newFileName;
            
        } catch (Exception e) {
            Log.e(TAG, "Error generating unique file name: " + e.getMessage());
            // 如果出错，返回原始文件名
            return fileName;
        }
    }

    /**
     * Get a timestamp in YYMMDD format for filename
     * @return Timestamp string in format YYMMDD (e.g., 251114 for Nov 14, 2025)
     */
    private String getTimestampForFilename() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMdd", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date());
    }
}
