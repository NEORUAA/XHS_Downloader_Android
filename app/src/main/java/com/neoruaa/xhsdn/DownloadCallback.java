package com.neoruaa.xhsdn;

public interface DownloadCallback {
    void onFileDownloaded(String filePath);
    void onDownloadProgress(String status);
    void onDownloadProgressUpdate(long downloaded, long total);
    void onDownloadError(String status, String originalUrl);
}