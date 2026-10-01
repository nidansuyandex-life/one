package com.example.healthapp;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
    private static final String PUBLIC_FOLDER = "健康生活备份";

    private final Context context;

    public BackupHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    /* ==================== 每日自动备份（App 私有目录） ==================== */

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
            Log.d(TAG, "daily backup saved: " + file.getName() + " size=" + file.length());
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

    /* ==================== 手动导出（公共「下载」目录） ==================== */

    /**
     * 手动导出到公共目录，用户可在系统「文件」App 里看到。
     * @return 显示给用户看的路径，失败返回空字符串
     */
    public String saveToDownloads(String json, String filename) {
        if (json == null || json.isEmpty()) return "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(json, filename);
        } else {
            return saveViaFileSystem(json, filename);
        }
    }

    /** Android 10+：通过 MediaStore 写入公共 Downloads，无需权限 */
    private String saveViaMediaStore(String json, String filename) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_FOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "MediaStore insert returned null");
                return "";
            }

            OutputStream os = context.getContentResolver().openOutputStream(uri);
            if (os == null) {
                Log.e(TAG, "openOutputStream returned null");
                return "";
            }
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);

            Log.d(TAG, "saved via MediaStore: " + uri);
            return "下载/" + PUBLIC_FOLDER + "/" + filename;
        } catch (Exception e) {
            Log.e(TAG, "saveViaMediaStore failed", e);
            return "";
        }
    }

    /** Android 9 及以下：直接写文件系统（需要 WRITE_EXTERNAL_STORAGE 权限） */
    private String saveViaFileSystem(String json, String filename) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_FOLDER);
            if (!dir.exists()) dir.mkdirs();

            File f = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();

            Log.d(TAG, "saved via FileSystem: " + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "saveViaFileSystem failed", e);
            return "";
        }
    }
}