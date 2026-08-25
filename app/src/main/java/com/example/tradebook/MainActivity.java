package com.example.tradebook;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String DEFAULT_SERVER_URL = "https://ldril380.synology.me";
    private static final int FILE_CHOOSER = 1001;
    private static final int STORAGE_PERMISSION = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingContentDisposition;
    private String pendingMimeType;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        configureSystemBars();
        prefs = getSharedPreferences("tradebook", MODE_PRIVATE);
        buildUi();

        String url = prefs.getString("server_url", "");
        if (url.isEmpty() || url.contains("192.168.") || url.contains(":8080")) {
            url = DEFAULT_SERVER_URL;
            prefs.edit().putString("server_url", url).apply();
        }
        loadServer(url);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackPressed);
        }
    }

    private void configureSystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.rgb(32, 33, 36));
            w.setNavigationBarColor(Color.WHITE);
        }
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(0);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(Color.rgb(32, 33, 36));

        TextView title = new TextView(this);
        title.setText("통합 장부");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button reload = smallButton("새로고침");
        reload.setOnClickListener(v -> webView.reload());
        bar.addView(reload);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                applyResponsiveMobileFix();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(
                    WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                // Do not provide EXTRA_MIME_TYPES here. The previous MIME allow-list
                // made Android DocumentsUI grey out HWP/HWPX, images and other valid
                // formats even though the web app accepts them. The web layer validates
                // the extension after selection, so the native picker must allow all files.
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");

                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    startActivityForResult(i, FILE_CHOOSER);
                } catch (Exception ex) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    /** Mobile-only repair for fixed-width/fixed-row web layouts. PC rendering is untouched. */
    private void applyResponsiveMobileFix() {
        if (webView == null) return;

        String js =
                "(function(){try{" +
                "var w=window.innerWidth||document.documentElement.clientWidth;" +
                "var m=document.querySelector('meta[name=\\\"viewport\\\"]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                "m.setAttribute('content','width=device-width, initial-scale=1, viewport-fit=cover');" +
                "var old=document.getElementById('__tradebook_mobile_fix');if(old)old.remove();" +
                "if(w<=900){" +
                "var s=document.createElement('style');s.id='__tradebook_mobile_fix';" +
                "s.textContent=" +
                "'*,*::before,*::after{box-sizing:border-box!important;}'+" +
                "'html,body{width:100%!important;max-width:100%!important;min-width:0!important;overflow-x:hidden!important;margin:0!important;}'+" +
                "'body{font-size:16px!important;}'+" +
                "'body>*{max-width:100%!important;min-width:0!important;}'+" +
                "'button,input,select,textarea{max-width:100%!important;min-width:0!important;}'+" +
                "'img,video,canvas,iframe{max-width:100%!important;height:auto;}'+" +
                "'table{max-width:100%!important;}'+" +
                "'nav,header{max-width:100%!important;overflow:visible!important;}'+" +
                "'a,button{white-space:normal!important;overflow-wrap:anywhere!important;}'+" +
                "'.container,.content,.card,.panel,.section{max-width:100%!important;min-width:0!important;}'+" +
                "'input[type=file]{max-width:100%!important;}'+" +
                "'@media(max-width:600px){h1{font-size:28px!important;line-height:1.2!important;}h2{font-size:24px!important;}h3{font-size:20px!important;}button{font-size:15px!important;line-height:1.2!important;padding:10px 14px!important;}}';" +
                "document.head.appendChild(s);" +
                "document.querySelectorAll('*').forEach(function(el){" +
                "var cs=getComputedStyle(el);" +
                "if(cs.display==='flex'||cs.display==='inline-flex'){el.style.minWidth='0';el.style.flexWrap='wrap';}" +
                "var r=el.getBoundingClientRect();" +
                "if(r.width>w+2 && cs.position!=='fixed' && cs.position!=='sticky'){el.style.maxWidth='100%';el.style.minWidth='0';}" +
                "});" +
                "}" +
                "}catch(e){}})();";

        webView.evaluateJavascript(js, null);
    }

    private Button smallButton(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }

    private void showServerDialog(boolean first) {
        EditText e = new EditText(this);
        e.setHint("https://ldril380.synology.me 또는 내부 NAS 주소");
        e.setSingleLine(true);
        e.setText(prefs.getString("server_url", DEFAULT_SERVER_URL));

        int p = dp(18);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, 0, p, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("NAS 서버 주소 설정")
                .setMessage("PC 웹과 같은 NAS 장부 서버 주소를 입력하세요.")
                .setView(box)
                .setPositiveButton("연결", null)
                .setNegativeButton(first ? "종료" : "취소", (x, w) -> { if(first) finish(); })
                .create();

        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = normalize(e.getText().toString());
            if (url.isEmpty()) { e.setError("서버 주소를 입력하세요."); return; }
            prefs.edit().putString("server_url", url).apply();
            d.dismiss();
            loadServer(url);
        }));
        d.show();
    }

    private String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void loadServer(String url) { webView.loadUrl(normalize(url) + "/"); }

    private void startDownload(String url, String ua, String cd, String mime) {
        pendingDownloadUrl = url;
        pendingDownloadUserAgent = ua;
        pendingContentDisposition = cd;
        pendingMimeType = mime;

        if (Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
            return;
        }
        doDownload();
    }

    private void doDownload() {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(pendingDownloadUrl));
            String cookie = CookieManager.getInstance().getCookie(pendingDownloadUrl);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (pendingDownloadUserAgent != null) req.addRequestHeader("User-Agent", pendingDownloadUserAgent);
            String name = URLUtil.guessFileName(pendingDownloadUrl, pendingContentDisposition, pendingMimeType);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "다운로드를 시작했습니다.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "다운로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) doDownload();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER || fileCallback == null) return;

        Uri[] out = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                out = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    out[i] = clip.getItemAt(i).getUri();
                    takePersistablePermission(out[i], data.getFlags());
                }
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
                takePersistablePermission(out[0], data.getFlags());
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    private void takePersistablePermission(Uri uri, int flags) {
        if (uri == null || Build.VERSION.SDK_INT < 19) return;
        try {
            int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) { }
    }

    private void handleBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else finish();
    }

    @Override public void onBackPressed() { handleBackPressed(); }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
