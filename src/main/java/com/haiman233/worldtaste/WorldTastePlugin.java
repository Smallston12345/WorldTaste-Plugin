package com.haiman233.worldtaste;

import com.haiman233.worldtaste.load.Setup;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 塵世百味 WorldTaste —— 獨立 Slimefun 附屬插件主類。
 *
 * <p>由原 RSC 腳本版改寫而來：內容仍來自同一組 YAML（已打包進 jar），但加載邏輯與原 JS 腳本
 * 行為全部以原生 Java 實現，不再依賴 RSC 及其 GraalVM 腳本引擎。</p>
 *
 * <p>依賴策略（與 README 的「必須 / 可選」表一致）：</p>
 * <ul>
 *   <li><b>硬依賴</b>：Slimefun、Gastronomicon（美食家）、ExoticGarden（異域花園）。
 *       缺任一個則在控制枱輸出「缺少 xxx，WorldTaste已自動卸載！」並禁用自身，不參與加載。
 *       注意這些仍寫在 plugin.yml 的 {@code softdepend} 而非 {@code depend}——若走 Bukkit 的
 *       depend 機制，插件在 onEnable 前就會被服務器攔截，自定義中文提示將永遠不會出現；
 *       放在 softdepend 還能保證「存在時先於本插件加載」，使內容引用能解析到其物品。</li>
 *   <li><b>軟依賴</b>：Cultivation（農耕工藝）、InfinityExpansion（無盡貪婪）、LogiTech。
 *       缺失僅輸出「缺少 xxx，部分玩法可能無法加載」後繼續加載。
 *       JustEnoughGuide（JEG）雖在 softdepend 中，但其缺失已有完整降級（原版指南渲染），
 *       故不做提示。</li>
 * </ul>
 */
public final class WorldTastePlugin extends JavaPlugin implements SlimefunAddon {

    private static WorldTastePlugin instance;

    /** 硬依賴：{插件名, 中文名}，缺任一即自動卸載。 */
    private static final String[][] HARD_DEPS = {
            {"Slimefun", "粘液科技"},
            {"Gastronomicon", "美食家"},
            {"ExoticGarden", "異域花園"},
    };

    /** 軟依賴：{插件名, 中文名}，缺失僅提示後繼續加載。 */
    private static final String[][] SOFT_DEPS = {
            {"Cultivation", "農耕工藝"},
            {"InfinityExpansion", "無盡貪婪"},
            {"LogiTech", "LogiTech"},
    };

    @Override
    public void onEnable() {
        instance = this;
        WT.plugin = this;
        getLogger().info("塵世百味 開始加載（獨立版）...");
        // 依賴檢查前置：硬依賴缺失時不加載任何內容，直接卸載
        if (!checkDependencies()) return;
        try {
            Setup.loadAll();
            getLogger().info("塵世百味 加載成功");
        } catch (Throwable e) {
            getLogger().severe("塵世百味 加載過程中出現異常: " + e);
            e.printStackTrace();
        }
    }

    /**
     * 啓動期依賴檢查。硬依賴缺失 → 輸出「缺少 xxx，WorldTaste已自動卸載！」並禁用自身，
     * 返回 false 中止加載；軟依賴缺失 → 輸出「缺少 xxx，部分玩法可能無法加載」後繼續。
     */
    private boolean checkDependencies() {
        for (String[] dep : HARD_DEPS) {
            if (Bukkit.getPluginManager().getPlugin(dep[0]) == null) {
                getLogger().severe("缺少 " + label(dep) + "，WorldTaste已自動卸載！");
                Bukkit.getPluginManager().disablePlugin(this);
                return false;
            }
        }
        for (String[] dep : SOFT_DEPS) {
            if (Bukkit.getPluginManager().getPlugin(dep[0]) == null) {
                getLogger().warning("缺少 " + label(dep) + "，部分玩法可能無法加載");
            }
        }
        return true;
    }

    /** 依賴展示名：中文名與插件名相同（如 LogiTech）時只顯示一處。 */
    private static String label(String[] dep) {
        return dep[1].equalsIgnoreCase(dep[0]) ? dep[0] : dep[0] + "（" + dep[1] + "）";
    }

    @Override
    public void onDisable() {
        getLogger().info("塵世百味 已卸載");
    }

    public static WorldTastePlugin getInstance() {
        return instance;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Override
    public String getBugTrackerURL() {
        return "https://github.com/ykcgly/WorldTaste-Plugin/issues";
    }
}
