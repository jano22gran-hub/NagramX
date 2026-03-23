package tw.nekomimi.nekogram.helpers.deletedmessages;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import java.util.ArrayList;
import java.util.List;
import tw.nekomimi.nekogram.NekoConfig;

public class DeletedMessagesManager {

    private static final DeletedMessagesDatabase db =
        DeletedMessagesDatabase.getInstance();

    /**
     * استدعيه في MessagesStorage.java قبل الحذف:
     * if (NekoConfig.saveDeletedMessages.Bool()) {
     *     DeletedMessagesManager.onMessagesDeleted(did, mids, cachedMessages);
     * }
     */
    public static void onMessagesDeleted(long did,
            List<Integer> mids, ArrayList<MessageObject> cached) {
        if (!NekoConfig.saveDeletedMessages.Bool()) return;
        for (int mid : mids) {
            MessageObject msg = find(cached, mid);
            if (msg == null) continue;
            db.saveDeleted(did, mid,
                msg.messageOwner.message,
                msg.messageOwner.from_id != null ?
                    msg.messageOwner.from_id.user_id : 0L,
                msg.messageOwner.post_author,
                msg.messageOwner.date,
                msg.messageOwner.media != null,
                "", getMediaType(msg));
        }
    }

    /**
     * استدعيه في MessagesStorage.java عند تحرير رسالة:
     * if (NekoConfig.saveDeletedMessages.Bool()) {
     *     DeletedMessagesManager.onMessageEdited(did, mid, ver, date, rawData);
     * }
     */
    public static void onMessageEdited(long did, int mid,
            int ver, int date, byte[] data) {
        if (!NekoConfig.saveDeletedMessages.Bool()) return;
        db.saveEdit(did, mid, ver, date, data);
    }

    public static boolean isDeleted(long did, int mid) {
        return db.isDeleted(did, mid);
    }

    public static void clearDeleted(long did) { db.clearDeleted(did); }

    private static MessageObject find(ArrayList<MessageObject> list, int mid) {
        if (list == null) return null;
        for (MessageObject m : list) if (m.getId() == mid) return m;
        return null;
    }

    private static String getMediaType(MessageObject m) {
        if (m.isPhoto())      return "photo";
        if (m.isVideo())      return "video";
        if (m.isVoice())      return "voice";
        if (m.isRoundVideo()) return "round";
        if (m.isSticker())    return "sticker";
        if (m.isGif())        return "gif";
        if (m.isDocument())   return "document";
        return "unknown";
    }
}