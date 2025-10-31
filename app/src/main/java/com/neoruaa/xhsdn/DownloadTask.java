package com.neoruaa.xhsdn;

import android.os.AsyncTask;
import android.util.Log;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class DownloadTask extends AsyncTask<String, String, Boolean> {
    private WeakReference<MainActivity> activityReference;
    private WeakReference<TextView> statusTextRef;
    private WeakReference<ProgressBar> progressBarRef;
    private WeakReference<Button> buttonRef;
    private final java.util.List<String> downloadedFiles = new java.util.ArrayList<>();  // 保存下载文件路径

    DownloadTask(MainActivity context, TextView statusText, ProgressBar progressBar, Button button) {
        activityReference = new WeakReference<>(context);
        statusTextRef = new WeakReference<>(statusText);
        progressBarRef = new WeakReference<>(progressBar);
        buttonRef = new WeakReference<>(button);
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        TextView statusText = statusTextRef.get();
        if (statusText != null) {
            statusText.setText(statusText.getContext().getString(R.string.initializing_download));
        }
    }

    @Override
    protected Boolean doInBackground(String... urls) {
        String url = urls[0];
        MainActivity activity = activityReference.get();
        
        if (activity == null || activity.isFinishing()) {
            return false;
        }

        try {
            // Create XHSDownloader instance with callback
            XHSDownloader downloader = new XHSDownloader(activity, new DownloadCallback() {
                @Override
                public void onFileDownloaded(String filePath) {
                    // 在UI线程中更新
                    if (activity != null) {
                        activity.addMediaToDisplay(filePath);
                        // 记录下载完成的文件路径
                        synchronized (downloadedFiles) {
                            downloadedFiles.add(filePath);
                        }
                    }
                }

                @Override
                public void onDownloadProgress(String status) {
                    publishProgress("STATUS:" + status);
                }
                
                @Override
                public void onDownloadProgressUpdate(long downloaded, long total) {
                    // Publish progress as a special format that can be identified in onProgressUpdate
                    publishProgress("PROGRESS:" + downloaded + ":" + total);
                }
                
                @Override
                public void onDownloadError(String status, String originalUrl) {
                    // Check if the error indicates JSON parsing failure to show web crawl option
                    MainActivity activity = activityReference.get();
                    if (activity != null) {
                        if (status.contains("No media URLs found") || 
                            status.contains("Failed to fetch post details") ||
                            status.contains("Could not extract post ID")) {
                            // Run on UI thread to update the interface
                            activity.runOnUiThread(() -> activity.showWebCrawlOption());
                        }
                    }
                    
                    // For error messages with original URLs, we'll pass both the status and URL
                    // We'll use a special format to indicate this is an error message with a clickable URL
                    publishProgress("[ERROR_URL]" + status + "[/ERROR_URL]" + originalUrl);
                }
            });
            
            // Extract and download the content
            return downloader.downloadContent(url);
        } catch (Exception e) {
            e.printStackTrace();
            publishProgress(activity.getString(R.string.error_occurred, e.getMessage()));
            return false;
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
        TextView statusText = statusTextRef.get();
        ProgressBar progressBar = progressBarRef.get();
        
        if (values.length > 0 && statusText != null) {
            String message = values[0];
            
            // Check if this is a progress update
            if (message.startsWith("PROGRESS:")) {
                // Extract downloaded and total bytes
                String[] parts = message.substring("PROGRESS:".length()).split(":");
                if (parts.length == 2) {
                    try {
                        long downloaded = Long.parseLong(parts[0]);
                        long total = Long.parseLong(parts[1]);
                        
                        if (progressBar != null) {
                            // Set progress bar to be indeterminate initially, then set max and progress
                            if (total > 0) {
                                progressBar.setMax((int) Math.min(total, Integer.MAX_VALUE));
                                progressBar.setProgress((int) Math.min(downloaded, Integer.MAX_VALUE));
                                progressBar.setVisibility(View.VISIBLE); // Ensure it's visible during download
                            }
                        }
                    } catch (NumberFormatException e) {
                        Log.e("DownloadTask", "Error parsing progress: " + e.getMessage());
                    }
                }
            } 
            // Check if this is an error message with a clickable URL
            else if (message.startsWith("[ERROR_URL]")) {
                // Extract the status message and original URL
                int endStatusIndex = message.indexOf("[/ERROR_URL]");
                if (endStatusIndex != -1) {
                    String status = message.substring("[ERROR_URL]".length(), endStatusIndex);
                    String originalUrl = message.substring(endStatusIndex + "[/ERROR_URL]".length());
                    
                    // Check if the error indicates JSON parsing failure to show web crawl option
                    MainActivity mainActivity = activityReference.get();
                    if (status.contains("No media URLs found") || 
                        status.contains("Failed to fetch post details") ||
                        status.contains("Could not extract post ID")) {
                        // Run on UI thread to update the interface
                        if (mainActivity != null) {
                            mainActivity.runOnUiThread(() -> mainActivity.showWebCrawlOption());
                        }
                    }
                    
                    // Create a clickable span for the original URL
                    android.text.SpannableStringBuilder spannable = new android.text.SpannableStringBuilder("\n" + status + " Original URL: " + originalUrl);
                    
                    // Find the position of the URL in the text
                    int urlStart = status.length() + 16; // +16 for " Original URL: "
                    int urlEnd = urlStart + originalUrl.length();
                    
                    // Add clickable span for the URL
                    spannable.setSpan(new android.text.style.URLSpan(originalUrl) {
                        @Override
                        public void onClick(View widget) {
                            // Open the URL in browser
                            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse(getURL()));
                            if (intent.resolveActivity(statusText.getContext().getPackageManager()) != null) {
                                statusText.getContext().startActivity(intent);
                            }
                        }
                    }, urlStart, urlEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    // Change the color of the error part to red
                    spannable.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.RED), 
                                   1, 1 + status.length(), 
                                   android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    statusText.append(spannable);
                } else {
                    // If format is wrong, just append as regular text
                    statusText.append("\n" + message);
                }
            } 
            // Handle regular status updates
            else if (message.startsWith("STATUS:")) {
                String status = message.substring("STATUS:".length());
                statusText.append("\n" + status);
            } else {
                // Regular status update (backward compatibility)
                statusText.append("\n" + message);
            }
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        MainActivity activity = activityReference.get();
        Button button = buttonRef.get();
        TextView statusText = statusTextRef.get();
        ProgressBar progressBar = progressBarRef.get();
        
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (button != null) {
            button.setEnabled(true);
        }
        
        if (progressBar != null) {
            progressBar.setVisibility(android.view.View.GONE);
        }

        if (statusText != null) {
            if (success) {
                statusText.append("\n" + statusText.getContext().getString(R.string.download_completed_successfully));
                // 显示所有下载文件的路径
                if (!downloadedFiles.isEmpty()) {
                    statusText.append("\n" + statusText.getContext().getString(R.string.downloaded_files_title));
                    synchronized (downloadedFiles) {
                        for (String filePath : downloadedFiles) {
                            statusText.append("\n" + filePath);
                        }
                    }
                }
            } else {
                statusText.append("\n" + statusText.getContext().getString(R.string.download_failed));
            }
        }
    }
}