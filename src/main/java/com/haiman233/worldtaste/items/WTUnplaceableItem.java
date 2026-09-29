package com.haiman233.worldtaste.items;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import org.bukkit.inventory.ItemStack;

/** 不可放置的普通物品（對應 RSC 默認 CustomUnplaceableItem，即 placeable 未置 true 時）。 */
public class WTUnplaceableItem extends SlimefunItem implements NotPlaceable {
    public WTUnplaceableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }
}
