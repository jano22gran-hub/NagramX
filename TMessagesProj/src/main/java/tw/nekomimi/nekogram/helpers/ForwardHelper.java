package tw.nekomimi.nekogram.helpers;

import org.telegram.tgnet.TLRPC;
import tw.nekomimi.nekogram.NekoConfig;

public class ForwardHelper {

    public static boolean canForward(Object messageObject) {
        if (messageObject == null) return false;
        if (NekoConfig.bypassNoForwards.Bool()) return true;
        try {
            java.lang.reflect.Field f = messageObject.getClass().getField("messageOwner");
            Object owner = f.get(messageObject);
            java.lang.reflect.Field nf = owner.getClass().getField("noforwards");
            return !((boolean) nf.get(owner));
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isChatNoForwards(TLRPC.Chat chat) {
        if (chat == null) return false;
        if (NekoConfig.bypassNoForwards.Bool()) return false;
        return chat.noforwards;
    }
}