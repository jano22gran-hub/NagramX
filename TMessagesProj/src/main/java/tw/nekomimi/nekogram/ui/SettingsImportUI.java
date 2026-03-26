package tw.nekomimi.nekogram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.AlertDialog;
import tw.nekomimi.nekogram.helpers.SessionImportHelper;

public class SettingsImportUI {

    public static void showImportDialog(Activity activity) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString("ImportSession",
            org.telegram.messenger.R.string.ImportSession));
        b.setMessage("استيراد جلسة كحساب ثاني (multi-account)");
        b.setPositiveButton(LocaleController.getString("OK",
            org.telegram.messenger.R.string.OK), (d, w) -> {
            SessionImportHelper.pendingMode = SessionImportHelper.ImportMode.IMPORT_AS_SECOND;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(i, SessionImportHelper.REQUEST_PICK);
        });
        b.setNegativeButton(LocaleController.getString("Cancel",
            org.telegram.messenger.R.string.Cancel), null);
        b.show();
    }

    public static void handleImportResult(Activity activity, Uri uri) {
        org.telegram.messenger.FileLog.d("NagramX: Import as second account from " + uri);
    }
}
