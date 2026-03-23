package tw.nekomimi.nekogram.helpers;

import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;

public class GhostModeHelper {

    private static final String PREF_FILE          = "nagram_ghost";
    private static final String KEY_ENABLED        = "ghost_mode_enabled";
    private static final String KEY_HIDE_ONLINE    = "ghost_hide_online";
    private static final String KEY_HIDE_READING   = "ghost_hide_reading";
    private static final String KEY_HIDE_TYPING    = "ghost_hide_typing";
    private static final String KEY_IMMED_OFFLINE  = "ghost_immediate_offline";
    private static final String KEY_NO_STORIES     = "ghost_no_read_stories";
    private static final String KEY_SEND_SILENCE   = "send_silence";

    public static final String K_HIDE_ONLINE   = KEY_HIDE_ONLINE;
    public static final String K_HIDE_READING  = KEY_HIDE_READING;
    public static final String K_HIDE_TYPING   = KEY_HIDE_TYPING;
    public static final String K_IMMED_OFFLINE = KEY_IMMED_OFFLINE;
    public static final String K_NO_STORIES    = KEY_NO_STORIES;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
            .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
    }

    public static void toggleGhostMode() {
        boolean cur = isEnabled();
        prefs().edit().putBoolean(KEY_ENABLED, !cur).apply();
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public static boolean shouldHideOnline() {
        return isEnabled() && prefs().getBoolean(KEY_HIDE_ONLINE, true);
    }

    public static boolean shouldHideReading() {
        return isEnabled() && prefs().getBoolean(KEY_HIDE_READING, true);
    }

    public static boolean shouldHideTyping() {
        return isEnabled() && prefs().getBoolean(KEY_HIDE_TYPING, true);
    }

    public static boolean shouldGoOfflineImmediately() {
        return isEnabled() && prefs().getBoolean(KEY_IMMED_OFFLINE, false);
    }

    public static boolean shouldHideStoryRead() {
        return isEnabled() && prefs().getBoolean(KEY_NO_STORIES, false);
    }

    public static int getSendSilence() {
        return prefs().getInt(KEY_SEND_SILENCE, 0);
    }

    public static void setSendSilence(int value) {
        prefs().edit().putInt(KEY_SEND_SILENCE, value).apply();
    }

    public static void set(String key, boolean val) {
        prefs().edit().putBoolean(key, val).apply();
    }
}