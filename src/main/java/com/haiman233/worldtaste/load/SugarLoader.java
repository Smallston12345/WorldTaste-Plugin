package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.JuicerRecipe;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 加載 sugar.yml 糖分值配置（材料 id → 糖分）。
 *
 * <p>id 寫法同 juicer.yml 的 ingredient（材質名或粘液物品 id，亦接受 mc:/sf: 前綴）。
 * 產物總糖分 = Σ 材料糖分 × 投入數量；未列出的材料按 0 計。糖分數據經產物 PDC
 * （juice_sugar）與盆狀態（BlockStorage）持久化，供後續玩法功能讀取。</p>
 */
public final class SugarLoader {

    private SugarLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "sugar.yml");
        int ok = 0;
        for (String id : y.getKeys(false)) {
            if (!y.isInt(id)) continue; // 頂行註釋説明自動被忽略，非數字值跳過
            int sugar = Math.max(0, y.getInt(id));
            JuicerRecipe.setSugar(resolveRef(id.trim()), sugar);
            ok++;
        }
        WT.plugin.getLogger().info("sugar.yml: 註冊 " + ok + " 種材料的糖分值");
    }

    /** 材料 id → 內容物 ref（mc:/sf: 前綴）。 */
    private static String resolveRef(String id) {
        if (id.startsWith("mc:") || id.startsWith("sf:")) return id;
        Material m = Material.matchMaterial(id);
        return m != null ? "mc:" + m.name() : "sf:" + id;
    }
}
