package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.behavior.Behaviors.Potion;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 消耗型食物（右鍵食用）。覆蓋原 WT_eatConsumable 與獨立食物腳本(yl/tang/jiu/yan/zhongdu 等)：
 * 主手消耗、副手校驗(默認禁粘液物品；offhandTool 指定必備工具如 yan 打火石/xuejia 剪刀)、按 opts 恢復飢餓/飽和/消耗/空氣/凍結並施加藥水。
 */
public class ConsumableItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {

    private final ConsumableOpts opts;

    public ConsumableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe, ConsumableOpts opts) {
        super(group, item, rt, recipe);
        this.opts = opts;
    }

    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            Player p = e.getPlayer();
            // 潛行右鍵不食用：讓出放置行為（食物可放置且挖掘保留粘液數據，見 PlantGuardListener）
            if (p.isSneaking()) return;
            if (opts.requireHungry && p.getFoodLevel() >= 20) return;
            PlayerInventory inv = p.getInventory();
            ItemStack off = inv.getItemInOffHand();

            if (opts.offhandTool != null) {
                if (off == null || off.getType() != opts.offhandTool) {
                    p.sendMessage("您必須使用主手且副手持有 " + com.haiman233.worldtaste.util.ZhNames.orEnglish(opts.offhandTool) + "！");
                    return;
                }
            } else if (off != null && SlimefunItem.getByItem(off) != null) {
                p.sendMessage("您必須使用主手進食且副手不能持有粘液科技物品！");
                return;
            }

            ItemStack main = inv.getItemInMainHand();
            if (main == null || main.getAmount() <= 0) return;
            // 到 0 必須清空主手槽位，避免 0 數量幽靈物品殘留（否則下次右鍵仍被識別/顯示）
            Stacks.consumeOneInMainHand(inv);
            if (opts.offhandTool != null && opts.consumeOffhand) {
                // 工具類副手（打火石/剪刀等有耐久物品）按 1 點耐久損耗，與原版使用工具一致；
                // 原 yan.js/xuejia.js 的 setAmount-1 整件消耗屬移植保真遺留（玩家反饋不合理）。
                // 不可損耗工具（無耐久上限）回退整件消耗；無敵工具不損耗也不消耗；
                // 耐久耗盡由 Stacks 清空副手槽位，防止 0 數量幽靈物品被 getType() 校驗無限複用。
                if (!Stacks.damageToolInOffHand(inv, 1)) {
                    Stacks.consumeOneInOffHand(inv);
                }
            }

            int food = opts.randomFood != null ? (ThreadLocalRandom.current().nextInt(opts.randomFood) + 1)
                    : (opts.food != null ? opts.food.intValue() : 0);
            if (opts.foodSet != null) p.setFoodLevel(opts.foodSet);
            else if (food > 0) p.setFoodLevel(p.getFoodLevel() + food);
            if (opts.saturationSet != null) p.setSaturation(opts.saturationSet);
            else if (opts.saturation != null) p.setSaturation((float) (p.getSaturation() + opts.saturation));
            if (opts.exhaustion != null) p.setExhaustion((float) (p.getExhaustion() - opts.exhaustion));
            if (opts.exhaustionSet != null) p.setExhaustion(opts.exhaustionSet.floatValue());
            if (opts.absorption != null) p.setAbsorptionAmount(opts.absorption);
            if (opts.remainingAirAdd != null) p.setRemainingAir(p.getRemainingAir() + opts.remainingAirAdd);
            if (opts.gameMode != null) {
                try { p.setGameMode(org.bukkit.GameMode.valueOf(opts.gameMode.toUpperCase(java.util.Locale.ROOT))); }
                catch (IllegalArgumentException ignored) {}
            }
            if (opts.satRegen != null) p.setSaturatedRegenRate(opts.satRegen);
            if (opts.unsatRegen != null) p.setUnsaturatedRegenRate(opts.unsatRegen);
            if (opts.starvation != null) p.setStarvationRate(opts.starvation);
            if (opts.maxAir != null) p.setMaximumAir(opts.maxAir);
            if (opts.remainingAir != null) p.setRemainingAir(opts.remainingAir);
            if (opts.freezeTicks != null) p.setFreezeTicks(opts.freezeTicks);

            for (Potion pt : opts.potions) {
                PotionEffectType type = PotionEffectType.getByName(pt.type);
                if (type != null) p.addPotionEffect(new PotionEffect(type, pt.duration, pt.amplifier, false));
                else WT.log("未知藥水類型: " + pt.type);
            }

            // 異域花園聯動：酒類飲品（items.yml alcohol 字段）飲用後累加其酒精度
            ExoticGardenHook.onDrink(p, this.getId());

            if (opts.message != null) p.sendMessage(opts.message);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_STRIDER_EAT, 1f, 1f);
        };
    }
}
