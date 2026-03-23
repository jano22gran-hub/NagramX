package tw.nekomimi.nekogram.helpers.deletedmessages;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public class DeletedMessagesDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME    = "nagram_deleted.db";
    private static final int    DB_VERSION = 2;

    private static final String SQL_DELETED =
        "CREATE TABLE IF NOT EXISTS deleted_keys(" +
        "did INTEGER NOT NULL," +
        "mid INTEGER NOT NULL," +
        "ts  INTEGER NOT NULL," +
        "text TEXT," +
        "from_id INTEGER," +
        "from_name TEXT," +
        "has_media INTEGER DEFAULT 0," +
        "media_path TEXT," +
        "media_type TEXT," +
        "PRIMARY KEY(did, mid))";

    private static final String SQL_EDITS =
        "CREATE TABLE IF NOT EXISTS message_edits(" +
        "did  INTEGER NOT NULL," +
        "mid  INTEGER NOT NULL," +
        "ver  INTEGER NOT NULL," +
        "date INTEGER NOT NULL," +
        "data BLOB NOT NULL," +
        "PRIMARY KEY(did, mid, ver))";

    private static DeletedMessagesDatabase instance;
    public static DeletedMessagesDatabase getInstance() {
        if (instance == null)
            instance = new DeletedMessagesDatabase(ApplicationLoader.applicationContext);
        return instance;
    }

    private DeletedMessagesDatabase(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_DELETED);
        db.execSQL(SQL_EDITS);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_did ON deleted_keys(did)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_ts  ON deleted_keys(ts)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edits_did_mid ON message_edits(did, mid)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        db.execSQL("DROP TABLE IF EXISTS deleted_keys");
        db.execSQL("DROP TABLE IF EXISTS message_edits");
        onCreate(db);
    }

    public void saveDeleted(long did, int mid, String text,
                             long fromId, String fromName, int date,
                             boolean hasMedia, String mediaPath, String mediaType) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("did", did); v.put("mid", mid);
            v.put("ts", System.currentTimeMillis() / 1000);
            v.put("text", text); v.put("from_id", fromId);
            v.put("from_name", fromName);
            v.put("has_media", hasMedia ? 1 : 0);
            v.put("media_path", mediaPath); v.put("media_type", mediaType);
            db.insertWithOnConflict("deleted_keys", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) { FileLog.e(e); }
    }

    public void saveEdit(long did, int mid, int ver, int date, byte[] data) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("did", did); v.put("mid", mid);
            v.put("ver", ver); v.put("date", date); v.put("data", data);
            db.insertWithOnConflict("message_edits", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) { FileLog.e(e); }
    }

    public boolean isDeleted(long did, int mid) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM deleted_keys WHERE did=? AND mid=? LIMIT 1",
                new String[]{String.valueOf(did), String.valueOf(mid)});
            boolean r = c != null && c.moveToFirst();
            if (c != null) c.close();
            return r;
        } catch (Exception e) { return false; }
    }

    public void clearDeleted(long did) {
        try { getWritableDatabase().delete("deleted_keys",
            "did=?", new String[]{String.valueOf(did)}); }
        catch (Exception e) { FileLog.e(e); }
    }
}