package com.neoruaa.xhsdn;

import android.os.AsyncTask;
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
                    publishProgress(status);
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
        if (statusText != null && values.length > 0) {
            statusText.append("\n" + values[0]);
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