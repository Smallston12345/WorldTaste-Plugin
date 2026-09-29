package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 工作台（workbenches.yml，如百味萬用爐）：複用 {@link WTRecipeMachine} 的配方/能量/GUI，
 * 但不自動 tick 合成；改為玩家點擊 click 槽位時即時合成（消耗能量、匹配並消耗輸入、產出）。
 */
public class WTWorkbench extends WTRecipeMachine {

    private final int clickSlot;

    public WTWorkbench(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                       int[] input, int[] output, List<WTRecipe> recipes,
                       int capacity, int consumption, int speed, MenuDef menu, boolean hideAll, int clickSlot) {
        super(group, item, rt, recipe, input, output, recipes, capacity, consumption, speed, menu, hideAll);
        this.clickSlot = clickSlot;
        // 用自定義 preset 覆蓋 AContainer 默認 preset，在 newInstance 掛接點擊合成
        new BlockMenuPreset(getId(), getItemName()) {
            @Override
            public void init() {
                WTWorkbench.this.constructMenu(this);
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                menu.addMenuClickHandler(clickSlot, (player, slot, clicked, action) -> {
                    WTWorkbench.this.craft(menu);
                    return false;
                });
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return flow == ItemTransportFlow.INSERT ? getInputSlots() : getOutputSlots();
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                if (p.hasPermission("slimefun.inventory.bypass")) return true;
                return WTWorkbench.this.canUse(p, false)
                        && (io.github.thebusybiscuit.slimefun4.implementation.Slimefun.instance().isUnitTest()
                        || io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getProtectionManager()
                                .hasPermission(p, b.getLocation(),
                                        io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction.INTERACT_BLOCK));
            }
        };
    }

    @Override
    protected void tick(Block b) {
        // 工作台不自動合成，僅在點擊 click 槽時合成
    }

    private void craft(BlockMenu menu) {
        // 順序很關鍵：先無消耗匹配，命中後才扣能量並消耗輸入、產出。
        //  · 若先扣能量再匹配：玩家空點擊會白扣機器能量（可被反覆刷空共享機器的能量）；
        //  · 若先消耗輸入再扣能量：沒電時已消耗輸入卻無產出（吞物品）。
        // 拆出無消耗 findMatch 後：無配方→不扣能量不消耗；有配方沒電→不消耗不產出（安全失敗）。
        var m = findMatch(menu);
        if (m == null) return;
        if (!takeCharge(menu.getLocation())) return;
        consumeMatch(menu, m);
        m.recipe.pushOutputs(menu, getOutputSlots());
    }
}
