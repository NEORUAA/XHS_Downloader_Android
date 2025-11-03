package com.neoruaa.xhsdn;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 瀑布流媒体适配器
 */
public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {
    private static final String TAG = "MediaAdapter";
    private List<String> mediaFiles = new ArrayList<>();
    private Context context;

    public MediaAdapter(Context context) {
        this.context = context;
    }

    /**
     * 添加媒体文件
     */
    public void addItem(String filePath) {
        mediaFiles.add(filePath);
        notifyItemInserted(mediaFiles.size() - 1);
    }

    /**
     * 清除所有项
     */
    public void clearItems() {
        int size = mediaFiles.size();
        mediaFiles.clear();
        notifyItemRangeRemoved(0, size);
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ImageView imageView = new ImageView(context);
        imageView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int padding = dpToPx(4);
        imageView.setPadding(padding, padding, padding, padding);
        return new MediaViewHolder(imageView);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        String filePath = mediaFiles.get(position);
        File mediaFile = new File(filePath);
        
        if (mediaFile.exists()) {
            String mimeType = getMimeType(filePath);
            
            if (isImageFile(mimeType)) {
                // 显示图片
                Bitmap bitmap = decodeSampledBitmapFromFile(filePath, 800, 800);
                if (bitmap != null) {
                    holder.imageView.setImageBitmap(bitmap);
                    holder.imageView.setBackgroundColor(0xFFEEEEEE);
                    holder.imageView.setOnClickListener(v -> openImageInExternalApp(filePath));
                }
            } else if (isVideoFile(mimeType)) {
                // 显示视频缩略图
                Bitmap thumbnail = createVideoThumbnail(filePath);
                if (thumbnail != null) {
                    holder.imageView.setBackgroundColor(0xFFEEEEEE);
                    // Overlay the play icon on the video thumbnail
                    try {
                        Bitmap playIconBitmap = getBitmapFromVectorDrawable(context, R.drawable.play_button_overlay, dpToPx(200), dpToPx(200));
                        Bitmap combinedBitmap = combineBitmapWithPlayIcon(thumbnail, playIconBitmap, dpToPx(16)); // 16dp padding
                        holder.imageView.setImageBitmap(combinedBitmap);
                    } catch (Exception e) {
                        // If overlay fails, just show the thumbnail
                        holder.imageView.setImageBitmap(thumbnail);
                        Log.e(TAG, "Failed to add play icon overlay: " + e.getMessage());
                    }
                    holder.imageView.setOnClickListener(v -> openVideoInExternalApp(filePath));
                } else {
                    holder.imageView.setImageResource(android.R.drawable.ic_media_play);
                    holder.imageView.setBackgroundColor(0xFFEEEEEE);
                    holder.imageView.setOnClickListener(v -> openVideoInExternalApp(filePath));
                }
            } else {
                // 其他文件类型
                holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                holder.imageView.setBackgroundColor(0xFFEEEEEE);
                holder.imageView.setOnClickListener(v -> openFileInExternalApp(filePath));
            }
        }
    }

    @Override
    public int getItemCount() {
        return mediaFiles.size();
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;

        MediaViewHolder(ImageView itemView) {
            super(itemView);
            this.imageView = itemView;
        }
    }

    // 辅助方法
    
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

    private Bitmap decodeSampledBitmapFromFile(String filePath, int reqWidth, int reqHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(filePath, options);
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int heightRatio = Math.round((float) height / (float) reqHeight);
            final int widthRatio = Math.round((float) width / (float) reqWidth);
            inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
        }

        return inSampleSize;
    }

    private Bitmap createVideoThumbnail(String filePath) {
        try {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            return retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
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
            uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "FileProvider failed, copying file to app cache: " + e.getMessage());
            try {
                File cacheDir = context.getCacheDir();
                File tempFile = new File(cacheDir, file.getName());
                copyFile(file, tempFile);
                uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", tempFile);
            } catch (Exception copyException) {
                Log.e(TAG, "Failed to copy file to cache: " + copyException.getMessage());
                return;
            }
        }

        intent.setDataAndType(uri, "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open image: " + e.getMessage());
        }
    }

    private void openVideoInExternalApp(String videoPath) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(videoPath);
        Uri uri;

        try {
            uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "FileProvider failed for video, copying to app cache: " + e.getMessage());
            try {
                File cacheDir = context.getCacheDir();
                File tempFile = new File(cacheDir, file.getName());
                copyFile(file, tempFile);
                uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", tempFile);
            } catch (Exception copyException) {
                Log.e(TAG, "Failed to copy video to cache: " + copyException.getMessage());
                return;
            }
        }

        intent.setDataAndType(uri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open video: " + e.getMessage());
        }
    }

    private void openFileInExternalApp(String filePath) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        File file = new File(filePath);
        String mimeType = getMimeType(filePath);
        Uri uri;

        try {
            uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "FileProvider failed for generic file, copying to app cache: " + e.getMessage());
            try {
                File cacheDir = context.getCacheDir();
                File tempFile = new File(cacheDir, file.getName());
                copyFile(file, tempFile);
                uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", tempFile);
            } catch (Exception copyException) {
                Log.e(TAG, "Failed to copy generic file to cache: " + copyException.getMessage());
                return;
            }
        }

        if (mimeType != null) {
            intent.setDataAndType(uri, mimeType);
        } else {
            intent.setDataAndType(uri, "*/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open file: " + e.getMessage());
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

    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    /**
     * Convert a vector drawable to bitmap
     */
    private Bitmap getBitmapFromVectorDrawable(Context context, int drawableId, int width, int height) {
        Drawable drawable = context.getResources().getDrawable(drawableId);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }
    
    /**
     * Combine a thumbnail with a play icon
     */
    private Bitmap combineBitmapWithPlayIcon(Bitmap thumbnail, Bitmap playIcon, int padding) {
        // Create a copy of the thumbnail to draw on
        Bitmap result = thumbnail.copy(thumbnail.getConfig(), true);
        Canvas canvas = new Canvas(result);
        
        // Calculate the position to draw the play icon (centered with padding)
        int iconX = (canvas.getWidth() - playIcon.getWidth()) / 2;
        int iconY = (canvas.getHeight() - playIcon.getHeight()) / 2;
        
        // Draw the play icon in the center of the thumbnail
        canvas.drawBitmap(playIcon, iconX, iconY, null);
        
        return result;
    }
}

