package com.neoruaa.xhsdn;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private EditText urlInput;
    private Button downloadButton;
    private Button webCrawlButton;
    private TextView statusText;
    private ProgressBar progressBar;
    private android.widget.ImageButton clearButton;
    private androidx.recyclerview.widget.RecyclerView imageContainer;
    private ScrollView statusScrollView;
    private MediaAdapter mediaAdapter;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int WEBVIEW_REQUEST_CODE = 1002;
    private String currentUrl; // Store the URL being processed to pass to WebView
    private boolean hasRequestedStoragePermission = false; // 标记是否已请求过存储权限，避免重复请求

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 设置状态栏和导航栏颜色为白色
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.setStatusBarColor(Color.parseColor("#212121"));
            window.setNavigationBarColor(Color.WHITE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        urlInput = findViewById(R.id.urlInput);
        downloadButton = findViewById(R.id.downloadButton);
        webCrawlButton = findViewById(R.id.webCrawlButton);
        statusText = findViewById(R.id.statusText);
        statusScrollView = findViewById(R.id.statusScrollView);
        progressBar = findViewById(R.id.progressBar);
        clearButton = findViewById(R.id.clearButton);
        imageContainer = findViewById(R.id.imageContainer);

        // 启用 statusText 的滚动功能
        statusText.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        
        // 默认滚动到底部
        statusScrollView.post(() -> statusScrollView.fullScroll(ScrollView.FOCUS_DOWN));

        // 初始化瀑布流布局
        setupWaterfallLayout();

        // 检查并请求存储权限
        checkPermissions();

        // Set the app version in the toolbar title
        setAppVersionInToolbar();
        clearButton.setOnClickListener(v -> {
            urlInput.setText("");
        });

        // Show/hide clear button based on text input
        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                clearButton.setVisibility(s.length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            }
        });

        downloadButton.setOnClickListener(v -> {
            // 清除之前显示的图片
            clearImageViews();
            
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.please_enter_url), Toast.LENGTH_SHORT).show();
                return;
            }
            // Store the current URL for potential web crawl fallback
            currentUrl = url;
            startDownload(url);
        });
        
        // Set up web crawl button
        webCrawlButton.setOnClickListener(v -> {
            if (currentUrl != null && !currentUrl.isEmpty()) {
                // Extract clean URL from input text (in case it contains extra text)
                String cleanUrl = extractCleanUrl(currentUrl);
                
                if (cleanUrl != null && !cleanUrl.isEmpty()) {
                    // Start WebView activity with the clean URL
                    Intent intent = new Intent(MainActivity.this, WebViewActivity.class);
                    intent.putExtra("url", cleanUrl);
                    startActivityForResult(intent, WEBVIEW_REQUEST_CODE);
                } else {
                    Toast.makeText(this, getString(R.string.invalid_url_format), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, getString(R.string.no_url_to_process), Toast.LENGTH_SHORT).show();
            }
        });

    }

    private void setAppVersionInToolbar() {
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            // Set the toolbar title with app name and version
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(getString(R.string.app_name) + " v" + versionName);
                // Set the subtitle to the app description
                getSupportActionBar().setSubtitle(getString(R.string.app_description));
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error getting app version: " + e.getMessage());
            // Fallback to just app name if version can't be retrieved
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(getString(R.string.app_name));
                getSupportActionBar().setSubtitle(getString(R.string.app_description));
            }
        }
    }

    private void checkPermissions() {
        checkPermissions(true);
    }
    
    private void checkPermissions(boolean requestIfNeeded) {
        // 对于Android 11+ (API 30+)，需要特殊的存储权限处理
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 使用MANAGE_EXTERNAL_STORAGE权限
            if (!Environment.isExternalStorageManager()) {
                // 只有在需要请求且尚未请求过时才打开设置页面
                if (requestIfNeeded && !hasRequestedStoragePermission) {
                    hasRequestedStoragePermission = true;
                    // 请求MANAGE_EXTERNAL_STORAGE权限
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                    Toast.makeText(this, getString(R.string.grant_all_files_access), Toast.LENGTH_LONG).show();
                }
            } else {
                // 权限已授予，重置标志
                hasRequestedStoragePermission = false;
            }
        } else {
            // Android 10及以下版本，检查传统存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                // 只有在需要请求且尚未请求过时才请求权限
                if (requestIfNeeded && !hasRequestedStoragePermission) {
                    hasRequestedStoragePermission = true;
                    // 请求存储权限
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, 
                                         Manifest.permission.READ_EXTERNAL_STORAGE},
                            PERMISSION_REQUEST_CODE);
                }
            } else {
                // 权限已授予，重置标志
                hasRequestedStoragePermission = false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.storage_permission_granted), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.storage_permission_denied), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 在应用恢复时检查权限状态，但不主动请求权限（避免重复提示）
        // 如果用户从设置页面返回，此时权限可能已授予，重置标志以便下次可以再次请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // 权限已授予，重置标志
                hasRequestedStoragePermission = false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，重置标志
                hasRequestedStoragePermission = false;
            }
        }
        // 只检查状态，不主动请求
        checkPermissions(false);
    }

    private void clearImageViews() {
        if (mediaAdapter != null) {
            mediaAdapter.clearItems();
        }
        displayedFiles.clear(); // 清除已显示文件的记录，允许重复下载时显示缩略图
    }
    
    /**
     * 初始化瀑布流布局
     */
    private void setupWaterfallLayout() {
        // 使用瀑布流布局管理器（2列）
        androidx.recyclerview.widget.StaggeredGridLayoutManager layoutManager = 
            new androidx.recyclerview.widget.StaggeredGridLayoutManager(2, 
                androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL);
        imageContainer.setLayoutManager(layoutManager);
        
        // 初始化适配器
        mediaAdapter = new MediaAdapter(this);
        imageContainer.setAdapter(mediaAdapter);
    }

    private void addMediaView(String filePath) {
        File mediaFile = new File(filePath);
        if (mediaFile.exists() && mediaAdapter != null) {
            // 添加媒体文件到适配器
            mediaAdapter.addItem(filePath);
        }
    }
    
    private boolean isImageFile(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }
    
    private boolean isVideoFile(String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }
    
    private String getMimeType(String filePath) {
        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(filePath);
        if (extension != null) {
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return null;
    }
    
    private Bitmap createVideoThumbnail(String filePath) {
        try {
            // 使用 MediaMetadataRetriever 获取视频缩略图
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            return retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC); // 1秒处的帧
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private void openImageInExternalApp(String imagePath) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(imagePath);
        Uri uri;
        
        try {
            // Try using FileProvider first
            uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            // If FileProvider fails, copy file to app's cache directory and use that
            Log.d("MainActivity", "FileProvider failed, copying file to app cache: " + e.getMessage());
            try {
                File cacheDir = getCacheDir();
                File tempFile = new File(cacheDir, file.getName());
                
                // Copy file to app's cache directory using traditional I/O
                copyFile(file, tempFile);
                
                uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tempFile);
            } catch (Exception copyException) {
                Log.e("MainActivity", "Failed to copy file to cache: " + copyException.getMessage());
                Toast.makeText(this, getString(R.string.cannot_open_image, copyException.getMessage()), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        intent.setDataAndType(uri, "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.cannot_open_image, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void copyFile(File source, File destination) throws java.io.IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(source);
             java.io.FileOutputStream output = new java.io.FileOutputStream(destination)) {
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }
    }
    
    private void openVideoInExternalApp(String videoPath) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(videoPath);
        Uri uri;
        
        try {
            // Try using FileProvider first
            uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            // If FileProvider fails, copy file to app's cache directory and use that
            Log.d("MainActivity", "FileProvider failed for video, copying to app cache: " + e.getMessage());
            try {
                File cacheDir = getCacheDir();
                File tempFile = new File(cacheDir, file.getName());
                
                // Copy file to app's cache directory using traditional I/O
                copyFile(file, tempFile);
                
                uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tempFile);
            } catch (Exception copyException) {
                Log.e("MainActivity", "Failed to copy video to cache: " + copyException.getMessage());
                Toast.makeText(this, getString(R.string.cannot_open_video, copyException.getMessage()), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        intent.setDataAndType(uri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.cannot_open_video, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    private Bitmap decodeSampledBitmapFromFile(String filePath, int reqWidth, int reqHeight) {
        // 首先获取位图的边界，不分配内存
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, options);

        // 计算采样率
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // 使用计算出的采样率解码位图
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(filePath, options);
    }
    
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // 原始位图的高度和宽度
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            // 计算高度和宽度的比率
            final int heightRatio = Math.round((float) height / (float) reqHeight);
            final int widthRatio = Math.round((float) width / (float) reqWidth);

            // 选择较小的比率作为inSampleSize
            inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
        }

        return inSampleSize;
    }
    
    private void openFileInExternalApp(String filePath) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(filePath);
        String mimeType = getMimeType(filePath);
        Uri uri;
        
        try {
            // Try using FileProvider first
            uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            // If FileProvider fails, copy file to app's cache directory and use that
            Log.d("MainActivity", "FileProvider failed for generic file, copying to app cache: " + e.getMessage());
            try {
                File cacheDir = getCacheDir();
                File tempFile = new File(cacheDir, file.getName());
                
                // Copy file to app's cache directory using traditional I/O
                copyFile(file, tempFile);
                
                uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tempFile);
            } catch (Exception copyException) {
                Log.e("MainActivity", "Failed to copy generic file to cache: " + copyException.getMessage());
                Toast.makeText(this, getString(R.string.cannot_open_file, copyException.getMessage()), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        if (mimeType != null) {
            intent.setDataAndType(uri, mimeType);
        } else {
            // 无法确定MIME类型时，使用通配符
            intent.setDataAndType(uri, "*/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.cannot_open_file, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Auto-scroll the status ScrollView to the bottom
     */
    public void autoScrollToBottom() {
        if (statusScrollView != null) {
            // Use post to ensure the scroll happens after the layout is updated
            statusScrollView.post(() -> statusScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private final java.util.Set<String> displayedFiles = new java.util.HashSet<>();

    public void addMediaToDisplay(String filePath) {
        runOnUiThread(() -> {
            // Check if this file has already been added to prevent duplicates
            if (!displayedFiles.contains(filePath)) {
                displayedFiles.add(filePath);
                addMediaView(filePath);
                
                // Auto-scroll to bottom of the image container
                if (imageContainer != null && mediaAdapter != null) {
                    imageContainer.post(() -> {
                        // Scroll to the last item (bottom of the list)
                        int lastPosition = mediaAdapter.getItemCount() - 1;
                        if (lastPosition >= 0) {
                            imageContainer.smoothScrollToPosition(lastPosition);
                        }
                    });
                }
            }
        });
    }

    private void startDownload(String url) {
        // Disable button and show progress
        downloadButton.setEnabled(false);
        webCrawlButton.setVisibility(View.GONE); // Hide the web crawl button during processing
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(getString(R.string.processing_url, url));
        autoScrollToBottom();

        // Create download task
        DownloadTask task = new DownloadTask(this, statusText, progressBar, downloadButton);
        task.execute(url);
        autoScrollToBottom(); // Scroll to bottom initially when starting download
    }
    
    /**
     * Method to show the web crawl button when JSON parsing fails
     */
    public void showWebCrawlOption() {
        runOnUiThread(() -> {
            webCrawlButton.setVisibility(View.VISIBLE);
                    statusText.append("\n" + getString(R.string.json_parsing_failed_web_crawl, getString(R.string.webview_title)));
                    autoScrollToBottom();
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == WEBVIEW_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Get the image URLs from the WebView activity
                ArrayList<String> imageUrls = data.getStringArrayListExtra("image_urls");
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    // 清除之前显示的图片和记录
                    clearImageViews();
                    
                    // Process the found image URLs
                    statusText.setText(getString(R.string.found_images_via_web_crawl, imageUrls.size()));
                    autoScrollToBottom();
                    
                    // Transform the URLs using the XHSDownloader's transformXhsCdnUrl method to get better quality images
                    List<String> transformedUrls = new ArrayList<>();
                    XHSDownloader xhsDownloader = new XHSDownloader(this); // Create temporary instance for URL transformation
                    for (String url : imageUrls) {
                        String transformedUrl = xhsDownloader.transformXhsCdnUrl(url);
                        if (transformedUrl != null && !transformedUrl.isEmpty()) {
                            transformedUrls.add(transformedUrl);
                        } else {
                            // If transformation fails, keep the original URL
                            transformedUrls.add(url);
                        }
                    }

                    statusText.append("\n" + getString(R.string.converted_cdn_original_images));
                    autoScrollToBottom();

                    for (String url : transformedUrls) {
                        statusText.append("\n" + url);
                        autoScrollToBottom();
                    }
                    
                    // Start downloading the transformed images
                    processImageUrls(new ArrayList<>(transformedUrls)); // Convert to ArrayList for compatibility
                } else {
                    statusText.append("\n" + getString(R.string.no_images_found_via_web_crawl));
                    autoScrollToBottom();
                }
            }
        }
    }
    
    /**
     * Process the image URLs found via web crawl
     */
    private void processImageUrls(ArrayList<String> imageUrls) {
        // Move the downloading to a background thread to avoid NetworkOnMainThreadException
        new Thread(() -> {
            try {
                // Create a temporary XHSDownloader to handle the downloads
                XHSDownloader xhsDownloader = new XHSDownloader(this, new DownloadCallback() {
                    @Override
                    public void onFileDownloaded(String filePath) {
                        // Add the downloaded file to the display on the UI thread
                        runOnUiThread(() -> addMediaToDisplay(filePath));
                    }

                    @Override
                    public void onDownloadError(String error, String originalUrl) {
                        runOnUiThread(() -> {
                            statusText.append("\n" + getString(R.string.download_error_for_url, error, originalUrl));
                            autoScrollToBottom();
                        });
                    }
                    
                    @Override
                    public void onDownloadProgress(String status) {
                        runOnUiThread(() -> {
                            statusText.append("\n" + status);
                            autoScrollToBottom();
                        });
                    }

                    @Override
                    public void onDownloadProgressUpdate(long downloaded, long total) {
                        // Not used in this context
                    }
                });
                
                // Get the post ID from the current URL to use as a prefix for filenames
                String postId = new XHSDownloader(this).extractPostId(extractCleanUrl(currentUrl));
                
                // Download each image
                for (int i = 0; i < imageUrls.size(); i++) {
                    String imageUrl = imageUrls.get(i);
                    String fileName = postId + "_" + (i + 1) + ".jpg"; // Use .jpg as default or determine from URL
                    
                    // Determine file extension based on URL
                    String extension = determineFileExtension(imageUrl);
                    fileName = postId + "_" + (i + 1) + "." + extension;
                    
                    xhsDownloader.downloadFile(imageUrl, fileName);
                }
                
                runOnUiThread(() -> {
                    statusText.append("\n" + getString(R.string.all_downloads_completed));
                    autoScrollToBottom();
                });
            } catch (Exception e) {
                Log.e("MainActivity", "Error processing image URLs", e);
                runOnUiThread(() -> {
                statusText.append("\n" + getString(R.string.error_processing_image_urls, e.getMessage()));
                autoScrollToBottom();
            });
            }
        }).start();
    }

    /**
     * Extract clean URL from input text that may contain extra text
     * @param inputText Input text that may contain a URL mixed with other text
     * @return Clean URL or null if no valid URL found
     */
    private String extractCleanUrl(String inputText) {
        if (inputText == null || inputText.isEmpty()) {
            return null;
        }
        
        // First try to find URLs with common XHS patterns
        java.util.regex.Pattern xhsPattern = java.util.regex.Pattern.compile("https?://[\\w\\-.]+\\.xhscdn\\.com/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
        java.util.regex.Matcher xhsMatcher = xhsPattern.matcher(inputText);
        if (xhsMatcher.find()) {
            return xhsMatcher.group();
        }
        
        // Then try to find xhslink.com URLs
        java.util.regex.Pattern xhsLinkPattern = java.util.regex.Pattern.compile("https?://xhslink\\.com/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
        java.util.regex.Matcher xhsLinkMatcher = xhsLinkPattern.matcher(inputText);
        if (xhsLinkMatcher.find()) {
            return xhsLinkMatcher.group();
        }
        
        // Then try to find general xiaohongshu.com URLs
        java.util.regex.Pattern xhsComPattern = java.util.regex.Pattern.compile("https?://[\\w\\-.]*xiaohongshu\\.com/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
        java.util.regex.Matcher xhsComMatcher = xhsComPattern.matcher(inputText);
        if (xhsComMatcher.find()) {
            return xhsComMatcher.group();
        }
        
        // Finally try to find any HTTP/HTTPS URL
        java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
        java.util.regex.Matcher urlMatcher = urlPattern.matcher(inputText);
        if (urlMatcher.find()) {
            return urlMatcher.group();
        }
        
        // If no URL found, return null
        return null;
    }
    
    /**
     * Determine the appropriate file extension based on the URL
     */
    private String determineFileExtension(String url) {
        if (url != null) {
            // Check for common video extensions in the URL first
            if (url.toLowerCase().contains(".mp4")) {
                return "mp4";
            } else if (url.toLowerCase().contains(".mov")) {
                return "mov";
            } else if (url.toLowerCase().contains(".avi")) {
                return "avi";
            } else if (url.toLowerCase().contains(".mkv")) {
                return "mkv";
            } else if (url.toLowerCase().contains(".webm")) {
                return "webm";
            }
            // Check for common image extensions in the URL
            else if (url.toLowerCase().contains(".jpg") || url.toLowerCase().contains(".jpeg")) {
                return "jpg";
            } else if (url.toLowerCase().contains(".png")) {
                return "png";
            } else if (url.toLowerCase().contains(".gif")) {
                return "gif";
            } else if (url.toLowerCase().contains(".webp")) {
                return "webp";
            }
        }
        // If URL has "video" in it or "sns-video" (Xiaohongshu video pattern), assume it's a video
        if (url != null && (url.contains("video") || url.contains("sns-video"))) {
            return "mp4";
        }
        return "jpg"; // Default fallback for images
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_settings) {
            showSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void showSettingsDialog() {
        SettingsDialog settingsDialog = new SettingsDialog(this);
        settingsDialog.setOnSettingsAppliedListener(savePath -> {
            // Handle settings applied if needed - savePath now represents the default path
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
        });
        settingsDialog.show();
    }
    
    /**
     * 将 dp 转换为像素
     * @param dp dp值
     * @return 像素值
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}