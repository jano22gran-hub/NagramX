package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SessionBackupHelper {

    public static final int REQUEST_PICK_FOLDER = 7001;

    public static void pickFolder(Activity activity) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                   Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(i, REQUEST_PICK_FOLDER);
    }

    public static void handleBackupResult(Activity activity, Uri treeUri) {
        try {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            );
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
                .format(new Date());
            String folderName = "NagramX_Session_" + stamp;

            Uri folderUri = android.provider.DocumentsContract.createDocument(
                activity.getContentResolver(), 
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)),
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                folderName);

            File filesDir = ApplicationLoader.applicationContext.getFilesDir();
            copyFileTo(activity, new File(filesDir, "tgnet.dat"), folderUri, "tgnet.dat");

            File prefsDir = new File(filesDir.getParent(), "shared_prefs");
            copyFileTo(activity, new File(prefsDir, "userconfing.xml"), folderUri, "userconfing.xml");

            FileLog.d("NagramX: Session backup done -> " + folderName);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void copyFileTo(Activity activity, File src, Uri destFolder, String name) throws Exception {
        if (!src.exists()) return;
        Uri destUri = android.provider.DocumentsContract.createDocument(
            activity.getContentResolver(), destFolder, "*/*", name);
        try (FileInputStream in = new FileInputStream(src);
             ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(destUri, "w");
             OutputStream out = new java.io.FileOutputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}