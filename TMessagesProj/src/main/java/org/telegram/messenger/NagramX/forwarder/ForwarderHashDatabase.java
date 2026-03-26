package org.telegram.messenger.NagramX.forwarder;

import android.content.Context;
import android.util.Log;

import org.telegram.messenger.ApplicationLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.Cursor;
import android.content.ContentValues;

/**
 * ForwarderHashDatabase — Singleton
 *
 * قاعدة بيانات مستقلة لتخزين هاشات الوسائط المُحوَّلة.
 * الغرض: منع تكرار التحويل عبر جلسات متعددة.
 *
 * الملف: getFilesDir()/NagramX/forwarder_hashes.db
 *
 * الإصلاحات المطبقة:
 * ✅ Singleton pattern — instance واحد فقط
 * ✅ Thread-safe LRU — get() بدل containsKey()
 * ✅ try-with-resources للـ Cursors
 * ✅ importFromJson — استيراد متوافق مع plugin Python
 * ✅ JSON escaping في التصدير
 * ✅ preloadToCache — إصلاح iterator.remove()
 */
public class ForwarderHashDatabase {

    private static final String TAG = "ForwarderHashDB";
    private static final String DB_NAME = "forwarder_hashes.db";
    private static final int DB_VERSION = 1;

    // ==========================================
    // Singleton
    // ==========================================
    private static volatile ForwarderHashDatabase instance;
    private static final Object INSTANCE_LOCK = new Object();

    public static ForwarderHashDatabase getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new ForwarderHashDatabase();
                }
            }
        }
        return instance;
    }

    // ==========================================
    // LRU Cache — O(1) للفحص بدون SQL
    // ==========================================
    private static final int CACHE_MAX_SIZE = 18_000;
    private final LinkedHashMap<String, Long> localCache = new LinkedHashMap<String, Long>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    };
    private final ReentrantLock cacheLock = new ReentrantLock();

    // ==========================================
    // Batch write queue
    // ==========================================
    private static final int BATCH_SIZE = 50;
    private final List<String[]> pendingWrites = new ArrayList<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ExecutorService bgWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ForwarderHashDB-Writer");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean corrupted = new AtomicBoolean(false);
    private DbHelper dbHelper;
    private final String dbPath;

    // ==========================================
    // SQLiteOpenHelper
    // ==========================================
    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx, String path) {
            super(ctx, path, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS hashes (" +
                "hash TEXT PRIMARY KEY, " +
                "timestamp INTEGER NOT NULL, " +
                "hash_type TEXT DEFAULT 'unknown'" +
                ")"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON hashes(timestamp)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_hash_type ON hashes(hash_type)");
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            db.execSQL("PRAGMA journal_mode=WAL");
            db.execSQL("PRAGMA synchronous=NORMAL");
            db.execSQL("PRAGMA cache_size=10000");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // مستقبلاً: migrations
        }
    }

    // ==========================================
    // Constructor — private لأنه Singleton
    // ==========================================
    private ForwarderHashDatabase() {
        Context ctx = ApplicationLoader.applicationContext;
        File dir = new File(ctx.getFilesDir(), "NagramX");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        dbPath = new File(dir, DB_NAME).getAbsolutePath();
        dbHelper = new DbHelper(ctx, dbPath);

        try {
            dbHelper.getWritableDatabase();
            Log.i(TAG, "DB جاهز: " + dbPath);
        } catch (Exception e) {
            Log.e(TAG, "فشل فتح DB: " + e.getMessage());
            corrupted.set(true);
        }
    }

    // ==========================================
    // preloadToCache — تحميل كل الهاشات للذاكرة
    // ✅ إصلاح: استخدام clear() بدل iterator.remove() المتكرر
    // ==========================================
    public int preloadToCache() {
        if (corrupted.get()) return 0;
        int count = 0;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT hash FROM hashes", null)) {
                List<String> allHashes = new ArrayList<>();
                while (cursor.moveToNext()) {
                    String hash = cursor.getString(0);
                    if (hash != null) {
                        allHashes.add(hash);
                    }
                }
                cacheLock.lock();
                try {
                    localCache.clear();
                    // لو عدد الهاشات أكبر من الحد، نأخذ فقط الأحدث
                    int start = Math.max(0, allHashes.size() - CACHE_MAX_SIZE);
                    for (int i = start; i < allHashes.size(); i++) {
                        localCache.put(allHashes.get(i), 0L);
                        count++;
                    }
                } finally {
                    cacheLock.unlock();
                }
            }
            Log.i(TAG, "preload: " + count + " هاش في الذاكرة");
        } catch (Exception e) {
            Log.w(TAG, "preload فشل: " + e.getMessage());
        }
        return count;
    }

    // ==========================================
    // isDuplicate — الفحص الرئيسي
    // ✅ إصلاح: get() بدل containsKey() لتجنب مشاكل access-order
    // ==========================================
    public boolean isDuplicate(String hash) {
        if (hash == null || hash.length() <= 10) return false;
        if (corrupted.get()) return false;

        // فحص الـ cache أولاً
        cacheLock.lock();
        try {
            if (localCache.get(hash) != null) return true;
        } finally {
            cacheLock.unlock();
        }

        // fallback إلى SQL
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM hashes WHERE hash=? LIMIT 1",
                new String[]{hash}
            )) {
                boolean found = cursor.moveToFirst();
                if (found) {
                    cacheLock.lock();
                    try {
                        localCache.put(hash, System.currentTimeMillis());
                    } finally {
                        cacheLock.unlock();
                    }
                }
                return found;
            }
        } catch (Exception e) {
            Log.w(TAG, "isDuplicate خطأ: " + e.getMessage());
            return false;
        }
    }

    // ==========================================
    // addHash — إضافة هاش (batch للأداء)
    // ==========================================
    public void addHash(String hash, String hashType) {
        if (hash == null || hash.length() <= 10) return;
        if (corrupted.get()) return;

        cacheLock.lock();
        try {
            localCache.put(hash, System.currentTimeMillis());
        } finally {
            cacheLock.unlock();
        }

        writeLock.lock();
        try {
            pendingWrites.add(new String[]{hash, String.valueOf(System.currentTimeMillis() / 1000), hashType});
            if (pendingWrites.size() >= BATCH_SIZE) {
                final List<String[]> batch = new ArrayList<>(pendingWrites);
                pendingWrites.clear();
                bgWriter.execute(() -> flushBatch(batch));
            }
        } finally {
            writeLock.unlock();
        }
    }

    // ==========================================
    // flushWrites — تفريغ كل الكتابات المعلقة
    // ==========================================
    public void flushWrites() {
        List<String[]> batch;
        writeLock.lock();
        try {
            if (pendingWrites.isEmpty()) return;
            batch = new ArrayList<>(pendingWrites);
            pendingWrites.clear();
        } finally {
            writeLock.unlock();
        }
        flushBatch(batch);
    }

    private void flushBatch(List<String[]> batch) {
        if (batch.isEmpty()) return;
        if (corrupted.get()) return;
        SQLiteDatabase db = null;
        try {
            db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                for (String[] row : batch) {
                    ContentValues cv = new ContentValues();
                    cv.put("hash", row[0]);
                    cv.put("timestamp", Long.parseLong(row[1]));
                    cv.put("hash_type", row[2] != null ? row[2] : "unknown");
                    db.insertWithOnConflict("hashes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            Log.w(TAG, "flushBatch خطأ: " + e.getMessage());
            if (e.getMessage() != null && (
                e.getMessage().contains("disk image is malformed") ||
                e.getMessage().contains("file is not a database")
            )) {
                corrupted.set(true);
                Log.e(TAG, "DB تالف — تم إيقاف الكتابة");
            }
        }
    }

    // ==========================================
    // getStats — إحصائيات
    // ✅ إصلاح: try-with-resources لكل Cursor
    // ==========================================
    public ForwarderStats getStats() {
        ForwarderStats stats = new ForwarderStats();
        stats.dbPath = dbPath;
        stats.corrupted = corrupted.get();

        cacheLock.lock();
        try {
            stats.cacheSize = localCache.size();
        } finally {
            cacheLock.unlock();
        }

        writeLock.lock();
        try {
            stats.pendingWrites = pendingWrites.size();
        } finally {
            writeLock.unlock();
        }

        File f = new File(dbPath);
        stats.dbSizeBytes = f.exists() ? f.length() : 0;
        File wal = new File(dbPath + "-wal");
        if (wal.exists()) stats.dbSizeBytes += wal.length();

        if (!corrupted.get()) {
            try {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                try (Cursor c1 = db.rawQuery("SELECT COUNT(*) FROM hashes", null)) {
                    if (c1.moveToFirst()) stats.totalHashes = c1.getLong(0);
                }
                try (Cursor c2 = db.rawQuery("SELECT MIN(timestamp), MAX(timestamp) FROM hashes", null)) {
                    if (c2.moveToFirst()) {
                        long minTs = c2.getLong(0);
                        if (minTs > 0) {
                            stats.oldestHashDays = (System.currentTimeMillis() / 1000 - minTs) / 86400.0;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getStats خطأ: " + e.getMessage());
            }
        }
        return stats;
    }

    // ==========================================
    // clearAll — مسح كل الهاشات
    // ==========================================
    public long clearAll() {
        cacheLock.lock();
        try {
            localCache.clear();
        } finally {
            cacheLock.unlock();
        }
        if (corrupted.get()) return 0;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long count = 0;
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM hashes", null)) {
                if (cursor.moveToFirst()) count = cursor.getLong(0);
            }
            db.execSQL("DELETE FROM hashes");
            Log.i(TAG, "تم مسح " + count + " هاش");
            return count;
        } catch (Exception e) {
            Log.e(TAG, "clearAll خطأ: " + e.getMessage());
            return 0;
        }
    }

    // ==========================================
    // exportToJson — تصدير لملف JSON
    // ✅ إصلاح: JSON escaping
    // ==========================================
    public boolean exportToJson(File outputFile) {
        if (corrupted.get()) return false;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT hash, timestamp, hash_type FROM hashes", null)) {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"version\":\"NagramX\",\"export_time\":")
                  .append(System.currentTimeMillis() / 1000)
                  .append(",\"hashes\":[");
                boolean first = true;
                while (cursor.moveToNext()) {
                    if (!first) sb.append(",");
                    sb.append("{\"hash\":\"").append(escapeJson(cursor.getString(0)))
                      .append("\",\"timestamp\":").append(cursor.getLong(1))
                      .append(",\"hash_type\":\"").append(escapeJson(cursor.getString(2))).append("\"}");
                    first = false;
                }
                sb.append("]}");
                try (FileWriter fw = new FileWriter(outputFile)) {
                    fw.write(sb.toString());
                }
            }
            Log.i(TAG, "تم التصدير: " + outputFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "exportToJson خطأ: " + e.getMessage());
            return false;
        }
    }

    // ==========================================
    // importFromJson — استيراد من ملف JSON
    // ✅ جديد: متوافق مع صيغة plugin Python
    // ==========================================
    public int importFromJson(File inputFile) {
        if (corrupted.get()) return 0;
        if (inputFile == null || !inputFile.exists()) return 0;

        int added = 0;
        try {
            // قراءة الملف
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            String json = sb.toString().trim();

            // استخراج الهاشات — parser بسيط بدون مكتبة خارجية
            // نبحث عن كل {"hash":"...","timestamp":...,"hash_type":"..."} داخل "hashes":[...]
            int hashesStart = json.indexOf("\"hashes\"");
            if (hashesStart == -1) return 0;

            int arrayStart = json.indexOf('[', hashesStart);
            if (arrayStart == -1) return 0;

            int arrayEnd = json.lastIndexOf(']');
            if (arrayEnd == -1 || arrayEnd <= arrayStart) return 0;

            String hashesSection = json.substring(arrayStart + 1, arrayEnd);

            // استخراج كل object
            int pos = 0;
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                while (pos < hashesSection.length()) {
                    int objStart = hashesSection.indexOf('{', pos);
                    if (objStart == -1) break;
                    int objEnd = hashesSection.indexOf('}', objStart);
                    if (objEnd == -1) break;

                    String obj = hashesSection.substring(objStart, objEnd + 1);
                    pos = objEnd + 1;

                    // استخراج القيم
                    String hash = extractJsonString(obj, "hash");
                    String hashType = extractJsonString(obj, "hash_type");
                    long timestamp = extractJsonLong(obj, "timestamp");

                    if (hash == null || hash.length() <= 10) continue;
                    if (hashType == null) hashType = "imported";
                    if (timestamp == 0) timestamp = System.currentTimeMillis() / 1000;

                    // فحص إذا موجود
                    boolean exists = false;
                    try (Cursor c = db.rawQuery("SELECT 1 FROM hashes WHERE hash=? LIMIT 1", new String[]{hash})) {
                        exists = c.moveToFirst();
                    }

                    if (!exists) {
                        ContentValues cv = new ContentValues();
                        cv.put("hash", hash);
                        cv.put("timestamp", timestamp);
                        cv.put("hash_type", hashType);
                        db.insertWithOnConflict("hashes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        added++;

                        // أضف للـ cache
                        cacheLock.lock();
                        try {
                            localCache.put(hash, timestamp);
                        } finally {
                            cacheLock.unlock();
                        }
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            Log.i(TAG, "تم استيراد " + added + " هاش من " + inputFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "importFromJson خطأ: " + e.getMessage());
        }
        return added;
    }

    // ==========================================
    // JSON Helpers — بسيط بدون مكتبة خارجية
    // ==========================================
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    private static long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return 0;
        start += search.length();
        StringBuilder num = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9') num.append(c);
            else if (num.length() > 0) break;
        }
        try {
            return Long.parseLong(num.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==========================================
    // getBgWriter + close
    // ==========================================
    public ExecutorService getBgWriter() {
        return bgWriter;
    }

    public void close() {
        bgWriter.execute(this::flushWrites);
        bgWriter.shutdown();
        try {
            bgWriter.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        try {
            dbHelper.close();
        } catch (Exception ignored) {}
        Log.i(TAG, "DB أُغلق بأمان");
        // Reset singleton عند الإغلاق
        synchronized (INSTANCE_LOCK) {
            instance = null;
        }
    }

    // ==========================================
    // Data class
    // ==========================================
    public static class ForwarderStats {
        public long totalHashes;
        public long cacheSize;
        public long dbSizeBytes;
        public double oldestHashDays;
        public int pendingWrites;
        public boolean corrupted;
        public String dbPath;

        public String formatSize() {
            if (dbSizeBytes >= 1024 * 1024) return String.format("%.1f MB", dbSizeBytes / (1024.0 * 1024));
            if (dbSizeBytes >= 1024) return String.format("%.1f KB", dbSizeBytes / 1024.0);
            return dbSizeBytes + " B";
        }
    }
}
