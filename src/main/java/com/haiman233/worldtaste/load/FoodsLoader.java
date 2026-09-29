package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.items.ItemSpec;
import com.haiman233.worldtaste.items.ScriptItemFactory;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加載 foods.yml：應用 FoodComponent(nutrition/saturation/canAlwaysEat) 使食物可食，
 * 並登記 onEat 腳本(kind=eat)供 {@link com.haiman233.worldtaste.behavior.FoodConsumeListener} 追加效果。
 * eatseconds：Paper 1.21.11 的 FoodComponent 不支持，忽略（與 RSC 一致）。
 */
public final class FoodsLoader {

    private FoodsLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "foods.yml");
        int ok = 0, skip = 0, foodFail = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                if (!RegisterConditions.pass(s)) { skip++; continue; }
                String effId = s.getString("id_alias", id);
                ItemGroup g = WT.group(s.getString("item_group"));
                if (g == null) { MissingItems.record("物品組缺失:" + s.getString("item_group")); skip++; continue; }
                ItemStack display = WT.preload.get(effId.toUpperCase(java.util.Locale.ROOT));
                if (display == null) { MissingItems.record("無展示物品:" + effId); skip++; continue; }
                display = display.clone();
                float eatSeconds = (float) s.getDouble("eatseconds", s.getDouble("eat_seconds", 0));
                boolean foodOk = FoodHelper.apply(display, s.getInt("nutrition", 0), (float) s.getDouble("saturation", 0),
                        s.getBoolean("always_eatable", false), eatSeconds);
                if (!foodOk) foodFail++;

                SlimefunItemStack sfis = new SlimefunItemStack(effId, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] recipe = Read.recipe(s.getConfigurationSection("recipe"), 9);
                ItemSpec spec = ItemSpec.from(effId, s);
                SlimefunItem item = ScriptItemFactory.create(spec, g, sfis, rt, recipe);
                if (spec.vanilla) { try { item.setUseableInWorkbench(true); } catch (Throwable ignored) {} }
                item.register(WT.plugin);

                String script = s.getString("script");
                if (script != null) {
                    ConsumableOpts opts = Behaviors.consumables.get(script.trim());
                    if (opts != null && !opts.use) Behaviors.foodOnEat.put(effId, opts);
                }
                ok++;
            } catch (Exception e) {
                WT.log("foods.yml " + id + " 註冊失敗: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("foods.yml: 註冊 " + ok + ", 跳過 " + skip);
        if (foodFail > 0) {
            WT.plugin.getLogger().severe("foods.yml: " + foodFail
                    + " 個食物的 FoodComponent 應用失敗，這些食物將不可食用！");
        }
    }
}
