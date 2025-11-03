package com.neoruaa.xhsdn;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;

import androidx.annotation.NonNull;

public class SettingsDialog extends Dialog {
    
    private CheckBox livePhotoCheckBox;
    private Button githubButton;
    private Button cancelButton;
    private Button applyButton;
    
    private OnSettingsAppliedListener settingsAppliedListener;
    
    public interface OnSettingsAppliedListener {
        void onSettingsApplied();
    }
    
    public SettingsDialog(@NonNull Context context) {
        super(context);
    }
    
    public void setOnSettingsAppliedListener(OnSettingsAppliedListener listener) {
        this.settingsAppliedListener = listener;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_settings);
        
        // Initialize views
        livePhotoCheckBox = findViewById(R.id.livePhotoCheckBox);
        githubButton = findViewById(R.id.githubButton);
        cancelButton = findViewById(R.id.cancelButton);
        applyButton = findViewById(R.id.applyButton);
        
        // Load current settings (only live photo setting remains)
        loadCurrentSettings();
        
        // Set up button listeners
        githubButton.setOnClickListener(v -> openGitHubRepository());
        cancelButton.setOnClickListener(v -> dismiss());
        applyButton.setOnClickListener(v -> applySettings());
    }
    
    private void loadCurrentSettings() {
        SharedPreferences prefs = getContext().getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        
        // Load live photo setting - now defaults to enabled
        boolean createLivePhotos = prefs.getBoolean("create_live_photos", true);
        livePhotoCheckBox.setChecked(createLivePhotos);
    }
    
    private void applySettings() {
        boolean createLivePhotos = livePhotoCheckBox.isChecked();
        
        // Save only the live photo setting (custom path functionality removed)
        SharedPreferences prefs = getContext().getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("create_live_photos", createLivePhotos);
        editor.apply();
        
        // Notify listener
        if (settingsAppliedListener != null) {
            settingsAppliedListener.onSettingsApplied();
        }
        
        dismiss();
    }
    
    private void openGitHubRepository() {
        String url = "https://github.com/NEORUAA/XHS_Downloader_Android";
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
        getContext().startActivity(intent);
    }
}