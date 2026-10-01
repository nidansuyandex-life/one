package com.example.healthapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_SIT = "com.example.healthapp.SIT_REMINDER";
    public static final String ACTION_BACKUP = "com.example.healthapp.BACKUP_REMINDER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        SharedPreferences sp = context.getSharedPreferences("health_app_settings", Context.MODE_PRIVATE);

        if (ACTION_SIT.equals(action)) {
            boolean on = sp.getBoolean("sit_reminder", true);
            if (!on) return;
            NotificationHelper.show(context, NotificationHelper.ID_SIT,
                    "该站起来走动了",
                    "起身 → 走动2–3分钟 → 下巴轻收5次 → 肩胛向后下轻收5–8次");
            NotificationHelper.scheduleSitReminder(context);

        } else if (ACTION_BACKUP.equals(action)) {
            boolean on = sp.getBoolean("backup_reminder", true);
            if (!on) return;
            NotificationHelper.show(context, NotificationHelper.ID_BACKUP,
                    "数据备份提醒",
                    "已超过 30 天未备份数据，打开 App → 设置 → 数据备份，导出 JSON");
            NotificationHelper.scheduleBackupReminder(context);
        }
    }
}