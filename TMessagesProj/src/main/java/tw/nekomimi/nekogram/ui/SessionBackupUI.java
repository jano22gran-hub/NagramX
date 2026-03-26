package tw.nekomimi.nekogram.ui;

import android.app.Activity;
import android.net.Uri;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.AlertDialog;
import tw.nekomimi.nekogram.helpers.SessionBackupHelper;

public class SessionBackupUI {

    public static void showBackupDialog(Activity activity) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString("SessionBackup", 
            org.telegram.messenger.R.string.SessionBackup));
        b.setMessage(LocaleController.getString("SessionBackup_desc",
            org.telegram.messenger.R.string.SessionBackup_desc));
        b.setPositiveButton(LocaleController.getString("OK",
            org.telegram.messenger.R.string.OK), (d, w) -> {
            SessionBackupHelper.pickFolder(activity);
        });
        b.setNegativeButton(LocaleController.getString("Cancel",
            org.telegram.messenger.R.string.Cancel), null);
        b.show();
    }

    public static void handleBackupResult(Activity activity, Uri uri) {
        SessionBackupHelper.handleBackupResult(activity, uri);
    }
}
