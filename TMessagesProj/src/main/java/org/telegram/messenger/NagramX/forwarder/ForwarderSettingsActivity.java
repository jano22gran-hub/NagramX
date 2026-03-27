package org.telegram.messenger.NagramX.forwarder;

import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
        actionBar.setTitle("⚙️ ForwarderPro");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        hashDb = ForwarderHashDatabase.getInstance();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(content);
        root.addView(scroll);

        // ═══════ نظام منع التكرار ═══════
        hdr(content, ctx, "🔐 نظام منع التكرار");
        LinearLayout card1 = card(content, ctx);
        sw(card1, ctx, "⏭️ تفعيل منع التكرار", "معطل = ينسخ كل شي بدون فحص", PREF_SKIP_DUPLICATES, true);
        sw(card1, ctx, "🔥 فحص صارم", "يستخدم access_hash للكشف الأدق", PREF_STRICT_DETECTION, true);
        div(card1, ctx);
        btn(card1, ctx, "📊 إحصائيات قاعدة البيانات", v -> showStats(ctx));
        btn(card1, ctx, "📤 تصدير الهاشات", v -> doExport(ctx));
        btn(card1, ctx, "📥 استيراد الهاشات", v -> doImport(ctx));
        btn(card1, ctx, "🗑️ مسح قاعدة البيانات", v -> confirmClear(ctx));

        // ═══════ صيانة ═══════
        hdr(content, ctx, "🛠️ صيانة");
        LinearLayout card2 = card(content, ctx);
        btn(card2, ctx, "🔍 فحص السلامة", v -> checkIntegrity(ctx));
        btn(card2, ctx, "🛠️ إصلاح + VACUUM", v -> doRepair(ctx));
        btn(card2, ctx, "🧹 حذف الهاشات القديمة", v -> doCleanup(ctx));

        // ═══════ إعدادات الإرسال ═══════
        hdr(content, ctx, "⏱️ إعدادات الإرسال");
        LinearLayout card3 = card(content, ctx);

        lblChunk = lbl(card3, ctx, "📦 حجم الدفعة: " + p(ctx).getInt(PREF_CHUNK_SIZE, DEF_CHUNK));
        seekChunk = new SeekBar(ctx);
        seekChunk.setMax(94);
        seekChunk.setProgress(p(ctx).getInt(PREF_CHUNK_SIZE, DEF_CHUNK) - 1);
        seekChunk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int pr, boolean u) { lblChunk.setText("📦 حجم الدفعة: " + (pr + 1)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { p(ctx).edit().putInt(PREF_CHUNK_SIZE, s.getProgress() + 1).apply(); }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2); sbp.topMargin = dp(4);
        card3.addView(seekChunk, sbp);

        edtDelay = edt(card3, ctx, "⏱️ التأخير بين الدفعات (ثواني)", String.valueOf(p(ctx).getFloat(PREF_DELAY_SECONDS, DEF_DELAY)),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edtMax = edt(card3, ctx, "📊 الحد الأقصى للرسائل", String.valueOf(p(ctx).getInt(PREF_MAX_MESSAGES, DEF_MAX)), InputType.TYPE_CLASS_NUMBER);
        sw(card3, ctx, "🔄 ترتيب عكسي", "الأحدث أولاً", PREF_REVERSE_ORDER, false);

        // ═══════ دفعات كبيرة ═══════
        hdr(content, ctx, "📦 الدفعات الكبيرة");
        LinearLayout card4 = card(content, ctx);
        Switch swLB = sw(card4, ctx, "تفعيل الدفعات الكبيرة", "حماية إضافية من FloodWait", PREF_LARGE_BATCH, false);

        largeBatchGroup = new LinearLayout(ctx);
        largeBatchGroup.setOrientation(LinearLayout.VERTICAL);
        largeBatchGroup.setVisibility(p(ctx).getBoolean(PREF_LARGE_BATCH, false) ? View.VISIBLE : View.GONE);

        edtLBSize = edt(largeBatchGroup, ctx, "📦 حجم الوجبة (رسالة)", String.valueOf(p(ctx).getInt(PREF_LARGE_BATCH_SIZE, DEF_LB_SIZE)), InputType.TYPE_CLASS_NUMBER);
        edtLBDelay = edt(largeBatchGroup, ctx, "⏱️ التوقف (ثانية)", String.valueOf(p(ctx).getFloat(PREF_LARGE_BATCH_DELAY, DEF_LB_DELAY)),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        TextView desc = new TextView(ctx);
        desc.setText("مثال: 2000 + 60s = كل 2000 رسالة ينتظر 60 ثانية");
        desc.setTextSize(11); desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams dp3 = new LinearLayout.LayoutParams(-1, -2); dp3.topMargin = dp(6);
        largeBatchGroup.addView(desc, dp3);
        card4.addView(largeBatchGroup);

        swLB.setOnCheckedChangeListener((v, checked) -> {
            p(ctx).edit().putBoolean(PREF_LARGE_BATCH, checked).apply();
            largeBatchGroup.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        // ═══════ خيارات التحويل ═══════
        hdr(content, ctx, "✂️ خيارات التحويل");
        LinearLayout card5 = card(content, ctx);
        sw(card5, ctx, "👤 إخفاء المُرسِل", "بدون ذكر المصدر", PREF_DROP_AUTHOR, false);
        sw(card5, ctx, "✂️ إخفاء التعليق", "حذف Caption", PREF_DROP_CAPTION, false);

        // ═══════ فلاتر الوسائط ═══════
        hdr(content, ctx, "🎭 فلاتر الوسائط");
        LinearLayout card6 = card(content, ctx);
        note(card6, ctx, "اختر واحداً — أو بدون = نسخ كامل");
        swMedia = sw(card6, ctx, "🖼️ وسائط فقط", "", PREF_MEDIA_ONLY, false);
        swPhoto = sw(card6, ctx, "📸 صور فقط", "", PREF_PHOTO_ONLY, false);
        swVideo = sw(card6, ctx, "🎬 فيديو فقط", "", PREF_VIDEO_ONLY, false);
        swAudio = sw(card6, ctx, "🎵 صوت فقط", "", PREF_AUDIO_ONLY, false);
        swGif   = sw(card6, ctx, "🎞️ GIF فقط", "", PREF_GIF_ONLY, false);
        View.OnClickListener fc = v -> enforceOne(v, ctx);
        swMedia.setOnClickListener(fc); swPhoto.setOnClickListener(fc);
        swVideo.setOnClickListener(fc); swAudio.setOnClickListener(fc); swGif.setOnClickListener(fc);

        // ═══════ فلاتر التاريخ ═══════
        hdr(content, ctx, "📅 فلاتر التاريخ");
        LinearLayout card7 = card(content, ctx);
        long from = p(ctx).getLong(PREF_FROM_DATE, 0), to = p(ctx).getLong(PREF_TO_DATE, 0);
        tvFrom = btn(card7, ctx, "📅 من: " + (from > 0 ? fmtD(from) : "بدون حد"), v -> pickD(ctx, true));
        tvTo = btn(card7, ctx, "📅 إلى: " + (to > 0 ? fmtD(to) : "بدون حد"), v -> pickD(ctx, false));
        btn(card7, ctx, "🗑️ مسح التواريخ", v -> {
            p(ctx).edit().putLong(PREF_FROM_DATE, 0).putLong(PREF_TO_DATE, 0).apply();
            tvFrom.setText("📅 من: بدون حد"); tvTo.setText("📅 إلى: بدون حد");
        });

        return root;
    }

    // ═══════ buildConfig ═══════
    public static ForwarderEngine.ForwardConfig buildConfig(Context ctx, long src, long tgt) {
        ForwarderEngine.ForwardConfig c = new ForwarderEngine.ForwardConfig();
        c.sourceId = src; c.targetId = tgt;
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        c.skipDuplicates = p.getBoolean(PREF_SKIP_DUPLICATES, true);
        c.strictDetection = p.getBoolean(PREF_STRICT_DETECTION, true);
        c.dropAuthor = p.getBoolean(PREF_DROP_AUTHOR, false);
        c.dropCaption = p.getBoolean(PREF_DROP_CAPTION, false);
        c.chunkSize = p.getInt(PREF_CHUNK_SIZE, DEF_CHUNK);
        c.delaySeconds = p.getFloat(PREF_DELAY_SECONDS, DEF_DELAY);
        c.maxMessages = p.getInt(PREF_MAX_MESSAGES, DEF_MAX);
        c.largeBatchEnabled = p.getBoolean(PREF_LARGE_BATCH, false);
        c.largeBatchSize = p.getInt(PREF_LARGE_BATCH_SIZE, DEF_LB_SIZE);
        c.largeBatchDelay = p.getFloat(PREF_LARGE_BATCH_DELAY, DEF_LB_DELAY);
        c.mediaOnly = p.getBoolean(PREF_MEDIA_ONLY, false);
        c.photoOnly = p.getBoolean(PREF_PHOTO_ONLY, false);
        c.videoOnly = p.getBoolean(PREF_VIDEO_ONLY, false);
        c.audioOnly = p.getBoolean(PREF_AUDIO_ONLY, false);
        c.gifOnly = p.getBoolean(PREF_GIF_ONLY, false);
        c.reverseOrder = p.getBoolean(PREF_REVERSE_ORDER, false);
        c.fromDateTs = p.getLong(PREF_FROM_DATE, 0);
        c.toDateTs = p.getLong(PREF_TO_DATE, 0);
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
        sb.append(s.integrityOk && !s.corrupted ? "✅ سليمة" : "⚠️ مشكلة!").append("\n\n");
        sb.append("📊 الهاشات: ").append(String.format("%,d", s.totalHashes)).append("\n");
        sb.append("💾 الحجم: ").append(s.formatSize()).append("\n");
        sb.append("🧠 كاش: ").append(String.format("%,d", s.cacheSize)).append("\n");
        sb.append("🔄 WAL: ").append(s.journalMode != null ? s.journalMode : "—").append("\n");
        if (s.oldestHashDays > 0) sb.append("📅 أقدم: ").append(String.format("%.0f", s.oldestHashDays)).append(" يوم\n");
        if (s.newestHashDays >= 0) sb.append("📅 أحدث: ").append(String.format("%.0f", s.newestHashDays)).append(" يوم\n");
        sb.append("\n");
        if (s.hashTypeBreakdown != null && !s.hashTypeBreakdown.isEmpty()) {
            sb.append("📊 الأنواع:\n");
            for (String[] i : s.hashTypeBreakdown) sb.append("  ").append(i[0]).append(": ").append(String.format("%,d", Long.parseLong(i[1]))).append("\n");
        }
        sb.append("\n📁 ").append(s.dbPath).append("\n📤 ").append(s.exportPath);
        new AlertDialog.Builder(ctx).setTitle("📊 إحصائيات").setMessage(sb.toString()).setPositiveButton("حسناً", null).show();
    }

    private void doExport(Context ctx) {
        AlertDialog[] dlg = {new AlertDialog.Builder(ctx).setTitle("⏳ تصدير...").setMessage("يرجى الانتظار").create()};
        dlg[0].setCancelable(false); dlg[0].show();
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String path = hashDb.exportToJson();
            AndroidUtilities.runOnUIThread(() -> {
                try { dlg[0].dismiss(); } catch (Exception ignored) {}
                new AlertDialog.Builder(ctx).setTitle(path != null ? "✅ تصدير" : "❌ فشل")
                    .setMessage(path != null ? "📁 " + path : "تحقق من الصلاحيات")
                    .setPositiveButton("حسناً", null).show();
            });
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
            long kb = f.length() / 1024;
            String size = kb > 1024 ? String.format("%.1f MB", kb / 1024.0) : kb + " KB";
            names[i] = f.getName() + " (" + size + ")";
        }
        new AlertDialog.Builder(ctx).setTitle("📥 اختر ملف").setItems(names, (d, w) -> {
            File sel = files.get(w);

            // ✅ Progress dialog مع شريط تقدم
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(20), dp(16), dp(20), dp(16));

            TextView tvMsg = new TextView(ctx);
            tvMsg.setText("⏳ جاري استيراد الهاشات...\n" + sel.getName());
            tvMsg.setTextSize(14);
            tvMsg.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            layout.addView(tvMsg);

            ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = dp(12);
            layout.addView(bar, bp);

            TextView tvProg = new TextView(ctx);
            tvProg.setText("تحضير..."); tvProg.setTextSize(12);
            tvProg.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.topMargin = dp(6);
            layout.addView(tvProg, tp);

            AlertDialog[] loadDlg = {new AlertDialog.Builder(ctx).setTitle("📥 استيراد").setView(layout).create()};
            loadDlg[0].setCancelable(false); loadDlg[0].show();

            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                int added = hashDb.importFromJson(sel, (addedSoFar, scanned, total) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (total > 0) { bar.setMax(total); bar.setProgress(scanned); }
                        tvProg.setText("✅ " + String.format("%,d", addedSoFar) + " جديد / " + String.format("%,d", scanned) + " فُحص");
                    })
                );
                AndroidUtilities.runOnUIThread(() -> {
                    try { loadDlg[0].dismiss(); } catch (Exception ignored) {}
                    new AlertDialog.Builder(ctx).setTitle(added > 0 ? "✅ استيراد" : "ℹ️ لا جديد")
                        .setMessage(added > 0 ? "تم استيراد " + String.format("%,d", added) + " هاش جديد" : "كل الهاشات موجودة مسبقاً")
                        .setPositiveButton("حسناً", null).show();
                });
            });
        }).setNegativeButton("إلغاء", null).show();
    }

    private void confirmClear(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🗑️ مسح").setMessage("⚠️ سيُحذف كل شي!\nغير قابل للتراجع.")
            .setPositiveButton("مسح", (d, w) -> {
                long c = hashDb.clearAll();
                new AlertDialog.Builder(ctx).setTitle("✅").setMessage("تم مسح " + String.format("%,d", c) + " هاش").setPositiveButton("حسناً", null).show();
            }).setNegativeButton("إلغاء", null).show();
    }

    private void checkIntegrity(Context ctx) {
        ForwarderHashDatabase.ForwarderStats s = hashDb.getStats();
        new AlertDialog.Builder(ctx).setTitle("🔍 سلامة")
            .setMessage((s.integrityOk ? "✅ سليمة" : "⚠️ مشكلة") + "\n\nالحجم: " + s.formatSize() +
                "\nالهاشات: " + String.format("%,d", s.totalHashes) + "\nWAL: " + s.journalMode)
            .setPositiveButton("حسناً", null).show();
    }

    private void doRepair(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🛠️ إصلاح").setMessage("• تصحيح hash_type\n• حذف indexes مكررة\n• VACUUM\n\n✅ آمن — لا حذف للهاشات")
            .setPositiveButton("ابدأ", (d, w) -> {
                AlertDialog[] ld = {new AlertDialog.Builder(ctx).setTitle("⏳").setMessage("جاري الإصلاح...").create()};
                ld[0].setCancelable(false); ld[0].show();
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    ForwarderHashDatabase.RepairResult r = hashDb.repairDatabase();
                    AndroidUtilities.runOnUIThread(() -> {
                        try { ld[0].dismiss(); } catch (Exception ignored) {}
                        new AlertDialog.Builder(ctx).setTitle(r.success ? "✅ تم" : "❌ فشل")
                            .setMessage(r.success ? "مُصحَّح: " + r.fixedTypes + "\nVACUUM: " + (r.vacuumed ? "✅" : "❌") + "\nوُفِّر: " + r.formatSaved() : r.error)
                            .setPositiveButton("حسناً", null).show();
                    });
                });
            }).setNegativeButton("إلغاء", null).show();
    }

    private void doCleanup(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🧹 تنظيف").setMessage("حذف: legacy_fp + legacy_numeric_id + unknown\n⚠️ غير قابل للتراجع")
            .setPositiveButton("حذف", (d, w) -> {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    int del = hashDb.cleanupLegacyHashes();
                    AndroidUtilities.runOnUIThread(() ->
                        new AlertDialog.Builder(ctx).setTitle(del > 0 ? "✅" : "ℹ️")
                            .setMessage(del > 0 ? "حُذف " + String.format("%,d", del) : "نظيف").setPositiveButton("حسناً", null).show());
                });
            }).setNegativeButton("إلغاء", null).show();
    }

    // ═══════ Helpers ═══════
    private void enforceOne(View v, Context ctx) {
        Switch[] all = {swMedia, swPhoto, swVideo, swAudio, swGif};
        String[] keys = {PREF_MEDIA_ONLY, PREF_PHOTO_ONLY, PREF_VIDEO_ONLY, PREF_AUDIO_ONLY, PREF_GIF_ONLY};
        android.content.SharedPreferences.Editor ed = p(ctx).edit();
        for (int i = 0; i < all.length; i++) { if (all[i] != v) { all[i].setChecked(false); ed.putBoolean(keys[i], false); } }
        ed.apply();
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

    // ═══════ UI Builders — بطاقات محسّنة ═══════
    private void hdr(LinearLayout r, Context c, String t) {
        TextView tv = new TextView(c); tv.setText(t); tv.setTextSize(14);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(16); p.bottomMargin = dp(4); p.leftMargin = dp(4);
        r.addView(tv, p);
    }

    private LinearLayout card(LinearLayout parent, Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4); lp.bottomMargin = dp(2);
        parent.addView(card, lp);
        return card;
    }

    private Switch sw(LinearLayout r, Context c, String txt, String sub, String key, boolean def) {
        Switch s = new Switch(c); s.setText(txt); s.setTextSize(15);
        s.setChecked(p(c).getBoolean(key, def));
        s.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        s.setOnCheckedChangeListener((v, ch) -> p(c).edit().putBoolean(key, ch).apply());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        r.addView(s, lp);
        if (sub != null && !sub.isEmpty()) {
            TextView t = new TextView(c); t.setText(sub); t.setTextSize(11);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.leftMargin = dp(48); sp.topMargin = dp(1);
            r.addView(t, sp);
        }
        return s;
    }

    private void note(LinearLayout r, Context c, String text) {
        TextView tv = new TextView(c); tv.setText(text); tv.setTextSize(12);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(4);
        r.addView(tv, p);
    }

    private TextView lbl(LinearLayout r, Context c, String t) {
        TextView tv = new TextView(c); tv.setText(t); tv.setTextSize(14);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10);
        r.addView(tv, p); return tv;
    }

    private EditText edt(LinearLayout r, Context c, String hint, String val, int type) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setInputType(type); e.setTextSize(14);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8);
        r.addView(e, p); return e;
    }

    private TextView btn(LinearLayout r, Context c, String t, View.OnClickListener cl) {
        TextView tv = new TextView(c); tv.setText(t); tv.setTextSize(15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setPadding(0, dp(10), 0, dp(10)); tv.setOnClickListener(cl);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(2);
        r.addView(tv, p); return tv;
    }

    private void div(LinearLayout r, Context c) {
        View v = new View(c); v.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 1); p.topMargin = dp(10); p.bottomMargin = dp(6);
        r.addView(v, p);
    }

    private int dp(int v) { return AndroidUtilities.dp(v); }
    private android.content.SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
}
