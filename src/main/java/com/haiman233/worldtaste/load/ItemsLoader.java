package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.BlockDrops;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.items.ItemSpec;
import com.haiman233.worldtaste.items.ScriptItemFactory;
import com.haiman233.worldtaste.machines.PowerConsumer;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 加載物品類配置。items.yml（消耗品/食材/裝飾）與 machines.yml（腳本驅動的可放置物品，多為作物）結構一致，
 * 統一經 {@link ScriptItemFactory} 按腳本+屬性分派物品子類。支持 register.conditions、lateInit(兩遍)、id_alias、
 * placeable、drop_from、vanilla、radiation/soulbound/anti_wither/piglin/energy 等屬性。
 */
public final class ItemsLoader {

    private ItemsLoader() {}

    public static void load() {
        loadFile("items.yml");
        loadFile("machines.yml");
    }

    public static void loadFile(String file) {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, file);
        int ok = 0, skip = 0;
        List<ConfigurationSection> late = new ArrayList<>();
        List<String> lateIds = new ArrayList<>();
        // 第一遍：非 lateInit
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getBoolean("lateInit", false)) {
                late.add(s);
                lateIds.add(id);
                continue;
            }
            try {
                if (register(id, s)) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log(file + " " + id + " 註冊失敗: " + e);
                skip++;
            }
        }
        // 第二遍：lateInit
        for (int i = 0; i < late.size(); i++) {
            String id = lateIds.get(i);
            try {
                if (register(id, late.get(i))) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log(file + " " + id + "(lateInit) 註冊失敗: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info(file + ": 註冊 " + ok + ", 跳過 " + skip);
    }

    /** 通用物品註冊（items/machines/foods/mob_drops/geo 複用）。成功返回 true。 */
    static boolean register(String id, ConfigurationSection s) {
        return register(id, s, null, null);
    }

    /**
     * 帶展示覆蓋的註冊重載（mob_drops 用）：掉落來源展示（自定義生物掉落類型 + 居中生物刷蛋）
     * 不是標準 recipe/recipe_type 字段，且 R6 緩存的配置只讀（不得向共享 section 寫入），
     * 故由 MobDropsLoader 解析 entity/chance 後經覆蓋參數注入；覆蓋為 null 時行為不變。
     */
    static boolean register(String id, ConfigurationSection s, RecipeType recipeTypeOverride,
                            ItemStack[] recipeOverride) {
        // 註冊條件
        if (!RegisterConditions.pass(s)) return false;

        // 佔位玻璃板物品（各分組分割板：材質 *_STAINED_GLASS_PANE 且無配方）：
        // 不註冊為 Slimefun 物品，不再出現在指南中；舊存檔遺留的此類物品因 id 未註冊而自然失效
        ConfigurationSection itemSec = s.getConfigurationSection("item");
        if (itemSec != null) {
            String mat = itemSec.getString("material", "");
            String rt = s.getString("recipe_type", "NULL");
            // separator: true 的玻璃板為分組頁內裝飾分隔符，正常註冊顯示
            if (mat.endsWith("_STAINED_GLASS_PANE") && ("NULL".equalsIgnoreCase(rt) || rt.isEmpty())
                    && !s.getBoolean("separator", false)) {
                return false;
            }
        }

        String effId = s.getString("id_alias", id);
        ItemGroup g = WT.group(s.getString("item_group"));
        if (g == null) {
            // 摺疊上報（按缺失的組 id 歸併計數），加載結束彙總為一行
            MissingItems.record("物品組缺失:" + s.getString("item_group"));
            return false;
        }
        // 釀造工藝總開關關閉：該玩法分組下的物品一律不註冊（分組按鈕保留，名稱標註「已禁用」）
        if (WTConfig.blockItem(s.getString("item_group"), effId)) return false;
        // 反向：釀造「已禁用」佔位説明頁只在關閉時註冊，玩法開啓時不能混進指南
        if (WTConfig.BREWING_DISABLED_NOTICE.equalsIgnoreCase(effId) && WTConfig.brewingEnabled) return false;
        // 裝飾分隔板組（groups.yml type: button，如 ws_zwf_*）內掛載的佔位物品
        //（PAPER「這就是一個佔位符而已」）一律不註冊——任何指南里都不出現
        if (g instanceof DecorativeSubGroup) {
            return false;
        }
        ItemStack display = WT.preload.get(effId.toUpperCase(java.util.Locale.ROOT));
        if (display == null) display = WT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
        if (display == null) {
            // 摺疊上報（按物品 id 歸併計數），加載結束彙總為一行
            MissingItems.record("無展示物品:" + effId);
            return false;
        }

        ItemSpec spec = ItemSpec.from(effId, s);
        // 酒類飲品（items.yml alcohol 字段）：展示 lore 追加酒精度並登記聯動數值。
        // 修改前先 clone，避免污染 WT.preload 中的共享展示堆（配方引用仍用原堆）。
        if (spec.alcohol > 0) {
            display = display.clone();
            org.bukkit.inventory.meta.ItemMeta meta = display.getItemMeta();
            java.util.List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add(Colors.c("&7▷▷ &b酒精度: &e" + spec.alcohol));
            meta.setLore(lore);
            display.setItemMeta(meta);
            ExoticGardenHook.register(effId, spec.alcohol);
        }

        SlimefunItemStack sfis = new SlimefunItemStack(effId, display);
        // 展示覆蓋（mob_drops：自定義掉落類型 + 居中生物刷怪蛋；覆蓋為 null 時沿用 yml 原解析）
        RecipeType rt = recipeTypeOverride != null
                ? recipeTypeOverride : RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
        ItemStack[] recipe = recipeOverride != null
                ? recipeOverride : Read.recipe(s.getConfigurationSection("recipe"), 9);

        SlimefunItem item = ScriptItemFactory.create(spec, g, sfis, rt, recipe);

        // 耗電/儲電物品（EnergyNetComponent）：展示 lore 追加儲電容量與耗電量。
        // 數值取自各機器類常量（EnergyNetComponent#getCapacity + PowerConsumer#getConsumption），
        // 改 Java 常量即自動同步，無需在 yml 裏再維護一份（也避免兩邊寫不一致）。
        if (item instanceof EnergyNetComponent comp) {
            appendEnergyLore(sfis, comp, item);
        }

        if (spec.vanilla) {
            try { item.setUseableInWorkbench(true); } catch (Throwable ignored) {}
        }
        item.register(WT.plugin);


        // 方塊破壞掉落
        if (spec.dropFrom != null) {
            Material block = Material.matchMaterial(spec.dropFrom);
            int[] range = parseAmountRange(s.getString("drop_amount", "1"));
            BlockDrops.add(block, effId, spec.dropChance, range[0], range[1]);
        }

        return true;
    }

    /**
     * 給耗電/儲電物品追加電量信息 lore。
     *
     * <p>必須在 {@code item.register()} 之前調用：註冊後 {@link SlimefunItemStack} 會被 locked，
     * 再 setItemMeta 會拋異常。{@code SlimefunItemStack#setItemMeta} 已重寫並會刷新內部
     * ItemMetaSnapshot，故指南/配方里讀到的也是帶電量行的新 lore。</p>
     */
    private static void appendEnergyLore(SlimefunItemStack sfis, EnergyNetComponent comp, SlimefunItem item) {
        ItemMeta meta = sfis.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add(Colors.c("&7▷▷ &e儲電容量: &e" + comp.getCapacity() + " J"));
        if (comp.getEnergyComponentType() == EnergyNetComponentType.CONSUMER
                && item instanceof PowerConsumer consumer && consumer.getConsumption() > 0) {
            lore.add(Colors.c("&7▷▷ &c運行時耗電: &c" + consumer.getConsumption() + " J/刻"));
        }
        meta.setLore(lore);
        sfis.setItemMeta(meta);
    }

    /** 解析 drop_amount（支持 "1" 或 "1-3" 區間），返回 {min,max}，由 BlockDrops 每次掉落時擲。 */
    private static int[] parseAmountRange(String value) {
        if (value != null) {
            int dash = value.indexOf('-');
            if (dash > 0) {
                try {
                    int lo = Integer.parseInt(value.substring(0, dash).trim());
                    int hi = Integer.parseInt(value.substring(dash + 1).trim());
                    if (lo >= 1 && hi >= lo) return new int[]{lo, hi};
                } catch (NumberFormatException ignored) { }
            }
            try {
                int v = Integer.parseInt(value.trim());
                if (v >= 1) return new int[]{v, v};
            } catch (NumberFormatException ignored) { }
        }
        return new int[]{1, 1};
    }
}