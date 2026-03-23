package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.view.WindowManager;
import tw.nekomimi.nekogram.NekoConfig;

public class ScreenshotHelper {

    /** يطبق الإعداد — استدعيه في onResume() */
    public static void apply(Activity activity) {
        if (activity == null) return;
        if (NekoConfig.allowScreenshots.Bool()) {
            activity.getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            );
        } else {
            activity.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            );
        }
    }
}