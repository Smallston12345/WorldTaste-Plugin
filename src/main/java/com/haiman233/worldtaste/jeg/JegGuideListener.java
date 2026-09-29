package com.haiman233.worldtaste.jeg;

import com.balugaq.jeg.api.objects.events.GuideEvents;
import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.guide.BigRecipeMenu;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * JEG 指南事件攔截：
 * <ul>
 *   <li>點擊塵世百味機器物品 → 取消 JEG 默認配方頁，改開 {@link BigRecipeMenu} 完整配方表；</li>
 *   <li>點擊裝飾分隔板（{@link DecorativeSubGroup}，普通原版玻璃板佔位）→ 取消打開動作，
 *       JEG 的 EventBuilder.ifSuccess 將已取消事件視為「點擊已處理」，菜單紋絲不動。</li>
 * </ul>
 * 僅當 JEG 存在時由插件加載本類（見 {@link JegGuideListener#register()}）。
 */
public final class JegGuideListener implements Listener {

    /** 僅在 JEG 可用時調用（類加載安全：本類直接引用 JEG API）。 */
    public static void register() {
        if (JegHook.available()) {
            Bukkit.getPluginManager().registerEvents(new JegGuideListener(), WT.plugin);
            WT.plugin.getLogger().info("JEG 集成：大配方菜單與裝飾板點擊攔截已啓用");
        }
    }

    private JegGuideListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onItemClick(GuideEvents.ItemButtonClickEvent e) {
        // 作弊模式點擊 = 領取物品，不攔截（否則終焉廚鍋等大型配方機器無法從指南拿取）
        if (e.getGuide().getMode() != SlimefunGuideMode.SURVIVAL_MODE) return;
        ItemStack clicked = e.getClickedItem();
        if (clicked == null || clicked.getType().isAir()) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (sf instanceof WTRecipeMachine machine && BigRecipeMenu.isLargeRecipeMachine(machine)) {
            // 僅大型配方機器（如終焉廚鍋）攔截並打開大配方菜單；普通機器保留 JEG 默認展示
            e.setCancelled(true);
            BigRecipeMenu.open(e.getPlayer(), machine, 0, null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGroupButtonClick(GuideEvents.ItemGroupButtonClickEvent e) {
        ItemStack clicked = e.getClickedItem();
        if (clicked == null || clicked.getType().isAir()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(DecorativeSubGroup.markKey(), PersistentDataType.BYTE)) {
            // 裝飾分隔板：取消打開動作——JEG 視為「點擊已處理」，菜單保持原樣
            e.setCancelled(true);
        }
    }
}