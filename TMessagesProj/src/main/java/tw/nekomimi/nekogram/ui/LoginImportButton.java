package tw.nekomimi.nekogram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import tw.nekomimi.nekogram.helpers.SessionImportHelper;

public class LoginImportButton {

    public static View createImportButton(Context context, Activity activity) {
        TextView btn = new TextView(context);
        btn.setText(LocaleController.getString("ImportSessionLogin",
            org.telegram.messenger.R.string.ImportSessionLogin));
        btn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btn.setPadding(0, 0, 0, 0);
        btn.setOnClickListener(v -> {
            SessionImportHelper.pendingMode = SessionImportHelper.ImportMode.IMPORT_AS_FIRST;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(i, SessionImportHelper.REQUEST_PICK);
        });
        return btn;
    }

    public static void handleImportResult(Activity activity, Uri uri) {
        // Copy tgnet.dat + userconfing.xml from picked folder to app files
        try {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,
                android.provider.DocumentsContract.getTreeDocumentId(uri));
            org.telegram.messenger.FileLog.d("NagramX: Session import from " + uri);
            // Restart app after import
            Intent restart = activity.getPackageManager()
                .getLaunchIntentForPackage(activity.getPackageName());
            if (restart != null) {
                restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                activity.startActivity(restart);
            }
            activity.finish();
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
