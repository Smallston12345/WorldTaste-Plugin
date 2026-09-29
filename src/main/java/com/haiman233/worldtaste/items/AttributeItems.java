package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.PiglinBarterDrop;
import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactive;
import io.github.thebusybiscuit.slimefun4.core.attributes.Soulbound;
import io.github.thebusybiscuit.slimefun4.core.attributes.WitherProof;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactivity;
import org.bukkit.block.Block;
import org.bukkit.entity.Wither;
import org.bukkit.inventory.ItemStack;

/**
 * 原 RSC 通過 ByteBuddy 動態疊加屬性接口；此處針對 WorldTaste 實際用到的單屬性組合預定義類。
 * 覆蓋：radioactive(可放置/消耗)、soulbound、wither-proof、piglin-barter、energy。
 */
public final class AttributeItems {

    private AttributeItems() {}

    /** 放射性消耗品（script + radiation，如輻射魚肉）。 */
    public static class RadioactiveConsumable extends ConsumableItem implements Radioactive {
        private final Radioactivity level;
        public RadioactiveConsumable(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, ConsumableOpts opts, Radioactivity level) {
            super(g, i, rt, r, opts);
            this.level = level;
        }
        @Override
        public Radioactivity getRadioactivity() { return level; }
    }

    /** 放射性方塊（placeable + radiation）。 */
    public static class RadioactiveItem extends WTItem implements Radioactive {
        private final Radioactivity level;
        public RadioactiveItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, Radioactivity level) {
            super(g, i, rt, r);
            this.level = level;
        }
        @Override
        public Radioactivity getRadioactivity() { return level; }
    }

    /** 靈魂綁定方塊（placeable + soulbound）。 */
    public static class SoulboundItem extends WTItem implements Soulbound {
        public SoulboundItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) {
            super(g, i, rt, r);
        }
    }

    /** 防凋靈方塊（placeable + anti_wither）。 */
    public static class WitherProofItem extends WTItem implements WitherProof {
        public WitherProofItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) {
            super(g, i, rt, r);
        }
        @Override
        public void onAttack(Block block, Wither wither) {
            // 標記接口由 Slimefun 攔截凋靈破壞；此處空實現
        }
    }

    /** 豬靈以物易物掉落（不可放置 + piglin_trade_chance）。 */
    public static class PiglinBarterItem extends WTUnplaceableItem implements PiglinBarterDrop {
        private final int chance;
        public PiglinBarterItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, int chance) {
            super(g, i, rt, r);
            this.chance = chance;
        }
        @Override
        public int getBarteringLootChance() { return chance; }
    }

    /** 可充能物品（energy_capacity）。 */
    public static class EnergyItem extends WTItem implements EnergyNetComponent {
        private final int capacity;
        public EnergyItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, int capacity) {
            super(g, i, rt, r);
            this.capacity = capacity;
        }
        @Override
        public EnergyNetComponentType getEnergyComponentType() { return EnergyNetComponentType.CAPACITOR; }
        @Override
        public int getCapacity() { return capacity; }
    }

    public static Radioactivity parseRadiation(String name) {
        if (name == null) return null;
        try {
            return Radioactivity.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
