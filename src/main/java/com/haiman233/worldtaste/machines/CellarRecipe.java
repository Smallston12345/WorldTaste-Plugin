package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒窖配方（cellar.yml 定義，CellarLoader 加載）。
 *
 * <p>匹配為<b>按材料份數</b>（原料數量）：池內各原料的總份數（每單位果汁按其組成折算材料數，
 * 如 1 單位甜漿果汁 = 3 份甜漿果）與配方 ingredient 數量成<b>整數倍比例</b>（k 倍）時匹配，
 * 產出池內全部果汁單位數（= 配方投入果汁單位數 × k）。產物可為酒水（aging=true，可進入
 * 陳化模式增長酒精度）或副產物（aging=false，無酒精度）。</p>
 */
public final class CellarRecipe {

    private static final Map<String, CellarRecipe> RECIPES = new LinkedHashMap<>();

    /** 比例匹配結果。 */
    public static final class MatchResult {
        public final CellarRecipe recipe;
        public final int multiplier;

        MatchResult(CellarRecipe recipe, int multiplier) {
            this.recipe = recipe;
            this.multiplier = multiplier;
        }
    }

    public final String key;
    /** 投入物：ref(mc:XXX/sf:XXX) → 該原料的總份數（材料數量）。 */
    public final Map<String, Integer> ingredientRefs;
    /** 投入總單位數。 */
    public final int totalInput;
    /** 是否允許陳釀（true = 產物含酒精，可陳化增長；false = 副產物，無酒精度）。 */
    public final boolean aging;
    /** 單位產物模板。 */
    public final ItemStack result;

    public CellarRecipe(String key, Map<String, Integer> ingredientRefs, boolean aging, ItemStack result) {
        this.key = key;
        this.ingredientRefs = ingredientRefs;
        this.totalInput = sum(ingredientRefs);
        this.aging = aging;
        this.result = result;
    }

    private static int sum(Map<String, Integer> refs) {
        int t = 0;
        for (int n : refs.values()) t += n;
        return t;
    }

    public static void register(CellarRecipe r) {
        RECIPES.put(r.key, r);
    }

    public static CellarRecipe byKey(String key) {
        return key == null ? null : RECIPES.get(key);
    }

    public static List<CellarRecipe> all() {
        return new ArrayList<>(RECIPES.values());
    }

    /**
     * 按材料份數匹配：portions 為池內各原料的總份數（ref → 每單位果汁組成 × 單位數，
     * 支持分數），與配方 ingredient 數量成整數倍（k≥1）比例時匹配；多個候選取倍率最小者。
     * 清水不參與（無原料 ref）。
     */
    public static MatchResult match(Map<String, Double> portions) {
        MatchResult best = null;
        double total = 0;
        for (double n : portions.values()) total += n;
        for (CellarRecipe r : RECIPES.values()) {
            if (r.totalInput == 0 || total < r.totalInput) continue;
            double kd = total / r.totalInput;
            int k = (int) Math.round(kd);
            if (k < 1 || Math.abs(kd - k) > 1e-6) continue;
            boolean ok = true;
            for (Map.Entry<String, Double> e : portions.entrySet()) {
                double expect = r.ingredientRefs.getOrDefault(e.getKey(), 0) * (double) k;
                if (Math.abs(e.getValue() - expect) > 1e-6) {
                    ok = false;
                    break;
                }
            }
            if (ok && (best == null || k < best.multiplier)) best = new MatchResult(r, k);
        }
        return best;
    }
}
