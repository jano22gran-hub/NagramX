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

    public static final String PREF_SKIP_DUPLICATES   = "fwd_skip_duplicates";
    public static final String PREF_STRICT_DETECTION  = "fwd_strict_detection";
    public static final String PREF_DROP_AUTHOR        = "fwd_drop_author";
    public static final String PREF_DROP_CAPTION       = "fwd_drop_caption";
    public static final String PREF_CHUNK_SIZE         = "fwd_chunk_size";
    public static final String PREF_DELAY_SECONDS      = "fwd_delay_seconds";
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

    private static final int DEFAULT_CHUNK = 50;
    private static final float DEFAULT_DELAY = 3f;
    private static final int DEFAULT_MAX = 30_000;

    private SeekBar seekbarChunk;
    private TextView labelChunkSize;
    private EditText editDelay, editMaxMessages;
    private TextView tvFromDate, tvToDate;
    private Switch switchMediaOnly, switchPhotoOnly, switchVideoOnly, switchAudioOnly, switchGifOnly;

    private ForwarderHashDatabase hashDb;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("⚙️ إعدادات التحويل Pro");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });

        hashDb = ForwarderHashDatabase.getInstance();

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);

        // ═══════════ منع التكرار ═══════════
        header(root, context, "🔐 نظام منع التكرار");

        addSwitch(root, context, "تجاهل الوسائط المُحوَّلة مسبقاً",
            "يفحص قاعدة بيانات الهاشات قبل التحويل", PREF_SKIP_DUPLICATES, true);

        addSwitch(root, context, "فحص صارم (file_reference)",
            "يستخدم file_reference للكشف الأدق", PREF_STRICT_DETECTION, true);

        clickable(root, context, "📊 إحصائيات قاعدة البيانات", v -> showStats(context));
        clickable(root, context, "🗑️ مسح قاعدة البيانات", v -> confirmClear(context));
        clickable(root, context, "📤 تصدير قاعدة البيانات", v -> doExport(context));
        clickable(root, context, "📥 استيراد قاعدة البيانات", v -> doImport(context));

        divider(root, context);

        // ═══════════ صيانة ═══════════
        header(root, context, "🛠️ صيانة قاعدة البيانات");

        clickable(root, context, "🔍 فحص سلامة قاعدة البيانات", v -> checkIntegrity(context));
        clickable(root, context, "🛠️ إصلاح وتحسين (Repair + VACUUM)", v -> doRepair(context));
        clickable(root, context, "🧹 حذف الهاشات القديمة غير المتوافقة", v -> doCleanup(context));

        divider(root, context);

        // ═══════════ إعدادات الإرسال ═══════════
        header(root, context, "⏱️ إعدادات الإرسال");

        labelChunkSize = label(root, context, "📦 حجم الدفعة: " + prefs(context).getInt(PREF_CHUNK_SIZE, DEFAULT_CHUNK));
        seekbarChunk = new SeekBar(context);
        seekbarChunk.setMax(94);
        seekbarChunk.setProgress(prefs(context).getInt(PREF_CHUNK_SIZE, DEFAULT_CHUNK) - 1);
        seekbarChunk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { labelChunkSize.setText("📦 حجم الدفعة: " + (p + 1)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { prefs(context).edit().putInt(PREF_CHUNK_SIZE, s.getProgress() + 1).apply(); }
        });
        LinearLayout.LayoutParams sbP = new LinearLayout.LayoutParams(-1, -2);
        sbP.topMargin = dp(6);
        root.addView(seekbarChunk, sbP);

        editDelay = editText(root, context, "⏱️ التأخير بين الدفعات (ثواني)",
            String.valueOf(prefs(context).getFloat(PREF_DELAY_SECONDS, DEFAULT_DELAY)),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        editMaxMessages = editText(root, context, "📊 الحد الأقصى للرسائل",
            String.valueOf(prefs(context).getInt(PREF_MAX_MESSAGES, DEFAULT_MAX)),
            InputType.TYPE_CLASS_NUMBER);

        addSwitch(root, context, "🔄 ترتيب عكسي (الأحدث أولاً)",
            "يحوّل من الأحدث للأقدم", PREF_REVERSE_ORDER, false);

        divider(root, context);

        // ═══════════ خيارات التحويل ═══════════
        header(root, context, "✂️ خيارات التحويل");

        addSwitch(root, context, "👤 إخفاء المُرسِل الأصلي",
            "يُرسِل بدون ذكر المصدر", PREF_DROP_AUTHOR, false);
        addSwitch(root, context, "✂️ إخفاء التعليق (Caption)",
            "يحذف النص المرفق مع الوسائط", PREF_DROP_CAPTION, false);

        divider(root, context);

        // ═══════════ فلاتر الوسائط ═══════════
        header(root, context, "🎭 فلاتر الوسائط (اختر واحداً)");

        switchMediaOnly = addSwitch(root, context, "🖼️ وسائط فقط", "", PREF_MEDIA_ONLY, false);
        switchPhotoOnly = addSwitch(root, context, "📸 صور فقط", "", PREF_PHOTO_ONLY, false);
        switchVideoOnly = addSwitch(root, context, "🎬 فيديو فقط", "", PREF_VIDEO_ONLY, false);
        switchAudioOnly = addSwitch(root, context, "🎵 صوت فقط", "", PREF_AUDIO_ONLY, false);
        switchGifOnly   = addSwitch(root, context, "🎞️ GIF فقط", "", PREF_GIF_ONLY, false);

        View.OnClickListener filterClick = v -> enforceOneFilter(v, context);
        switchMediaOnly.setOnClickListener(filterClick);
        switchPhotoOnly.setOnClickListener(filterClick);
        switchVideoOnly.setOnClickListener(filterClick);
        switchAudioOnly.setOnClickListener(filterClick);
        switchGifOnly.setOnClickListener(filterClick);

        divider(root, context);

        // ═══════════ فلاتر التاريخ ═══════════
        header(root, context, "📅 فلاتر التاريخ");

        long from = prefs(context).getLong(PREF_FROM_DATE, 0);
        long to = prefs(context).getLong(PREF_TO_DATE, 0);
        tvFromDate = clickable(root, context, "📅 من: " + (from > 0 ? fmtDate(from) : "بدون حد"), v -> pickDate(context, true));
        tvToDate = clickable(root, context, "📅 إلى: " + (to > 0 ? fmtDate(to) : "بدون حد"), v -> pickDate(context, false));
        clickable(root, context, "🗑️ مسح فلاتر التاريخ", v -> {
            prefs(context).edit().putLong(PREF_FROM_DATE, 0).putLong(PREF_TO_DATE, 0).apply();
            tvFromDate.setText("📅 من: بدون حد");
            tvToDate.setText("📅 إلى: بدون حد");
        });

        return scroll;
    }

    // ═══════════════════════════════════════════
    // إحصائيات مفصلة
    // ═══════════════════════════════════════════
    private void showStats(Context ctx) {
        ForwarderHashDatabase.ForwarderStats s = hashDb.getStats();
        StringBuilder sb = new StringBuilder();
        sb.append("📁 مسار DB:\n").append(s.dbPath).append("\n\n");
        sb.append("📤 مسار التصدير:\n").append(s.exportPath).append("\n\n");
        sb.append("━━━━━━━━━━━━━━━━━\n");
        sb.append("📊 إجمالي الهاشات: ").append(String.format("%,d", s.totalHashes)).append("\n");
        sb.append("💾 حجم DB: ").append(s.formatSize()).append("\n");
        sb.append("🧠 الكاش: ").append(String.format("%,d", s.cacheSize)).append("\n");
        sb.append("📝 كتابات معلقة: ").append(s.pendingWrites).append("\n");
        sb.append("🔄 WAL: ").append(s.journalMode != null ? s.journalMode : "—").append("\n\n");

        if (s.oldestHashDays > 0) sb.append("📅 أقدم هاش: ").append(String.format("%.1f", s.oldestHashDays)).append(" يوم\n");
        if (s.newestHashDays >= 0) sb.append("📅 أحدث هاش: ").append(String.format("%.1f", s.newestHashDays)).append(" يوم\n\n");

        sb.append("━━━━━━━━━━━━━━━━━\n");
        sb.append(s.integrityOk ? "✅ سلامة DB: سليمة" : "⚠️ سلامة DB: تالفة!").append("\n");
        sb.append(s.corrupted ? "⚠️ حالة: تالفة" : "✅ حالة: تعمل").append("\n\n");

        if (s.hashTypeBreakdown != null && !s.hashTypeBreakdown.isEmpty()) {
            sb.append("📊 توزيع أنواع الهاشات:\n");
            for (String[] item : s.hashTypeBreakdown) {
                sb.append("   • ").append(item[0]).append(": ").append(String.format("%,d", Long.parseLong(item[1]))).append("\n");
            }
        }

        new AlertDialog.Builder(ctx).setTitle("📊 إحصائيات قاعدة البيانات").setMessage(sb.toString())
            .setPositiveButton("حسناً", null).show();
    }

    // ═══════════════════════════════════════════
    // تصدير
    // ═══════════════════════════════════════════
    private void doExport(Context ctx) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String path = hashDb.exportToJson();
            AndroidUtilities.runOnUIThread(() -> {
                if (path != null) {
                    new AlertDialog.Builder(ctx).setTitle("✅ تم التصدير")
                        .setMessage("📁 الملف:\n" + path).setPositiveButton("حسناً", null).show();
                } else {
                    new AlertDialog.Builder(ctx).setTitle("❌ فشل التصدير")
                        .setMessage("تحقق من صلاحيات التخزين.\n\nتأكد من إعطاء التطبيق صلاحية 'All files access' من إعدادات الهاتف.")
                        .setPositiveButton("حسناً", null).show();
                }
            });
        });
    }

    // ═══════════════════════════════════════════
    // استيراد
    // ═══════════════════════════════════════════
    private void doImport(Context ctx) {
        List<File> files = hashDb.findImportFiles();
        if (files.isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("📥 استيراد")
                .setMessage("لا توجد ملفات هاشات.\n\nأماكن البحث:\n📁 " + hashDb.getMediaDir() +
                    "\n📁 Downloads\n📁 ForwarderPro_State\n\nضع ملف JSON في أحد هذه المجلدات.")
                .setPositiveButton("حسناً", null).show();
            return;
        }

        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            names[i] = f.getName() + " (" + (f.length() / 1024) + " KB)\n📁 " + f.getParent();
        }

        new AlertDialog.Builder(ctx).setTitle("📥 اختر ملف الاستيراد")
            .setItems(names, (d, which) -> {
                File sel = files.get(which);
                new AlertDialog.Builder(ctx).setTitle("📥 تأكيد")
                    .setMessage("استيراد من:\n" + sel.getName() + "\n\nسيُضاف الجديد فقط.")
                    .setPositiveButton("استيراد", (d2, w) -> {
                        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                            int added = hashDb.importFromJson(sel);
                            AndroidUtilities.runOnUIThread(() ->
                                new AlertDialog.Builder(ctx)
                                    .setTitle(added > 0 ? "✅ تم الاستيراد" : "ℹ️ لا جديد")
                                    .setMessage(added > 0 ? "تم استيراد " + added + " هاش جديد" : "كل الهاشات موجودة مسبقاً")
                                    .setPositiveButton("حسناً", null).show()
                            );
                        });
                    }).setNegativeButton("إلغاء", null).show();
            }).setNegativeButton("إلغاء", null).show();
    }

    // ═══════════════════════════════════════════
    // فحص السلامة
    // ═══════════════════════════════════════════
    private void checkIntegrity(Context ctx) {
        ForwarderHashDatabase.ForwarderStats s = hashDb.getStats();
        String status = s.integrityOk ? "✅ قاعدة البيانات سليمة تماماً" : "⚠️ قاعدة البيانات بها مشاكل!";
        new AlertDialog.Builder(ctx).setTitle("🔍 فحص السلامة").setMessage(status +
            "\n\nالحجم: " + s.formatSize() + "\nالهاشات: " + String.format("%,d", s.totalHashes) +
            "\nWAL: " + s.journalMode)
            .setPositiveButton("حسناً", null).show();
    }

    // ═══════════════════════════════════════════
    // إصلاح
    // ═══════════════════════════════════════════
    private void doRepair(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🛠️ إصلاح DB")
            .setMessage("سيقوم بـ:\n• تصحيح hash_type للسجلات الخاطئة\n• حذف indexes مكررة\n• VACUUM لتقليص الحجم\n\n✅ آمن — لا حذف للهاشات")
            .setPositiveButton("ابدأ", (d, w) -> {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    ForwarderHashDatabase.RepairResult r = hashDb.repairDatabase();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (r.success) {
                            new AlertDialog.Builder(ctx).setTitle("✅ اكتمل الإصلاح")
                                .setMessage("🔖 hash_type مُصحَّح: " + r.fixedTypes +
                                    "\n🗂️ Index مكرر: " + (r.removedDuplicateIndex ? "حُذف" : "لا يوجد") +
                                    "\n📦 VACUUM: " + (r.vacuumed ? "✅" : "❌") +
                                    "\n💡 وُفِّر: " + r.formatSaved())
                                .setPositiveButton("حسناً", null).show();
                        } else {
                            new AlertDialog.Builder(ctx).setTitle("❌ فشل").setMessage(r.error)
                                .setPositiveButton("حسناً", null).show();
                        }
                    });
                });
            }).setNegativeButton("إلغاء", null).show();
    }

    // ═══════════════════════════════════════════
    // تنظيف الهاشات القديمة
    // ═══════════════════════════════════════════
    private void doCleanup(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🧹 حذف القديمة")
            .setMessage("سيحذف الهاشات من أنظمة قديمة:\n• legacy_fp\n• legacy_numeric_id\n• unknown\n\n⚠️ غير قابل للتراجع")
            .setPositiveButton("حذف", (d, w) -> {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    int deleted = hashDb.cleanupLegacyHashes();
                    AndroidUtilities.runOnUIThread(() ->
                        new AlertDialog.Builder(ctx).setTitle(deleted > 0 ? "✅ تم" : "ℹ️ نظيف")
                            .setMessage(deleted > 0 ? "تم حذف " + deleted + " هاش قديم" : "لا توجد هاشات قديمة")
                            .setPositiveButton("حسناً", null).show()
                    );
                });
            }).setNegativeButton("إلغاء", null).show();
    }

    // ═══════════════════════════════════════════
    private void confirmClear(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("🗑️ مسح كل الهاشات")
            .setMessage("⚠️ سيُحذف كل شي!\nغير قابل للتراجع.")
            .setPositiveButton("مسح", (d, w) -> {
                long c = hashDb.clearAll();
                new AlertDialog.Builder(ctx).setTitle("✅ تم").setMessage("تم مسح " + c + " هاش").setPositiveButton("حسناً", null).show();
            }).setNegativeButton("إلغاء", null).show();
    }

    private void enforceOneFilter(View activated, Context ctx) {
        Switch[] all = {switchMediaOnly, switchPhotoOnly, switchVideoOnly, switchAudioOnly, switchGifOnly};
        String[] keys = {PREF_MEDIA_ONLY, PREF_PHOTO_ONLY, PREF_VIDEO_ONLY, PREF_AUDIO_ONLY, PREF_GIF_ONLY};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == activated) continue;
            all[i].setChecked(false);
            prefs(ctx).edit().putBoolean(keys[i], false).apply();
        }
    }

    private void pickDate(Context ctx, boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(ctx, (v, y, m, d) -> {
            Calendar s = Calendar.getInstance();
            s.set(y, m, d, 0, 0, 0);
            long ts = s.getTimeInMillis() / 1000;
            if (isFrom) {
                prefs(ctx).edit().putLong(PREF_FROM_DATE, ts).apply();
                tvFromDate.setText("📅 من: " + fmtDate(ts));
            } else {
                prefs(ctx).edit().putLong(PREF_TO_DATE, ts).apply();
                tvToDate.setText("📅 إلى: " + fmtDate(ts));
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String fmtDate(long ts) {
        try { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts * 1000)); }
        catch (Exception e) { return "—"; }
    }

    // ═══════════════════════════════════════════
    // buildConfig — static
    // ═══════════════════════════════════════════
    public static ForwarderEngine.ForwardConfig buildConfig(Context ctx, long src, long tgt) {
        ForwarderEngine.ForwardConfig c = new ForwarderEngine.ForwardConfig();
        c.sourceId = src; c.targetId = tgt;
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        c.skipDuplicates = p.getBoolean(PREF_SKIP_DUPLICATES, true);
        c.strictDetection = p.getBoolean(PREF_STRICT_DETECTION, true);
        c.dropAuthor = p.getBoolean(PREF_DROP_AUTHOR, false);
        c.dropCaption = p.getBoolean(PREF_DROP_CAPTION, false);
        c.chunkSize = p.getInt(PREF_CHUNK_SIZE, DEFAULT_CHUNK);
        c.delaySeconds = p.getFloat(PREF_DELAY_SECONDS, DEFAULT_DELAY);
        c.maxMessages = p.getInt(PREF_MAX_MESSAGES, DEFAULT_MAX);
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

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (getParentActivity() != null && editDelay != null) {
            try {
                prefs(getParentActivity()).edit()
                    .putFloat(PREF_DELAY_SECONDS, Float.parseFloat(editDelay.getText().toString()))
                    .putInt(PREF_MAX_MESSAGES, Integer.parseInt(editMaxMessages.getText().toString()))
                    .apply();
            } catch (Exception ignored) {}
        }
    }

    // ═══════════════════════════════════════════
    // UI Helpers
    // ═══════════════════════════════════════════
    private void header(LinearLayout root, Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(8);
        root.addView(tv, p);
    }

    private Switch addSwitch(LinearLayout root, Context ctx, String text, String sub, String key, boolean def) {
        Switch sw = new Switch(ctx);
        sw.setText(text);
        sw.setChecked(prefs(ctx).getBoolean(key, def));
        sw.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        sw.setTextSize(15);
        sw.setOnCheckedChangeListener((v, c) -> prefs(ctx).edit().putBoolean(key, c).apply());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        root.addView(sw, p);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(sub); s.setTextSize(12);
            s.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.leftMargin = dp(48); sp.topMargin = dp(2);
            root.addView(s, sp);
        }
        return sw;
    }

    private TextView label(LinearLayout root, Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(14);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        root.addView(tv, p);
        return tv;
    }

    private EditText editText(LinearLayout root, Context ctx, String hint, String val, int type) {
        EditText et = new EditText(ctx);
        et.setHint(hint); et.setText(val); et.setInputType(type);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        et.setTextSize(14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        root.addView(et, p);
        return et;
    }

    private TextView clickable(LinearLayout root, Context ctx, String text, View.OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setPadding(0, dp(10), 0, dp(10));
        tv.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(4);
        root.addView(tv, p);
        return tv;
    }

    private void divider(LinearLayout root, Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 1);
        p.topMargin = dp(14); p.bottomMargin = dp(6);
        root.addView(v, p);
    }

    private int dp(int v) { return AndroidUtilities.dp(v); }
    private android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
}
