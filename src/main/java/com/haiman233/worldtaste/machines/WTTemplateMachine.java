package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.inventory.ItemStack;

/**
 * 模板機器（template_machines.yml）：在 templateSlot 放入對應模板物品後，僅匹配該模板下的配方。
 * 不放模板則不合成；模板物品不被消耗。
 */
public class WTTemplateMachine extends WTRecipeMachine {

    private final int templateSlot;
    private final Map<String, List<WTRecipe>> byTemplate;
    /** 模板堆疊數放大產出（對齊 RSC moreOutputIfMoreTemplates：產出 amount *= 模板數量）。 */
    private final boolean moreOutputIfMoreTemplates;

    public WTTemplateMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                             int[] input, int[] output, List<WTRecipe> allRecipes,
                             Map<String, List<WTRecipe>> byTemplate,
                             int capacity, int consumption, int speed, MenuDef menu, boolean hideAll, int templateSlot,
                             boolean moreOutputIfMoreTemplates) {
        super(group, item, rt, recipe, input, output, allRecipes, capacity, consumption, speed, menu, hideAll);
        this.templateSlot = templateSlot;
        this.byTemplate = byTemplate;
        this.moreOutputIfMoreTemplates = moreOutputIfMoreTemplates;
        // super() 末尾重建 preset 時本類字段 templateSlot 尚未賦值（讀到 0），
        // 導致真正的模板槽被背景封死。字段就緒後再重建一次 preset。
        createPreset(this, getInventoryTitle(), this::constructMenu);
    }

    @Override
    protected Set<Integer> extraFunctionalSlots() {
        return Collections.singleton(templateSlot);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) {
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        SlimefunItem sf = SlimefunItem.getByItem(tpl);
        if (sf == null) return null;
        List<WTRecipe> list = byTemplate.get(sf.getId().toUpperCase(java.util.Locale.ROOT));
        if (list == null || list.isEmpty()) return null;
        return matchRecipes(inv, list);
    }

    @Override
    protected void pushRecipeOutputs(org.bukkit.block.Block b, BlockMenu inv, WTRecipe r) {
        if (!moreOutputIfMoreTemplates) {
            super.pushRecipeOutputs(b, inv, r);
            return;
        }
        // 對齊 RSC CustomTemplateMachine:274-275：產出數量乘以當前模板堆疊數（模板不被消耗，堆疊持續放大）。
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        int mult = (tpl != null && tpl.getType() != org.bukkit.Material.AIR) ? Math.max(1, tpl.getAmount()) : 1;
        r.pushOutputs(inv, getOutputSlots(), mult);
    }
}
