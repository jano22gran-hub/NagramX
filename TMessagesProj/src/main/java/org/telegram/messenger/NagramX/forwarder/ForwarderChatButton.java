package org.telegram.messenger.NagramX.forwarder;

import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

/**
 * ForwarderChatButton — زر التحويل في الدردشة
 *
 * الإصلاحات المطبقة:
 * ✅ Singleton hashDb — بدل new ForwarderHashDatabase() كل مرة
 * ✅ static buildConfig — بدل إنشاء ForwarderSettingsActivity instance
 * ✅ cleanup في كل مسارات الخروج
 */
public class ForwarderChatButton {

    // ==========================================
    // startForwardFlow — نقطة الدخول
    // ==========================================
    public static void startForwardFlow(BaseFragment fragment, long sourceDialogId) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        openChatSelection(fragment, sourceDialogId);
    }

    // ==========================================
    // openChatSelection
    // ==========================================
    private static void openChatSelection(BaseFragment fragment, long sourceId) {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", 0);
        args.putBoolean("allowGlobalSearch", true);

        DialogsActivity dialogsActivity = new DialogsActivity(args);
        dialogsActivity.setDelegate((parentFragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;

            long targetId = dids.get(0).dialogId;

            if (sourceId == targetId) {
                Toast.makeText(fragment.getParentActivity(),
                    "لا يمكن التحويل إلى نفس الدردشة", Toast.LENGTH_SHORT).show();
                return false;
            }

            parentFragment.finishFragment();

            AndroidUtilities.runOnUIThread(() ->
                showConfirmDialog(fragment, sourceId, targetId)
            );
            return true;
        });

        fragment.presentFragment(dialogsActivity);
    }

    // ==========================================
    // showConfirmDialog
    // ==========================================
    private static void showConfirmDialog(BaseFragment fragment, long sourceId, long targetId) {
        Context ctx = fragment.getParentActivity();
        if (ctx == null) return;

        MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);

        final long[] ids = {sourceId, targetId};

        android.content.SharedPreferences prefs = ctx.getSharedPreferences(
            ForwarderSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(16);
        layout.setPadding(pad, pad, pad, pad);

        // --- معلومات المصدر ---
        android.widget.TextView srcLabel = new android.widget.TextView(ctx);
        srcLabel.setTextSize(12);
        srcLabel.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayText));
        srcLabel.setText("المصدر");
        layout.addView(srcLabel);

        android.widget.TextView srcName = new android.widget.TextView(ctx);
        srcName.setTextSize(15);
        srcName.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
        srcName.setText("📥  " + getChatName(mc, ids[0]));
        android.widget.LinearLayout.LayoutParams srcP =
            new android.widget.LinearLayout.LayoutParams(-1, -2);
        srcP.bottomMargin = AndroidUtilities.dp(8);
        layout.addView(srcName, srcP);

        // زر تغيير المصدر
        android.widget.TextView btnChangeSrc = new android.widget.TextView(ctx);
        btnChangeSrc.setText("🔄 تغيير المصدر");
        btnChangeSrc.setTextSize(13);
        btnChangeSrc.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueText));
        btnChangeSrc.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        layout.addView(btnChangeSrc);

        // --- معلومات الهدف ---
        android.widget.TextView tgtLabel = new android.widget.TextView(ctx);
        tgtLabel.setTextSize(12);
        tgtLabel.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayText));
        tgtLabel.setText("الهدف");
        layout.addView(tgtLabel);

        android.widget.TextView tgtName = new android.widget.TextView(ctx);
        tgtName.setTextSize(15);
        tgtName.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
        tgtName.setText("📤  " + getChatName(mc, ids[1]));
        android.widget.LinearLayout.LayoutParams tgtP =
            new android.widget.LinearLayout.LayoutParams(-1, -2);
        tgtP.bottomMargin = AndroidUtilities.dp(8);
        layout.addView(tgtName, tgtP);

        // زر تغيير الهدف
        android.widget.TextView btnChangeTgt = new android.widget.TextView(ctx);
        btnChangeTgt.setText("🔄 تغيير الهدف");
        btnChangeTgt.setTextSize(13);
        btnChangeTgt.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueText));
        btnChangeTgt.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        layout.addView(btnChangeTgt);

        // --- ملخص الإعدادات ---
        android.widget.TextView settingsSummary = new android.widget.TextView(ctx);
        int chunk   = prefs.getInt(ForwarderSettingsActivity.PREF_CHUNK_SIZE, 50);
        float delay = prefs.getFloat(ForwarderSettingsActivity.PREF_DELAY_SECONDS, 3f);
        boolean dup = prefs.getBoolean(ForwarderSettingsActivity.PREF_SKIP_DUPLICATES, true);
        boolean reverse = prefs.getBoolean(ForwarderSettingsActivity.PREF_REVERSE_ORDER, false);
        settingsSummary.setText(
            "📦 دفعة: " + chunk + "  •  ⏱ تأخير: " + delay + "s  •  🔐 تكرار: " + (dup ? "✓" : "✗") +
            (reverse ? "  •  🔄 عكسي" : ""));
        settingsSummary.setTextSize(12);
        settingsSummary.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
            org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayText));
        layout.addView(settingsSummary);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("🔄 تحويل التاريخ");
        builder.setView(layout);

        final AlertDialog[] dlg = {null};

        builder.setPositiveButton("🚀 ابدأ التحويل", (dialog, which) -> {
            dialog.dismiss();
            startEngine(fragment, ids[0], ids[1], ctx);
        });

        builder.setNeutralButton("⚙️ الإعدادات", (dialog, which) -> {
            dialog.dismiss();
            fragment.presentFragment(new ForwarderSettingsActivity());
        });

        builder.setNegativeButton("إلغاء", null);
        dlg[0] = builder.show();

        // تغيير المصدر
        btnChangeSrc.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            android.os.Bundle args = new android.os.Bundle();
            args.putBoolean("onlySelect", true);
            args.putInt("dialogsType", 0);
            DialogsActivity da = new DialogsActivity(args);
            da.setDelegate((pf, dids, msg, param, notify, sd, srp, tf) -> {
                if (dids == null || dids.isEmpty()) return false;
                ids[0] = dids.get(0).dialogId;
                pf.finishFragment();
                AndroidUtilities.runOnUIThread(() -> showConfirmDialog(fragment, ids[0], ids[1]));
                return true;
            });
            fragment.presentFragment(da);
        });

        // تغيير الهدف
        btnChangeTgt.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            android.os.Bundle args = new android.os.Bundle();
            args.putBoolean("onlySelect", true);
            args.putInt("dialogsType", 0);
            DialogsActivity da = new DialogsActivity(args);
            da.setDelegate((pf, dids, msg, param, notify, sd, srp, tf) -> {
                if (dids == null || dids.isEmpty()) return false;
                ids[1] = dids.get(0).dialogId;
                if (ids[0] == ids[1]) {
                    android.widget.Toast.makeText(ctx,
                        "المصدر والهدف لا يمكن أن يكونا نفس الدردشة",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return false;
                }
                pf.finishFragment();
                AndroidUtilities.runOnUIThread(() -> showConfirmDialog(fragment, ids[0], ids[1]));
                return true;
            });
            fragment.presentFragment(da);
        });
    }

    // ==========================================
    // startEngine — تشغيل ForwarderEngine
    // ✅ إصلاح: Singleton hashDb + static buildConfig
    // ==========================================
    private static void startEngine(BaseFragment fragment, long sourceId, long targetId, Context ctx) {
        // ✅ Singleton بدل new
        ForwarderHashDatabase hashDb = ForwarderHashDatabase.getInstance();
        ForwarderEngine engine = new ForwarderEngine(hashDb);

        // ✅ static buildConfig — بدون إنشاء Fragment
        ForwarderEngine.ForwardConfig config = ForwarderSettingsActivity.buildConfig(ctx, sourceId, targetId);

        // Progress dialog
        AlertDialog[] progressDialog = {null};
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        layout.setPadding(pad, pad, pad, pad);

        android.widget.TextView statusTv = new android.widget.TextView(ctx);
        statusTv.setText("جاري التحضير...");
        statusTv.setTextSize(15);
        layout.addView(statusTv);

        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(ctx,
            null, android.R.attr.progressBarStyleHorizontal);
        android.widget.LinearLayout.LayoutParams pbParams =
            new android.widget.LinearLayout.LayoutParams(-1, -2);
        pbParams.topMargin = AndroidUtilities.dp(12);
        layout.addView(progressBar, pbParams);

        AlertDialog.Builder pBuilder = new AlertDialog.Builder(ctx);
        pBuilder.setTitle("🔄 تحويل الرسائل");
        pBuilder.setView(layout);

        pBuilder.setPositiveButton("توقف مؤقت", (d, w) -> {
            if (engine.isRunning()) engine.pause();
        });
        pBuilder.setNegativeButton("إلغاء", (d, w) -> {
            engine.stop();
            d.dismiss();
        });
        pBuilder.setNeutralButton("في الخلفية", (d, w) -> d.dismiss());

        progressDialog[0] = pBuilder.show();
        progressDialog[0].setCancelable(false);

        // Callback
        engine.start(config, new ForwarderEngine.ProgressCallback() {
            @Override
            public void onProgress(int sent, int total, String msg) {
                AndroidUtilities.runOnUIThread(() -> {
                    statusTv.setText(msg);
                    if (total > 0) {
                        progressBar.setMax(total);
                        progressBar.setProgress(sent);
                    }
                });
            }

            @Override
            public void onComplete(int totalSent, int totalSkipped, long durationMs) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) {
                        progressDialog[0].dismiss();
                    }

                    double secs = durationMs / 1000.0;
                    String speed = secs > 0 ? String.format("%.1f", totalSent / secs) : "0";
                    String report =
                        "📥 المصدر:  " + getChatName(MessagesController.getInstance(config.account), sourceId) + "\n" +
                        "📤 الهدف:   " + getChatName(MessagesController.getInstance(config.account), targetId) + "\n" +
                        "─────────────────────\n" +
                        "✅ تم تحويل: " + String.format("%,d", totalSent) + " رسالة\n" +
                        "⏭️ تم تخطي: " + String.format("%,d", totalSkipped) + " مكرر\n" +
                        "⚡ السرعة:  " + speed + " رسالة/ث";

                    new AlertDialog.Builder(ctx)
                        .setTitle("✅ اكتمل التحويل")
                        .setMessage(report)
                        .setPositiveButton("حسناً", null)
                        .show();
                });
            }

            @Override
            public void onError(String errorMsg) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) {
                        progressDialog[0].dismiss();
                    }
                    new AlertDialog.Builder(ctx)
                        .setTitle("❌ خطأ")
                        .setMessage(errorMsg)
                        .setPositiveButton("حسناً", null)
                        .show();
                });
            }

            @Override
            public void onRestricted(String chatName) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) {
                        progressDialog[0].dismiss();
                    }
                    new AlertDialog.Builder(ctx)
                        .setTitle("🚫 التحويل محظور")
                        .setMessage("لا يمكن التحويل من:\n" + chatName +
                            "\n\nهذه الدردشة تمنع إعادة توجيه رسائلها.")
                        .setPositiveButton("حسناً", null)
                        .show();
                });
            }
        });
    }

    // ==========================================
    // getChatName
    // ==========================================
    private static String getChatName(MessagesController mc, long dialogId) {
        try {
            if (dialogId > 0) {
                TLRPC.User user = mc.getUser(dialogId);
                if (user != null) {
                    String name = (user.first_name != null ? user.first_name : "") +
                                  (user.last_name != null ? " " + user.last_name : "");
                    return name.trim();
                }
            } else {
                TLRPC.Chat chat = mc.getChat(-dialogId);
                if (chat != null) return chat.title;
            }
        } catch (Exception ignored) {}
        return String.valueOf(dialogId);
    }
}
