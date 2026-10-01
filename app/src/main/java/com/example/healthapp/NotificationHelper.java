package com.example.healthapp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class NotificationHelper {

    public static final String CHANNEL_ID = "health_app_default";

    public static final int ID_SIT = 1001;
    public static final int ID_SPORT = 1002;
    public static final int ID_BACKUP = 1003;

    private static final int REQ_SIT = 2001;
    private static final int REQ_BACKUP = 2003;

    public static final long SIT_INTERVAL = 30L * 60 * 1000;
    public static final long BACKUP_INTERVAL = 30L * 24 * 60 * 60 * 1000;

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "健康生活提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("久坐提醒、训练超时、数据备份提醒");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    public static void show(Context ctx, int id, String title, String body) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, intent, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
         .setContentTitle(title)
         .setContentText(body)
         .setStyle(new Notification.BigTextStyle().bigText(body))
         .setAutoCancel(true)
         .setContentIntent(pi);

        try { nm.notify(id, b.build()); } catch (Exception e) { e.printStackTrace(); }
    }

    public static void scheduleSitReminder(Context ctx) {
        long at = System.currentTimeMillis() + SIT_INTERVAL;
        setAlarm(ctx, ReminderReceiver.ACTION_SIT, REQ_SIT, at);
    }

    public static void cancelSitReminder(Context ctx) {
        cancelAlarm(ctx, ReminderReceiver.ACTION_SIT, REQ_SIT);
    }

    public static void scheduleBackupReminder(Context ctx) {
        long at = System.currentTimeMillis() + BACKUP_INTERVAL;
        setAlarm(ctx, ReminderReceiver.ACTION_BACKUP, REQ_BACKUP, at);
    }

    public static void cancelBackupReminder(Context ctx) {
        cancelAlarm(ctx, ReminderReceiver.ACTION_BACKUP, REQ_BACKUP);
    }

    private static void setAlarm(Context ctx, String action, int reqCode, long triggerAt) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, i, flags);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void cancelAlarm(Context ctx, String action, int reqCode) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, i, flags);
        am.cancel(pi);
    }
}