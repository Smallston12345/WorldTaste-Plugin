package com.haiman233.worldtaste.jeg;

/**
 * JEG（JustEnoughGuide）集成：僅負責兩件事——
 * <ul>
 *   <li>檢測 JEG 是否安裝（供 {@link JegGuideListener} 決定是否註冊指南事件攔截）；</li>
 *   <li>打開 JEG 指南主菜單（大配方菜單的返回按鈕用；反射調用，JEG 未裝時靜默）。</li>
 * </ul>
 *
 * <p>機器配方補全不集成 JEG 的 RecipeCompletable（曾嘗試後棄用）：JEG Build 205 對綁定槽配方
 * 存在循環左移缺陷（填充順序與 GUI 佈局不一致），其補全按鈕也會與自定義補全按鈕重複，
 * 統一走 {@link com.haiman233.worldtaste.guide.RecipeFillMenu} 自定義補全。</p>
 */
public final class JegHook {

    private JegHook() {}

    /** JEG 是否可用（檢測其指南事件類是否存在——本插件的 JEG 集成僅依賴事件與指南打開 API）。 */
    public static boolean available() {
        try {
            Class.forName("com.balugaq.jeg.api.objects.events.GuideEvents");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打開 JEG 指南主菜單（大配方菜單返回用；JEG 未安裝或 API 變更時靜默）。 */
    public static void openGuide(org.bukkit.entity.Player p) {
        try {
            Class<?> clazz = Class.forName("com.balugaq.jeg.utils.GuideUtil");
            Class<?> modeCls = Class.forName("io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode");
            Object mode = io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide.class
                    .getMethod("getDefaultMode").invoke(null);
            clazz.getMethod("openMainMenuAsync", org.bukkit.entity.Player.class, modeCls, int.class)
                    .invoke(null, p, mode, 1);
        } catch (Throwable ignored) {
            // JEG 未安裝或 API 變更：靜默
        }
    }
}
