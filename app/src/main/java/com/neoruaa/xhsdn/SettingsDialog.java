package com.neoruaa.xhsdn;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.annotation.NonNull;

public class SettingsDialog extends Dialog {
    
    private EditText savePathEditText;
    private CheckBox livePhotoCheckBox;
    private Button resetPathButton;
    private Button githubButton;
    private Button cancelButton;
    private Button applyButton;
    
    private OnSettingsAppliedListener settingsAppliedListener;
    
    public interface OnSettingsAppliedListener {
        void onSettingsApplied(String savePath);
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
        savePathEditText = findViewById(R.id.savePathEditText);
        livePhotoCheckBox = findViewById(R.id.livePhotoCheckBox);
        resetPathButton = findViewById(R.id.resetPathButton);
        githubButton = findViewById(R.id.githubButton);
        cancelButton = findViewById(R.id.cancelButton);
        applyButton = findViewById(R.id.applyButton);
        
        // Load current settings
        loadCurrentSettings();
        
        // Set up button listeners
        resetPathButton.setOnClickListener(v -> resetToDefaultPath());
        githubButton.setOnClickListener(v -> openGitHubRepository());
        cancelButton.setOnClickListener(v -> dismiss());
        applyButton.setOnClickListener(v -> applySettings());
    }
    
    private void loadCurrentSettings() {
        SharedPreferences prefs = getContext().getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        String customPath = prefs.getString("custom_save_path", getDefaultSavePath());
        savePathEditText.setText(customPath);
        
        // Load live photo setting - now defaults to enabled
        boolean createLivePhotos = prefs.getBoolean("create_live_photos", true);
        livePhotoCheckBox.setChecked(createLivePhotos);
    }
    
    private void resetToDefaultPath() {
        savePathEditText.setText(getDefaultSavePath());
    }
    
    private String getDefaultSavePath() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath() + "/xhs";
    }
    
    private void applySettings() {
        String savePath = savePathEditText.getText().toString().trim();
        boolean createLivePhotos = livePhotoCheckBox.isChecked();
        
        // Save the settings
        SharedPreferences prefs = getContext().getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("custom_save_path", savePath);
        editor.putBoolean("create_live_photos", createLivePhotos);
        editor.apply();
        
        // Notify listener
        if (settingsAppliedListener != null) {
            settingsAppliedListener.onSettingsApplied(savePath);
        }
        
        dismiss();
    }
    
    private void openGitHubRepository() {
        String url = "https://github.com/NEORUAA/XHS_Downloader_Android";
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
        getContext().startActivity(intent);
    }
}