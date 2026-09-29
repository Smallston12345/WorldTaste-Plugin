package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 加載 mob_drops.yml：以普通物品註冊，並記錄 entity/chance 供生物死亡掉落監聽器使用。
 *
 * <p>指南展示：配方類型為自定義的 {@link #mobDropType()}（worldtaste:mob_drop，圖標為刷怪蛋
 * 「生物掉落」），配方中央放置該生物的刷怪蛋（lore「擊殺 XX 時會有 N% 的概率掉落」，生物名走
 * translatable 由客户端按語言渲染），玩家在指南里即可看到產物由哪種生物掉落。entity/chance
 * 不是標準 recipe/recipe_type 字段結構，且 R6 緩存的配置只讀（不得向共享 section 寫入），故
 * 展示經 {@link ItemsLoader#register(String, ConfigurationSection, RecipeType, ItemStack[])}
 * 的覆蓋參數注入。</p>
 *
 * <p><b>為什麼不用 Slimefun 內置 {@code RecipeType.MOB_DROP}</b>：本服 Slimefun fork 在
 * {@code RecipeType.register} 時對 MOB_DROP 實例走 {@code registerMobDrop}——取配方第 5 格
 * 物品的<b>顯示名</b>經 {@code EntityType.valueOf} 解析實體並註冊進原生掉落表，顯示名不是
 * 實體枚舉名（如我們的蛋無顯示名/中文名）會直接拋 IllegalArgumentException 導致物品加載
 * 失敗；即使解析成功也會走 fork 原生掉落，與本插件的 MobDropListener 重複掉落。因此用
 * 自定義類型僅做展示，掉落仍由 {@code MobDropListener} 按 chance 處理。</p>
 *
 * <p>無刷怪蛋材質的生物（GIANT/PLAYER/MANNEQUIN 等）按 RSC 回退為 EGG 圖標；entity: MARKER
 * 為舊版本的佔位條目（真實生物存在時經 register.conditions 版本條件互斥替換；MARKER 實體
 * 永不觸發死亡事件，實際不掉落），不注入掉落展示，保持無配方顯示。</p>
 */
public final class MobDropsLoader {

    private MobDropsLoader() {}

    /** 按實體類型(大寫)索引的掉落表：onDeath 直接按類型查表，避免線性掃描全部掉落(刷怪塔高頻死亡場景)。 */
    public static final Map<String, List<Drop>> drops = new HashMap<>();

    /** 自定義掉落展示配方類型（懶加載：構造需 WT.plugin 已就緒）。 */
    private static RecipeType mobDropType;

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "mob_drops.yml");
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                String entity = s.getString("entity");
                int chance = s.getInt("chance", 0);
                // 註冊用的是 id_alias（effId），記錄時也要用同一個 id，否則監聽器 getById 查不到
                String effId = s.getString("id_alias", id);

                // 指南展示覆蓋：自定義掉落類型 + 居中刷怪蛋（對齊 RSC 視覺；MARKER 佔位不展示）
                EntityType type = parseEntityType(entity);
                RecipeType rtOverride = null;
                ItemStack[] recipeOverride = null;
                if (type != null && type != EntityType.MARKER) {
                    ItemStack egg = dropSourceEgg(type, chance);
                    if (egg != null) {
                        rtOverride = mobDropType();
                        recipeOverride = new ItemStack[] {null, null, null, null, egg, null, null, null, null};
                    }
                }

                if (!ItemsLoader.register(id, s, rtOverride, recipeOverride)) {
                    skip++;
                    continue;
                }

                if (entity != null && chance > 0) {
                    // 掉落表鍵統一取 EntityType#name()（當前運行時枚舉名）：修復 yml 舊枚舉名
                    // （MUSHROOM_COW/SNOWMAN）在 1.20.5+ 服務端與 EntityType#name() 永不相等的
                    // 死鍵——此前這類條目註冊了物品但永不掉落。
                    String key = type != null ? type.name() : entity.toUpperCase(Locale.ROOT);
                    drops.computeIfAbsent(key, k -> new ArrayList<>()).add(new Drop(effId, key, chance));
                }
                ok++;
            } catch (Exception e) {
                WT.log("mob_drops.yml " + id + " 註冊失敗: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("mob_drops.yml: 註冊 " + ok + ", 跳過 " + skip);
    }

    /**
     * 自定義掉落展示配方類型（worldtaste:mob_drop）：圖標為殭屍刷怪蛋，命名「生物掉落」。
     * 不用內置 RecipeType.MOB_DROP 的原因見類註釋（fork 會解析配方第 5 格顯示名 → 崩潰/重複掉落）。
     */
    private static RecipeType mobDropType() {
        if (mobDropType == null) {
            ItemStack icon = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("生物掉落", NamedTextColor.AQUA));
                meta.lore(List.of(Component.text("擊殺對應生物有概率獲得", NamedTextColor.GRAY)));
                icon.setItemMeta(meta);
            }
            mobDropType = new RecipeType(new NamespacedKey(WT.plugin, "mob_drop"), icon);
        }
        return mobDropType;
    }

    /** 解析實體類型；兼容舊枚舉名（MUSHROOM_COW→MOOSHROOM、SNOWMAN→SNOW_GOLEM），失敗返回 null。 */
    private static EntityType parseEntityType(String name) {
        if (name == null || name.isEmpty()) return null;
        String u = name.toUpperCase(Locale.ROOT);
        switch (u) {
            case "MUSHROOM_COW": u = "MOOSHROOM"; break;
            case "SNOWMAN": u = "SNOW_GOLEM"; break;
            case "ZOMBIE_PIGMAN": u = "ZOMBIFIED_PIGLIN"; break;
            default: break;
        }
        try {
            return EntityType.valueOf(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 構建掉落來源展示蛋（對齊 RSC MobDropsReader：實體刷怪蛋 + 「擊殺 XX 時會有 N% 的概率掉落」
     * lore，生物名用 translatable，由客户端按語言文件渲染中文名）。無對應刷怪蛋材質的實體回退
     * EGG 圖標（與 RSC 一致）。構建失敗返回 null（該條目退回無配方展示，不影響註冊與掉落）。
     * 注意：蛋只帶 lore 不帶顯示名——顯示名會被 fork 的 registerMobDrop 解析，須保持無關。
     */
    private static ItemStack dropSourceEgg(EntityType type, int chance) {
        try {
            Material eggMat = Material.matchMaterial(type.name() + "_SPAWN_EGG");
            if (eggMat == null) eggMat = Material.EGG;
            ItemStack egg = new ItemStack(eggMat);
            ItemMeta meta = egg.getItemMeta();
            if (meta == null) return null;
            Component lore = Component.text()
                    .append(Component.text("擊殺 ", NamedTextColor.GREEN))
                    .append(Component.translatable(type.translationKey()).color(NamedTextColor.AQUA))
                    .append(Component.text(" 時會有 ", NamedTextColor.GREEN))
                    .append(Component.text(chance + "%", NamedTextColor.AQUA))
                    .append(Component.text(" 的概率掉落", NamedTextColor.GREEN))
                    .build();
            meta.lore(List.of(lore));
            egg.setItemMeta(meta);
            return egg;
        } catch (Exception e) {
            WT.log("mob_drops " + type + ": 掉落來源展示構建失敗，退回無配方: " + e);
            return null;
        }
    }

    public static final class Drop {
        public final String itemId;
        public final String entity;
        public final int chance;
        Drop(String itemId, String entity, int chance) {
            this.itemId = itemId;
            this.entity = entity;
            this.chance = chance;
        }
    }
}
