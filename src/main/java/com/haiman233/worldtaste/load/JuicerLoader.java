package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.JuicerRecipe;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 加載 juicer.yml 榨汁盆配方（須在 items/foods 物品註冊後調用，產物/投入物可按
 * material_type:slimefun 引用已註冊物品）。
 *
 * <pre>
 * # 頭部混合配置
 * mix:
 *   recipes: [PINGGUO_ZHAPEN]   # 可混合配方：其投入物可任意組合投入盆中混合榨汁
 *   progress: 10                # 混合榨汁所需踩踏進度
 *   bottle: {...}               # 瓶裝混合產物模板（item 段同構）
 *   bucket: {...}               # 桶裝混合產物模板
 *
 * # 普通配方：多材料 + 數量投入
 * KEY:
 *   ingredient:
 *     - APPLE:3                 # id:數量（id 為材質名或粘液物品 id）
 *     - WT_PUTAOTI:2
 *   type: player,anvil          # 逗號分隔多值：踩踏與鐵砧砸擊均可
 *   progress: 10
 *   yield: 3                    # 榨好後的接取份數（1~3，默認 3）
 *   result: {...}
 * </pre>
 */
public final class JuicerLoader {

    private JuicerLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "juicer.yml");
        loadSettings(y);
        loadMix(y);
        int ok = 0, skip = 0;
        for (String key : y.getKeys(false)) {
            if (key.equalsIgnoreCase("mix")) continue; // 頭部混合配置段，非配方
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue; // 全局設置等標量鍵
            try {
                if (register(key, s)) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log("juicer.yml " + key + " 解析失敗，跳過: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("juicer.yml: 註冊 " + ok + ", 跳過 " + skip
                + (JuicerRecipe.mix != null ? ", 可混合物品 " + JuicerRecipe.mix.mixableRefs.size() : ""));
    }

    /** 解析頭部全局設置：玩家名 lore 開關與文本格式、鐵砧損耗開關與概率。 */
    private static void loadSettings(YamlConfiguration y) {
        JuicerRecipe.playerNames = y.getBoolean("player-names", true);
        JuicerRecipe.playerNamesFormat = y.getString("player-names-format", JuicerRecipe.playerNamesFormat);
        JuicerRecipe.anvilDamage = y.getBoolean("anvil-damage", false);
        JuicerRecipe.anvilDamageChance = Math.max(1, Math.min(10, y.getInt("anvil-damage-chance", 3)));
        JuicerRecipe.sugarAlcoholRatio = Math.max(0, y.getDouble("sugar-alcohol-ratio", 0.5));
        String growth = y.getString("aging-growth", "0.05~0.15");
        try {
            String[] parts = growth.split("~");
            double min = Double.parseDouble(parts[0].trim());
            double max = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : min;
            JuicerRecipe.agingGrowthMin = Math.max(0, Math.min(min, max));
            JuicerRecipe.agingGrowthMax = Math.max(min, max);
        } catch (NumberFormatException ex) {
            WT.log("juicer.yml aging-growth 格式錯誤（應為 min~max），使用默認值");
            JuicerRecipe.agingGrowthMin = 0.05;
            JuicerRecipe.agingGrowthMax = 0.15;
        }
        JuicerRecipe.wineLoreFormat = y.getString("wine-lore-format", JuicerRecipe.wineLoreFormat);
        JuicerRecipe.wineShowContents = y.getBoolean("wine-show-contents", JuicerRecipe.wineShowContents);
        JuicerRecipe.wineContentsVertical = y.getBoolean("wine-contents-vertical", JuicerRecipe.wineContentsVertical);
    }

    /** 解析頭部 mix 段（可混合材料列表 + 榨汁進度 + 瓶/桶產物模板），放在配方註冊之前。 */
    private static void loadMix(YamlConfiguration y) {
        ConfigurationSection s = y.getConfigurationSection("mix");
        if (s == null) {
            JuicerRecipe.mix = null;
            return;
        }
        JuicerRecipe.MixCfg cfg = new JuicerRecipe.MixCfg();
        // recipes 直接為材料 id 列表（材質名或粘液物品 id）
        for (String id : s.getStringList("recipes")) {
            if (id == null || id.trim().isEmpty()) continue;
            String v = id.trim();
            String ref;
            if (v.startsWith("sf:")) {
                ref = "sf:" + v.substring(3);
            } else {
                Material m = Material.matchMaterial(v);
                ref = m != null ? "mc:" + m.name() : "sf:" + v;
            }
            cfg.mixableRefs.add(ref);
        }
        cfg.progress = Math.max(1, s.getInt("progress", 10));
        ItemStack bottle = Read.item(s.getConfigurationSection("bottle"), false);
        if (bottle == null) {
            bottle = new ItemStack(Material.POTION);
            ItemMeta meta = bottle.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Colors.c("&f&l混合果汁"));
                bottle.setItemMeta(meta);
            }
        }
        ItemStack bucket = Read.item(s.getConfigurationSection("bucket"), false);
        if (bucket == null) {
            bucket = new ItemStack(Material.WATER_BUCKET);
            ItemMeta meta = bucket.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Colors.c("&f&l混合果汁桶"));
                bucket.setItemMeta(meta);
            }
        }
        cfg.bottleTemplate = bottle;
        cfg.bucketTemplate = bucket;
        JuicerRecipe.mix = cfg.mixableRefs.isEmpty() ? null : cfg;
    }

    private static boolean register(String key, ConfigurationSection s) {
        Map<String, Integer> ingredientRefs = parseIngredient(s, key);
        if (ingredientRefs == null) return false;
        if (sum(ingredientRefs) > 10) {
            WT.log("juicer.yml " + key + ": 投入總數超過盆容量 10，該配方無法投滿，跳過");
            return false;
        }
        String firstRef = ingredientRefs.keySet().iterator().next();
        ItemStack ingredientDisplay = resolveDisplay(firstRef, key);
        if (ingredientDisplay == null) return false;

        // 壓榨方式：支持逗號分隔多值「player,anvil」——踩踏與鐵砧砸擊均可榨汁
        boolean playerType = false;
        boolean anvilType = false;
        for (String t : s.getString("type", "player").toLowerCase(java.util.Locale.ROOT).split("[,，]")) {
            String v = t.trim();
            if (v.equals("player")) playerType = true;
            else if (v.equals("anvil")) anvilType = true;
        }
        if (!playerType && !anvilType) playerType = true; // 無有效值時回退玩家型
        int progress = Math.max(1, s.getInt("progress", 10));
        // 榨好後的接取份數（= 榨汁盆含水等級，1~3；瓶子每次 1 份，桶滿時一次接完）
        int yield = Math.max(1, Math.min(3, s.getInt("yield", 3)));

        ItemStack result = Read.item(s.getConfigurationSection("result"), true);
        if (result == null) {
            WT.log("juicer.yml " + key + ": result 無效，跳過");
            return false;
        }

        JuicerRecipe recipe = new JuicerRecipe(key, ingredientRefs, ingredientDisplay,
                playerType, anvilType, progress, yield, result);
        // 可選 bucket 段：自定義桶裝形態（缺省自動生成「桶裝<產物名>」，lore 與瓶裝一致）
        ItemStack bucketForm = Read.item(s.getConfigurationSection("bucket"), false);
        if (bucketForm != null) recipe.bucketForm = bucketForm;
        JuicerRecipe.register(recipe);
        return true;
    }

    private static int sum(Map<String, Integer> refs) {
        int t = 0;
        for (int n : refs.values()) t += n;
        return t;
    }

    /**
     * 解析投入物多集：ingredient 支持「id:數量」字符串/列表（id 為材質名或粘液物品 id，
     * 數量缺省 1），兼容舊的單 id 字符串寫法。全部無效返回 null。
     */
    private static Map<String, Integer> parseIngredient(ConfigurationSection s, String key) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        Object ing = s.get("ingredient");
        if (ing instanceof java.util.List) {
            for (Object o : (java.util.List<?>) ing) {
                if (o instanceof String) addRef(refs, (String) o, key);
            }
        } else if (ing instanceof String) {
            addRef(refs, (String) ing, key);
        } else if (s.isConfigurationSection("ingredient")) {
            // 兼容舊段寫法 { material_type, material, amount }
            ConfigurationSection sec = s.getConfigurationSection("ingredient");
            String type = sec.getString("material_type", "mc");
            String material = sec.getString("material", "");
            int amount = Math.max(1, sec.getInt("amount", 1));
            String ref = "slimefun".equalsIgnoreCase(type) ? "sf:" + material : refOfMaterial(material, key);
            if (ref != null) refs.put(ref, amount);
        }
        if (refs.isEmpty()) {
            WT.log("juicer.yml " + key + ": 缺少有效 ingredient，跳過");
            return null;
        }
        return refs;
    }

    private static void addRef(Map<String, Integer> refs, String entry, String key) {
        String v = entry.trim();
        if (v.isEmpty()) return;
        int count = 1;
        int idx = v.lastIndexOf(':');
        if (idx > 0) {
            try {
                count = Integer.parseInt(v.substring(idx + 1).trim());
                v = v.substring(0, idx).trim();
            } catch (NumberFormatException ignored) {
                // 冒號後不是數字：整段視為 id（數量按 1）
            }
        }
        if (count < 1) count = 1;
        String ref;
        if (v.startsWith("sf:")) {
            ref = "sf:" + v.substring(3);
        } else {
            Material m = Material.matchMaterial(v);
            if (m != null) {
                ref = "mc:" + m.name();
            } else {
                ref = "sf:" + v; // 非原版材質按粘液物品 id 處理
            }
        }
        refs.merge(ref, count, Integer::sum);
    }

    private static String refOfMaterial(String material, String key) {
        Material m = Material.matchMaterial(material);
        if (m == null) {
            WT.log("juicer.yml " + key + ": 未知材質 " + material + "，跳過");
            return null;
        }
        return "mc:" + m.name();
    }

    /** 投入物展示堆：粘液物品取其註冊物品（未註冊則摺疊上報並跳過），原版材質直接構建。 */
    private static ItemStack resolveDisplay(String ref, String key) {
        if (ref.startsWith("sf:")) {
            SlimefunItem sf = SlimefunItem.getById(ref.substring(3));
            if (sf == null) {
                // 與 Read.resolve 同口徑：摺疊上報，不逐條刷屏
                MissingItems.record(ref.substring(3));
                return null;
            }
            return sf.getItem().clone();
        }
        Material m = Material.matchMaterial(ref.substring(3));
        return new ItemStack(m != null ? m : Material.BARRIER);
    }
}
