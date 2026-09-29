package com.haiman233.worldtaste.machines;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** 菜單定義（來自 menus.yml）：標題/尺寸/裝飾槽位/進度槽。純數據，由機器在 constructMenu 中使用。 */
public final class MenuDef {

    public final String id;
    public final String title;
    public int size = -1;
    public int progressSlot = -1;
    public ItemStack progressItem;
    public final Map<Integer, ItemStack> items = new HashMap<>();

    public MenuDef(String id, String title) {
        this.id = id;
        this.title = title;
    }
}
