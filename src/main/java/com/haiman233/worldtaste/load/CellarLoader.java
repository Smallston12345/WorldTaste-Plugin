package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.CellarRecipe;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加載 cellar.yml 酒窖配方（須在 items/foods 物品註冊後調用）。
 *
 * <pre>
 * options:
 *   cellar-name: true   # 酒窖命名功能開關
 * KEY:
 *   ingredient:
 *     - APPLE:2          # 材料id:數量（id 為材質名或粘液物品 id；數量 = 該原料的總份數，
 *                        # 即榨汁盆配方的材料寫法，如 1 單位甜漿果汁 = 3 份甜漿果）
 *     - SWEET_BERRIES:1
 *   aging: true          # 是否允許陳釀（true=產物含酒精可陳化；false=副產物無酒精度）
 *   result:              # 每單位產物（item 段同構）
 *     material: POTION
 *     name: '&6&l水果酒'
 *     amount: 1
 * </pre>
 */
public final class CellarLoader {

    private CellarLoader() {}

    /** 酒窖命名功能開關（cellar.yml 的 options.cellar-name，缺省開啓）。 */
    public static boolean cellarNameEnabled = true;

    /** 測試模式開關（cellar.yml 的 options.test-mode，缺省開啓）：釀造立即完成。 */
    public static boolean testMode = true;

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "cellar.yml");
        cellarNameEnabled = y.getBoolean("options.cellar-name", true);
        testMode = y.getBoolean("options.test-mode", true);
        int ok = 0, skip = 0;
        for (String key : y.getKeys(false)) {
            if (key.equalsIgnoreCase("options")) continue;
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            try {
                if (register(key, s)) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log("cellar.yml " + key + " 解析失敗，跳過: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("cellar.yml: 註冊 " + ok + ", 跳過 " + skip);
    }

    private static boolean register(String key, ConfigurationSection s) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        for (String entry : s.getStringList("ingredient")) {
            String v = entry == null ? "" : entry.trim();
            if (v.isEmpty()) continue;
            int count = 1;
            int idx = v.lastIndexOf(':');
            if (idx > 0) {
                try {
                    count = Integer.parseInt(v.substring(idx + 1).trim());
                    v = v.substring(0, idx).trim();
                } catch (NumberFormatException ignored) {
                    // 冒號後非數字：整段視為 id
                }
            }
            if (count < 1) count = 1;
            String ref;
            if (v.startsWith("mc:") || v.startsWith("sf:")) {
                ref = v;
            } else {
                Material m = Material.matchMaterial(v);
                ref = m != null ? "mc:" + m.name() : "sf:" + v;
            }
            refs.merge(ref, count, Integer::sum);
        }
        if (refs.isEmpty()) {
            WT.log("cellar.yml " + key + ": 缺少有效 ingredient，跳過");
            return false;
        }
        boolean aging = s.getBoolean("aging", true);
        ItemStack result = Read.item(s.getConfigurationSection("result"), true);
        if (result == null) {
            WT.log("cellar.yml " + key + ": result 無效，跳過");
            return false;
        }
        CellarRecipe.register(new CellarRecipe(key, refs, aging, result));
        return true;
    }
}
