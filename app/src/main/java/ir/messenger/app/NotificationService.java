package ir.messenger.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * وقتی صفحه وب بسته است، سرور را polling میکند و اعلان بومی نشان میدهد.
 * توکن و لیست گفتگوها توسط وباپ از طریق MessengerBridge در SharedPreferences ذخیره میشود.
 */
public class NotificationService extends Service {

    private static final String TAG = "MsgNotifService";
    private static final String PREFS = "messenger_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "notif_token";
    private static final String KEY_ME = "notif_me_id";
    private static final String KEY_CHATS = "notif_chats";
    private static final String KEY_CUR = "notif_current_chat";
    private static final String KEY_FOREGROUND = "notif_foreground";
    private static final String KEY_LAST = "notif_last_event";

    private volatile boolean running = true;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(Notifier.SERVICE_ID, Notifier.serviceNotification(this));
        }
        worker = new Thread(this::loop, "messenger-notification-poller");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void saveLast(long id) {
        prefs().edit().putLong(KEY_LAST, id).apply();
    }

    private void loop() {
        while (running) {
            try {
                SharedPreferences p = prefs();
                String base = p.getString(KEY_URL, "");
                String token = p.getString(KEY_TOKEN, "");
                if (base == null || base.isEmpty() || token == null || token.isEmpty()) {
                    sleep(3000);
                    continue;
                }
                long last = p.getLong(KEY_LAST, -1);
                String url = base + "/api.php?r=/poll&token=" + URLEncoder.encode(token, "UTF-8");
                if (last >= 0) url += "&since=" + last;

                String raw = httpGet(url, token);
                JSONObject j = new JSONObject(raw);
                long fresh = j.optLong("last", last);
                if (last < 0) {
                    // اولین اجرا: فقط مکان نشانگر را میگیریم تا پیامهای قدیمی اعلان نشود
                    saveLast(fresh >= 0 ? fresh : 0);
                } else {
                    handleEvents(j.optJSONArray("events"), p);
                    saveLast(fresh >= 0 ? fresh : last);
                }
            } catch (Exception e) {
                Log.w(TAG, "poll failed: " + e.getMessage());
            }
            sleep(2500);
        }
    }

    private void handleEvents(JSONArray events, SharedPreferences p) {
        if (events == null) return;
        boolean foreground = "1".equals(p.getString(KEY_FOREGROUND, "0"));
        String myId = p.getString(KEY_ME, "");
        String curChat = p.getString(KEY_CUR, "");
        JSONObject chats = new JSONObject();
        try { chats = new JSONObject(p.getString(KEY_CHATS, "{}")); } catch (Exception ignored) { }

        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            String type = e.optString("type");

            /* منشن: اعلان مخصوص، جدا از پیام معمولی */
            if ("mention".equals(type)) {
                String chatId = String.valueOf(e.optLong("chat_id", -1));
                if (!curChat.isEmpty() && chatId.equals(curChat)) continue;
                JSONObject c = chats.optJSONObject(chatId);
                if (c != null && c.optBoolean("muted")) continue;
                String title = c != null && !c.optString("title", "").isEmpty()
                        ? c.optString("title") : "گروه";
                String who = e.optString("from_name", "");
                String text = (who.isEmpty() ? "" : who + ": ") + e.optString("preview", "");
                if (!foreground) Notifier.show(this, "منشن در " + title, text, chatId);
                continue;
            }

            if (!"msg".equals(type)) continue;
            JSONObject m = e.optJSONObject("message");
            if (m == null || "system".equals(m.optString("kind"))) continue;

            String sender = String.valueOf(m.optLong("sender_id", -1));
            if (!myId.isEmpty() && sender.equals(myId)) continue;

            /* اگر همین پیام ما را منشن کرده، اعلانش را رویداد mention میدهد */
            JSONArray men = m.optJSONArray("mentions");
            boolean mentionsMe = false;
            if (men != null && !myId.isEmpty()) {
                for (int k = 0; k < men.length(); k++) {
                    if (String.valueOf(men.optLong(k, -1)).equals(myId)) { mentionsMe = true; break; }
                }
            }
            if (mentionsMe) continue;

            String chatId = String.valueOf(m.optLong("chat_id", -1));
            if (!curChat.isEmpty() && chatId.equals(curChat)) continue;

            JSONObject c = chats.optJSONObject(chatId);
            if (c != null && c.optBoolean("muted")) continue;

            String title = c != null && !c.optString("title", "").isEmpty()
                    ? c.optString("title") : "پیام جدید";
            String name = m.optString("sender_name", "");
            String body = preview(m);
            if (c != null && "group".equals(c.optString("type")) && !name.isEmpty()) {
                body = name + ": " + body;
            }
            // وقتی برنامه باز است، وباپ خودش اعلان را میسازد (جلوگیری از دو اعلان)
            if (!foreground) Notifier.show(this, title, body, chatId);
        }
    }

    private String preview(JSONObject m) {
        String body = m.optString("body", "");
        if (body == null || body.isEmpty()) {
            String kind = m.optString("kind", "");
            String mime = m.optString("file_mime", "");
            if ("file".equals(kind)) {
                if (mime.startsWith("image/")) body = "📷 عکس";
                else if (mime.startsWith("video/")) body = "🎬 ویدیو";
                else if (mime.startsWith("audio/")) body = "🎵 پیام صوتی";
                else body = "📎 فایل";
                String fn = m.optString("file_name", "");
                if (fn != null && !fn.isEmpty()) body += " · " + fn;
            } else if ("call".equals(kind)) body = "تماس";
            else body = "پیام جدید";
        }
        return body.length() > 140 ? body.substring(0, 140) : body;
    }

    private String httpGet(String address, String token) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(address).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setRequestProperty("X-Token", token);
        con.setRequestProperty("Accept", "application/json");
        try {
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
