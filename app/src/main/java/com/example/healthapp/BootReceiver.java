package com.example.healthapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences sp = context.getSharedPreferences("health_app_settings", Context.MODE_PRIVATE);
        if (sp.getBoolean("sit_reminder", true)) {
            NotificationHelper.scheduleSitReminder(context);
        }
        if (sp.getBoolean("backup_reminder", true)) {
            NotificationHelper.scheduleBackupReminder(context);
        }
    }
}