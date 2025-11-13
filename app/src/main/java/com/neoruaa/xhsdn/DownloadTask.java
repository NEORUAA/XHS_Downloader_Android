package com.neoruaa.xhsdn;

import android.os.AsyncTask;
import android.util.Log;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class DownloadTask extends AsyncTask<String, String, Boolean> {
    private WeakReference<MainActivity> activityReference;
    private WeakReference<TextView> statusTextRef;
    private WeakReference<ProgressBar> progressBarRef;
    private WeakReference<TextView> progressTextRef;
    private WeakReference<LinearLayout> progressContainerRef;
    private WeakReference<Button> buttonRef;
    private final java.util.Set<String> downloadedFiles = new java.util.HashSet<>();  // 保存下载文件路径，使用Set去重
    private int totalMediaCount = 0;  // 总媒体数量
    private int downloadedCount = 0;  // 已下载数量

    DownloadTask(MainActivity context, TextView statusText, ProgressBar progressBar, TextView progressText, LinearLayout progressContainer, Button button) {
        activityReference = new WeakReference<>(context);
        statusTextRef = new WeakReference<>(statusText);
        progressBarRef = new WeakReference<>(progressBar);
        progressTextRef = new WeakReference<>(progressText);
        progressContainerRef = new WeakReference<>(progressContainer);
        buttonRef = new WeakReference<>(button);
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        // 在开始下载前重置计数
        downloadedCount = 0;
        totalMediaCount = 0;
    }

    @Override
    protected Boolean doInBackground(String... urls) {
        String url = urls[0];
        MainActivity activity = activityReference.get();

        if (activity == null || activity.isFinishing()) {
            return false;
        }

        try {
            // 首先获取总媒体数量
            XHSDownloader countDownloader = new XHSDownloader(activity);
            totalMediaCount = countDownloader.getMediaCount(url);
            updateOverallProgress(); // 更新初始进度

            // Create XHSDownloader instance with callback
            XHSDownloader downloader = new XHSDownloader(activity, new DownloadCallback() {
                @Override
                public void onFileDownloaded(String filePath) {
                    // 在UI线程中更新
                    if (activity != null) {
                        activity.addMediaToDisplay(filePath);
                        // 记录下载完成的文件路径，使用Set自动去重
                        synchronized (downloadedFiles) {
                            downloadedFiles.add(filePath);
                        }

                        // 递增已下载数量并更新总体进度
                        downloadedCount++;
                        updateOverallProgress();
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

    /**
     * 更新总体下载进度
     */
    private void updateOverallProgress() {
        MainActivity activity = activityReference.get();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                ProgressBar progressBar = progressBarRef.get();
                TextView progressText = progressTextRef.get();
                LinearLayout progressContainer = progressContainerRef.get();

                if (progressBar != null && progressText != null) {
                    if (totalMediaCount > 0) {
                        // 计算总体进度百分比
                        int overallProgress = (int) ((downloadedCount * 100L) / totalMediaCount);
                        progressBar.setMax(100);
                        progressBar.setProgress(overallProgress);

                        // 更新进度文本显示 X/Y 格式
                        progressText.setText(downloadedCount + "/" + totalMediaCount);

                        // 确保进度容器可见
                        if (progressContainer != null) {
                            progressContainer.setVisibility(View.VISIBLE);
                        }
                    } else {
                        // 如果总数未知，显示下载的文件数但不计算百分比
                        progressText.setText(downloadedCount + "/?");
                        if (progressContainer != null) {
                            progressContainer.setVisibility(View.VISIBLE);
                        }
                    }
                }
            });
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
        TextView statusText = statusTextRef.get();

        if (values.length > 0 && statusText != null) {
            String message = values[0];

            // Check if this is a progress update (for individual file download progress)
            if (message.startsWith("PROGRESS:")) {
                // Extract downloaded and total bytes
                String[] parts = message.substring("PROGRESS:".length()).split(":");
                if (parts.length == 2) {
                    try {
                        long downloaded = Long.parseLong(parts[0]);
                        long total = Long.parseLong(parts[1]);

                        // 仍保留单个文件的下载进度信息在状态文本中
                        if (total > 0) {
                            int percentage = (int) ((downloaded * 100) / total);

                            // 在状态文本中显示单个文件的进度
                            if (statusText != null) {
                                // 使用 \r 来覆盖当前行（但Android TextView不支持\r，所以我们只更新最后一行）
                                String currentText = statusText.getText().toString();
                                String[] lines = currentText.split("\n");

                                // 如果最后一行是进度信息，替换它；否则添加新行
                                String progressInfo = statusText.getContext().getString(R.string.individual_file_download_progress, percentage);
                                String progressPrefix = statusText.getContext().getString(R.string.individual_file_download_progress, 0).replace("0%", "");
                                if (lines.length > 0 && lines[lines.length - 1].startsWith(progressPrefix)) {
                                    // 替换最后一行
                                    StringBuilder newText = new StringBuilder();
                                    for (int i = 0; i < lines.length - 1; i++) {
                                        newText.append(lines[i]).append("\n");
                                    }
                                    newText.append(progressInfo);
                                    statusText.setText(newText.toString());
                                } else {
                                    // 添加新的进度行
                                    statusText.append("\n" + progressInfo);
                                    // Auto-scroll to bottom after updating status
                                    MainActivity activity = activityReference.get();
                                    if (activity != null) {
                                        activity.runOnUiThread(() -> activity.autoScrollToBottom());
                                    }
                                }
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

                    // Auto-scroll to bottom after updating status
                    MainActivity activity = activityReference.get();
                    if (activity != null) {
                        activity.runOnUiThread(() -> activity.autoScrollToBottom());
                    }
                } else {
                    // If format is wrong, just append as regular text
                    statusText.append("\n" + message);
                }
            }
            // Handle regular status updates
            else if (message.startsWith("STATUS:")) {
                String status = message.substring("STATUS:".length());
                statusText.append("\n" + status);
                // Auto-scroll to bottom after updating status
                MainActivity activity = activityReference.get();
                if (activity != null) {
                    activity.runOnUiThread(() -> activity.autoScrollToBottom());
                }
            } else {
                // Regular status update (backward compatibility)
                statusText.append("\n" + message);
                // Auto-scroll to bottom after updating status
                MainActivity activity = activityReference.get();
                if (activity != null) {
                    activity.runOnUiThread(() -> activity.autoScrollToBottom());
                }
            }
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        MainActivity activity = activityReference.get();
        Button button = buttonRef.get();
        TextView statusText = statusTextRef.get();
        ProgressBar progressBar = progressBarRef.get();
        TextView progressText = progressTextRef.get();
        LinearLayout progressContainer = progressContainerRef.get();

        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (button != null) {
            button.setEnabled(true);
        }

        // 隐藏进度容器（包含进度条和进度文本）
        if (progressContainer != null) {
            progressContainer.setVisibility(android.view.View.GONE);
        }

        if (statusText != null) {
            if (success) {
                statusText.append("\n" + statusText.getContext().getString(R.string.download_completed_successfully));
                // Auto-scroll to bottom after updating status
                if (activity != null) {
                    activity.runOnUiThread(() -> activity.autoScrollToBottom());
                }

                // 只显示存放目录路径，不显示每个文件的详细路径
                if (!downloadedFiles.isEmpty()) {
                    synchronized (downloadedFiles) {
                        // 获取第一个文件的目录路径
                        String firstFilePath = downloadedFiles.iterator().next();
                        java.io.File firstFile = new java.io.File(firstFilePath);
                        String directoryPath = firstFile.getParent();

                        // 显示文件数量和存放目录
                        int fileCount = downloadedFiles.size();
                        statusText.append("\n成功下载 " + fileCount + " 个文件");
                        statusText.append("\n存放路径: " + directoryPath);

                        // Auto-scroll to bottom again after adding download info
                        if (activity != null) {
                            activity.runOnUiThread(() -> activity.autoScrollToBottom());
                        }
                    }
                }
            } else {
                statusText.append("\n" + statusText.getContext().getString(R.string.download_failed));
                // Auto-scroll to bottom after updating status
                if (activity != null) {
                    activity.runOnUiThread(() -> activity.autoScrollToBottom());
                }
            }
        }
    }
}