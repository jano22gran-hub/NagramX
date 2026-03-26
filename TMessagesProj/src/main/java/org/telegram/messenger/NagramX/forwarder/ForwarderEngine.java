package org.telegram.messenger.NagramX.forwarder;

import android.os.SystemClock;
import android.util.Log;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
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

/**
 * ForwarderEngine — محرك التحويل الكامل
 *
 * الإصلاحات المطبقة:
 * ✅ safeSleep — Math.max(0, remaining) لمنع IllegalArgumentException
 * ✅ delay loop — نفس الإصلاح
 * ✅ reverse order — if (!config.reverseOrder) بدل double-reverse
 * ✅ noforwards check — فحص chat.noforwards قبل البدء
 * ✅ worker shutdown — في finally block
 * ✅ strictDetection — يُمرر لـ computeHash ويُستخدم فعلياً
 */
public class ForwarderEngine {

    private static final String TAG = "ForwarderEngine";
    private static final int CHUNK_MAX = 95;
    private static final int HISTORY_LIMIT = 100;
    private static final int HISTORY_TIMEOUT_MS = 45_000;
    private static final int SEND_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRIES = 5;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    // ==========================================
    // إعدادات التحويل
    // ==========================================
    public static class ForwardConfig {
        public long sourceId;
        public long targetId;
        public int account = UserConfig.selectedAccount;

        public int chunkSize = 50;
        public float delaySeconds = 3f;
        public boolean dropAuthor = false;
        public boolean dropCaption = false;

        public boolean mediaOnly = false;
        public boolean photoOnly = false;
        public boolean videoOnly = false;
        public boolean audioOnly = false;
        public boolean gifOnly = false;

        public long fromDateTs = 0;
        public long toDateTs = 0;

        public boolean skipDuplicates = true;
        public boolean strictDetection = true;

        public int maxMessages = 30_000;
        public boolean reverseOrder = false;
    }

    // ==========================================
    // Callback
    // ==========================================
    public interface ProgressCallback {
        void onProgress(int sent, int total, String statusMsg);
        void onComplete(int totalSent, int totalSkipped, long durationMs);
        void onError(String errorMsg);
        void onRestricted(String chatName);
    }

    // ==========================================
    // State
    // ==========================================
    private final ForwarderHashDatabase hashDb;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger totalSent = new AtomicInteger(0);
    private final AtomicInteger totalSkipped = new AtomicInteger(0);

    private volatile ExecutorService worker;

    public ForwarderEngine(ForwarderHashDatabase hashDb) {
        this.hashDb = hashDb;
    }

    // ==========================================
    // start — نقطة الدخول
    // ==========================================
    public void start(ForwardConfig config, ProgressCallback callback) {
        if (worker != null && !worker.isTerminated()) {
            callback.onError("عملية تحويل جارية بالفعل");
            return;
        }
        stopped.set(false);
        paused.set(false);
        totalSent.set(0);
        totalSkipped.set(0);

        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ForwarderEngine-Worker");
            t.setDaemon(true);
            return t;
        });

        worker.execute(() -> runForwarding(config, callback));
    }

    public void pause()  { paused.set(true);  }
    public void resume() { paused.set(false); }
    public void stop()   { stopped.set(true); paused.set(false); }
    public boolean isRunning() { return worker != null && !worker.isTerminated(); }

    // ==========================================
    // runForwarding — Worker الرئيسي
    // ✅ إصلاح: worker.shutdown() في finally
    // ✅ إصلاح: فحص noforwards قبل البدء
    // ==========================================
    private void runForwarding(ForwardConfig config, ProgressCallback callback) {
        long startTime = SystemClock.elapsedRealtime();
        try {
            // ✅ فحص noforwards قبل البدء — يوفر وقت الجمع
            MessagesController mc = MessagesController.getInstance(config.account);
            if (config.sourceId < 0) {
                TLRPC.Chat chat = mc.getChat(-config.sourceId);
                if (chat != null && chat.noforwards) {
                    callback.onRestricted(chat.title != null ? chat.title : String.valueOf(config.sourceId));
                    return;
                }
            }

            // 1) preload الهاشات
            if (config.skipDuplicates) {
                int loaded = hashDb.preloadToCache();
                Log.i(TAG, "preload: " + loaded + " هاش");
            }

            // 2) جمع IDs
            callback.onProgress(0, 0, "جاري جمع الرسائل...");
            List<Integer> messageIds = collectPhase(config, callback);

            if (messageIds.isEmpty()) {
                callback.onError("لا توجد رسائل للتحويل");
                return;
            }
            if (stopped.get()) return;

            // 3) إرسال
            int total = messageIds.size();
            sendPhase(messageIds, config, total, callback);

            long duration = SystemClock.elapsedRealtime() - startTime;
            callback.onComplete(totalSent.get(), totalSkipped.get(), duration);

        } catch (Exception e) {
            Log.e(TAG, "خطأ حرج: " + e.getMessage(), e);
            callback.onError("خطأ: " + e.getMessage());
        } finally {
            hashDb.flushWrites();
            // ✅ shutdown الـ worker
            if (worker != null) {
                worker.shutdown();
            }
        }
    }

    // ==========================================
    // collectPhase — جمع IDs مع فلترة
    // ==========================================
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> collectedHashes
            = new java.util.concurrent.ConcurrentHashMap<>();

    private List<Integer> collectPhase(ForwardConfig config, ProgressCallback callback) throws InterruptedException {
        List<Integer> allIds = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        collectedHashes.clear();
        int offsetId = 0;
        boolean hasMore = true;
        int retryCount = 0;
        int duplicatesFound = 0;

        MessagesController mc = MessagesController.getInstance(config.account);

        while (hasMore && !stopped.get() && allIds.size() < config.maxMessages) {
            while (paused.get() && !stopped.get()) {
                Thread.sleep(300);
            }
            if (stopped.get()) break;

            final CountDownLatch latch = new CountDownLatch(1);
            final List<TLRPC.Message> batch = new ArrayList<>();
            final boolean[] batchHasMore = {false};
            final int finalOffsetId = offsetId;

            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = mc.getInputPeer(config.sourceId);
            req.offset_id = finalOffsetId;
            req.limit = HISTORY_LIMIT;
            req.add_offset = 0;
            req.max_id = 0;
            req.min_id = 0;
            req.hash = 0;

            ConnectionsManager.getInstance(config.account).sendRequest(req, (response, error) -> {
                try {
                    if (error != null || response == null) {
                        latch.countDown();
                        return;
                    }
                    if (response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                        for (TLObject obj : msgs.messages) {
                            if (obj instanceof TLRPC.Message) {
                                batch.add((TLRPC.Message) obj);
                            }
                        }
                        batchHasMore[0] = batch.size() >= HISTORY_LIMIT;
                    }
                } finally {
                    latch.countDown();
                }
            });

            boolean gotResponse = latch.await(HISTORY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!gotResponse) {
                retryCount++;
                Log.w(TAG, "timeout " + retryCount);
                if (retryCount >= MAX_RETRIES) break;
                continue;
            }
            retryCount = 0;

            if (batch.isEmpty()) {
                hasMore = false;
                break;
            }

            int minId = Integer.MAX_VALUE;
            for (TLRPC.Message msg : batch) {
                if (msg.id < minId) minId = msg.id;

                if (!passesMediaFilter(msg, config)) continue;
                if (!passesDateFilter(msg, config)) continue;

                if (config.skipDuplicates) {
                    String hash = computeHash(msg, config.strictDetection);
                    if (hash != null && hashDb.isDuplicate(hash)) {
                        duplicatesFound++;
                        totalSkipped.incrementAndGet();
                        continue;
                    }
                    if (hash != null) {
                        collectedHashes.put(msg.id, hash);
                    }
                }

                if (!seenIds.contains(msg.id)) {
                    seenIds.add(msg.id);
                    allIds.add(msg.id);
                }
            }

            offsetId = (minId == Integer.MAX_VALUE) ? 0 : Math.max(0, minId - 1);
            hasMore = batchHasMore[0] && offsetId > 0;

            if (config.maxMessages > 0 && allIds.size() >= config.maxMessages) {
                hasMore = false;
            }

            callback.onProgress(0, allIds.size(),
                "جمع: " + allIds.size() + " رسالة (تم تخطي " + duplicatesFound + " مكرر)");
        }

        // ✅ إصلاح: reverse مرة واحدة فقط بدل مرتين
        // getHistory يُرجع الأحدث أولاً
        if (!config.reverseOrder) {
            // الافتراضي: نعكس ليكون الأقدم أولاً
            java.util.Collections.reverse(allIds);
        }
        // لو reverseOrder = true: نخلي الترتيب كما جاء (الأحدث أولاً)

        Log.i(TAG, "جمع " + allIds.size() + " رسالة، تجاوز " + duplicatesFound + " مكرر");
        return allIds;
    }

    // ==========================================
    // sendPhase — الإرسال دفعة دفعة
    // ✅ إصلاح: delay loop مع Math.max(0, remaining)
    // ==========================================
    private void sendPhase(List<Integer> messageIds, ForwardConfig config,
                           int total, ProgressCallback callback) throws InterruptedException {

        int actualChunk = Math.min(config.chunkSize, CHUNK_MAX);
        int consecutiveFailures = 0;
        MessagesController mc = MessagesController.getInstance(config.account);

        int i = 0;
        while (i < messageIds.size() && !stopped.get()) {

            while (paused.get() && !stopped.get()) {
                Thread.sleep(300);
            }
            if (stopped.get()) break;

            int end = Math.min(i + actualChunk, messageIds.size());
            List<Integer> chunk = messageIds.subList(i, end);

            boolean success = sendChunkNative(chunk, config, mc, callback);

            if (success) {
                if (config.skipDuplicates) {
                    final List<Integer> sentChunk = new ArrayList<>(chunk);
                    hashDb.getBgWriter().execute(() -> {
                        for (int msgId : sentChunk) {
                            String hash = collectedHashes.remove(msgId);
                            if (hash != null) {
                                hashDb.addHash(hash, inferHashType(hash));
                            }
                        }
                        hashDb.flushWrites();
                    });
                }
                totalSent.addAndGet(chunk.size());
                consecutiveFailures = 0;
                callback.onProgress(totalSent.get(), total,
                    "تم: " + totalSent.get() + " / " + total);
            } else {
                consecutiveFailures++;
                Log.w(TAG, "فشل chunk " + i + ", failures=" + consecutiveFailures);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    callback.onError("فشل متكرر في الإرسال بعد " + MAX_CONSECUTIVE_FAILURES + " محاولات");
                    break;
                }
                continue;
            }

            i = end;

            // ✅ إصلاح: تأخير مع Math.max
            if (i < messageIds.size() && config.delaySeconds > 0) {
                long delayMs = (long)(config.delaySeconds * 1000);
                long deadline = SystemClock.elapsedRealtime() + delayMs;
                while (SystemClock.elapsedRealtime() < deadline && !stopped.get()) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    Thread.sleep(Math.min(remaining, 300));
                    while (paused.get() && !stopped.get()) Thread.sleep(300);
                }
            }
        }
    }

    // ==========================================
    // sendChunkNative — الإرسال الفعلي
    // ==========================================
    private boolean sendChunkNative(List<Integer> chunk, ForwardConfig config,
                                     MessagesController mc, ProgressCallback callback)
                                     throws InterruptedException {

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (stopped.get()) return false;

            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};
            final String[] errorText = {null};

            TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
            req.from_peer = mc.getInputPeer(config.sourceId);
            req.to_peer   = mc.getInputPeer(config.targetId);
            req.flags = 0;

            if (config.dropAuthor) {
                req.flags |= (1 << 11);
                req.drop_author = true;
            }
            if (config.dropCaption) {
                req.flags |= (1 << 16);
                req.drop_media_captions = true;
            }

            req.id = new ArrayList<>(chunk);
            req.random_id = new ArrayList<>();
            for (int ignored : chunk) {
                req.random_id.add(Utilities.random.nextLong());
            }

            ConnectionsManager.getInstance(config.account).sendRequest(req, (response, error) -> {
                try {
                    if (error != null) {
                        String errMsg = error.text != null ? error.text : "UNKNOWN";
                        errorText[0] = errMsg;
                        Log.w(TAG, "sendChunk error: " + errMsg);

                        if (errMsg.contains("CHAT_FORWARDS_RESTRICTED")) {
                            stopped.set(true);
                            String srcName = String.valueOf(config.sourceId);
                            try {
                                TLRPC.Chat chat = MessagesController.getInstance(config.account)
                                    .getChat(-config.sourceId);
                                if (chat != null) srcName = chat.title;
                            } catch (Exception ignored2) {}
                            callback.onRestricted(srcName);
                        }
                    } else {
                        success[0] = true;
                    }
                } finally {
                    latch.countDown();
                }
            });

            boolean responded = latch.await(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!responded) {
                Log.w(TAG, "send timeout, attempt " + attempt);
                Thread.sleep(2000L * (attempt + 1));
                continue;
            }

            if (success[0]) return true;
            if (stopped.get()) return false;

            // FloodWait
            if (errorText[0] != null && errorText[0].contains("FLOOD_WAIT")) {
                int waitSec = parseFloodWait(errorText[0]);
                if (waitSec > 0) {
                    Log.i(TAG, "FloodWait " + waitSec + "s");
                    callback.onProgress(totalSent.get(), -1, "FloodWait " + waitSec + "s...");
                    safeSleep(waitSec * 1000L);
                    continue;
                }
            }

            Thread.sleep(1500L * (attempt + 1));
        }
        return false;
    }

    // ==========================================
    // computeHash — حساب هاش الوسائط
    // ✅ إصلاح: strict parameter يُستخدم الآن
    // ==========================================
    public static String computeHash(TLRPC.Message msg, boolean strict) {
        if (msg == null || msg.media == null) return null;
        try {
            TLRPC.MessageMedia media = msg.media;

            if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) media).document;
                if (doc == null) return null;

                // الوضع الصارم: file_reference أولاً
                if (strict && doc.file_reference != null && doc.file_reference.length >= 8) {
                    String refHash = sha256Hex(doc.file_reference);
                    return "DOC_REF|" + refHash;
                }
                // access_hash + dc_id
                if (doc.access_hash != 0 && doc.dc_id != 0) {
                    return "DOC_LEGACY|" + doc.dc_id + "_" + doc.access_hash + "_" + doc.id;
                }
                // fallback
                return "DOC_OLD|" + doc.id + "|" + doc.size + "|" + doc.mime_type;

            } else if (media instanceof TLRPC.TL_messageMediaPhoto) {
                TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
                if (photo == null) return null;

                if (strict && photo.file_reference != null && photo.file_reference.length >= 8) {
                    String refHash = sha256Hex(photo.file_reference);
                    return "PHOTO_REF|" + refHash;
                }
                if (photo.access_hash != 0 && photo.dc_id != 0) {
                    return "PHOTO_LEGACY|" + photo.dc_id + "_" + photo.access_hash + "_" + photo.id;
                }
                return "PHOTO_OLD|" + photo.id;
            }
        } catch (Exception e) {
            Log.w(TAG, "computeHash خطأ: " + e.getMessage());
        }
        return null;
    }

    // ==========================================
    // فلاتر
    // ==========================================
    private boolean passesMediaFilter(TLRPC.Message msg, ForwardConfig c) {
        boolean anyFilter = c.mediaOnly || c.photoOnly || c.videoOnly || c.audioOnly || c.gifOnly;
        if (!anyFilter) return true;
        if (msg.media == null) return false;

        String type = getMediaType(msg);
        if (c.photoOnly) return "photo".equals(type);
        if (c.videoOnly) return "video".equals(type);
        if (c.audioOnly) return "audio".equals(type);
        if (c.gifOnly)   return "gif".equals(type);
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
        long msgDate = msg.date;
        if (c.fromDateTs > 0 && msgDate < c.fromDateTs) return false;
        if (c.toDateTs > 0 && msgDate > c.toDateTs + 86399) return false;
        return true;
    }

    // ==========================================
    // Helpers
    // ==========================================
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return Long.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    static String inferHashType(String hash) {
        if (hash == null) return "unknown";
        if (hash.startsWith("DOC_REF|"))      return "document_ref";
        if (hash.startsWith("PHOTO_REF|"))    return "photo_ref";
        if (hash.startsWith("DOC_LEGACY|"))   return "document_legacy";
        if (hash.startsWith("PHOTO_LEGACY|")) return "photo_legacy";
        if (hash.startsWith("DOC_OLD|"))      return "document_old";
        if (hash.startsWith("PHOTO_OLD|"))    return "photo_old";
        if (hash.startsWith("UID:"))          return "document_unique";
        if (hash.length() == 32)              return "content_doc";
        return "unknown";
    }

    private int parseFloodWait(String errorText) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("FLOOD_WAIT[_ ]?(\\d+)")
                .matcher(errorText);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 0;
    }

    // ✅ إصلاح: Math.max(0, remaining) لمنع IllegalArgumentException
    private void safeSleep(long ms) {
        long deadline = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < deadline && !stopped.get()) {
            try {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                Thread.sleep(Math.min(500, remaining));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
