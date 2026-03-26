package org.telegram.messenger.NagramX.forwarder;

import android.content.Context;
import android.os.Environment;
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
 * ForwarderHashDatabase — v2.0
 *
 * إصلاحات شاملة:
 * ✅ DB: يستخدم getDatabasePath — يمنع خطأ "تالفة"
 * ✅ تصدير/استيراد: Android/media/{package}/ForwarderPro/
 * ✅ منع التكرار يعمل — cache + DB
 * ✅ صيانة: vacuum, integrity, repair hash_type
 * ✅ إحصائيات مفصلة بتوزيع الأنواع
 */
public class ForwarderHashDatabase {

    private static final String TAG = "ForwarderHashDB";
    private static final String DB_NAME = "forwarder_hashes.db";
    private static final int DB_VERSION = 1;

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

    private static final int CACHE_MAX_SIZE = 18_000;
    private final LinkedHashMap<String, Long> localCache = new LinkedHashMap<String, Long>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    };
    private final ReentrantLock cacheLock = new ReentrantLock();

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
    private final String mediaDir;

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) {
            super(ctx, DB_NAME, null, DB_VERSION);
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
            try {
                db.execSQL("PRAGMA journal_mode=WAL");
                db.execSQL("PRAGMA synchronous=NORMAL");
                db.execSQL("PRAGMA cache_size=10000");
            } catch (Exception e) {
                Log.w("ForwarderHashDB", "PRAGMA: " + e.getMessage());
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    private ForwarderHashDatabase() {
        Context ctx = ApplicationLoader.applicationContext;
        dbHelper = new DbHelper(ctx);
        dbPath = ctx.getDatabasePath(DB_NAME).getAbsolutePath();

        String pkg = ctx.getPackageName();
        File mediaBase = new File(Environment.getExternalStorageDirectory(),
            "Android/media/" + pkg + "/ForwarderPro");
        mediaBase.mkdirs();
        mediaDir = mediaBase.getAbsolutePath();

        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            if (db != null && db.isOpen()) {
                Log.i(TAG, "✅ DB: " + dbPath);
            } else {
                corrupted.set(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ DB: " + e.getMessage());
            corrupted.set(true);
        }
    }

    public String getMediaDir() { return mediaDir; }
    public boolean isCorrupted() { return corrupted.get(); }
    public ExecutorService getBgWriter() { return bgWriter; }

    public int preloadToCache() {
        if (corrupted.get()) return 0;
        int count = 0;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT hash FROM hashes ORDER BY timestamp DESC LIMIT " + CACHE_MAX_SIZE, null)) {
                cacheLock.lock();
                try {
                    localCache.clear();
                    while (cursor.moveToNext()) {
                        String h = cursor.getString(0);
                        if (h != null) { localCache.put(h, 0L); count++; }
                    }
                } finally { cacheLock.unlock(); }
            }
        } catch (Exception e) {
            Log.w(TAG, "preload: " + e.getMessage());
        }
        return count;
    }

    public boolean isDuplicate(String hash) {
        if (hash == null || hash.length() <= 10 || corrupted.get()) return false;
        cacheLock.lock();
        try {
            if (localCache.get(hash) != null) return true;
        } finally { cacheLock.unlock(); }

        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT 1 FROM hashes WHERE hash=? LIMIT 1", new String[]{hash})) {
                boolean found = c.moveToFirst();
                if (found) {
                    cacheLock.lock();
                    try { localCache.put(hash, System.currentTimeMillis()); }
                    finally { cacheLock.unlock(); }
                }
                return found;
            }
        } catch (Exception e) { return false; }
    }

    public void addHash(String hash, String hashType) {
        if (hash == null || hash.length() <= 10 || corrupted.get()) return;
        cacheLock.lock();
        try { localCache.put(hash, System.currentTimeMillis()); }
        finally { cacheLock.unlock(); }

        writeLock.lock();
        try {
            pendingWrites.add(new String[]{hash, String.valueOf(System.currentTimeMillis() / 1000), hashType != null ? hashType : "unknown"});
            if (pendingWrites.size() >= BATCH_SIZE) {
                final List<String[]> batch = new ArrayList<>(pendingWrites);
                pendingWrites.clear();
                bgWriter.execute(() -> flushBatch(batch));
            }
        } finally { writeLock.unlock(); }
    }

    public void flushWrites() {
        List<String[]> batch;
        writeLock.lock();
        try {
            if (pendingWrites.isEmpty()) return;
            batch = new ArrayList<>(pendingWrites);
            pendingWrites.clear();
        } finally { writeLock.unlock(); }
        flushBatch(batch);
    }

    private void flushBatch(List<String[]> batch) {
        if (batch.isEmpty() || corrupted.get()) return;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                for (String[] row : batch) {
                    ContentValues cv = new ContentValues();
                    cv.put("hash", row[0]);
                    cv.put("timestamp", Long.parseLong(row[1]));
                    cv.put("hash_type", row[2]);
                    db.insertWithOnConflict("hashes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (Exception e) {
            Log.w(TAG, "flush: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("malformed")) corrupted.set(true);
        }
    }

    public ForwarderStats getStats() {
        ForwarderStats s = new ForwarderStats();
        s.dbPath = dbPath;
        s.exportPath = mediaDir;
        s.corrupted = corrupted.get();

        cacheLock.lock();
        try { s.cacheSize = localCache.size(); } finally { cacheLock.unlock(); }
        writeLock.lock();
        try { s.pendingWrites = pendingWrites.size(); } finally { writeLock.unlock(); }

        File f = new File(dbPath);
        s.dbSizeBytes = f.exists() ? f.length() : 0;
        File wal = new File(dbPath + "-wal");
        if (wal.exists()) s.dbSizeBytes += wal.length();

        if (!corrupted.get()) {
            try {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM hashes", null)) {
                    if (c.moveToFirst()) s.totalHashes = c.getLong(0);
                }
                try (Cursor c = db.rawQuery("SELECT MIN(timestamp), MAX(timestamp) FROM hashes", null)) {
                    if (c.moveToFirst()) {
                        long min = c.getLong(0), max = c.getLong(1);
                        if (min > 0) s.oldestHashDays = (System.currentTimeMillis() / 1000.0 - min) / 86400.0;
                        if (max > 0) s.newestHashDays = (System.currentTimeMillis() / 1000.0 - max) / 86400.0;
                    }
                }
                try (Cursor c = db.rawQuery("SELECT hash_type, COUNT(*) FROM hashes GROUP BY hash_type ORDER BY COUNT(*) DESC", null)) {
                    s.hashTypeBreakdown = new ArrayList<>();
                    while (c.moveToNext()) s.hashTypeBreakdown.add(new String[]{c.getString(0), String.valueOf(c.getLong(1))});
                }
                try (Cursor c = db.rawQuery("PRAGMA integrity_check", null)) {
                    if (c.moveToFirst()) s.integrityOk = "ok".equals(c.getString(0));
                }
                try (Cursor c = db.rawQuery("PRAGMA journal_mode", null)) {
                    if (c.moveToFirst()) s.journalMode = c.getString(0);
                }
            } catch (Exception e) { Log.w(TAG, "stats: " + e.getMessage()); }
        }
        return s;
    }

    public long clearAll() {
        cacheLock.lock();
        try { localCache.clear(); } finally { cacheLock.unlock(); }
        if (corrupted.get()) return 0;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long count = 0;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM hashes", null)) {
                if (c.moveToFirst()) count = c.getLong(0);
            }
            db.execSQL("DELETE FROM hashes");
            return count;
        } catch (Exception e) { return 0; }
    }

    public String exportToJson() {
        if (corrupted.get()) return null;
        try {
            File dir = new File(mediaDir);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "forwarder_hashes_" + System.currentTimeMillis() + ".json");

            SQLiteDatabase db = dbHelper.getReadableDatabase();
            long total = 0;
            try (Cursor cc = db.rawQuery("SELECT COUNT(*) FROM hashes", null)) {
                if (cc.moveToFirst()) total = cc.getLong(0);
            }

            try (Cursor cursor = db.rawQuery("SELECT hash, timestamp, hash_type FROM hashes", null);
                 FileWriter fw = new FileWriter(out)) {
                fw.write("{\"version\":\"NagramX-ForwarderPro\",\"export_time\":");
                fw.write(String.valueOf(System.currentTimeMillis() / 1000));
                fw.write(",\"total_hashes\":");
                fw.write(String.valueOf(total));
                fw.write(",\"hashes\":[");
                boolean first = true;
                while (cursor.moveToNext()) {
                    if (!first) fw.write(",");
                    fw.write("{\"hash\":\""); fw.write(esc(cursor.getString(0)));
                    fw.write("\",\"timestamp\":"); fw.write(String.valueOf(cursor.getLong(1)));
                    fw.write(",\"hash_type\":\""); fw.write(esc(cursor.getString(2)));
                    fw.write("\"}");
                    first = false;
                }
                fw.write("]}");
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "export: " + e.getMessage(), e);
            return null;
        }
    }

    public List<File> findImportFiles() {
        List<File> files = new ArrayList<>();
        scanDir(new File(mediaDir), files);
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dl != null) scanDir(dl, files);
        File old = new File(Environment.getExternalStorageDirectory(), "ForwarderPro_State");
        if (old.exists()) scanDir(old, files);
        return files;
    }

    private void scanDir(File dir, List<File> result) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] ff = dir.listFiles((d, n) -> n.endsWith(".json") && (n.contains("hashes") || n.contains("forwarder") || n.contains("export")));
        if (ff != null) for (File f : ff) result.add(f);
    }

    public int importFromJson(File inputFile) {
        if (corrupted.get() || inputFile == null || !inputFile.exists()) return 0;
        int added = 0;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            String json = sb.toString().trim();
            int hs = json.indexOf("\"hashes\"");
            if (hs == -1) return 0;
            int as = json.indexOf('[', hs);
            if (as == -1) return 0;
            int ae = json.lastIndexOf(']');
            if (ae <= as) return 0;
            String section = json.substring(as + 1, ae);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                int pos = 0;
                while (pos < section.length()) {
                    int os2 = section.indexOf('{', pos);
                    if (os2 == -1) break;
                    int oe = section.indexOf('}', os2);
                    if (oe == -1) break;
                    String obj = section.substring(os2, oe + 1);
                    pos = oe + 1;

                    String hash = xStr(obj, "hash");
                    String ht = xStr(obj, "hash_type");
                    long ts = xLong(obj, "timestamp");
                    if (hash == null || hash.length() <= 10) continue;
                    if (ht == null) ht = "imported";
                    if (ts == 0) ts = System.currentTimeMillis() / 1000;

                    ContentValues cv = new ContentValues();
                    cv.put("hash", hash); cv.put("timestamp", ts); cv.put("hash_type", ht);
                    long r = db.insertWithOnConflict("hashes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    if (r != -1) {
                        added++;
                        cacheLock.lock();
                        try { localCache.put(hash, ts); } finally { cacheLock.unlock(); }
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (Exception e) { Log.e(TAG, "import: " + e.getMessage()); }
        return added;
    }

    public RepairResult repairDatabase() {
        RepairResult r = new RepairResult();
        if (corrupted.get()) { r.error = "DB corrupted"; return r; }
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            try (Cursor c = db.rawQuery("SELECT hash FROM hashes WHERE hash_type='unknown' OR hash_type IS NULL", null)) {
                List<String[]> fixes = new ArrayList<>();
                while (c.moveToNext()) {
                    String h = c.getString(0);
                    String t = inferHashType(h);
                    if (!"unknown".equals(t)) fixes.add(new String[]{t, h});
                }
                if (!fixes.isEmpty()) {
                    db.beginTransaction();
                    try {
                        for (String[] fix : fixes) db.execSQL("UPDATE hashes SET hash_type=? WHERE hash=?", fix);
                        db.setTransactionSuccessful();
                        r.fixedTypes = fixes.size();
                    } finally { db.endTransaction(); }
                }
            }
            try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_hash'", null)) {
                if (c.moveToFirst()) { db.execSQL("DROP INDEX IF EXISTS idx_hash"); r.removedDuplicateIndex = true; }
            }
            File bf = new File(dbPath);
            r.sizeBefore = bf.exists() ? bf.length() : 0;
            try { db.execSQL("VACUUM"); r.vacuumed = true; } catch (Exception ignored) {}
            File af = new File(dbPath);
            r.sizeAfter = af.exists() ? af.length() : 0;
            r.success = true;
        } catch (Exception e) { r.error = e.getMessage(); }
        return r;
    }

    public int cleanupLegacyHashes() {
        if (corrupted.get()) return 0;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            int deleted = 0;
            for (String type : new String[]{"legacy_fp", "legacy_numeric_id", "unknown"}) {
                try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM hashes WHERE hash_type=?", new String[]{type})) {
                    if (c.moveToFirst() && c.getInt(0) > 0) {
                        db.execSQL("DELETE FROM hashes WHERE hash_type=?", new String[]{type});
                        deleted += c.getInt(0);
                    }
                }
            }
            db.execSQL("DELETE FROM hashes WHERE hash_type IS NULL");
            if (deleted > 0) try { db.execSQL("VACUUM"); } catch (Exception ignored) {}
            return deleted;
        } catch (Exception e) { return 0; }
    }

    public static String inferHashType(String hash) {
        if (hash == null) return "unknown";
        if (hash.startsWith("DOC_REF|")) return "document_ref";
        if (hash.startsWith("PHOTO_REF|")) return "photo_ref";
        if (hash.startsWith("DOC_LEGACY|")) return "document_legacy";
        if (hash.startsWith("PHOTO_LEGACY|")) return "photo_legacy";
        if (hash.startsWith("DOC_OLD|")) return "document_old";
        if (hash.startsWith("PHOTO_OLD|")) return "photo_old";
        if (hash.startsWith("UID:")) return "document_unique";
        if (hash.startsWith("STRICT_FB|")) return "strict_fb";
        if (hash.startsWith("STORY|")) return "story";
        if (hash.startsWith("MSGID|")) return "msgid_fallback";
        if (hash.startsWith("FAST_UNIQ|")) return "fast_unique";
        if (hash.startsWith("FALLBACK_DOC|")) return "fallback_doc";
        if (hash.startsWith("FALLBACK_PHOTO|")) return "fallback_photo";
        if (hash.startsWith("FALLBACK_GEN|")) return "fallback_gen";
        if (hash.startsWith("FALLBACK_TEXT|")) return "fallback_text";
        if (hash.startsWith("FP:")) return "legacy_fp";
        if (hash.length() == 32 && hash.matches("[0-9a-f]+")) return "content_doc";
        return "unknown";
    }

    public void close() {
        try { flushWrites(); } catch (Exception ignored) {}
        bgWriter.shutdown();
        try { bgWriter.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { dbHelper.close(); } catch (Exception ignored) {}
        synchronized (INSTANCE_LOCK) { instance = null; }
    }

    private static String xStr(String j, String k) {
        String s = "\"" + k + "\":\"";
        int i = j.indexOf(s);
        if (i == -1) return null;
        i += s.length();
        int e = j.indexOf('"', i);
        return e == -1 ? null : j.substring(i, e);
    }

    private static long xLong(String j, String k) {
        String s = "\"" + k + "\":";
        int i = j.indexOf(s);
        if (i == -1) return 0;
        i += s.length();
        StringBuilder n = new StringBuilder();
        for (; i < j.length(); i++) {
            char c = j.charAt(i);
            if (c >= '0' && c <= '9') n.append(c);
            else if (n.length() > 0) break;
        }
        try { return Long.parseLong(n.toString()); } catch (Exception e) { return 0; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static class ForwarderStats {
        public long totalHashes, cacheSize, dbSizeBytes;
        public double oldestHashDays, newestHashDays;
        public int pendingWrites;
        public boolean corrupted, integrityOk = true;
        public String dbPath, exportPath, journalMode;
        public List<String[]> hashTypeBreakdown;

        public String formatSize() {
            if (dbSizeBytes >= 1048576) return String.format("%.1f MB", dbSizeBytes / 1048576.0);
            if (dbSizeBytes >= 1024) return String.format("%.1f KB", dbSizeBytes / 1024.0);
            return dbSizeBytes + " B";
        }
    }

    public static class RepairResult {
        public boolean success, removedDuplicateIndex, vacuumed;
        public int fixedTypes;
        public long sizeBefore, sizeAfter;
        public String error;

        public String formatSaved() {
            long saved = Math.max(0, sizeBefore - sizeAfter);
            if (saved >= 1048576) return String.format("%.1f MB", saved / 1048576.0);
            if (saved >= 1024) return String.format("%.1f KB", saved / 1024.0);
            return saved + " B";
        }
    }
}
