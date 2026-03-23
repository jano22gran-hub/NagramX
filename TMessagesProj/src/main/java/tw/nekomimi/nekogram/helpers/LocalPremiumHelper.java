package tw.nekomimi.nekogram.helpers;

import tw.nekomimi.nekogram.NekoConfig;

public class LocalPremiumHelper {

    /**
     * NagramX Local Premium
     * استدعيه في UserConfig.java في دالة isPremium():
     *
     * public boolean isPremium() {
     *     if (LocalPremiumHelper.isLocalPremiumEnabled()) return true;
     *     return currentUser != null && currentUser.premium;
     * }
     */
    public static boolean isLocalPremiumEnabled() {
        return NekoConfig.localPremium.Bool();
    }
}