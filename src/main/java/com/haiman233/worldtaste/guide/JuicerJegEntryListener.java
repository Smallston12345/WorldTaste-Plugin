package com.haiman233.worldtaste.guide;

import com.balugaq.jeg.api.objects.events.GuideEvents;
import com.haiman233.worldtaste.machines.JuicerBasin;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * JEG 模式榨汁盆入口（僅 JEG 可用時註冊，本類直接引用 JEG API）：
 * 攔截指南中點擊榨汁盆，打開自身配方概覽頁（右下角金蘋果進入配方展示頁）。
 */
public final class JuicerJegEntryListener implements Listener {

    public JuicerJegEntryListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onItemClick(GuideEvents.ItemButtonClickEvent e) {
        // 作弊模式點擊 = 領取物品，不攔截
        if (e.getGuide().getMode() != SlimefunGuideMode.SURVIVAL_MODE) return;
        ItemStack clicked = e.getClickedItem();
        if (clicked == null) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (!(sf instanceof JuicerBasin)) return;
        e.setCancelled(true);
        JuicerMenu.openBasinOverview(e.getPlayer());
    }
}
