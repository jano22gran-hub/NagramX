package tw.nekomimi.nekogram.helpers;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import java.util.ArrayList;
import java.util.regex.Pattern;
import tw.nekomimi.nekogram.NekoConfig;

public class RegexFilterHelper extends SQLiteOpenHelper {

    private static RegexFilterHelper inst;
    public static RegexFilterHelper get() {
        if (inst == null) inst = new RegexFilterHelper();
        return inst;
    }

    private RegexFilterHelper() {
        super(ApplicationLoader.applicationContext, "nagram_filters.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS regex_filters(" +
            "regex TEXT NOT NULL, PRIMARY KEY(regex))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    public void addFilter(String regex) {
        try {
            Pattern.compile(regex);
            getWritableDatabase().execSQL(
                "INSERT OR IGNORE INTO regex_filters(regex) VALUES(?)",
                new Object[]{regex});
        } catch (Exception e) {
            FileLog.e("Invalid regex: " + e.getMessage());
        }
    }

    public void removeFilter(String regex) {
        getWritableDatabase().execSQL(
            "DELETE FROM regex_filters WHERE regex=?",
            new Object[]{regex});
    }

    public ArrayList<String> getAll() {
        ArrayList<String> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT regex FROM regex_filters ORDER BY regex ASC", null);
        if (c != null) {
            while (c.moveToNext()) list.add(c.getString(0));
            c.close();
        }
        return list;
    }

    public boolean isFiltered(String messageText) {
        if (!NekoConfig.enableFilters.Bool()) return false;
        if (messageText == null) return false;
        for (String regex : getAll()) {
            try {
                if (Pattern.compile(regex).matcher(messageText).find())
                    return true;
            } catch (Exception ignore) {}
        }
        return false;
    }
}