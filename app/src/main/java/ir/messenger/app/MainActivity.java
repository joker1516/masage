package ir.messenger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * پوسته اندرویدی پیامرسان داخلی.
 * آدرس هاست فقط بار اول پرسیده میشود؛ بعداً از منوی همبرگری داخل programs قابل تغییر است.
 * اعلانها حتی وقتی صفحه بسته است توسط NotificationService ارسال میشوند.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MessengerWrapper";
    private static final String PREFS = "messenger_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "notif_token";
    private static final String KEY_ME = "notif_me_id";
    private static final String KEY_ME_NAME = "notif_me_name";
    private static final String KEY_CHATS = "notif_chats";
    private static final String KEY_CUR = "notif_current_chat";
    private static final String KEY_FOREGROUND = "notif_foreground";
    private static final int REQ_FILE_CHOOSER = 102;
    private static final int REQ_RUNTIME = 103;

    private WebView web;
    private ProgressBar progress;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private String pendingUrl;
    private String currentBase;
    private String pendingChatId;
    private String pendingPermKind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifier.ensureChannels(this);
        setForegroundFlag("1");
        handleIncomingIntent(getIntent());
        String saved = loadUrl();
        if (saved == null || saved.isEmpty()) showSetup(null);
        else openApp(saved);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra("chat_id");
        if (id != null && !id.isEmpty()) {
            pendingChatId = id;
            if (web != null) deliverPendingChat();
        }
    }

    private void deliverPendingChat() {
        if (web == null || pendingChatId == null) return;
        final String id = pendingChatId;
        pendingChatId = null;
        web.evaluateJavascript(
                "if(window.openChatFromNative){window.openChatFromNative('" + id + "');}", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setForegroundFlag("1");
    }

    @Override
    protected void onPause() {
        setForegroundFlag("0");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        setForegroundFlag("0");
        super.onDestroy();
    }

    /* ───────── تنظیمات ذخیره آدرس ───────── */

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String loadUrl() {
        return prefs().getString(KEY_URL, "");
    }

    private void saveUrl(String url) {
        android.content.SharedPreferences.Editor e = prefs().edit();
        if (url == null || url.isEmpty()) e.remove(KEY_URL);
        else e.putString(KEY_URL, url);
        e.apply();
    }

    private void setPref(String key, String value) {
        android.content.SharedPreferences.Editor e = prefs().edit();
        if (value == null || value.isEmpty()) e.remove(key);
        else e.putString(key, value);
        e.apply();
    }

    private void setForegroundFlag(String v) {
        setPref(KEY_FOREGROUND, v);
    }

    /** آدرس واردشده را پاک و استاندارد میکند و «index.html» اضافه نمیکند. */
    private String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        if (s.endsWith("/index.html")) s = s.substring(0, s.length() - "/index.html".length());
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        try {
            URL u = new URL(s);
            if (u.getHost() == null || u.getHost().isEmpty()) return null;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ───────── صفحه اول: وارد کردن آدرس ───────── */

    private void showSetup(String current) {
        web = null;
        progress = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(40), dp(28), dp(24));
        root.setBackgroundColor(0xFFF4F7FC);

        TextView title = new TextView(this);
        title.setText("اتصال به سرور پیامرسان");
        title.setTextSize(21);
        title.setTextColor(0xFF12203A);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));

        TextView help = new TextView(this);
        help.setText("آدرس هاست یا IP سرور را وارد کنید.\n"
                + "مثال: https://messenger.example.com\n"
                + "برای IP محلی از http://192.168.1.10:8080 استفاده کنید.");
        help.setTextSize(13);
        help.setTextColor(0xFF64748B);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, 0, 0, dp(18));

        final EditText input = new EditText(this);
        input.setHint("https://...");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(current == null ? "" : current);
        input.setBackgroundColor(0xFFFFFFFF);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));

        Button connect = new Button(this);
        connect.setText("اتصال");
        connect.setAllCaps(false);
        connect.setTextColor(0xFFFFFFFF);
        connect.setBackgroundColor(0xFF3390EC);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(14);
        connect.setLayoutParams(bp);
        connect.setOnClickListener(v -> {
            String value = normalizeUrl(input.getText().toString());
            if (value == null) {
                input.setError("آدرس معتبر نیست");
                return;
            }
            saveUrl(value);
            openApp(value);
        });

        TextView note = new TextView(this);
        note.setText("بعداً از منوی همبرگری داخل برنامه ← «آدرس سرور» میتوانید آدرس را عوض کنید.");
        note.setTextSize(12);
        note.setTextColor(0xFF94A3B8);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);

        root.addView(title);
        root.addView(help);
        root.addView(input);
        root.addView(connect);
        root.addView(note);
        setContentView(root);
    }

    /* ───────── مجوزها (فقط هنگام نیاز، نه در شروع برنامه) ───────── */

    private String[] permsFor(String kind) {
        if (kind == null) return new String[0];
        switch (kind) {
            case "camera":
                return new String[]{android.Manifest.permission.CAMERA};
            case "mic":
                return new String[]{android.Manifest.permission.RECORD_AUDIO};
            case "notifications":
                return Build.VERSION.SDK_INT >= 33
                        ? new String[]{android.Manifest.permission.POST_NOTIFICATIONS}
                        : new String[0];
            case "media":
                if (Build.VERSION.SDK_INT >= 33) {
                    return new String[]{android.Manifest.permission.READ_MEDIA_IMAGES,
                            android.Manifest.permission.READ_MEDIA_VIDEO};
                }
                return new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
            default:
                return new String[0];
        }
    }

    private void requestRuntimePermission(final String kind) {
        runOnUiThread(() -> {
            String[] perms = permsFor(kind);
            if (Build.VERSION.SDK_INT < 23 || perms.length == 0) {
                callbackPermission(kind, true);
                return;
            }
            List<String> need = new ArrayList<>();
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
            if (need.isEmpty()) {
                callbackPermission(kind, true);
                return;
            }
            pendingPermKind = kind;
            requestPermissions(need.toArray(new String[0]), REQ_RUNTIME);
        });
    }

    private void callbackPermission(String kind, boolean granted) {
        if (web == null) return;
        String k = kind == null ? "" : kind.replace("'", "");
        web.evaluateJavascript(
                "if(window.onNativePermission){window.onNativePermission('" + k + "'," + granted + ");}", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RUNTIME) return;
        boolean all = grantResults.length > 0;
        for (int g : grantResults) if (g != PackageManager.PERMISSION_GRANTED) all = false;
        String kind = pendingPermKind;
        pendingPermKind = null;
        callbackPermission(kind, all);
    }

    /* ───────── بارگذاری سایت ───────── */

    private void openApp(String base) {
        currentBase = base;
        /* پارامتر یکتا باعث می‌شود WebView نسخه قدیمی HTML/CSS را از کش نگیرد */
        final String target = base + "/index.html?v=" + System.currentTimeMillis();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        String ua = s.getUserAgentString();
        s.setUserAgentString((ua == null ? "" : ua + " ") + "MessengerAndroid/1.0");

        web.addJavascriptInterface(new JsBridge(), "MessengerBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(view, request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(ProgressBar.GONE);
                view.evaluateJavascript(
                        "if(window.__messengerNativeReady){window.__messengerNativeReady();}", null);
                deliverPendingChat();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    progress.setVisibility(ProgressBar.GONE);
                    Toast.makeText(MainActivity.this,
                            "اتصال به سرور برقرار نشد؛ آدرس سرور را چک کنید", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                progress.setVisibility(ProgressBar.GONE);
                Toast.makeText(MainActivity.this,
                        "اتصال به سرور برقرار نشد؛ آدرس سرور را چک کنید", Toast.LENGTH_LONG).show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                final List<String> granted = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                            || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) granted.add(r);
                }
                if (granted.isEmpty()) request.deny();
                else request.grant(granted.toArray(new String[0]));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                boolean canCamera = Build.VERSION.SDK_INT < 23
                        || checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (canCamera && camera.resolveActivity(getPackageManager()) != null) {
                    cameraUri = createCameraUri();
                    if (cameraUri != null) {
                        camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    }
                }

                Intent picker = params.createIntent();
                Intent chooser = Intent.createChooser(picker, "انتخاب فایل");
                if (cameraUri != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                }
                try {
                    startActivityForResult(chooser, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "انتخاب فایل ممکن نشد", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        web.loadUrl(target);
    }

    private boolean handleUrl(WebView view, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            view.loadUrl(url);
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            Log.w(TAG, "cannot open external url: " + url);
        }
        return true;
    }

    private Uri createCameraUri() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "msg_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Messenger");
        }
        try {
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER) return;
        ValueCallback<Uri[]> cb = filePathCallback;
        filePathCallback = null;
        if (cb == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (cameraUri != null) {
                results = new Uri[]{cameraUri};
            }
        }
        cb.onReceiveValue(results);
        cameraUri = null;
    }

    /* ───────── پل جاوااسکریپت ───────── */

    public class JsBridge {
        @JavascriptInterface
        public void setToken(String token) {
            setPref(KEY_TOKEN, token == null ? "" : token.trim());
            if (token == null || token.trim().isEmpty()) stopNotificationService();
            else startNotificationService();
        }

        @JavascriptInterface
        public void setMe(String id, String name) {
            setPref(KEY_ME, id);
            setPref(KEY_ME_NAME, name);
        }

        @JavascriptInterface
        public void syncChats(String json) {
            setPref(KEY_CHATS, json == null ? "{}" : json);
        }

        @JavascriptInterface
        public void setCurrentChat(String id) {
            setPref(KEY_CUR, id == null ? "" : id);
        }

        @JavascriptInterface
        public void setForeground(String on) {
            setForegroundFlag("1".equals(on) ? "1" : "0");
        }

        @JavascriptInterface
        public void notify(String title, String body, String chatId) {
            Notifier.show(MainActivity.this, title, body, chatId);
        }

        /** درخواست مجوز فقط زمانی که کاربر از داخل برنامه به آن نیاز پیدا می‌کند. */
        @JavascriptInterface
        public void requestPermission(String kind) {
            requestRuntimePermission(kind);
        }

        @JavascriptInterface
        public String serverUrl() {
            return currentBase == null ? "" : currentBase;
        }

        @JavascriptInterface
        public void changeServer() {
            runOnUiThread(MainActivity.this::showUrlDialog);
        }

        @JavascriptInterface
        public void logout() {
            setPref(KEY_TOKEN, "");
            setPref(KEY_CHATS, "{}");
            setPref(KEY_CUR, "");
            stopNotificationService();
        }
    }

    private void startNotificationService() {
        try {
            Intent i = new Intent(this, NotificationService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot start notification service: " + e.getMessage());
        }
    }

    private void stopNotificationService() {
        try { stopService(new Intent(this, NotificationService.class)); } catch (Exception ignored) { }
    }

    /* ───────── تغییر آدرس از منوی همبرگری ───────── */

    private void showUrlDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(loadUrl());

        new AlertDialog.Builder(this)
                .setTitle("آدرس سرور")
                .setMessage("اگر دامنه یا IP هاست عوض شد، آدرس جدید را وارد کنید.")
                .setView(input)
                .setPositiveButton("ذخیره و اتصال", (DialogInterface dialog, int which) -> {
                    String value = normalizeUrl(input.getText().toString());
                    if (value == null) {
                        Toast.makeText(this, "آدرس معتبر نیست", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveUrl(value);
                    openApp(value);
                })
                .setNeutralButton("پاک کردن آدرس", (DialogInterface dialog, int which) -> {
                    saveUrl(null);
                    showSetup(null);
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
