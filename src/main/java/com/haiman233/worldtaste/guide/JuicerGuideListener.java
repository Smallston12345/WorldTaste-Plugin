package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.JuicerBasin;
import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 指南榨汁盆入口（雙模式）：
 * <ul>
 *   <li><b>JEG 模式</b>（安裝 JEG 時啓用 {@link JuicerJegEntryListener}）：攔截指南點擊榨汁盆，
 *       打開 {@link JuicerMenu#openBasinOverview} 自身配方概覽頁，右下角金蘋果進入配方展示頁
 *       （JEG 指南頁面經其 formatter 重繪且大於 54 格，無法按物品掃描注入）；</li>
 *   <li><b>注入模式</b>（無 JEG 的原版 Slimefun 指南）：點擊榨汁盆放行默認配方頁，延遲注入
 *       金蘋果按鈕到右下角。</li>
 * </ul>
 * 點擊帶標記的金蘋果按鈕 → 打開 {@link JuicerMenu} 配方展示頁。
 */
public final class JuicerGuideListener implements Listener {

    static final NamespacedKey KEY_ENTRY =
            new NamespacedKey(WT.plugin, "zhapen_menu_entry");

    public static void register() {
        Bukkit.getPluginManager().registerEvents(new JuicerGuideListener(), WT.plugin);
        if (JegHook.available()) {
            Bukkit.getPluginManager().registerEvents(new JuicerJegEntryListener(), WT.plugin);
        }
    }

    private JuicerGuideListener() {}

    /** 無 JEG：指南中點擊榨汁盆 → 放行默認展示，延遲注入入口按鈕。 */
    @EventHandler
    public void onBasinClick(InventoryClickEvent e) {
        if (JegHook.available()) return; // JEG 模式由 JuicerJegEntryListener 處理
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (!(sf instanceof JuicerBasin)) return;
        Player p = (Player) e.getWhoClicked();
        Bukkit.getScheduler().runTaskLater(WT.plugin, () -> injectEntry(p), 2);
    }

    /** 在榨汁盆配方頁右下角注入金蘋果入口（已有的 slot 依次向前尋找空位）。 */
    private void injectEntry(Player p) {
        if (!p.isOnline()) return;
        Inventory top = Views.top(p.getOpenInventory());
        if (top.getSize() < 54) return;
        boolean isBasinPage = false;
        for (ItemStack it : top) {
            if (it == null) continue;
            SlimefunItem sf = SlimefunItem.getByItem(it);
            if (sf instanceof JuicerBasin) {
                isBasinPage = true;
                break;
            }
        }
        if (!isBasinPage) return;
        for (int slot = 53; slot >= 45; slot--) {
            ItemStack cur = top.getItem(slot);
            if (cur != null && isEntry(cur)) return; // 已注入
            // 原版指南配方頁空位也會填背景板：背景板視為可注入位置
            if (cur == null || cur.getType().isAir() || cur == ChestMenuUtils.getBackground()) {
                top.setItem(slot, JuicerMenu.entryButton());
                return;
            }
        }
    }

    /** 點擊入口按鈕：打開展示菜單。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntryClick(InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem();
        if (cur == null || !isEntry(cur)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p) {
            JuicerMenu.openRecipeList(p, 0);
        }
    }

    private static boolean isEntry(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(KEY_ENTRY, PersistentDataType.BYTE);
    }

    private static ItemStack entryButton() {
        ItemStack it = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GOLD + "榨汁盆配方");
            meta.getPersistentDataContainer().set(KEY_ENTRY, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }
}
