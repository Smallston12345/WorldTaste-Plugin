package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 甜度試紙（items.yml 定義物品本體與配方，本類只掛載檢測行為）：
 * 主手拿果汁/飲品（帶糖分 PDC），副手持試紙，右鍵空氣/方塊後副手消耗一張試紙，
 * 在聊天欄輸出當前手持飲品的糖分值。
 *
 * <p>判定依據為 {@link JuicerRecipe#KEY_ITEM_SUGAR}——榨汁盆瓶裝/桶裝產物與酒窖
 * 成品均寫入該 PDC；無糖分數據的物品不觸發檢測（不消耗試紙）。右鍵時取消事件，
 * 避免藥水果汁被原版飲用流程吞掉。物品識別按 Slimefun id
 * （{@link #ID}，items.yml 註冊），不依賴物品類。</p>
 */
public final class SweetnessPaper {

    /** 物品 id（items.yml 中的鍵，檢測行為綁定與此）。 */
    public static final String ID = "WT_SWEET_PAPER";

    private SweetnessPaper() {}

    /** 掛載檢測監聽（Setup 調用；物品本體由 items.yml 註冊，配方在 items.yml 中配置）。 */
    public static void register() {
        SlimefunItem item = SlimefunItem.getById(ID);
        if (item == null) {
            WT.log("甜度試紙 " + ID + " 未在 items.yml 中註冊，檢測功能未啓用");
            return;
        }
        Bukkit.getPluginManager().registerEvents(new DetectListener(), WT.plugin);
    }

    /** 讀取飲品的糖分 PDC（未寫入糖分數據的物品返回 null = 不觸發檢測）。 */
    private static Integer readSugar(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER);
    }

    private static final class DetectListener implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (e.getHand() != EquipmentSlot.HAND) return;
            if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            Integer sugar = readSugar(e.getItem());
            if (sugar == null) return;
            Player p = e.getPlayer();
            ItemStack off = p.getInventory().getItemInOffHand();
            SlimefunItem paper = SlimefunItem.getByItem(off);
            if (paper == null || !ID.equals(paper.getId())) return;
            // 取消原版交互（果汁多為藥水材質，防止右鍵時被喝掉）
            e.setCancelled(true);
            if (off.getAmount() > 1) off.setAmount(off.getAmount() - 1);
            else p.getInventory().setItemInOffHand(null);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            if (sugar <= 0) {
                p.sendMessage("§e甜度檢測：§7當前飲品幾乎不含糖分。");
            } else {
                p.sendMessage("§e甜度檢測：§f當前飲品的糖分為 §b" + sugar + "§e 點。");
            }
        }
    }
}
