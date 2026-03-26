package org.telegram.messenger.NagramX.forwarder;

import android.os.SystemClock;
import android.util.Log;

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

    public static class ForwardConfig {
        public long sourceId;
        public long targetId;
        public int account = UserConfig.selectedAccount;

        // دفعة صغيرة
        public int chunkSize = 50;
        public float delaySeconds = 3f;

        // ✅ دفعة كبيرة (Large Batch)
        public boolean largeBatchEnabled = false;
        public int largeBatchSize = 2000;       // كل كم رسالة يتوقف
        public float largeBatchDelay = 60f;     // كم ثانية يتوقف

        public boolean dropAuthor = false;
        public boolean dropCaption = false;

        public boolean mediaOnly = false;
        public boolean photoOnly = false;
        public boolean videoOnly = false;
        public boolean audioOnly = false;
        public boolean gifOnly = false;

        public long fromDateTs = 0;
        public long toDateTs = 0;

        // ✅ skipDuplicates = false يعني يحول كل شي بدون فحص هاش
        public boolean skipDuplicates = true;
        public boolean strictDetection = true;

        public int maxMessages = 30_000;
        public boolean reverseOrder = false;
    }

    public interface ProgressCallback {
        void onProgress(int sent, int total, String statusMsg);
        void onComplete(int totalSent, int totalSkipped, long durationMs);
        void onError(String errorMsg);
        void onRestricted(String chatName);
    }

    private final ForwarderHashDatabase hashDb;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger totalSent = new AtomicInteger(0);
    private final AtomicInteger totalSkipped = new AtomicInteger(0);
    private volatile ExecutorService worker;

    public ForwarderEngine(ForwarderHashDatabase hashDb) {
        this.hashDb = hashDb;
    }

    public void start(ForwardConfig config, ProgressCallback callback) {
        if (worker != null && !worker.isTerminated()) {
            callback.onError("عملية تحويل جارية بالفعل");
            return;
        }
        stopped.set(false); paused.set(false);
        totalSent.set(0); totalSkipped.set(0);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ForwarderEngine-Worker"); t.setDaemon(true); return t;
        });
        worker.execute(() -> runForwarding(config, callback));
    }

    public void pause()  { paused.set(true); }
    public void resume() { paused.set(false); }
    public void stop()   { stopped.set(true); paused.set(false); }
    public boolean isRunning() { return worker != null && !worker.isTerminated(); }

    private void runForwarding(ForwardConfig config, ProgressCallback callback) {
        long startTime = SystemClock.elapsedRealtime();
        try {
            // فحص noforwards
            MessagesController mc = MessagesController.getInstance(config.account);
            if (config.sourceId < 0) {
                TLRPC.Chat chat = mc.getChat(-config.sourceId);
                if (chat != null && chat.noforwards) {
                    callback.onRestricted(chat.title != null ? chat.title : String.valueOf(config.sourceId));
                    return;
                }
            }

            // preload هاشات لو مفعل
            if (config.skipDuplicates) {
                int loaded = hashDb.preloadToCache();
                Log.i(TAG, "preload: " + loaded);
            }

            callback.onProgress(0, 0, "جاري جمع الرسائل...");
            List<CollectedMessage> messages = collectPhase(config, callback);

            if (messages.isEmpty()) {
                callback.onError("لا توجد رسائل للتحويل");
                return;
            }
            if (stopped.get()) return;

            sendPhase(messages, config, messages.size(), callback);

            long duration = SystemClock.elapsedRealtime() - startTime;
            callback.onComplete(totalSent.get(), totalSkipped.get(), duration);
        } catch (Exception e) {
            Log.e(TAG, "خطأ: " + e.getMessage(), e);
            callback.onError("خطأ: " + e.getMessage());
        } finally {
            hashDb.flushWrites();
            if (worker != null) worker.shutdown();
        }
    }

    static class CollectedMessage {
        int msgId;
        String hash;
        CollectedMessage(int id, String h) { msgId = id; hash = h; }
    }

    // ==========================================
    // collectPhase — جمع سريع
    // ==========================================
    private List<CollectedMessage> collectPhase(ForwardConfig config, ProgressCallback callback) throws InterruptedException {
        List<CollectedMessage> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int offsetId = 0;
        boolean hasMore = true;
        int retries = 0, dupsFound = 0, emptyRuns = 0;

        MessagesController mc = MessagesController.getInstance(config.account);

        while (hasMore && !stopped.get() && result.size() < config.maxMessages) {
            while (paused.get() && !stopped.get()) Thread.sleep(300);
            if (stopped.get()) break;

            final CountDownLatch latch = new CountDownLatch(1);
            final List<TLRPC.Message> batch = new ArrayList<>();
            final boolean[] more = {false};

            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = mc.getInputPeer(config.sourceId);
            req.offset_id = offsetId;
            req.limit = HISTORY_LIMIT;
            req.add_offset = 0; req.max_id = 0; req.min_id = 0; req.hash = 0;

            ConnectionsManager.getInstance(config.account).sendRequest(req, (resp, err) -> {
                try {
                    if (err != null || resp == null) { latch.countDown(); return; }
                    if (resp instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) resp;
                        for (TLObject obj : msgs.messages)
                            if (obj instanceof TLRPC.Message) batch.add((TLRPC.Message) obj);
                        more[0] = batch.size() >= HISTORY_LIMIT;
                    }
                } finally { latch.countDown(); }
            });

            if (!latch.await(HISTORY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (++retries >= MAX_RETRIES) break;
                continue;
            }
            retries = 0;
            if (batch.isEmpty()) break;

            int added = 0;
            int minId = Integer.MAX_VALUE;
            for (TLRPC.Message msg : batch) {
                if (msg.id < minId) minId = msg.id;
                if (!passesMediaFilter(msg, config)) continue;
                if (!passesDateFilter(msg, config)) continue;

                String hash = null;
                if (config.skipDuplicates) {
                    hash = computeHash(msg, config.strictDetection);
                    if (hash != null && hashDb.isDuplicate(hash)) {
                        dupsFound++; totalSkipped.incrementAndGet();
                        continue;
                    }
                }

                if (!seen.contains(msg.id)) {
                    seen.add(msg.id);
                    result.add(new CollectedMessage(msg.id, hash));
                    added++;
                }
            }

            if (added == 0) { if (++emptyRuns >= 3) break; } else emptyRuns = 0;

            offsetId = (minId == Integer.MAX_VALUE) ? 0 : Math.max(0, minId - 1);
            hasMore = more[0] && offsetId > 0;
            if (config.maxMessages > 0 && result.size() >= config.maxMessages) hasMore = false;

            callback.onProgress(0, result.size(),
                "جمع: " + result.size() + " رسالة" + (dupsFound > 0 ? " (تخطي " + dupsFound + " مكرر)" : ""));
        }

        if (!config.reverseOrder) java.util.Collections.reverse(result);
        return result;
    }

    // ==========================================
    // sendPhase — مع Large Batch delay
    // ==========================================
    private void sendPhase(List<CollectedMessage> messages, ForwardConfig config,
                           int total, ProgressCallback callback) throws InterruptedException {

        int actualChunk = Math.min(config.chunkSize, CHUNK_MAX);
        int consecutiveFailures = 0;
        int sentSinceLargeBatchPause = 0; // عدّاد للدفعة الكبيرة
        MessagesController mc = MessagesController.getInstance(config.account);

        int i = 0;
        while (i < messages.size() && !stopped.get()) {
            while (paused.get() && !stopped.get()) Thread.sleep(300);
            if (stopped.get()) break;

            int end = Math.min(i + actualChunk, messages.size());
            List<CollectedMessage> chunk = messages.subList(i, end);

            List<Integer> ids = new ArrayList<>();
            for (CollectedMessage cm : chunk) ids.add(cm.msgId);

            boolean success = sendChunkNative(ids, config, mc, callback);

            if (success) {
                // تسجيل الهاشات فوراً
                if (config.skipDuplicates) {
                    for (CollectedMessage cm : chunk) {
                        if (cm.hash != null) {
                            hashDb.addHash(cm.hash, ForwarderHashDatabase.inferHashType(cm.hash));
                        }
                    }
                    hashDb.flushWrites();
                }

                int chunkCount = chunk.size();
                totalSent.addAndGet(chunkCount);
                sentSinceLargeBatchPause += chunkCount;
                consecutiveFailures = 0;

                callback.onProgress(totalSent.get(), total,
                    "✅ " + totalSent.get() + " / " + total);

                // ✅ Large Batch: لو وصلنا الحد → توقف
                if (config.largeBatchEnabled && config.largeBatchSize > 0
                    && sentSinceLargeBatchPause >= config.largeBatchSize
                    && i + chunkCount < messages.size()) {

                    sentSinceLargeBatchPause = 0;
                    int waitSec = (int) config.largeBatchDelay;
                    callback.onProgress(totalSent.get(), total,
                        "⏸ توقف دفعة كبيرة — " + waitSec + " ثانية (" + totalSent.get() + "/" + total + ")");

                    safeSleep((long)(config.largeBatchDelay * 1000));
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    callback.onError("فشل متكرر بعد " + MAX_CONSECUTIVE_FAILURES + " محاولات");
                    break;
                }
                continue;
            }

            i = end;

            // تأخير بين الدفعات الصغيرة
            if (i < messages.size() && config.delaySeconds > 0) {
                long delayMs = (long)(config.delaySeconds * 1000);
                long deadline = SystemClock.elapsedRealtime() + delayMs;
                while (SystemClock.elapsedRealtime() < deadline && !stopped.get()) {
                    long r = deadline - SystemClock.elapsedRealtime();
                    if (r <= 0) break;
                    Thread.sleep(Math.min(r, 300));
                    while (paused.get() && !stopped.get()) Thread.sleep(300);
                }
            }
        }
    }

    // ==========================================
    // sendChunkNative
    // ==========================================
    private boolean sendChunkNative(List<Integer> chunk, ForwardConfig config,
                                     MessagesController mc, ProgressCallback callback) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (stopped.get()) return false;
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};
            final String[] errorText = {null};

            TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
            req.from_peer = mc.getInputPeer(config.sourceId);
            req.to_peer = mc.getInputPeer(config.targetId);
            req.flags = 0;
            if (config.dropAuthor) { req.flags |= (1 << 11); req.drop_author = true; }
            if (config.dropCaption) { req.flags |= (1 << 16); req.drop_media_captions = true; }
            req.id = new ArrayList<>(chunk);
            req.random_id = new ArrayList<>();
            for (int ignored : chunk) req.random_id.add(Utilities.random.nextLong());

            ConnectionsManager.getInstance(config.account).sendRequest(req, (resp, err) -> {
                try {
                    if (err != null) {
                        errorText[0] = err.text != null ? err.text : "UNKNOWN";
                        if (errorText[0].contains("CHAT_FORWARDS_RESTRICTED")) {
                            stopped.set(true);
                            String name = String.valueOf(config.sourceId);
                            try {
                                TLRPC.Chat chat = MessagesController.getInstance(config.account).getChat(-config.sourceId);
                                if (chat != null) name = chat.title;
                            } catch (Exception ignored2) {}
                            callback.onRestricted(name);
                        }
                    } else success[0] = true;
                } finally { latch.countDown(); }
            });

            if (!latch.await(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Thread.sleep(2000L * (attempt + 1)); continue;
            }
            if (success[0]) return true;
            if (stopped.get()) return false;

            if (errorText[0] != null && errorText[0].contains("FLOOD_WAIT")) {
                int w = parseFloodWait(errorText[0]);
                if (w > 0) {
                    callback.onProgress(totalSent.get(), -1, "⏳ FloodWait " + w + "s...");
                    safeSleep(w * 1000L); continue;
                }
            }
            Thread.sleep(1500L * (attempt + 1));
        }
        return false;
    }

    // ==========================================
    // computeHash — ثابت عبر الجلسات
    // ==========================================
    public static String computeHash(TLRPC.Message msg, boolean strict) {
        if (msg == null) return null;

        if (msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) {
            if (msg.message != null && msg.message.length() > 0) {
                return "TEXT|" + sha256Hex(msg.message.getBytes()) + "|" + msg.date;
            }
            return null;
        }

        try {
            TLRPC.MessageMedia media = msg.media;
            if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) media).document;
                if (doc == null) return null;
                if (doc.access_hash != 0 && doc.dc_id != 0)
                    return "DOC_LEGACY|" + doc.dc_id + "_" + doc.access_hash + "_" + doc.id;
                return "DOC_OLD|" + doc.id + "|" + doc.size + "|" + doc.mime_type;
            } else if (media instanceof TLRPC.TL_messageMediaPhoto) {
                TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
                if (photo == null) return null;
                if (photo.access_hash != 0 && photo.dc_id != 0)
                    return "PHOTO_LEGACY|" + photo.dc_id + "_" + photo.access_hash + "_" + photo.id;
                return "PHOTO_OLD|" + photo.id;
            }
        } catch (Exception e) { Log.w(TAG, "hash: " + e.getMessage()); }
        return null;
    }

    // ==========================================
    // فلاتر
    // ==========================================
    private boolean passesMediaFilter(TLRPC.Message msg, ForwardConfig c) {
        boolean anyFilter = c.mediaOnly || c.photoOnly || c.videoOnly || c.audioOnly || c.gifOnly;
        if (!anyFilter) return true;
        if (msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) return false;
        String type = getMediaType(msg);
        if (c.photoOnly) return "photo".equals(type);
        if (c.videoOnly) return "video".equals(type);
        if (c.audioOnly) return "audio".equals(type);
        if (c.gifOnly) return "gif".equals(type);
        if (c.mediaOnly) return type != null;
        return true;
    }

    private String getMediaType(TLRPC.Message msg) {
        if (msg.media == null) return null;
        if (msg.media instanceof TLRPC.TL_messageMediaPhoto) return "photo";
        if (msg.media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) msg.media).document;
            if (doc == null) return "document";
            String mime = doc.mime_type != null ? doc.mime_type.toLowerCase() : "";
            if (mime.contains("gif")) return "gif";
            if (mime.contains("video") || mime.contains("mp4")) return "video";
            if (mime.contains("audio") || mime.contains("voice") || mime.contains("ogg")) return "audio";
            return "document";
        }
        return null;
    }

    private boolean passesDateFilter(TLRPC.Message msg, ForwardConfig c) {
        if (c.fromDateTs == 0 && c.toDateTs == 0) return true;
        long d = msg.date;
        if (c.fromDateTs > 0 && d < c.fromDateTs) return false;
        return c.toDateTs <= 0 || d <= c.toDateTs + 86399;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { return Long.toHexString(java.util.Arrays.hashCode(data)); }
    }

    private int parseFloodWait(String text) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("FLOOD_WAIT[_ ]?(\\d+)").matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 0;
    }

    private void safeSleep(long ms) {
        long deadline = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < deadline && !stopped.get()) {
            try {
                long r = deadline - SystemClock.elapsedRealtime();
                if (r <= 0) break;
                Thread.sleep(Math.min(500, r));
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
        }
    }
}
