package com.haiman233.worldtaste.items;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 動物奶桶擠取：空桶（原版鐵桶）右鍵對應生物 → 獲得對應的奶桶飲品，
 * 與原版擠牛奶同款交互（主手桶消耗 1 個；整堆持有時數量 -1、奶桶入包）。
 *
 * <p>對應關係：駱駝 → 駱駝奶、馬 → 馬奶、羊 → 羊奶、驢 → 驢奶
 * （物品本體在 foods.yml 飲料分類中定義，原合成配方已移除）。</p>
 */
public final class MilkBucketListener implements Listener {

    public static final MilkBucketListener INSTANCE = new MilkBucketListener();

    /** 生物 → 奶桶物品 id。 */
    private static final Map<EntityType, String> MILKS = Map.of(
            EntityType.CAMEL, "WT_LUOTUONAI",
            EntityType.HORSE, "WT_MANAI",
            EntityType.SHEEP, "WT_YANGNAI",
            EntityType.DONKEY, "WT_LVNAI");

    private MilkBucketListener() {}

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String id = MILKS.get(e.getRightClicked().getType());
        if (id == null) return;
        SlimefunItem milk = SlimefunItem.getById(id);
        if (milk == null) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        // 僅原版空鐵桶可擠取（粘液物品桶不算）
        if (held == null || held.getType() != Material.BUCKET
                || SlimefunItem.getByItem(held) != null) return;
        e.setCancelled(true);
        ItemStack out = milk.getItem().clone();
        out.setAmount(1);
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            p.getInventory().addItem(out).values()
                    .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
        } else {
            p.getInventory().setItemInMainHand(out);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_COW_MILK, 1f, 1f);
    }
}
