package org.telegram.messenger.NagramX.forwarder;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

public class ForwarderChatButton {

    public static void startForwardFlow(BaseFragment fragment, long sourceDialogId) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        openChatSelection(fragment, sourceDialogId);
    }

    private static void openChatSelection(BaseFragment fragment, long sourceId) {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", 0);
        args.putBoolean("allowGlobalSearch", true);

        DialogsActivity da = new DialogsActivity(args);
        da.setDelegate((parentFragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;
            long targetId = dids.get(0).dialogId;
            if (sourceId == targetId) {
                Toast.makeText(fragment.getParentActivity(), "لا يمكن التحويل إلى نفس الدردشة", Toast.LENGTH_SHORT).show();
                return false;
            }
            parentFragment.finishFragment();
            AndroidUtilities.runOnUIThread(() -> showConfirmDialog(fragment, sourceId, targetId));
            return true;
        });
        fragment.presentFragment(da);
    }

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

        // المصدر
        addInfoRow(layout, ctx, "المصدر", "📥  " + getChatName(mc, ids[0]));
        android.widget.TextView btnSrc = addLink(layout, ctx, "🔄 تغيير المصدر");

        // الهدف
        addInfoRow(layout, ctx, "الهدف", "📤  " + getChatName(mc, ids[1]));
        android.widget.TextView btnTgt = addLink(layout, ctx, "🔄 تغيير الهدف");

        // ملخص
        int chunk = prefs.getInt(ForwarderSettingsActivity.PREF_CHUNK_SIZE, 50);
        float delay = prefs.getFloat(ForwarderSettingsActivity.PREF_DELAY_SECONDS, 3f);
        boolean dup = prefs.getBoolean(ForwarderSettingsActivity.PREF_SKIP_DUPLICATES, true);
        boolean lb = prefs.getBoolean(ForwarderSettingsActivity.PREF_LARGE_BATCH, false);
        boolean rev = prefs.getBoolean(ForwarderSettingsActivity.PREF_REVERSE_ORDER, false);

        String summary = "📦 دفعة: " + chunk + "  •  ⏱ " + delay + "s  •  🔐 " + (dup ? "✓" : "✗");
        if (lb) {
            int lbs = prefs.getInt(ForwarderSettingsActivity.PREF_LARGE_BATCH_SIZE, 2000);
            float lbd = prefs.getFloat(ForwarderSettingsActivity.PREF_LARGE_BATCH_DELAY, 60f);
            summary += "\n📦 وجبة كبيرة: " + lbs + " رسالة / " + (int)lbd + "s";
        }
        if (rev) summary += "  •  🔄 عكسي";

        android.widget.TextView tvSum = new android.widget.TextView(ctx);
        tvSum.setText(summary);
        tvSum.setTextSize(12);
        tvSum.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = AndroidUtilities.dp(12);
        layout.addView(tvSum, sp);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("🔄 تحويل التاريخ");
        builder.setView(layout);

        final AlertDialog[] dlg = {null};
        builder.setPositiveButton("🚀 ابدأ", (d, w) -> { d.dismiss(); startEngine(fragment, ids[0], ids[1], ctx); });
        builder.setNeutralButton("⚙️ الإعدادات", (d, w) -> { d.dismiss(); fragment.presentFragment(new ForwarderSettingsActivity()); });
        builder.setNegativeButton("إلغاء", null);
        dlg[0] = builder.show();

        btnSrc.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); changeChat(fragment, ids, 0); });
        btnTgt.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); changeChat(fragment, ids, 1); });
    }

    private static void changeChat(BaseFragment fragment, long[] ids, int index) {
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", 0);
        DialogsActivity da = new DialogsActivity(args);
        da.setDelegate((pf, dids, msg, param, notify, sd, srp, tf) -> {
            if (dids == null || dids.isEmpty()) return false;
            ids[index] = dids.get(0).dialogId;
            if (ids[0] == ids[1]) {
                Toast.makeText(fragment.getParentActivity(), "المصدر والهدف لا يمكن أن يكونا نفس الدردشة", Toast.LENGTH_SHORT).show();
                return false;
            }
            pf.finishFragment();
            AndroidUtilities.runOnUIThread(() -> showConfirmDialog(fragment, ids[0], ids[1]));
            return true;
        });
        fragment.presentFragment(da);
    }

    // ==========================================
    // startEngine — مع progress محسّن
    // ==========================================
    private static void startEngine(BaseFragment fragment, long sourceId, long targetId, Context ctx) {
        ForwarderHashDatabase hashDb = ForwarderHashDatabase.getInstance();
        ForwarderEngine engine = new ForwarderEngine(hashDb);
        ForwarderEngine.ForwardConfig config = ForwarderSettingsActivity.buildConfig(ctx, sourceId, targetId);

        // بناء Progress Dialog محسّن
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        layout.setPadding(pad, pad, pad, pad);

        // حالة
        android.widget.TextView tvStatus = new android.widget.TextView(ctx);
        tvStatus.setText("جاري التحضير...");
        tvStatus.setTextSize(15);
        tvStatus.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        layout.addView(tvStatus);

        // شريط تقدم
        android.widget.ProgressBar bar = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        android.widget.LinearLayout.LayoutParams bp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = AndroidUtilities.dp(12);
        layout.addView(bar, bp);

        // نسبة + ETA
        android.widget.TextView tvPercent = new android.widget.TextView(ctx);
        tvPercent.setText("0%");
        tvPercent.setTextSize(13);
        tvPercent.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        android.widget.LinearLayout.LayoutParams pp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = AndroidUtilities.dp(6);
        layout.addView(tvPercent, pp);

        // تفاصيل (مكرر + سرعة)
        android.widget.TextView tvDetails = new android.widget.TextView(ctx);
        tvDetails.setText("");
        tvDetails.setTextSize(12);
        tvDetails.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        android.widget.LinearLayout.LayoutParams dp2 = new android.widget.LinearLayout.LayoutParams(-1, -2);
        dp2.topMargin = AndroidUtilities.dp(4);
        layout.addView(tvDetails, dp2);

        AlertDialog.Builder pb = new AlertDialog.Builder(ctx);
        pb.setTitle("🔄 تحويل الرسائل");
        pb.setView(layout);

        final long[] engineStartTime = {SystemClock.elapsedRealtime()};
        final boolean[] isPaused = {false};

        pb.setPositiveButton("⏸ توقف", (d, w) -> {
            if (!isPaused[0]) { engine.pause(); isPaused[0] = true; }
            else { engine.resume(); isPaused[0] = false; }
        });
        pb.setNegativeButton("❌ إلغاء", (d, w) -> { engine.stop(); d.dismiss(); });
        pb.setNeutralButton("🔽 خلفية", (d, w) -> d.dismiss());

        AlertDialog[] progressDlg = {pb.show()};
        progressDlg[0].setCancelable(false);

        engine.start(config, new ForwarderEngine.ProgressCallback() {
            @Override
            public void onProgress(int sent, int total, String statusMsg) {
                AndroidUtilities.runOnUIThread(() -> {
                    tvStatus.setText(statusMsg);
                    if (total > 0) {
                        bar.setMax(total);
                        bar.setProgress(sent);

                        // نسبة مئوية
                        int pct = (int)((sent * 100.0) / total);
                        long elapsed = SystemClock.elapsedRealtime() - engineStartTime[0];

                        // ETA
                        String eta = "—";
                        if (sent > 0 && elapsed > 2000) {
                            double speed = sent * 1000.0 / elapsed;
                            int remaining = total - sent;
                            long etaSec = (long)(remaining / speed);
                            if (etaSec > 60) eta = (etaSec / 60) + ":" + String.format("%02d", etaSec % 60) + " د";
                            else eta = etaSec + " ث";
                        }

                        tvPercent.setText(pct + "% — متبقي: " + eta);

                        // سرعة
                        if (elapsed > 2000) {
                            double speed = sent * 1000.0 / elapsed;
                            tvDetails.setText(String.format("⚡ %.1f رسالة/ث", speed));
                        }
                    }

                    // تحديث زر التوقف
                    if (progressDlg[0] != null) {
                        try {
                            progressDlg[0].getButton(AlertDialog.BUTTON_POSITIVE)
                                .setText(isPaused[0] ? "▶️ استمرار" : "⏸ توقف");
                        } catch (Exception ignored) {}
                    }
                });
            }

            @Override
            public void onComplete(int totalSent, int totalSkipped, long durationMs) {
                AndroidUtilities.runOnUIThread(() -> {
                    dismiss(progressDlg);
                    double secs = durationMs / 1000.0;
                    String speed = secs > 0 ? String.format("%.1f", totalSent / secs) : "0";

                    String report =
                        "📥 المصدر: " + getChatName(MessagesController.getInstance(config.account), sourceId) + "\n" +
                        "📤 الهدف: " + getChatName(MessagesController.getInstance(config.account), targetId) + "\n" +
                        "━━━━━━━━━━━━━━━━━\n" +
                        "✅ تم تحويل: " + String.format("%,d", totalSent) + " رسالة\n" +
                        "⏭️ تم تخطي: " + String.format("%,d", totalSkipped) + " مكرر\n" +
                        "⚡ السرعة: " + speed + " رسالة/ث\n" +
                        "⏱️ المدة: " + formatDuration(durationMs);

                    new AlertDialog.Builder(ctx)
                        .setTitle("✅ اكتمل التحويل")
                        .setMessage(report)
                        .setPositiveButton("حسناً", null).show();
                });
            }

            @Override
            public void onError(String errorMsg) {
                AndroidUtilities.runOnUIThread(() -> {
                    dismiss(progressDlg);
                    new AlertDialog.Builder(ctx).setTitle("❌ خطأ").setMessage(errorMsg)
                        .setPositiveButton("حسناً", null).show();
                });
            }

            @Override
            public void onRestricted(String chatName) {
                AndroidUtilities.runOnUIThread(() -> {
                    dismiss(progressDlg);
                    new AlertDialog.Builder(ctx).setTitle("🚫 محظور")
                        .setMessage("لا يمكن التحويل من:\n" + chatName + "\n\nهذه الدردشة تمنع إعادة التوجيه.")
                        .setPositiveButton("حسناً", null).show();
                });
            }
        });
    }

    // ==========================================
    // Helpers
    // ==========================================
    private static void dismiss(AlertDialog[] dlg) {
        if (dlg[0] != null && dlg[0].isShowing()) {
            try { dlg[0].dismiss(); } catch (Exception ignored) {}
        }
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + " ثانية";
        long min = sec / 60;
        sec = sec % 60;
        if (min < 60) return min + ":" + String.format("%02d", sec) + " دقيقة";
        long hr = min / 60;
        min = min % 60;
        return hr + ":" + String.format("%02d", min) + ":" + String.format("%02d", sec);
    }

    private static void addInfoRow(android.widget.LinearLayout layout, Context ctx, String label, String value) {
        android.widget.TextView lbl = new android.widget.TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(12);
        lbl.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        layout.addView(lbl);

        android.widget.TextView val = new android.widget.TextView(ctx);
        val.setText(value);
        val.setTextSize(15);
        val.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        android.widget.LinearLayout.LayoutParams vp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        vp.bottomMargin = AndroidUtilities.dp(8);
        layout.addView(val, vp);
    }

    private static android.widget.TextView addLink(android.widget.LinearLayout layout, Context ctx, String text) {
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        layout.addView(tv);
        return tv;
    }

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
