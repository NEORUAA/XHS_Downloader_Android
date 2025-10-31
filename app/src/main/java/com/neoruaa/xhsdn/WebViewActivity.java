package com.neoruaa.xhsdn;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebViewActivity extends AppCompatActivity {
    private static final String TAG = "WebViewActivity";
    private WebView webView;
    private EditText urlEditText;
    private Button goButton;
    private Button crawlImagesButton;
    private ProgressBar progressBar;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_view);

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

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.webview_title));
        }
        
        webView = findViewById(R.id.webview);
        urlEditText = findViewById(R.id.url_edit_text);
        goButton = findViewById(R.id.go_button);
        crawlImagesButton = findViewById(R.id.crawl_images_button);
        progressBar = findViewById(R.id.progress_bar);
        
        // Enable JavaScript
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // Set PC User Agent to prevent redirecting to mobile app
        webSettings.setUserAgentString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        
        // Set up web view client
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                urlEditText.setText(url);
            }
            
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                progressBar.setProgress(newProgress);
            }
        });
        
        // Set up buttons
        goButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            if (!url.isEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                webView.loadUrl(url);
            }
        });
        
        crawlImagesButton.setOnClickListener(v -> {
            // Execute JavaScript to find image URLs on the page
            webView.postDelayed(() -> {
                // Read the JavaScript code from assets file for easy maintenance and debugging
                String jsCode = readAssetFile("xhs_extractor.js");

                if (jsCode != null) {
                    webView.evaluateJavascript(jsCode, result -> {
                        try {
                            Log.d(TAG, "Raw JavaScript result: " + result);

                            // Check if the result is null or empty before parsing
                            if (result == null || result.equals("null") || result.equals("")) {
                                Log.d(TAG, "JavaScript returned null or empty - no URLs found");
                                Toast.makeText(WebViewActivity.this, getString(R.string.no_urls_found_javascript_null), Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Parse the JSON result directly (it's an array)
                            JSONArray urlsArray = new JSONArray(result);
                            List<String> allUrls = new ArrayList<>();

                            for (int i = 0; i < urlsArray.length(); i++) {
                                String url = urlsArray.getString(i);

                                if (url != null && !url.isEmpty()) {
                                    // Only add URLs that start with http and are not blob/data URLs
                                    if (url.startsWith("http") && !url.startsWith("blob:") && !url.startsWith("data:")) {
                                        allUrls.add(url);
                                    } else {
                                        Log.d(TAG, "Skipping URL (blob/data): " + url);
                                    }
                                }
                            }

                            Log.d(TAG, "Found " + allUrls.size() + " accessible URLs: " + allUrls);

                            if (!allUrls.isEmpty()) {
                                // Return to MainActivity with the found URLs
                                Intent resultIntent = new Intent();
                                resultIntent.putStringArrayListExtra("image_urls", new ArrayList<>(allUrls));
                                setResult(RESULT_OK, resultIntent);
                                finish();
                            } else {
                                Toast.makeText(WebViewActivity.this, getString(R.string.no_accessible_urls_found), Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing URLs", e);
                            Toast.makeText(WebViewActivity.this, getString(R.string.error_parsing_urls, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, 10);
        });
        
        // Load the URL from intent if available
        Intent intent = getIntent();
        if (intent != null && intent.getStringExtra("url") != null) {
            String url = intent.getStringExtra("url");
            urlEditText.setText(url);
            webView.loadUrl(url);
        } else {
            // Default to a blank page
            webView.loadUrl("about:blank");
        }
    }
    
    /**
     * Read JavaScript code from assets file
     * This allows easy maintenance and debugging of the JavaScript code
     */
    private String readAssetFile(String fileName) {
        try {
            java.io.InputStream inputStream = getAssets().open(fileName);
            int size = inputStream.available();
            byte[] buffer = new byte[size];
            inputStream.read(buffer);
            inputStream.close();
            return new String(buffer, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Error reading asset file: " + fileName, e);
            return null;
        }
    }
}