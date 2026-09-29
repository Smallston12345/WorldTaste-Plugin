package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import java.lang.reflect.Method;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;

/**
 * 給食物物品應用 Paper FoodComponent（nutrition/saturation/canAlwaysEat/eatSeconds）。
 * 通過 {@link org.bukkit.inventory.meta.ItemMeta#getFood()} 獲取組件（無組件時自動創建空實例），
 * 修改後 {@code setFood} 應用；eatSeconds 通過反射調用（Paper 1.21.6+ 支持）。
 */
public final class FoodHelper {

    private FoodHelper() {}

    /**
     * 1.20.5+ 才有 {@code ItemMeta#getFood}/FoodComponent（已核對 1.20.4 javadoc 完全不存在）。
     * 啓動時探測一次；低版本整體跳過，避免每個食物在 catch(Throwable) 裏逐條刷 NoSuchMethodError。
     */
    private static final boolean HAS_FOOD_API = detectFoodApi();
    private static boolean lowVersionLogged = false;

    private static boolean detectFoodApi() {
        try {
            org.bukkit.inventory.meta.ItemMeta.class.getMethod("getFood");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 應用 FoodComponent。返回是否成功（應用失敗時返回 false，便於上層統計升級告警）。
     *
     * <p>對齊 RSC {@code FoodReader} 的取值語義：
     * <ul>
     *   <li>{@code nutrition<1} → 提升為 1（WorldTaste 多數飲品/汁 gz1/gz2/fmjpgz 等 {@code kind:eat} 腳本
     *       在 foods.yml 無 nutrition，缺省 0；RSC 將 &lt;1 一律提升為 1 使其可食，恢復值主體由 onEat
     *       腳本 {@code opts.food/saturation} 提供）；</li>
     *   <li>{@code saturation<0} → 0；</li>
     *   <li>{@code canAlwaysEat} 取食物自身 {@code always_eatable}（默認 false，即飢餓時才可食）。</li>
     * </ul>
     * 實現説明：Paper 1.21.11 起 {@code org.bukkit.craftbukkit.inventory.components.CraftFoodComponent}
     * 已無無參構造器（僅有 FoodProperties / 拷貝 / Map 三種構造），反射 {@code newInstance()} 會拋
     * NoSuchMethodException；改用官方 API {@code ItemMeta#getFood()}：無 food 組件時返回新建的空實例，
     * 返回值為快照，修改後必須 {@code setFood} 應用。兼容 Paper 1.21.2+。
     * {@code eatSeconds} 非標準 API，仍以反射方式嘗試，不存在則忽略。</p>
     */
    public static boolean apply(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat, float eatSeconds) {
        if (stack == null) return true;
        if (!HAS_FOOD_API) {
            if (!lowVersionLogged) {
                lowVersionLogged = true;
                WT.log("當前服務端低於 1.20.5，無 FoodComponent API，foods.yml 的營養/飽食度組件不生效（腳本食物效果不受影響）");
            }
            return true;
        }
        if (nutrition < 1) nutrition = 1;
        if (saturation < 0f) saturation = 0f;
        final int fFood = nutrition;
        final float fSat = saturation;
        final boolean fAlways = canAlwaysEat;
        boolean[] ok = {true};
        stack.editMeta(meta -> {
            try {
                FoodComponent fc = meta.getFood();
                fc.setNutrition(fFood);
                fc.setSaturation(fSat);
                fc.setCanAlwaysEat(fAlways);
                if (eatSeconds > 0) {
                    try {
                        Method m = fc.getClass().getMethod("setEatSeconds", float.class);
                        m.invoke(fc, eatSeconds);
                    } catch (NoSuchMethodException ignored) {
                        // 當前 Paper 版本 FoodComponent 無 eatSeconds，忽略（與 RSC 一致）
                    }
                }
                meta.setFood(fc);
            } catch (Throwable e) {
                ok[0] = false;
                WT.log("FoodComponent 應用失敗: " + e);
            }
        });
        return ok[0];
    }
}