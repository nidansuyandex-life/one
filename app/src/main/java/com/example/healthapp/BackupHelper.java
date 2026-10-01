package com.example.healthapp;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BackupHelper {

    private static final String TAG = "BackupHelper";
    private static final int KEEP_DAYS = 7;

    private final Context context;

    public BackupHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    private File getBackupDir() {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "backups");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public void saveDaily(String json) {
        if (json == null || json.length() < 10) return;
        try {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            File dir = getBackupDir();
            File file = new File(dir, date + ".json");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();
            Log.d(TAG, "backup saved: " + file.getName() + " size=" + file.length());
            cleanupOld();
        } catch (Exception e) {
            Log.e(TAG, "saveDaily failed", e);
        }
    }

    private void cleanupOld() {
        try {
            File dir = getBackupDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - (long) KEEP_DAYS * 24 * 3600 * 1000;
            for (File f : files) {
                if (f.lastModified() < cutoff) f.delete();
            }
        } catch (Exception e) { Log.e(TAG, "cleanup failed", e); }
    }

    public String list() {
        JSONArray arr = new JSONArray();
        try {
            File dir = getBackupDir();
            File[] files = dir.listFiles();
            if (files != null) {
                List<File> list = new ArrayList<>(Arrays.asList(files));
                Collections.sort(list, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (File f : list) {
                    if (!f.getName().endsWith(".json")) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName().replace(".json", ""));
                    o.put("size", f.length());
                    o.put("time", f.lastModified());
                    arr.put(o);
                }
            }
        } catch (Exception e) { Log.e(TAG, "list failed", e); }
        return arr.toString();
    }

    public String read(String date) {
        try {
            File dir = getBackupDir();
            File f = new File(dir, date + ".json");
            if (!f.exists()) return "";
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            fis.close();
            if (n <= 0) return "";
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "read failed", e);
            return "";
        }
    }
}