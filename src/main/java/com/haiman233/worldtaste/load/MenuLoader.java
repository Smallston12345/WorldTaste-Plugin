package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.MenuDef;
import com.haiman233.worldtaste.util.Colors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加載 menus.yml → {@link WT#menus}（供機器取 GUI 佈局）。支持 slots 單槽與 a-b 區間寫法。 */
public final class MenuLoader {

    private MenuLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "menus.yml");
        int ok = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                MenuDef menu = new MenuDef(id, Colors.c(s.getString("title", id)));
                int size = s.getInt("size", -1);
                if (size > 0) menu.size = size;
                ConfigurationSection slots = s.getConfigurationSection("slots");
                if (slots != null) {
                    for (String key : slots.getKeys(false)) {
                        ConfigurationSection slotSec = slots.getConfigurationSection(key);
                        if (slotSec == null) continue;
                        ItemStack item = Read.item(slotSec, false);
                        boolean isProgress = slotSec.getBoolean("progressbar", false)
                                || slotSec.isConfigurationSection("progressbar");
                        for (int slot : parseSlots(key)) {
                            if (item != null) menu.items.put(slot, item);
                            if (isProgress) {
                                menu.progressSlot = slot;
                                menu.progressItem = item;
                            }
                        }
                    }
                }
                WT.menus.put(id, menu);
                ok++;
            } catch (Exception e) {
                WT.log("menus.yml " + id + " 失敗: " + e);
            }
        }
        WT.plugin.getLogger().info("menus.yml: 註冊 " + ok);
    }

    /** 解析槽位鍵：單值 "6" 或區間 "10-17"。非法/反轉/越界（>53）返回空數組（僅跳過該槽，不連累整個菜單）。 */
    private static int[] parseSlots(String key) {
        key = key.trim();
        int dash = key.indexOf('-');
        if (dash > 0) {
            try {
                int lo = Integer.parseInt(key.substring(0, dash));
                int hi = Integer.parseInt(key.substring(dash + 1));
                // 反轉區間(如 17-10)曾導致 NegativeArraySizeException 逃出 NumberFormatException
                // 捕獲、連累整個菜單註冊失敗；越界槽位(>53)則會在 constructMenu 越界炸掉機器註冊，
                // 此處一併校驗後返回空數組，實現故障隔離。
                if (lo >= 0 && hi < 54 && hi >= lo) {
                    int[] out = new int[hi - lo + 1];
                    for (int i = 0; i < out.length; i++) out[i] = lo + i;
                    return out;
                }
            } catch (NumberFormatException ignored) { }
        }
        try {
            int v = Integer.parseInt(key);
            return (v >= 0 && v < 54) ? new int[] { v } : new int[0];
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }
}
