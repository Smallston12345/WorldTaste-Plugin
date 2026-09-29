package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;

/**
 * 温度控制器（machines.yml 中 script: wendu，材質塗蠟銅塊）：酒窖多方塊的配套能源方塊，
 * 無 UI。儲電 500J，耗電 50J/t。
 *
 * <p>自身無獨立 ticker：酒窖運行（釀造/陳化計時推進）時由管理器 ticker 協調雙機耗電——
 * 管理器扣 100J/t、本方塊扣 50J/t，任一蓄電不足則釀造/陳化直接失敗產生廢液。
 * 電網會自動向其補電。</p>
 *
 * <p>耗電量經 {@link PowerConsumer} 暴露，註冊時自動寫入物品 lore。</p>
 */
public class TemperatureController extends SlimefunItem implements EnergyNetComponent, PowerConsumer {

    public static final int CAPACITY = 500;
    public static final int CONSUMPTION = 50;

    public TemperatureController(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                                 org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return CAPACITY;
    }

    @Override
    public int getConsumption() {
        return CONSUMPTION;
    }
}
