package tw.nekomimi.nekogram.helpers;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;

public class GhostExclusionsDB extends SQLiteOpenHelper {

    private static GhostExclusionsDB inst;
    public static GhostExclusionsDB get() {
        if (inst == null) inst = new GhostExclusionsDB();
        return inst;
    }

    private GhostExclusionsDB() {
        super(ApplicationLoader.applicationContext, "nagram_ghost.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS exception_users(" +
            "did INTEGER NOT NULL," +
            "exception_reading INTEGER DEFAULT 0 NOT NULL," +
            "exception_typing INTEGER DEFAULT 0 NOT NULL," +
            "PRIMARY KEY(did))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exception_users_did ON exception_users(did)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) { onCreate(db); }

    public void setExclusion(long did, boolean exReading, boolean exTyping) {
        ContentValues v = new ContentValues();
        v.put("did", did);
        v.put("exception_reading", exReading ? 1 : 0);
        v.put("exception_typing",  exTyping  ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("exception_users",
            null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean isReadingExcluded(long did) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT exception_reading FROM exception_users WHERE did=?",
            new String[]{String.valueOf(did)});
        if (c != null && c.moveToFirst()) {
            boolean r = c.getInt(0) == 1; c.close(); return r;
        }
        return false;
    }

    public boolean isTypingExcluded(long did) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT exception_typing FROM exception_users WHERE did=?",
            new String[]{String.valueOf(did)});
        if (c != null && c.moveToFirst()) {
            boolean r = c.getInt(0) == 1; c.close(); return r;
        }
        return false;
    }
}