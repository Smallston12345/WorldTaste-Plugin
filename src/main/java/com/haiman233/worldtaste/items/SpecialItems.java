package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 特殊腳本物品（原獨立 .js 腳本），全部為手寫 Java 實現。 */
public final class SpecialItems {

    private SpecialItems() {}

    public static SlimefunItem create(String id, ItemGroup group, SlimefunItemStack sfis,
                                      RecipeType rt, ItemStack[] recipe, String script) {
        switch (script) {
            case "yurenjie/buyunping": return new CloudBottleItem(group, sfis, rt, recipe);
            case "jurenwan": return new GiantPillItem(group, sfis, rt, recipe);
            default: return null;
        }
    }

    /** 捕雲瓶：僅在雲層(Y=192-196)可用；晴天掉落 WT_CLOUD，雨/雷天掉落 WT_THUNDERCLOUD。 */
    private static class CloudBottleItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {
        CloudBottleItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) { super(g, i, rt, r); }

        @Override
        public ItemUseHandler getItemHandler() {
            return e -> {
                Player p = e.getPlayer();
                Location l = p.getLocation();
                if (l.getY() < 192 || l.getY() > 196) {
                    p.sendMessage("§c您必須在雲層(Y=192-196)才能使用捕雲瓶！");
                    return;
                }
                ItemStack off = p.getInventory().getItemInOffHand();
                if (off != null && SlimefunItem.getByItem(off) != null) {
                    p.sendMessage("您必須使用主手捕雲且副手不能持有粘液科技物品！");
                    return;
                }
                ItemStack main = p.getInventory().getItemInMainHand();
                if (main == null || main.getAmount() <= 0) return;

                boolean clear = !p.getWorld().hasStorm() && !p.getWorld().isThundering();
                String dropId = clear ? "WT_CLOUD" : "WT_THUNDERCLOUD";
                SlimefunItem sf = SlimefunItem.getById(dropId);
                if (sf == null) {
                    // 掉落物未註冊：不消耗瓶子（避免吞物品），僅記錄並提示
                    WT.log("捕雲瓶掉落物未註冊: " + dropId);
                    return;
                }
                // 到 0 必須清空主手槽位，避免 0 數量幽靈物品殘留
                Stacks.consumeOneInMainHand(p.getInventory());
                p.getWorld().dropItemNaturally(l, sf.getItem().clone());
                p.sendMessage("§b成功捕獲了" + (clear ? "雲朵" : "烏雲") + "！");
                p.getWorld().playSound(l, Sound.ENTITY_PLAYER_SPLASH, 1f, 1f);
            };
        }
    }

    /** 巨人丸：右鍵在瞄準方塊上方生成一隻巨人（GIANT）。 */
    private static class GiantPillItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {
        GiantPillItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) { super(g, i, rt, r); }

        @Override
        public ItemUseHandler getItemHandler() {
            return e -> {
                Player p = e.getPlayer();
                if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
                    p.sendMessage("請主手持有相應物品");
                    return;
                }
                org.bukkit.inventory.PlayerInventory inv = p.getInventory();
                ItemStack off = inv.getItemInOffHand();
                if (off != null && SlimefunItem.getByItem(off) != null) {
                    p.sendMessage("您必須使用主手且副手不能持有粘液科技物品！");
                    return;
                }
                ItemStack main = inv.getItemInMainHand();
                if (main == null || main.getAmount() <= 0) return;
                // 先校驗瞄準方塊再消耗：準星無方塊（如望向天空）時不消耗，避免吞物品。
                // 不用 getTargetBlock(Set,int)：其永不為 null，無目標時返回視野末端空氣方塊，
                // 會把巨人憑空生成在半空。
                Block target = p.getTargetBlockExact(5);
                if (target == null || target.getType().isAir()) {
                    p.sendMessage("§c請瞄準 5 格內的方塊使用巨人丸！");
                    return;
                }
                // 到 0 必須清空主手槽位，避免 0 數量幽靈物品殘留
                Stacks.consumeOneInMainHand(inv);
                Location loc = target.getLocation().add(0, 1, 0);
                loc.getWorld().spawnEntity(loc, EntityType.GIANT);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_STRIDER_EAT, 1f, 1f);
            };
        }
    }
}
