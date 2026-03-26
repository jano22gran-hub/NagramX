package org.telegram.messenger.NagramX.forwarder;

import android.app.DatePickerDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ForwarderSettingsActivity extends BaseFragment {

    public static final String PREF_SKIP_DUPLICATES    = "fwd_skip_duplicates";
    public static final String PREF_STRICT_DETECTION   = "fwd_strict_detection";
    public static final String PREF_DROP_AUTHOR        = "fwd_drop_author";
    public static final String PREF_DROP_CAPTION       = "fwd_drop_caption";
    public static final String PREF_CHUNK_SIZE         = "fwd_chunk_size";
    public static final String PREF_DELAY_SECONDS      = "fwd_delay_seconds";
    public static final String PREF_LARGE_BATCH        = "fwd_large_batch";
    public static final String PREF_LARGE_BATCH_SIZE   = "fwd_large_batch_size";
    public static final String PREF_LARGE_BATCH_DELAY  = "fwd_large_batch_delay";
    public static final String PREF_MEDIA_ONLY         = "fwd_media_only";
    public static final String PREF_PHOTO_ONLY         = "fwd_photo_only";
    public static final String PREF_VIDEO_ONLY         = "fwd_video_only";
    public static final String PREF_AUDIO_ONLY         = "fwd_audio_only";
    public static final String PREF_GIF_ONLY           = "fwd_gif_only";
    public static final String PREF_MAX_MESSAGES       = "fwd_max_messages";
    public static final String PREF_REVERSE_ORDER      = "fwd_reverse_order";
    public static final String PREF_FROM_DATE          = "fwd_from_date";
    public static final String PREF_TO_DATE            = "fwd_to_date";
    public static final String PREFS_NAME              = "NagramX_Forwarder";

    private static final int DEF_CHUNK = 50;
    private static final float DEF_DELAY = 3f;
    private static final int DEF_MAX = 30_000;
    private static final int DEF_LB_SIZE = 2000;
    private static final float DEF_LB_DELAY = 60f;

    private SeekBar seekChunk;
    private TextView lblChunk;
    private EditText edtDelay, edtMax, edtLBSize, edtLBDelay;
    private TextView tvFrom, tvTo;
    private Switch swMedia, swPhoto, swVideo, swAudio, swGif;
    private LinearLayout largeBatchGroup;

    private ForwarderHashDatabase hashDb;

    @Override
    public View createView(Context ctx) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("⚙️ إعدادات التحويل Pro");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        hashDb = ForwarderHashDatabase.getInstance();
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);

        // ═══════ نظام منع التكرار ═══════
        hdr(root, ctx, "🔐 نظام منع التكرار");
        sw(root, ctx, "⏭️ تفعيل منع التكرار (Hash System)",
            "لو معطل — يحول كل شي بدون فحص (نسخ كامل)", PREF_SKIP_DUPLICATES, true);
        sw(root, ctx, "🔥 فحص صارم (Strict Detection)",
            "يستخدم access_hash للكشف الأدق", PREF_STRICT_DETECTION, true);

        btn(root, ctx, "📊 إحصائيات قاعدة البيانات", v -> showStats(ctx));
        btn(root, ctx, "🗑️ مسح قاعدة البيانات", v -> confirmClear(ctx));
        btn(root, ctx, "📤 تصدير قاعدة البيانات", v -> doExport(ctx));
        btn(root, ctx, "📥 استيراد قاعدة البيانات", v -> doImport(ctx));
        div(root, ctx);

        // ═══════ صيانة ═══════
        hdr(root, ctx, "🛠️ صيانة قاعدة البيانات");
        btn(root, ctx, "🔍 فحص السلامة", v -> checkIntegrity(ctx));
        btn(root, ctx, "🛠️ إصلاح + VACUUM", v -> doRepair(ctx));
        btn(root, ctx, "🧹 حذف الهاشات القديمة", v -> doCleanup(ctx));
        div(root, ctx);

        // ═══════ إعدادات الإرسال ═══════
        hdr(root, ctx, "⏱️ إعدادات الإرسال");

        lblChunk = lbl(root, ctx, "📦 حجم الدفعة: " + p(ctx).getInt(PREF_CHUNK_SIZE, DEF_CHUNK));
        seekChunk = new SeekBar(ctx);
        seekChunk.setMax(94);
        seekChunk.setProgress(p(ctx).getInt(PREF_CHUNK_SIZE, DEF_CHUNK) - 1);
        seekChunk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int pr, boolean u) { lblChunk.setText("📦 حجم الدفعة: " + (pr + 1)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { p(ctx).edit().putInt(PREF_CHUNK_SIZE, s.getProgress() + 1).apply(); }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2); sbp.topMargin = dp(6);
        root.addView(seekChunk, sbp);

        edtDelay = edt(root, ctx, "⏱️ التأخير بين الدفعات (ثواني)",
            String.valueOf(p(ctx).getFloat(PREF_DELAY_SECONDS, DEF_DELAY)),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        edtMax = edt(root, ctx, "📊 الحد الأقصى للرسائل",
            String.valueOf(p(ctx).getInt(PREF_MAX_MESSAGES, DEF_MAX)), InputType.TYPE_CLASS_NUMBER);

        sw(root, ctx, "🔄 ترتيب عكسي (الأحدث أولاً)", "يحوّل من الأحدث للأقدم", PREF_REVERSE_ORDER, false);
        div(root, ctx);

        // ═══════ ✅ دفعات كبيرة (Large Batch) ═══════
        hdr(root, ctx, "📦 الدفعات الكبيرة (حماية إضافية)");

        Switch swLB = sw(root, ctx, "تفعيل الدفعات الكبيرة",
            "يتوقف بعد عدد محدد من الرسائل لفترة طويلة", PREF_LARGE_BATCH, false);

        largeBatchGroup = new LinearLayout(ctx);
        largeBatchGroup.setOrientation(LinearLayout.VERTICAL);
        largeBatchGroup.setVisibility(p(ctx).getBoolean(PREF_LARGE_BATCH, false) ? View.VISIBLE : View.GONE);

        edtLBSize = edt(largeBatchGroup, ctx, "📦 حجم الوجبة الكبيرة (رسالة)",
            String.valueOf(p(ctx).getInt(PREF_LARGE_BATCH_SIZE, DEF_LB_SIZE)), InputType.TYPE_CLASS_NUMBER);

        edtLBDelay = edt(largeBatchGroup, ctx, "⏱️ التوقف بين الوجبات (ثانية)",
            String.valueOf(p(ctx).getFloat(PREF_LARGE_BATCH_DELAY, DEF_LB_DELAY)),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // شرح
        TextView desc = new TextView(ctx);
        desc.setText("مثال: حجم 2000 + توقف 60s\n= كل 2000 رسالة ينتظر 60 ثانية قبل المتابعة");
        desc.setTextSize(12);
        desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1, -2); dp2.topMargin = dp(8);
        largeBatchGroup.addView(desc, dp2);

        root.addView(largeBatchGroup);

        swLB.setOnCheckedChangeListener((v, checked) -> {
            p(ctx).edit().putBoolean(PREF_LARGE_BATCH, checked).apply();
            largeBatchGroup.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        div(root, ctx);

        // ═══════ خيارات التحويل ═══════
        hdr(root, ctx, "✂️ خيارات التحويل");
        sw(root, ctx, "👤 إخفاء المُرسِل", "يُرسِل بدون ذكر المصدر", PREF_DROP_AUTHOR, false);
        sw(root, ctx, "✂️ إخفاء التعليق", "يحذف Caption", PREF_DROP_CAPTION, false);
        div(root, ctx);

        // ═══════ فلاتر الوسائط ═══════
        hdr(root, ctx, "🎭 فلاتر الوسائط (اختر واحداً — أو بدون = نسخ كامل)");
        swMedia = sw(root, ctx, "🖼️ وسائط فقط", "", PREF_MEDIA_ONLY, false);
        swPhoto = sw(root, ctx, "📸 صور فقط", "", PREF_PHOTO_ONLY, false);
        swVideo = sw(root, ctx, "🎬 فيديو فقط", "", PREF_VIDEO_ONLY, false);
        swAudio = sw(root, ctx, "🎵 صوت فقط", "", PREF_AUDIO_ONLY, false);
        swGif   = sw(root, ctx, "🎞️ GIF فقط", "", PREF_GIF_ONLY, false);

        View.OnClickListener fc = v -> enforceOne(v, ctx);
        swMedia.setOnClickListener(fc); swPhoto.setOnClickListener(fc);
        swVideo.setOnClickListener(fc); swAudio.setOnClickListener(fc); swGif.setOnClickListener(fc);
        div(root, ctx);

        // ═══════ فلاتر التاريخ ═══════
        hdr(root, ctx, "📅 فلاتر التاريخ");
        long from = p(ctx).getLong(PREF_FROM_DATE, 0), to = p(ctx).getLong(PREF_TO_DATE, 0);
        tvFrom = btn(root, ctx, "📅 من: " + (from > 0 ? fmtD(from) : "بدون حد"), v -> pickD(ctx, true));
        tvTo = btn(root, ctx, "📅 إلى: " + (to > 0 ? fmtD(to) : "بدون حد"), v -> pickD(ctx, false));
        btn(root, ctx, "🗑️ مسح التواريخ", v -> {
            p(ctx).edit().putLong(PREF_FROM_DATE, 0).putLong(PREF_TO_DATE, 0).apply();
            tvFrom.setText("📅 من: بدون حد"); tvTo.setText("📅 إلى: بدون حد");
        });

        return scroll;
    }

    // ═══════ buildConfig — static ═══════
    public static ForwarderEngine.ForwardConfig buildConfig(Context ctx, long src, long tgt) {
        ForwarderEngine.ForwardConfig c = new ForwarderEngine.ForwardConfig();
        c.sourceId = src; c.targetId = tgt;
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        c.skipDuplicates     = p.getBoolean(PREF_SKIP_DUPLICATES, true);
        c.strictDetection    = p.getBoolean(PREF_STRICT_DETECTION, true);
        c.dropAuthor         = p.getBoolean(PREF_DROP_AUTHOR, false);
        c.dropCaption        = p.getBoolean(PREF_DROP_CAPTION, false);
        c.chunkSize          = p.getInt(PREF_CHUNK_SIZE, DEF_CHUNK);
        c.delaySeconds       = p.getFloat(PREF_DELAY_SECONDS, DEF_DELAY);
        c.maxMessages        = p.getInt(PREF_MAX_MESSAGES, DEF_MAX);
        c.largeBatchEnabled  = p.getBoolean(PREF_LARGE_BATCH, false);
        c.largeBatchSize     = p.getInt(PREF_LARGE_BATCH_SIZE, DEF_LB_SIZE);
        c.largeBatchDelay    = p.getFloat(PREF_LARGE_BATCH_DELAY, DEF_LB_DELAY);
        c.mediaOnly          = p.getBoolean(PREF_MEDIA_ONLY, false);
        c.photoOnly          = p.getBoolean(PREF_PHOTO_ONLY, false);
        c.videoOnly          = p.getBoolean(PREF_VIDEO_ONLY, false);
        c.audioOnly          = p.getBoolean(PREF_AUDIO_ONLY, false);
        c.gifOnly            = p.getBoolean(PREF_GIF_ONLY, false);
        c.reverseOrder       = p.getBoolean(PREF_REVERSE_ORDER, false);
        c.fromDateTs         = p.getLong(PREF_FROM_DATE, 0);
        c.toDateTs           = p.getLong(PREF_TO_DATE, 0);
        return c;
    }

    @Override public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (getParentActivity() != null && edtDelay != null) {
            try {
                android.content.SharedPreferences.Editor e = p(getParentActivity()).edit();
                e.putFloat(PREF_DELAY_SECONDS, Float.parseFloat(edtDelay.getText().toString()));
                e.putInt(PREF_MAX_MESSAGES, Integer.parseInt(edtMax.getText().toString()));
                if (edtLBSize != null) e.putInt(PREF_LARGE_BATCH_SIZE, Integer.parseInt(edtLBSize.getText().toString()));
                if (edtLBDelay != null) e.putFloat(PREF_LARGE_BATCH_DELAY, Float.parseFloat(edtLBDelay.getText().toString()));
                e.apply();
            } catch (Exception ignored) {}
        }
    }

    // ═══════ DB Operations ═══════
    private void showStats(Context ctx) {
        ForwarderHashDatabase.ForwarderStats s = hashDb.getStats();
        StringBuilder sb = new StringBuilder();
        sb.append("📁 DB: ").append(s.dbPath).append("\n");
        sb.append("📤 تصدير: ").append(s.exportPath).append("\n\n");
        sb.append("📊 الهاشات: ").append(String.format("%,d", s.totalHashes)).append("\n");
        sb.append("💾 الحجم: ").append(s.formatSize()).append("\n");
        sb.append("🧠 كاش: ").append(String.format("%,d", s.cacheSize)).append("\n");
        sb.append("🔄 WAL: ").append(s.journalMode != null ? s.journalMode : "—").append("\n\n");
        if (s.oldestHashDays > 0) sb.append("📅 أقدم: ").append(String.format("%.1f", s.oldestHashDays)).append(" يوم\n");
        if (s.newestHashDays >= 0) sb.append("📅 أحدث: ").append(String.format("%.1f", s.newestHashDays)).append(" يوم\n\n");
        sb.append(s.integrityOk && !s.corrupted ? "✅ سليمة" : "⚠️ مشكلة!").append("\n\n");
        if (s.hashTypeBreakdown != null) {
            sb.append("📊 الأنواع:\n");
            for (String[] i : s.hashTypeBreakdown) sb.append("  • ").append(i[0]).append(": ").append(String.format("%,d", Long.parseLong(i[1]))).append("\n");
        }
        new AlertDialog.Builder(ctx).setTitle("📊 إحصائيات").setMessage(sb.toString()).setPositiveButton("حسناً", null).show();
    }

    private void doExport(Context ctx) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String path = hashDb.exportToJson();
            AndroidUtilities.runOnUIThread(() ->
                new AlertDialog.Builder(ctx).setTitle(path != null ? "✅ تصدير" : "❌ فشل")
                    .setMessage(path != null ? "📁 " + path : "تحقق من الصلاحيات")
                    .setPositiveButton("حسناً", null).show());
        });
    }

    private void doImport(Context ctx) {
        List<File> files = hashDb.findImportFiles();
        if (files.isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("📥 استيراد")
                .setMessage("لا توجد ملفات.\n\n📁 " + hashDb.getMediaDir() + "\n📁 Downloads\n📁 ForwarderPro_State")
                .setPositiveButton("حسناً", null).show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            names[i] = f.getName() + " (" + (f.length() / 1024) + " KB)\n" + f.getParent();
        }
        new AlertDialog.Builder(ctx).setTitle("📥 اختر ملف").setItems(names, (d, w) -> {
            File sel = files.get(w);
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                int added = hashDb.importFromJson(sel);
                AndroidUtilities.runOnUIThread(() ->
                    new AlertDialog.Builder(ctx).setTitle(added > 0 ? "✅ استيراد" : "ℹ️ لا جديد")
                        .setMessage(added > 0 ? "تم استيراد " + added + " هاش" : "كل الهاشات موجودة")
                        .setPositiveButton("حسناً", null).show());
            });
        }).setNegativeButton("إلغاء", null).show();
    }

    private void confirmClear(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🗑️ مسح").setMessage("⚠️ غير قابل للتراجع!")
            .setPositiveButton("مسح", (d, w) -> {
                long c = hashDb.clearAll();
                new AlertDialog.Builder(ctx).setTitle("✅").setMessage("تم مسح " + c + " هاش").setPositiveButton("حسناً", null).show();
            }).setNegativeButton("إلغاء", null).show();
    }

    private void checkIntegrity(Context ctx) {
        ForwarderHashDatabase.ForwarderStats s = hashDb.getStats();
        new AlertDialog.Builder(ctx).setTitle("🔍 سلامة")
            .setMessage((s.integrityOk ? "✅ سليمة" : "⚠️ مشكلة") + "\nالحجم: " + s.formatSize() + "\nالهاشات: " + String.format("%,d", s.totalHashes))
            .setPositiveButton("حسناً", null).show();
    }

    private void doRepair(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🛠️ إصلاح").setMessage("إصلاح hash_type + VACUUM\n✅ آمن")
            .setPositiveButton("ابدأ", (d, w) -> {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    ForwarderHashDatabase.RepairResult r = hashDb.repairDatabase();
                    AndroidUtilities.runOnUIThread(() ->
                        new AlertDialog.Builder(ctx).setTitle(r.success ? "✅" : "❌")
                            .setMessage(r.success ? "مُصحَّح: " + r.fixedTypes + "\nVACUUM: " + (r.vacuumed ? "✅" : "❌") + "\nوُفِّر: " + r.formatSaved() : r.error)
                            .setPositiveButton("حسناً", null).show());
                });
            }).setNegativeButton("إلغاء", null).show();
    }

    private void doCleanup(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🧹 تنظيف").setMessage("حذف legacy + unknown\n⚠️ غير قابل للتراجع")
            .setPositiveButton("حذف", (d, w) -> {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    int del = hashDb.cleanupLegacyHashes();
                    AndroidUtilities.runOnUIThread(() ->
                        new AlertDialog.Builder(ctx).setTitle(del > 0 ? "✅" : "ℹ️")
                            .setMessage(del > 0 ? "حُذف " + del : "نظيف").setPositiveButton("حسناً", null).show());
                });
            }).setNegativeButton("إلغاء", null).show();
    }

    // ═══════ Helpers ═══════
    private void enforceOne(View v, Context ctx) {
        Switch[] all = {swMedia, swPhoto, swVideo, swAudio, swGif};
        String[] keys = {PREF_MEDIA_ONLY, PREF_PHOTO_ONLY, PREF_VIDEO_ONLY, PREF_AUDIO_ONLY, PREF_GIF_ONLY};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == v) continue;
            all[i].setChecked(false); p(ctx).edit().putBoolean(keys[i], false).apply();
        }
    }

    private void pickD(Context ctx, boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(ctx, (v, y, m, d) -> {
            Calendar s = Calendar.getInstance(); s.set(y, m, d, 0, 0, 0);
            long ts = s.getTimeInMillis() / 1000;
            if (isFrom) { p(ctx).edit().putLong(PREF_FROM_DATE, ts).apply(); tvFrom.setText("📅 من: " + fmtD(ts)); }
            else { p(ctx).edit().putLong(PREF_TO_DATE, ts).apply(); tvTo.setText("📅 إلى: " + fmtD(ts)); }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String fmtD(long ts) {
        try { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts * 1000)); } catch (Exception e) { return "—"; }
    }

    // ═══════ UI Builders ═══════
    private void hdr(LinearLayout r, Context c, String t) {
        TextView tv = new TextView(c); tv.setText(t); tv.setTextSize(16);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(20); p.bottomMargin = dp(8);
        r.addView(tv, p);
    }

    private Switch sw(LinearLayout r, Context c, String txt, String sub, String key, boolean def) {
        Switch s = new Switch(c); s.setText(txt); s.setTextSize(15);
        s.setChecked(p(c).getBoolean(key, def));
        s.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        s.setOnCheckedChangeListener((v, ch) -> p(c).edit().putBoolean(key, ch).apply());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        r.addView(s, lp);
        if (sub != null && !sub.isEmpty()) {
            TextView t = new TextView(c); t.setText(sub); t.setTextSize(12);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.leftMargin = dp(48); sp.topMargin = dp(2);
            r.addView(t, sp);
        }
        return s;
    }

    private TextView lbl(LinearLayout r, Context c, String t) {
        TextView tv = new TextView(c); tv.setText(t); tv.setTextSize(14);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(14);
        r.addView(tv, p); return tv;
    }

    private EditText edt(LinearLayout r, Context c, String hint, String val, int type) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setInputType(type); e.setTextSize(14);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10);
        r.addView(e, p); return e;
    }

    private TextView btn(LinearLayout r, Context c, String t, View.OnClickListener cl) {
        TextView tv = new TextView(c); tv.setText(t); tv.setTextSize(15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setPadding(0, dp(10), 0, dp(10)); tv.setOnClickListener(cl);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(4);
        r.addView(tv, p); return tv;
    }

    private void div(LinearLayout r, Context c) {
        View v = new View(c); v.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 1); p.topMargin = dp(14); p.bottomMargin = dp(6);
        r.addView(v, p);
    }

    private int dp(int v) { return AndroidUtilities.dp(v); }
    private android.content.SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
}
