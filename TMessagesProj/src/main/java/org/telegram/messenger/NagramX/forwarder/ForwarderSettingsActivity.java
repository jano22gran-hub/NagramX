package org.telegram.messenger.NagramX.forwarder;

import android.app.DatePickerDialog;
import android.content.Context;
import android.os.Bundle;
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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * ForwarderSettingsActivity — صفحة الإعدادات
 *
 * الإصلاحات المطبقة:
 * ✅ buildConfig أصبح static — لا حاجة لإنشاء Fragment
 * ✅ إضافة import hashes UI
 * ✅ إضافة date filters (fromDate / toDate)
 * ✅ إضافة reverseOrder toggle
 * ✅ AsyncTask.execute بديل: Executors
 */
public class ForwarderSettingsActivity extends BaseFragment {

    // مفاتيح SharedPreferences
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

    private static final int    DEFAULT_CHUNK_SIZE   = 50;
    private static final float  DEFAULT_DELAY        = 3f;
    private static final int    DEFAULT_MAX_MESSAGES = 30_000;

    // UI references
    private Switch switchSkipDuplicates;
    private Switch switchStrictDetection;
    private Switch switchDropAuthor;
    private Switch switchDropCaption;
    private Switch switchMediaOnly;
    private Switch switchPhotoOnly;
    private Switch switchVideoOnly;
    private Switch switchAudioOnly;
    private Switch switchGifOnly;
    private Switch switchReverseOrder;
    private SeekBar seekbarChunk;
    private TextView labelChunkSize;
    private EditText editDelay;
    private EditText editMaxMessages;
    private TextView tvFromDate;
    private TextView tvToDate;

    // DB reference
    private ForwarderHashDatabase hashDb;

    public ForwarderSettingsActivity() {}

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("إعدادات التحويل");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        hashDb = ForwarderHashDatabase.getInstance();

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int hPad = AndroidUtilities.dp(16);
        int vPad = AndroidUtilities.dp(12);
        layout.setPadding(hPad, vPad, hPad, vPad);
        scrollView.addView(layout);

        // ==========================================
        // قسم: منع التكرار
        // ==========================================
        addSectionHeader(layout, context, "🔐 منع التكرار");

        switchSkipDuplicates = addSwitch(layout, context,
            "تجاهل الوسائط المُحوَّلة مسبقاً",
            "يفحص قاعدة بيانات الهاشات قبل كل تحويل",
            PREF_SKIP_DUPLICATES, true);

        switchStrictDetection = addSwitch(layout, context,
            "فحص صارم (file_reference)",
            "يستخدم file_reference للكشف الأدق عن المكررات",
            PREF_STRICT_DETECTION, true);

        TextView btnDbStats = addClickableText(layout, context, "📊 إحصائيات قاعدة البيانات");
        btnDbStats.setOnClickListener(v -> showDbStats(context));

        TextView btnClearDb = addClickableText(layout, context, "🗑 مسح قاعدة بيانات الهاشات");
        btnClearDb.setOnClickListener(v -> confirmClearDb(context));

        TextView btnExportDb = addClickableText(layout, context, "📤 تصدير قاعدة البيانات");
        btnExportDb.setOnClickListener(v -> exportDb(context));

        // ✅ جديد: استيراد
        TextView btnImportDb = addClickableText(layout, context, "📥 استيراد قاعدة البيانات");
        btnImportDb.setOnClickListener(v -> importDb(context));

        addDivider(layout, context);

        // ==========================================
        // قسم: إعدادات الإرسال
        // ==========================================
        addSectionHeader(layout, context, "⏱ إعدادات الإرسال");

        labelChunkSize = addLabel(layout, context, "حجم الدفعة: " + getPrefs(context).getInt(PREF_CHUNK_SIZE, DEFAULT_CHUNK_SIZE));
        seekbarChunk = new SeekBar(context);
        seekbarChunk.setMax(94);
        seekbarChunk.setProgress(getPrefs(context).getInt(PREF_CHUNK_SIZE, DEFAULT_CHUNK_SIZE) - 1);
        seekbarChunk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                labelChunkSize.setText("حجم الدفعة: " + (p + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                getPrefs(context).edit().putInt(PREF_CHUNK_SIZE, s.getProgress() + 1).apply();
            }
        });
        LinearLayout.LayoutParams sbParams = new LinearLayout.LayoutParams(-1, -2);
        sbParams.topMargin = AndroidUtilities.dp(6);
        layout.addView(seekbarChunk, sbParams);

        editDelay = addEditText(layout, context,
            "التأخير بين الدفعات (ثواني)",
            String.valueOf(getPrefs(context).getFloat(PREF_DELAY_SECONDS, DEFAULT_DELAY)),
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        editMaxMessages = addEditText(layout, context,
            "الحد الأقصى للرسائل",
            String.valueOf(getPrefs(context).getInt(PREF_MAX_MESSAGES, DEFAULT_MAX_MESSAGES)),
            InputType.TYPE_CLASS_NUMBER);

        // ✅ جديد: ترتيب عكسي
        switchReverseOrder = addSwitch(layout, context,
            "ترتيب عكسي (الأحدث أولاً)",
            "يحوّل من الأحدث للأقدم بدل العكس",
            PREF_REVERSE_ORDER, false);

        addDivider(layout, context);

        // ==========================================
        // قسم: خيارات التحويل
        // ==========================================
        addSectionHeader(layout, context, "✂️ خيارات التحويل");

        switchDropAuthor = addSwitch(layout, context,
            "إخفاء المُرسِل الأصلي",
            "يُرسِل الرسائل بدون ذكر المصدر",
            PREF_DROP_AUTHOR, false);

        switchDropCaption = addSwitch(layout, context,
            "إخفاء التعليق (Caption)",
            "يحذف النص المرفق مع الصور والفيديوهات",
            PREF_DROP_CAPTION, false);

        addDivider(layout, context);

        // ==========================================
        // قسم: فلاتر الوسائط
        // ==========================================
        addSectionHeader(layout, context, "🎭 فلاتر الوسائط (اختر واحداً فقط)");

        switchMediaOnly  = addSwitch(layout, context, "وسائط فقط (صور + فيديو)", "", PREF_MEDIA_ONLY,  false);
        switchPhotoOnly  = addSwitch(layout, context, "صور فقط",                  "", PREF_PHOTO_ONLY,  false);
        switchVideoOnly  = addSwitch(layout, context, "فيديو فقط",                "", PREF_VIDEO_ONLY,  false);
        switchAudioOnly  = addSwitch(layout, context, "صوت فقط",                  "", PREF_AUDIO_ONLY,  false);
        switchGifOnly    = addSwitch(layout, context, "GIF فقط",                  "", PREF_GIF_ONLY,    false);

        View.OnClickListener mediaFilterListener = v -> enforceOnlyOneFilter(v, context);
        switchMediaOnly.setOnClickListener(mediaFilterListener);
        switchPhotoOnly.setOnClickListener(mediaFilterListener);
        switchVideoOnly.setOnClickListener(mediaFilterListener);
        switchAudioOnly.setOnClickListener(mediaFilterListener);
        switchGifOnly.setOnClickListener(mediaFilterListener);

        addDivider(layout, context);

        // ==========================================
        // ✅ جديد: فلاتر التاريخ
        // ==========================================
        addSectionHeader(layout, context, "📅 فلاتر التاريخ");

        long savedFrom = getPrefs(context).getLong(PREF_FROM_DATE, 0);
        long savedTo = getPrefs(context).getLong(PREF_TO_DATE, 0);

        tvFromDate = addClickableText(layout, context,
            "📅 من تاريخ: " + (savedFrom > 0 ? formatDate(savedFrom) : "بدون حد"));
        tvFromDate.setOnClickListener(v -> showDatePicker(context, true));

        tvToDate = addClickableText(layout, context,
            "📅 إلى تاريخ: " + (savedTo > 0 ? formatDate(savedTo) : "بدون حد"));
        tvToDate.setOnClickListener(v -> showDatePicker(context, false));

        TextView btnClearDates = addClickableText(layout, context, "🗑 مسح فلاتر التاريخ");
        btnClearDates.setOnClickListener(v -> {
            getPrefs(context).edit().putLong(PREF_FROM_DATE, 0).putLong(PREF_TO_DATE, 0).apply();
            tvFromDate.setText("📅 من تاريخ: بدون حد");
            tvToDate.setText("📅 إلى تاريخ: بدون حد");
        });

        addDivider(layout, context);

        return scrollView;
    }

    // ==========================================
    // enforceOnlyOneFilter
    // ==========================================
    private void enforceOnlyOneFilter(View activated, Context context) {
        Switch[] all = {switchMediaOnly, switchPhotoOnly, switchVideoOnly, switchAudioOnly, switchGifOnly};
        String[] keys = {PREF_MEDIA_ONLY, PREF_PHOTO_ONLY, PREF_VIDEO_ONLY, PREF_AUDIO_ONLY, PREF_GIF_ONLY};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == activated) continue;
            all[i].setChecked(false);
            getPrefs(context).edit().putBoolean(keys[i], false).apply();
        }
    }

    // ==========================================
    // Date Picker
    // ==========================================
    private void showDatePicker(Context context, boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dpd = new DatePickerDialog(context, (view, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day, 0, 0, 0);
            long ts = selected.getTimeInMillis() / 1000;
            if (isFrom) {
                getPrefs(context).edit().putLong(PREF_FROM_DATE, ts).apply();
                tvFromDate.setText("📅 من تاريخ: " + formatDate(ts));
            } else {
                getPrefs(context).edit().putLong(PREF_TO_DATE, ts).apply();
                tvToDate.setText("📅 إلى تاريخ: " + formatDate(ts));
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dpd.show();
    }

    private String formatDate(long ts) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts * 1000));
        } catch (Exception e) {
            return "---";
        }
    }

    // ==========================================
    // DB Operations
    // ==========================================
    private void showDbStats(Context context) {
        ForwarderHashDatabase.ForwarderStats stats = hashDb.getStats();
        String msg =
            "📁 المسار: " + stats.dbPath + "\n" +
            "📊 الهاشات: " + String.format("%,d", stats.totalHashes) + "\n" +
            "💾 الحجم: " + stats.formatSize() + "\n" +
            "🧠 الكاش: " + String.format("%,d", stats.cacheSize) + "\n" +
            "📅 أقدم هاش: " + String.format("%.1f", stats.oldestHashDays) + " يوم\n" +
            (stats.corrupted ? "⚠️ قاعدة البيانات تالفة!" : "✅ قاعدة البيانات سليمة");

        new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
            .setTitle("🔐 إحصائيات قاعدة البيانات")
            .setMessage(msg)
            .setPositiveButton("حسناً", null)
            .show();
    }

    private void confirmClearDb(Context context) {
        new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
            .setTitle("🗑 مسح قاعدة البيانات")
            .setMessage("هل تريد مسح كل الهاشات؟\n⚠️ هذا الإجراء غير قابل للتراجع.")
            .setPositiveButton("مسح", (dialog, which) -> {
                long count = hashDb.clearAll();
                new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                    .setTitle("✅ تم المسح")
                    .setMessage("تم مسح " + count + " هاش")
                    .setPositiveButton("حسناً", null)
                    .show();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    // ✅ إصلاح: بديل AsyncTask.execute
    private void exportDb(Context context) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File exportDir = new File(
                    context.getExternalFilesDir(null), "NagramX_Export");
                //noinspection ResultOfMethodCallIgnored
                exportDir.mkdirs();
                File exportFile = new File(exportDir,
                    "forwarder_hashes_" + System.currentTimeMillis() + ".json");
                boolean ok = hashDb.exportToJson(exportFile);
                AndroidUtilities.runOnUIThread(() -> {
                    new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                        .setTitle(ok ? "✅ تم التصدير" : "❌ فشل التصدير")
                        .setMessage(ok ? "📁 " + exportFile.getAbsolutePath() : "تحقق من صلاحيات الكتابة")
                        .setPositiveButton("حسناً", null)
                        .show();
                });
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() ->
                    new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                        .setTitle("❌ خطأ")
                        .setMessage(e.getMessage())
                        .setPositiveButton("حسناً", null)
                        .show()
                );
            }
        });
    }

    // ✅ جديد: استيراد قاعدة البيانات
    private void importDb(Context context) {
        // ابحث عن ملفات JSON في مجلد التصدير
        File exportDir = new File(context.getExternalFilesDir(null), "NagramX_Export");
        File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS);

        java.util.List<File> jsonFiles = new java.util.ArrayList<>();

        // ابحث في مجلد التصدير
        if (exportDir.exists() && exportDir.isDirectory()) {
            File[] files = exportDir.listFiles((dir, name) ->
                name.endsWith(".json") && (name.contains("hashes") || name.contains("forwarder")));
            if (files != null) {
                for (File f : files) jsonFiles.add(f);
            }
        }

        // ابحث في Downloads
        if (downloadsDir != null && downloadsDir.exists() && downloadsDir.isDirectory()) {
            File[] files = downloadsDir.listFiles((dir, name) ->
                name.endsWith(".json") && (name.contains("hashes") || name.contains("forwarder")));
            if (files != null) {
                for (File f : files) jsonFiles.add(f);
            }
        }

        if (jsonFiles.isEmpty()) {
            new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                .setTitle("📥 استيراد")
                .setMessage("لا توجد ملفات هاشات.\n\nابحث في:\n📁 " +
                    (exportDir.exists() ? exportDir.getAbsolutePath() : "NagramX_Export") +
                    "\n📁 Downloads\n\nضع ملف JSON يحتوي على هاشات في أحد هذه المجلدات.")
                .setPositiveButton("حسناً", null)
                .show();
            return;
        }

        // اعرض قائمة الملفات
        String[] fileNames = new String[jsonFiles.size()];
        for (int i = 0; i < jsonFiles.size(); i++) {
            File f = jsonFiles.get(i);
            long sizeKb = f.length() / 1024;
            fileNames[i] = f.getName() + " (" + sizeKb + " KB)";
        }

        new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
            .setTitle("📥 اختر ملف الاستيراد")
            .setItems(fileNames, (dialog, which) -> {
                File selected = jsonFiles.get(which);
                // تأكيد
                new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                    .setTitle("📥 تأكيد الاستيراد")
                    .setMessage("استيراد من:\n" + selected.getName() + "\n\nسيتم إضافة الهاشات الجديدة فقط.")
                    .setPositiveButton("استيراد", (d2, w2) -> {
                        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                            int added = hashDb.importFromJson(selected);
                            AndroidUtilities.runOnUIThread(() ->
                                new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                                    .setTitle(added > 0 ? "✅ تم الاستيراد" : "ℹ️ لا جديد")
                                    .setMessage(added > 0
                                        ? "تم استيراد " + added + " هاش جديد"
                                        : "لم يتم إضافة أي هاشات جديدة (كلها موجودة مسبقاً)")
                                    .setPositiveButton("حسناً", null)
                                    .show()
                            );
                        });
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    // ==========================================
    // ✅ buildConfig — أصبح static
    // ==========================================
    public static ForwarderEngine.ForwardConfig buildConfig(Context context, long sourceId, long targetId) {
        ForwarderEngine.ForwardConfig cfg = new ForwarderEngine.ForwardConfig();
        cfg.sourceId = sourceId;
        cfg.targetId = targetId;

        android.content.SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cfg.skipDuplicates  = p.getBoolean(PREF_SKIP_DUPLICATES, true);
        cfg.strictDetection = p.getBoolean(PREF_STRICT_DETECTION, true);
        cfg.dropAuthor      = p.getBoolean(PREF_DROP_AUTHOR, false);
        cfg.dropCaption     = p.getBoolean(PREF_DROP_CAPTION, false);
        cfg.chunkSize       = p.getInt(PREF_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
        cfg.delaySeconds    = p.getFloat(PREF_DELAY_SECONDS, DEFAULT_DELAY);
        cfg.maxMessages     = p.getInt(PREF_MAX_MESSAGES, DEFAULT_MAX_MESSAGES);
        cfg.mediaOnly       = p.getBoolean(PREF_MEDIA_ONLY, false);
        cfg.photoOnly       = p.getBoolean(PREF_PHOTO_ONLY, false);
        cfg.videoOnly       = p.getBoolean(PREF_VIDEO_ONLY, false);
        cfg.audioOnly       = p.getBoolean(PREF_AUDIO_ONLY, false);
        cfg.gifOnly         = p.getBoolean(PREF_GIF_ONLY, false);
        cfg.reverseOrder    = p.getBoolean(PREF_REVERSE_ORDER, false);
        cfg.fromDateTs      = p.getLong(PREF_FROM_DATE, 0);
        cfg.toDateTs        = p.getLong(PREF_TO_DATE, 0);
        return cfg;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (getParentActivity() != null && editDelay != null) {
            try {
                float delay = Float.parseFloat(editDelay.getText().toString());
                int max = Integer.parseInt(editMaxMessages.getText().toString());
                getPrefs(getParentActivity()).edit()
                    .putFloat(PREF_DELAY_SECONDS, delay)
                    .putInt(PREF_MAX_MESSAGES, max)
                    .apply();
            } catch (Exception ignored) {}
        }
        // لا نغلق hashDb — هو Singleton
    }

    // ==========================================
    // UI helpers
    // ==========================================
    private void addSectionHeader(LinearLayout layout, Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = AndroidUtilities.dp(16);
        p.bottomMargin = AndroidUtilities.dp(6);
        layout.addView(tv, p);
    }

    private Switch addSwitch(LinearLayout layout, Context ctx, String text, String subtext,
                              String prefKey, boolean defaultValue) {
        Switch sw = new Switch(ctx);
        sw.setText(text);
        sw.setChecked(getPrefs(ctx).getBoolean(prefKey, defaultValue));
        sw.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        sw.setOnCheckedChangeListener((v, checked) ->
            getPrefs(ctx).edit().putBoolean(prefKey, checked).apply());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = AndroidUtilities.dp(8);
        layout.addView(sw, p);
        if (!subtext.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(subtext);
            sub.setTextSize(12);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.leftMargin = AndroidUtilities.dp(40);
            layout.addView(sub, sp);
        }
        return sw;
    }

    private TextView addLabel(LinearLayout layout, Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = AndroidUtilities.dp(12);
        layout.addView(tv, p);
        return tv;
    }

    private EditText addEditText(LinearLayout layout, Context ctx, String hint, String value, int inputType) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setText(value);
        et.setInputType(inputType);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = AndroidUtilities.dp(8);
        layout.addView(et, p);
        return et;
    }

    private TextView addClickableText(LinearLayout layout, Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = AndroidUtilities.dp(4);
        layout.addView(tv, p);
        return tv;
    }

    private void addDivider(LinearLayout layout, Context ctx) {
        View divider = new View(ctx);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 1);
        p.topMargin = AndroidUtilities.dp(12);
        p.bottomMargin = AndroidUtilities.dp(4);
        layout.addView(divider, p);
    }

    private android.content.SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
