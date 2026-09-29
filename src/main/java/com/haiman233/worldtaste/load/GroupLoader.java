package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.guide.WTNestedGroup;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SeasonalItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import java.time.Month;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加載 groups.yml：nested / sub / button / seasonal / locked / normal。 */
public final class GroupLoader {

    private GroupLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "groups.yml");
        // 先註冊 nested 根組，再註冊子組（SubItemGroup 構造需要父 NestedItemGroup）
        // 逐條 try/catch 故障隔離：registerNested 內 Read.item(PlayerHead/Skin) 或組構造/註冊
        // 可能對單條壞數據拋異常；若無隔離會中止整個 GroupLoader，其後所有物品因「物品組缺失」被跳過。
        for (String key : y.getKeys(false)) {
            try {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                String type = s.getString("type", "normal").toLowerCase(Locale.ROOT);
                if (type.equals("nested") || type.equals("parent")) registerNested(key, s);
            } catch (Exception e) {
                WT.log("groups " + key + " 註冊失敗，跳過: " + e);
            }
        }
        int ok = WT.groups.size();
        for (String key : y.getKeys(false)) {
            try {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                String type = s.getString("type", "normal").toLowerCase(Locale.ROOT);
                if (type.equals("nested") || type.equals("parent")) continue;
                registerChild(key, s, type);
            } catch (Exception e) {
                WT.log("groups " + key + " 註冊失敗，跳過: " + e);
            }
        }
        WT.plugin.getLogger().info("groups.yml: 註冊 " + (WT.groups.size() - ok) + " 子組，共 " + WT.groups.size());
    }

    private static void registerNested(String key, ConfigurationSection s) {
        ItemStack display = WTConfig.applyDisabledSuffix(key, Read.item(s.getConfigurationSection("item"), false));
        if (display == null) {
            WT.log("groups " + key + ": 無展示物品");
            return;
        }
        int tier = s.getInt("tier", 3);
        NestedItemGroup g = WTNestedGroup.create(nsKey(key), display, tier);
        g.register(WT.plugin);
        WT.groups.put(key.toLowerCase(Locale.ROOT), g);
    }

    private static void registerChild(String key, ConfigurationSection s, String type) {
        // 玩法關閉的分組（如釀造工藝）按鈕保留，僅在名稱後追加「已禁用」
        ItemStack display = WTConfig.applyDisabledSuffix(key, Read.item(s.getConfigurationSection("item"), false));
        if (display == null) {
            WT.log("groups " + key + ": 無展示物品");
            return;
        }
        int tier = s.getInt("tier", 3);
        switch (type) {
            case "seasonal": {
                int month = s.getInt("month", 1);
                SeasonalItemGroup g = new SeasonalItemGroup(nsKey(key), Month.of(Math.max(1, Math.min(12, month))), tier, display);
                g.register(WT.plugin);
                WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                break;
            }
            case "button": {
                // 裝飾分隔板/標籤（RSC 中 actions: none 的對應物）：註冊為 DecorativeSubGroup——
                // 展示為普通原版玻璃板（無名字/lore）；原版指南點擊無反應；
                // JEG 指南點擊由 JegGuideListener 取消 ItemGroupButtonClickEvent，同樣無反應
                String parentId = s.getString("parent");
                ItemGroup parent = parentId == null ? null : WT.groups.get(parentId.toLowerCase(Locale.ROOT));
                if (parent instanceof NestedItemGroup nested) {
                    DecorativeSubGroup g = new DecorativeSubGroup(nsKey(key), nested, display, tier);
                    g.register(WT.plugin);
                    WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                }
                // 無嵌套父組的 button：純裝飾無承載，直接忽略（註冊為普通組會混入指南主菜單）
                break;
            }
            case "sub":
            default: {
                String parentId = s.getString("parent");
                ItemGroup parent = parentId == null ? null : WT.groups.get(parentId.toLowerCase(Locale.ROOT));
                if (parent instanceof NestedItemGroup) {
                    SubItemGroup g = new SubItemGroup(nsKey(key), (NestedItemGroup) parent, display, tier);
                    g.register(WT.plugin);
                    WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                } else {
                    // 無 nested 父組：退化為普通組
                    ItemGroup g = new ItemGroup(nsKey(key), display, tier);
                    g.register(WT.plugin);
                    WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                }
                break;
            }
        }
    }

    private static NamespacedKey nsKey(String key) {
        return new NamespacedKey(WT.plugin, key.toLowerCase(Locale.ROOT));
    }
}