package ir.messenger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** ساخت اعلان بومی اندروید؛ هم پیامها و هم اعلان ماندگار سرویس. */
public final class Notifier {

    public static final String CHANNEL_MSG = "messenger_messages";
    public static final String CHANNEL_SILENT = "messenger_service";
    public static final int SERVICE_ID = 1001;

    private Notifier() { }

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel msg = new NotificationChannel(CHANNEL_MSG, "پیامها", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("اعلان پیامهای جدید");
        msg.enableVibration(true);
        msg.setVibrationPattern(new long[]{0, 180, 90, 180});
        msg.enableLights(true);
        nm.createNotificationChannel(msg);

        NotificationChannel silent = new NotificationChannel(CHANNEL_SILENT, "سرویس اعلان", NotificationManager.IMPORTANCE_LOW);
        silent.setDescription("برای دریافت اعلانها در پسزمینه");
        silent.enableVibration(false);
        silent.setSound(null, null);
        nm.createNotificationChannel(silent);
    }

    public static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** نمایش اعلان پیام. با زدن روی آن، گفتگوی مربوطه باز میشود. */
    public static void show(Context c, String title, String body, String chatId) {
        if (!canNotify(c)) return;
        ensureChannels(c);

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (chatId != null && !chatId.isEmpty()) open.putExtra("chat_id", chatId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, chatId == null ? 7 : chatId.hashCode(), open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_MSG)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title == null || title.isEmpty() ? "پیام جدید" : title)
                .setContentText(body == null ? "" : body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body));
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }

    /** اعلان ماندگار و بیصدا برای سرویس پسزمینه. */
    public static Notification serviceNotification(Context c) {
        ensureChannels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_SILENT)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("پیامرسان")
                .setContentText("برای دریافت اعلانها فعال است")
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }
}
