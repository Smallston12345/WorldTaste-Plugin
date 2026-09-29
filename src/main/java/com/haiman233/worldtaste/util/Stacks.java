package com.haiman233.worldtaste.util;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/** ItemStack 輔助操作。 */
public final class Stacks {

    private Stacks() {}

    /** 按「類型 + 顯示名」匹配已註冊 Slimefun 物品（用於還原無 id 的展示物品）。
     *  多方塊/機器產出在加載期目標物品未註冊（如 RegisterConditions 不滿足）時會退化為
     *  preload 展示物品（缺 slimefun id PDC），導致右鍵食用/放置行為異常；此方法按外觀反查。 */
    public static io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem findRegisteredByAppearance(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        org.bukkit.Material type = stack.getType();
        String name = null;
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) name = meta.getDisplayName();
        for (io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf : io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getRegistry().getAllSlimefunItems()) {
            org.bukkit.inventory.ItemStack it = sf.getItem();
            if (it.getType() != type) continue;
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            if (name != null) {
                if (m != null && name.equals(m.getDisplayName())) return sf;
            } else {
                if (m == null || !m.hasDisplayName()) return sf;
            }
        }
        return null;
    }

    /** 附魔發光（隱藏附魔紋）。 */
    public static void glow(ItemStack item) {
        if (item == null) return;
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(m);
    }

    /**
     * 安全扣除主手 1 個物品：數量減到 0 時清空槽位（set null），避免殘留 0 數量“幽靈物品”。
     *
     * <p>直接 {@code setAmount(amount - 1)} 到 0 時，槽位仍持有一個 0 數量的原物品引用：
     * 其類型/Slimefun 綁定不變，會被 {@code SlimefunItem.getByItem} 或 {@code getType()} 繼續識別為“存在”，
     * 從而被無消耗地重複利用（釣魚魚餌→無限釣獲、打火石→無限點煙）。到 0 必須清空槽位。</p>
     */
    public static void consumeOneInMainHand(PlayerInventory inv) {
        if (inv == null) return;
        ItemStack it = inv.getItemInMainHand();
        if (it == null) return;
        int left = it.getAmount() - 1;
        if (left <= 0) inv.setItemInMainHand(null);
        else it.setAmount(left);
    }

    /** 同 {@link #consumeOneInMainHand}，作用於副手。 */
    public static void consumeOneInOffHand(PlayerInventory inv) {
        if (inv == null) return;
        ItemStack it = inv.getItemInOffHand();
        if (it == null) return;
        int left = it.getAmount() - 1;
        if (left <= 0) inv.setItemInOffHand(null);
        else it.setAmount(left);
    }
    /**
     * 對副手工具施加耐久損耗（玩家右鍵消耗型物品時調用）：有耐久上限且非無敵的物品扣
     * {@code amount} 點耐久並寫回副手；耐久耗盡時工具損壞消失（清空副手槽位，防止 0 數量
     * 幽靈物品殘留被 getType() 校驗無限複用）；無敵（Unbreakable）工具不損耗也不消耗。
     *
     * @return true = 已按耐久處理（含損壞消失與無敵豁免）；false = 物品不可損耗
     *         （無耐久上限），調用方可回退整件消耗。
     */
    public static boolean damageToolInOffHand(PlayerInventory inv, int amount) {
        if (inv == null) return false;
        ItemStack it = inv.getItemInOffHand();
        if (it == null || it.getType().isAir()) return false;
        if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) return false;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return false;
        if (damageable.isUnbreakable()) return true;
        int newDamage = damageable.getDamage() + Math.max(1, amount);
        if (newDamage >= max) {
            inv.setItemInOffHand(null);
            return true;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = it.clone();
        updated.setItemMeta(damageable);
        inv.setItemInOffHand(updated);
        return true;
    }
}
