package org.telegram.messenger.NagramX.forwarder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ForwarderEngine {

    private static final String TAG = "ForwarderEngine";
    private static final int CHUNK_MAX = 95;
    private static final int HISTORY_LIMIT = 100;
    private static final int HISTORY_TIMEOUT_MS = 45_000;
    private static final int SEND_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRIES = 5;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final String STATE_PREFS = "ForwarderPro_State";

    public static class ForwardConfig {
        public long sourceId, targetId;
        public int account = UserConfig.selectedAccount;
        public int chunkSize = 50;
        public float delaySeconds = 3f;
        public boolean largeBatchEnabled = false;
        public int largeBatchSize = 2000;
        public float largeBatchDelay = 60f;
        public boolean dropAuthor = false, dropCaption = false;
        public boolean mediaOnly = false, photoOnly = false, videoOnly = false, audioOnly = false, gifOnly = false;
        public long fromDateTs = 0, toDateTs = 0;
        public boolean skipDuplicates = true, strictDetection = true;
        public int maxMessages = 30_000;
        public boolean reverseOrder = false;
        public int resumeFromIndex = 0; // ✅ للاستمرار من نقطة محددة
    }

    public interface ProgressCallback {
        void onProgress(int sent, int total, String statusMsg);
        void onComplete(int totalSent, int totalSkipped, long durationMs);
        void onError(String errorMsg);
        void onRestricted(String chatName);
    }

    // ==========================================
    // ✅ SavedState — حالة التحويل المحفوظة
    // ==========================================
    public static class SavedState {
        public long sourceId, targetId;
        public int sentCount, totalCollected;
        public long timestamp; // متى حُفظت
        public int lastSentIndex; // آخر index مُرسَل بالقائمة

        public String getAge() {
            long mins = (System.currentTimeMillis() - timestamp) / 60_000;
            if (mins < 1) return "الآن";
            if (mins < 60) return mins + " دقيقة";
            long hrs = mins / 60;
            if (hrs < 24) return hrs + " ساعة";
            return (hrs / 24) + " يوم";
        }
    }

    /**
     * يفحص لو فيه حالة محفوظة لهالمصدر والهدف
     */
    public static SavedState getSavedState(long sourceId, long targetId) {
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        String key = sourceId + "_" + targetId;
        long ts = p.getLong(key + "_ts", 0);
        if (ts == 0) return null;

        // لو أقدم من 7 أيام — نحذفها
        if (System.currentTimeMillis() - ts > 7 * 24 * 3600_000L) {
            clearState(sourceId, targetId);
            return null;
        }

        SavedState s = new SavedState();
        s.sourceId = sourceId;
        s.targetId = targetId;
        s.sentCount = p.getInt(key + "_sent", 0);
        s.totalCollected = p.getInt(key + "_total", 0);
        s.lastSentIndex = p.getInt(key + "_idx", 0);
        s.timestamp = ts;
        return s;
    }

    /**
     * يحفظ الحالة الحالية
     */
    private static void saveState(long sourceId, long targetId, int sentCount, int totalCollected, int lastSentIndex) {
        String key = sourceId + "_" + targetId;
        ApplicationLoader.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key + "_ts", System.currentTimeMillis())
            .putInt(key + "_sent", sentCount)
            .putInt(key + "_total", totalCollected)
            .putInt(key + "_idx", lastSentIndex)
            .apply();
    }

    /**
     * يمسح الحالة (بعد الاكتمال أو لما المستخدم يختار "من جديد")
     */
    public static void clearState(long sourceId, long targetId) {
        String key = sourceId + "_" + targetId;
        ApplicationLoader.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key + "_ts").remove(key + "_sent").remove(key + "_total").remove(key + "_idx")
            .apply();
    }

    // ==========================================
    // Instance
    // ==========================================
    private final ForwarderHashDatabase hashDb;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger totalSent = new AtomicInteger(0);
    private final AtomicInteger totalSkipped = new AtomicInteger(0);
    private volatile ExecutorService worker;

    public ForwarderEngine(ForwarderHashDatabase hashDb) { this.hashDb = hashDb; }

    public void start(ForwardConfig config, ProgressCallback cb) {
        if (worker != null && !worker.isTerminated()) { cb.onError("عملية جارية"); return; }
        stopped.set(false); paused.set(false); totalSent.set(0); totalSkipped.set(0);
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Forwarder"); t.setDaemon(true); return t; });
        worker.execute(() -> run(config, cb));
    }

    public void pause() { paused.set(true); }
    public void resume() { paused.set(false); }
    public void stop() { stopped.set(true); paused.set(false); }
    public boolean isRunning() { return worker != null && !worker.isTerminated(); }

    private void run(ForwardConfig cfg, ProgressCallback cb) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            MessagesController mc = MessagesController.getInstance(cfg.account);
            if (cfg.sourceId < 0) {
                TLRPC.Chat chat = mc.getChat(-cfg.sourceId);
                if (chat != null && chat.noforwards) { cb.onRestricted(chat.title != null ? chat.title : ""); return; }
            }
            if (cfg.skipDuplicates) hashDb.preloadToCache();

            cb.onProgress(0, 0, "جاري جمع الرسائل...");
            List<CM> msgs = collect(cfg, cb);
            if (msgs.isEmpty()) { cb.onError("لا توجد رسائل للتحويل"); return; }
            if (stopped.get()) return;

            // ✅ لو فيه استمرار — نقص الرسائل اللي انحولت
            int startIndex = cfg.resumeFromIndex;
            if (startIndex > 0 && startIndex < msgs.size()) {
                msgs = new ArrayList<>(msgs.subList(startIndex, msgs.size()));
                cb.onProgress(0, msgs.size(), "⏩ استمرار من رسالة " + (startIndex + 1));
            }

            int total = msgs.size();
            send(msgs, cfg, total, cb);

            // ✅ لو اكتمل — نمسح الحالة
            if (!stopped.get()) {
                clearState(cfg.sourceId, cfg.targetId);
            }

            cb.onComplete(totalSent.get(), totalSkipped.get(), SystemClock.elapsedRealtime() - t0);
        } catch (Exception e) {
            cb.onError("خطأ: " + e.getMessage());
        } finally {
            hashDb.flushWrites();
            if (worker != null) worker.shutdown();
        }
    }

    static class CM { int id; String hash; CM(int i, String h) { id = i; hash = h; } }

    private List<CM> collect(ForwardConfig cfg, ProgressCallback cb) throws InterruptedException {
        List<CM> res = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int off = 0; boolean more = true; int retries = 0, dups = 0, empty = 0;
        MessagesController mc = MessagesController.getInstance(cfg.account);

        while (more && !stopped.get() && res.size() < cfg.maxMessages) {
            while (paused.get() && !stopped.get()) Thread.sleep(300);
            if (stopped.get()) break;

            final CountDownLatch l = new CountDownLatch(1);
            final List<TLRPC.Message> batch = new ArrayList<>();
            final boolean[] hasMore = {false};

            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = mc.getInputPeer(cfg.sourceId);
            req.offset_id = off; req.limit = HISTORY_LIMIT;

            ConnectionsManager.getInstance(cfg.account).sendRequest(req, (r, e) -> {
                try {
                    if (e != null || r == null) { l.countDown(); return; }
                    if (r instanceof TLRPC.messages_Messages) {
                        for (TLObject o : ((TLRPC.messages_Messages) r).messages)
                            if (o instanceof TLRPC.Message) batch.add((TLRPC.Message) o);
                        hasMore[0] = batch.size() >= HISTORY_LIMIT;
                    }
                } finally { l.countDown(); }
            });

            if (!l.await(HISTORY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { if (++retries >= MAX_RETRIES) break; continue; }
            retries = 0;
            if (batch.isEmpty()) break;

            int added = 0; int minId = Integer.MAX_VALUE;
            for (TLRPC.Message msg : batch) {
                if (msg.id < minId) minId = msg.id;
                if (!passFilter(msg, cfg)) continue;
                if (!passDate(msg, cfg)) continue;

                String hash = null;
                if (cfg.skipDuplicates) {
                    hash = computeHash(msg);
                    if (hash != null && hashDb.isDuplicate(hash)) { dups++; totalSkipped.incrementAndGet(); continue; }
                }
                if (!seen.contains(msg.id)) { seen.add(msg.id); res.add(new CM(msg.id, hash)); added++; }
            }

            if (added == 0) { if (++empty >= 3) break; } else empty = 0;
            off = minId == Integer.MAX_VALUE ? 0 : Math.max(0, minId - 1);
            more = hasMore[0] && off > 0;
            if (cfg.maxMessages > 0 && res.size() >= cfg.maxMessages) more = false;
            cb.onProgress(0, res.size(), "جمع: " + res.size() + (dups > 0 ? " (تخطي " + dups + " مكرر)" : ""));
        }
        if (!cfg.reverseOrder) java.util.Collections.reverse(res);
        return res;
    }

    private void send(List<CM> msgs, ForwardConfig cfg, int total, ProgressCallback cb) throws InterruptedException {
        int chunk = Math.min(cfg.chunkSize, CHUNK_MAX);
        int fails = 0, lbCount = 0;
        MessagesController mc = MessagesController.getInstance(cfg.account);

        int i = 0;
        while (i < msgs.size() && !stopped.get()) {
            while (paused.get() && !stopped.get()) Thread.sleep(300);
            if (stopped.get()) break;

            int end = Math.min(i + chunk, msgs.size());
            List<CM> ch = msgs.subList(i, end);
            List<Integer> ids = new ArrayList<>();
            for (CM cm : ch) ids.add(cm.id);

            if (sendChunk(ids, cfg, mc, cb)) {
                if (cfg.skipDuplicates) {
                    for (CM cm : ch) if (cm.hash != null) hashDb.addHash(cm.hash, ForwarderHashDatabase.inferHashType(cm.hash));
                    hashDb.flushWrites();
                }
                int n = ch.size();
                totalSent.addAndGet(n); lbCount += n; fails = 0;
                cb.onProgress(totalSent.get(), total, "✅ " + totalSent.get() + " / " + total);

                // ✅ حفظ الحالة كل chunk
                saveState(cfg.sourceId, cfg.targetId, totalSent.get(), total, cfg.resumeFromIndex + i + n);

                if (cfg.largeBatchEnabled && cfg.largeBatchSize > 0 && lbCount >= cfg.largeBatchSize && i + n < msgs.size()) {
                    lbCount = 0;
                    cb.onProgress(totalSent.get(), total, "⏸ توقف وجبة — " + (int) cfg.largeBatchDelay + "s");
                    safeSleep((long)(cfg.largeBatchDelay * 1000));
                }
            } else {
                if (++fails >= MAX_CONSECUTIVE_FAILURES) { cb.onError("فشل متكرر"); break; }
                continue;
            }
            i = end;
            if (i < msgs.size() && cfg.delaySeconds > 0) safeSleep((long)(cfg.delaySeconds * 1000));
        }
    }

    private boolean sendChunk(List<Integer> ids, ForwardConfig cfg, MessagesController mc, ProgressCallback cb) throws InterruptedException {
        for (int a = 0; a < MAX_RETRIES; a++) {
            if (stopped.get()) return false;
            final CountDownLatch l = new CountDownLatch(1);
            final boolean[] ok = {false};
            final String[] err = {null};

            TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
            req.from_peer = mc.getInputPeer(cfg.sourceId);
            req.to_peer = mc.getInputPeer(cfg.targetId);
            req.flags = 0;
            if (cfg.dropAuthor) { req.flags |= (1 << 11); req.drop_author = true; }
            if (cfg.dropCaption) { req.flags |= (1 << 16); req.drop_media_captions = true; }
            req.id = new ArrayList<>(ids);
            req.random_id = new ArrayList<>();
            for (int x : ids) req.random_id.add(Utilities.random.nextLong());

            ConnectionsManager.getInstance(cfg.account).sendRequest(req, (r, e) -> {
                try {
                    if (e != null) {
                        err[0] = e.text != null ? e.text : "UNKNOWN";
                        if (err[0].contains("CHAT_FORWARDS_RESTRICTED")) { stopped.set(true); cb.onRestricted(""); }
                    } else ok[0] = true;
                } finally { l.countDown(); }
            });

            if (!l.await(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { Thread.sleep(2000L * (a + 1)); continue; }
            if (ok[0]) return true;
            if (stopped.get()) return false;
            if (err[0] != null && err[0].contains("FLOOD_WAIT")) {
                int w = parseFlood(err[0]);
                if (w > 0) { cb.onProgress(totalSent.get(), -1, "⏳ FloodWait " + w + "s"); safeSleep(w * 1000L); continue; }
            }
            Thread.sleep(1500L * (a + 1));
        }
        return false;
    }

    // ==========================================
    // ✅ computeHash — منع تكرار عالمي
    // الهاش يعتمد على المحتوى فقط — مو على الكروب
    // ==========================================
    public static String computeHash(TLRPC.Message msg) {
        if (msg == null) return null;

        try {
            TLRPC.MessageMedia media = msg.media;

            if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) media).document;
                if (doc != null) {
                    if (doc.access_hash != 0 && doc.dc_id != 0)
                        return "DOC_LEGACY|" + doc.dc_id + "_" + doc.access_hash + "_" + doc.id;
                    String mime = doc.mime_type != null ? doc.mime_type : "";
                    return "DOC_OLD|" + doc.id + "|" + doc.size + "|" + mime;
                }
                return "FALLBACK_DOC|" + msg.id + "|0|unknown|0";
            }

            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
                if (photo != null) {
                    if (photo.access_hash != 0 && photo.id != 0 && photo.dc_id != 0)
                        return "PHOTO_LEGACY|" + photo.id + "_" + photo.access_hash + "_" + photo.dc_id;
                    return "PHOTO_OLD|" + photo.id;
                }
                return "FALLBACK_PHOTO|" + msg.id + "|0";
            }

            if (media != null && !(media instanceof TLRPC.TL_messageMediaEmpty)) {
                return "FALLBACK_GEN|" + msg.id + "|" + msg.date + "|" + media.getClass().getSimpleName().hashCode();
            }

        } catch (Exception e) {
            Log.w(TAG, "computeHash: " + e.getMessage());
        }

        if (msg.message != null && msg.message.length() > 0) {
            return "TEXT|" + sha256(msg.message.getBytes()) + "|" + msg.date;
        }

        return null;
    }

    // ==========================================
    // فلاتر
    // ==========================================
    private boolean passFilter(TLRPC.Message msg, ForwardConfig c) {
        boolean any = c.mediaOnly || c.photoOnly || c.videoOnly || c.audioOnly || c.gifOnly;
        if (!any) return true;
        if (msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) return false;
        String t = mediaType(msg);
        if (c.photoOnly) return "photo".equals(t);
        if (c.videoOnly) return "video".equals(t);
        if (c.audioOnly) return "audio".equals(t);
        if (c.gifOnly) return "gif".equals(t);
        if (c.mediaOnly) return t != null;
        return true;
    }

    private String mediaType(TLRPC.Message msg) {
        if (msg.media == null) return null;
        if (msg.media instanceof TLRPC.TL_messageMediaPhoto) return "photo";
        if (msg.media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document d = ((TLRPC.TL_messageMediaDocument) msg.media).document;
            if (d == null) return "document";
            String m = d.mime_type != null ? d.mime_type.toLowerCase() : "";
            if (m.contains("gif")) return "gif";
            if (m.contains("video") || m.contains("mp4")) return "video";
            if (m.contains("audio") || m.contains("voice") || m.contains("ogg")) return "audio";
            return "document";
        }
        return null;
    }

    private boolean passDate(TLRPC.Message msg, ForwardConfig c) {
        if (c.fromDateTs == 0 && c.toDateTs == 0) return true;
        long d = msg.date;
        if (c.fromDateTs > 0 && d < c.fromDateTs) return false;
        return c.toDateTs <= 0 || d <= c.toDateTs + 86399;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { return Long.toHexString(java.util.Arrays.hashCode(data)); }
    }

    private int parseFlood(String t) {
        try { java.util.regex.Matcher m = java.util.regex.Pattern.compile("FLOOD_WAIT[_ ]?(\\d+)").matcher(t);
            if (m.find()) return Integer.parseInt(m.group(1)); } catch (Exception ignored) {} return 0;
    }

    private void safeSleep(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end && !stopped.get()) {
            try { long r = end - SystemClock.elapsedRealtime(); if (r <= 0) break; Thread.sleep(Math.min(500, r)); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
        }
    }
}
