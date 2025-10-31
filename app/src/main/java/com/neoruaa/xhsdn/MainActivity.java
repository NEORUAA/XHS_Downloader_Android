package com.neoruaa.xhsdn;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private EditText urlInput;
    private Button downloadButton;
    private TextView statusText;
    private ProgressBar progressBar;
    private TextView versionTextView;
    private android.widget.ImageButton clearButton;
    private LinearLayout titleLayout;
    private LinearLayout imageContainer;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 设置状态栏和导航栏颜色为白色
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.setStatusBarColor(Color.WHITE);
            window.setNavigationBarColor(Color.WHITE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        urlInput = findViewById(R.id.urlInput);
        downloadButton = findViewById(R.id.downloadButton);
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        versionTextView = findViewById(R.id.versionTextView);
        clearButton = findViewById(R.id.clearButton);
        titleLayout = findViewById(R.id.titleLayout);
        imageContainer = findViewById(R.id.imageContainer);

        // 检查并请求存储权限
        checkPermissions();

        // Set the app version in the versionTextView
        setAppVersion();

        // Set up the title layout click functionality to open GitHub repository
        if (titleLayout != null) {
            titleLayout.setOnClickListener(v -> openGitHubRepository());
        }

        // Set up the clear button functionality
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
            startDownload(url);
        });
    }

    private void setAppVersion() {
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (versionTextView != null) {
                versionTextView.setText("v" + versionName);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error getting app version: " + e.getMessage());
            if (versionTextView != null) {
                versionTextView.setText("v?.?.?");
            }
        }
    }

    private void openGitHubRepository() {
        String url = "https://github.com/NEORUAA/XHS_Downloader_Android";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.e("MainActivity", "Error opening GitHub repository: " + e.getMessage());
            // Fallback: try to show a toast message
            Toast.makeText(this, "Cannot open browser", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkPermissions() {
        // 对于Android 11+ (API 30+)，需要特殊的存储权限处理
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 使用MANAGE_EXTERNAL_STORAGE权限
            if (!Environment.isExternalStorageManager()) {
                // 请求MANAGE_EXTERNAL_STORAGE权限
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
                Toast.makeText(this, getString(R.string.grant_all_files_access), Toast.LENGTH_LONG).show();
            }
        } else {
            // Android 10及以下版本，检查传统存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                // 请求存储权限
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, 
                                     Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
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
        // 在应用恢复时再次检查权限状态
        checkPermissions();
    }

    private void clearImageViews() {
        imageContainer.removeAllViews();
    }

    private void addMediaView(String filePath) {
        File mediaFile = new File(filePath);
        if (mediaFile.exists()) {
            String mimeType = getMimeType(filePath);
            
            if (isImageFile(mimeType)) {
                // 显示图片 - 使用采样率避免内存溢出
                Bitmap bitmap = decodeSampledBitmapFromFile(filePath, 600, 600); // 限制在600x600像素内
                if (bitmap != null) {
                    ImageView imageView = new ImageView(this);
                    imageView.setImageBitmap(bitmap);
                    imageView.setLayoutParams(new LinearLayout.LayoutParams(300, 300)); // 设置固定大小
                    imageView.setAdjustViewBounds(true);
                    imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    imageView.setPadding(5, 5, 5, 5);
                    
                    // 添加点击事件来查看大图
                    imageView.setOnClickListener(v -> openImageInExternalApp(filePath));
                    
                    imageContainer.addView(imageView);
                }
            } else if (isVideoFile(mimeType)) {
                // 创建视频缩略图（包括Live Photo视频）
                // 生成视频缩略图
                Bitmap thumbnail = createVideoThumbnail(filePath);
                if (thumbnail != null) {
                    ImageView thumbnailView = new ImageView(this);
                    thumbnailView.setImageBitmap(thumbnail);
                    thumbnailView.setLayoutParams(new LinearLayout.LayoutParams(300, 300));
                    thumbnailView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbnailView.setPadding(5, 5, 5, 5);
                    
                    // 添加播放图标覆盖
                    thumbnailView.setBackgroundResource(R.drawable.play_button_overlay); // 如果没有此资源，将显示纯缩略图
                    
                    // 添加点击事件来播放视频
                    thumbnailView.setOnClickListener(v -> openVideoInExternalApp(filePath));
                    
                    imageContainer.addView(thumbnailView);
                } else {
                    // 如果无法生成缩略图，显示一个通用视频图标
                    ImageView placeholderView = new ImageView(this);
                    placeholderView.setImageResource(android.R.drawable.ic_media_play); // 使用播放图标
                    placeholderView.setLayoutParams(new LinearLayout.LayoutParams(300, 300));
                    placeholderView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    placeholderView.setPadding(5, 5, 5, 5);
                    
                    placeholderView.setOnClickListener(v -> openVideoInExternalApp(filePath));
                    
                    imageContainer.addView(placeholderView);
                }
            } else {
                // 对于其他类型的文件，显示通用图标
                ImageView genericView = new ImageView(this);
                genericView.setImageResource(android.R.drawable.ic_menu_gallery); // 使用系统默认附件图标
                genericView.setLayoutParams(new LinearLayout.LayoutParams(300, 300));
                genericView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                genericView.setPadding(5, 5, 5, 5);
                
                genericView.setOnClickListener(v -> openFileInExternalApp(filePath));
                
                imageContainer.addView(genericView);
            }
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
                Toast.makeText(this, "Cannot open image: " + copyException.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        intent.setDataAndType(uri, "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Cannot open video: " + copyException.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        intent.setDataAndType(uri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Cannot open file: " + copyException.getMessage(), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void addMediaToDisplay(String filePath) {
        runOnUiThread(() -> addMediaView(filePath));
    }

    private void startDownload(String url) {
        // Disable button and show progress
        downloadButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(getString(R.string.processing_url, url));

        // Create download task
        DownloadTask task = new DownloadTask(this, statusText, progressBar, downloadButton);
        task.execute(url);
    }
}