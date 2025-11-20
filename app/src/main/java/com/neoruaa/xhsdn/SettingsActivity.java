package com.neoruaa.xhsdn;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private CheckBox livePhotoCheckBox;
    private CheckBox customNamingCheckBox;
    private View customNamingContainer;
    private EditText customFormatEditText;
    private RecyclerView availableTokensRecyclerView;
    private Button resetFormatButton;
    private TokenAdapter tokenAdapter;
    private boolean isLoadingSettings = false;
    private boolean hasAppliedChanges = false;

    private interface TokenInsertListener {
        void onInsert(String placeholder);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        configureWindowChrome();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.settings));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        livePhotoCheckBox = findViewById(R.id.livePhotoCheckBox);
        customNamingCheckBox = findViewById(R.id.customNamingCheckBox);
        customNamingContainer = findViewById(R.id.customNamingContainer);
        customFormatEditText = findViewById(R.id.customFormatEditText);
        availableTokensRecyclerView = findViewById(R.id.availableTokensRecyclerView);
        resetFormatButton = findViewById(R.id.resetFormatButton);
        Button githubButton = findViewById(R.id.githubButton);

        setupTokenRecycler();
        setupDragAndDrop();

        resetFormatButton.setOnClickListener(v -> customFormatEditText.setText(NamingFormat.DEFAULT_TEMPLATE));
        customNamingCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isLoadingSettings) return;
            updateCustomNamingVisibility(isChecked);
            applySettings(false);
        });
        livePhotoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isLoadingSettings) return;
            applySettings(false);
        });
        customFormatEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isLoadingSettings) return;
                applySettings(false);
            }
        });
        githubButton.setOnClickListener(v -> openGitHubRepository());

        loadCurrentSettings();
    }

    private void configureWindowChrome() {
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
    }

    private void loadCurrentSettings() {
        isLoadingSettings = true;
        SharedPreferences prefs = getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        boolean createLivePhotos = prefs.getBoolean("create_live_photos", true);
        livePhotoCheckBox.setChecked(createLivePhotos);

        boolean hasNewFlag = prefs.contains("use_custom_naming_format");
        boolean legacyFlag = prefs.getBoolean("use_metadata_file_names", false);
        boolean useCustomNaming = hasNewFlag ? prefs.getBoolean("use_custom_naming_format", false) : legacyFlag;
        customNamingCheckBox.setChecked(useCustomNaming);

        String formatTemplate = prefs.getString("custom_naming_template", NamingFormat.DEFAULT_TEMPLATE);
        if (TextUtils.isEmpty(formatTemplate)) {
            formatTemplate = NamingFormat.DEFAULT_TEMPLATE;
        }
        customFormatEditText.setText(formatTemplate);
        customFormatEditText.setSelection(customFormatEditText.getText().length());
        updateCustomNamingVisibility(useCustomNaming);
        isLoadingSettings = false;
    }

    private boolean applySettings(boolean showToast) {
        if (isLoadingSettings) {
            return false;
        }
        boolean createLivePhotos = livePhotoCheckBox.isChecked();
        boolean useCustomNaming = customNamingCheckBox.isChecked();
        String template = customFormatEditText.getText() != null ? customFormatEditText.getText().toString().trim() : "";
        if (TextUtils.isEmpty(template)) {
            template = NamingFormat.DEFAULT_TEMPLATE;
        }

        SharedPreferences prefs = getSharedPreferences("XHSDownloaderPrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("create_live_photos", createLivePhotos);
        editor.putBoolean("use_custom_naming_format", useCustomNaming);
        editor.putString("custom_naming_template", template);
        editor.remove("use_metadata_file_names");
        editor.apply();

        hasAppliedChanges = true;
        if (showToast) {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void setupTokenRecycler() {
        availableTokensRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        tokenAdapter = new TokenAdapter(NamingFormat.getAvailableTokens(), this::insertTokenPlaceholder);
        availableTokensRecyclerView.setAdapter(tokenAdapter);
        availableTokensRecyclerView.setNestedScrollingEnabled(false);
    }

    private void setupDragAndDrop() {
        customFormatEditText.setOnDragListener((v, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                ClipDescription description = event.getClipDescription();
                return description != null && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
            } else if (event.getAction() == DragEvent.ACTION_DROP) {
                ClipData clipData = event.getClipData();
                if (clipData != null && clipData.getItemCount() > 0) {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text != null) {
                        insertTokenPlaceholder(text.toString());
                    }
                }
                return true;
            }
            return true;
        });
    }

    private void updateCustomNamingVisibility(boolean enabled) {
        customNamingContainer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        customFormatEditText.setEnabled(enabled);
        resetFormatButton.setEnabled(enabled);
        availableTokensRecyclerView.setAlpha(enabled ? 1f : 0.5f);
        if (tokenAdapter != null) {
            tokenAdapter.setEnabled(enabled);
        }
    }

    private void insertTokenPlaceholder(String placeholder) {
        if (customFormatEditText.getText() == null) {
            return;
        }
        int start = Math.max(customFormatEditText.getSelectionStart(), 0);
        customFormatEditText.getText().insert(start, placeholder);
        customFormatEditText.requestFocus();
        customFormatEditText.setSelection(start + placeholder.length());
    }

    private void openGitHubRepository() {
        String url = "https://github.com/NEORUAA/XHS_Downloader_Android";
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        setResult(hasAppliedChanges ? RESULT_OK : RESULT_CANCELED);
        super.finish();
    }

    private static class TokenAdapter extends RecyclerView.Adapter<TokenAdapter.TokenViewHolder> {
        private final List<NamingFormat.TokenDefinition> tokens;
        private final TokenInsertListener tokenConsumer;
        private boolean enabled = true;

        TokenAdapter(List<NamingFormat.TokenDefinition> tokens, TokenInsertListener consumer) {
            this.tokens = tokens;
            this.tokenConsumer = consumer;
        }

        void setEnabled(boolean enabled) {
            if (this.enabled != enabled) {
                this.enabled = enabled;
                notifyDataSetChanged();
            } else {
                this.enabled = enabled;
            }
        }

        @Override
        public int getItemCount() {
            return tokens.size();
        }

        @Override
        public TokenViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_token_chip, parent, false);
            return new TokenViewHolder(view);
        }

        @Override
        public void onBindViewHolder(TokenViewHolder holder, int position) {
            NamingFormat.TokenDefinition token = tokens.get(position);
            holder.bind(token, tokenConsumer, enabled);
        }

        static class TokenViewHolder extends RecyclerView.ViewHolder {
            private final android.widget.TextView label;
            private final android.widget.TextView placeholder;

            TokenViewHolder(View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.tokenLabel);
                placeholder = itemView.findViewById(R.id.tokenPlaceholder);
            }

            void bind(NamingFormat.TokenDefinition token, TokenInsertListener consumer, boolean enabled) {
                Context context = itemView.getContext();
                label.setText(context.getString(token.labelResId));
                String placeholderText = token.getPlaceholder();
                placeholder.setText(placeholderText);
                itemView.setAlpha(enabled ? 1f : 0.5f);
                itemView.setOnClickListener(enabled ? v -> consumer.onInsert(placeholderText) : null);
                if (enabled) {
                    itemView.setOnLongClickListener(v -> {
                        ClipData dragData = ClipData.newPlainText("naming_token", placeholderText);
                        View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            v.startDragAndDrop(dragData, shadow, null, 0);
                        } else {
                            v.startDrag(dragData, shadow, null, 0);
                        }
                        return true;
                    });
                } else {
                    itemView.setOnLongClickListener(null);
                }
            }
        }
    }
}
