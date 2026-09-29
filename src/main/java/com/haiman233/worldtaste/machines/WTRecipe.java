package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 帶概率的機器配方：每個輸出有獨立 chance，可選 chooseOne（多輸出擇一）。
 * 對應 RSC 的 CustomMachineRecipe。ticks = seconds * 2（MachineRecipe 約定）。
 */
public class WTRecipe extends MachineRecipe {

    private final int[] chances;
    private final boolean chooseOne;
    private final boolean[] noConsume;
    /** 每個輸入綁定的菜單槽（-1=任意輸入槽），用於 linked 機器。 */
    private final int[] inSlots;
    /** 每個輸出綁定的菜單槽（-1=任意輸出槽），用於 linked 機器。 */
    private final int[] outSlots;
    /** 每個輸入每次合成的耐久消耗點數（0=按數量整件消耗，&gt;0=工具類輸入按耐久損耗而不消耗物品本身）。 */
    private final int[] inputDamage;
    /** 每個輸入預解析的 Slimefun id（原版輸入為 null）；惰性解析（首次 {@link #inputSfId} 時填充）。
     *  供 {@link WTRecipeMachine} 的 SF-id 預篩使用：兩端均 SF 且 id 不同時可安全跳過昂貴的
     *  {@code SlimefunUtils.isItemSimilar}（其 both-SF 分支按 id 比較，id 不同必返回 false）。 */
    private String[] inputSfIds;

    public WTRecipe(int seconds, ItemStack[] input, ItemStack[] output, int[] chances, boolean chooseOne, boolean[] noConsume) {
        this(seconds, input, output, chances, chooseOne, noConsume, new int[0], new int[0], new int[0]);
    }

    public WTRecipe(int seconds, ItemStack[] input, ItemStack[] output, int[] chances, boolean chooseOne,
                    boolean[] noConsume, int[] inSlots, int[] outSlots, int[] inputDamage) {
        super(seconds, input, output);
        this.chances = chances;
        this.chooseOne = chooseOne;
        this.noConsume = noConsume;
        this.inSlots = inSlots;
        this.outSlots = outSlots;
        this.inputDamage = inputDamage;
    }

    public int inSlot(int i) {
        return (inSlots != null && i < inSlots.length) ? inSlots[i] : -1;
    }

    /**
     * 第 i 個輸入預解析的 Slimefun id（原版輸入返回 null）。
     * <p>惰性解析並緩存：僅在 {@link WTRecipeMachine} 啓用 SF-id 預篩時被調用（多為每 tick 主線程訪問），
     * 故多方塊等不經過該路徑的 WTRecipe 不會為此付出解析開銷。重複解析為冪等（同一輸入得到同一 id），
     * 即便極端併發下重複填充也無害。
     */
    public String inputSfId(int i) {
        String[] ids = inputSfIds;
        if (ids == null) {
            ItemStack[] inputs = getInput();
            ids = new String[inputs.length];
            for (int k = 0; k < inputs.length; k++) {
                ItemStack in = inputs[k];
                if (in != null) {
                    SlimefunItem sf = SlimefunItem.getByItem(in);
                    ids[k] = sf != null ? sf.getId() : null;
                }
            }
            inputSfIds = ids;
        }
        return (i >= 0 && i < ids.length) ? ids[i] : null;
    }

    /** 第 i 個輸出的概率（百分比，越界返回 100）。 */
    public int chance(int i) {
        return (chances != null && i >= 0 && i < chances.length) ? chances[i] : 100;
    }

    public boolean isNoConsume(int index) {
        return index >= 0 && index < noConsume.length && noConsume[index];
    }

    /**
     * 第 i 個輸入每次合成消耗的耐久點數（0=按數量整件消耗，&gt;0=工具類輸入按耐久損耗，如釣竿）。
     * 配置缺省或越界返回 0（整件消耗）。
     */
    public int inputDamage(int i) {
        return (inputDamage != null && i >= 0 && i < inputDamage.length) ? inputDamage[i] : 0;
    }

    /** 完成時滾動產出並把每個通過項推入其綁定槽（無綁定則推入 freeSlots）。 */
    public void pushOutputs(me.mrCookieSlime.Slimefun.api.inventory.BlockMenu inv, int[] freeSlots) {
        pushOutputs(inv, freeSlots, 1);
    }

    /**
     * 完成時滾動產出並把每個通過項推入其綁定槽（無綁定則推入 freeSlots）。
     *
     * @param multiplier 產出數量乘數（&gt;1 時每個產出 amount *= multiplier）。用於模板機的
     *                   {@code moreOutputIfMoreTemplates}（按模板堆疊數放大產出，對齊 RSC
     *                   CustomTemplateMachine:274-275）；默認 1（普通機器不受影響）。
     */
    public void pushOutputs(me.mrCookieSlime.Slimefun.api.inventory.BlockMenu inv, int[] freeSlots, int multiplier) {
        ItemStack[] base = getOutput();
        List<Integer> passed = new ArrayList<>();
        for (int i = 0; i < base.length; i++) {
            int ch = i < chances.length ? chances[i] : 100;
            if (ch >= 100 || (ch > 0 && ThreadLocalRandom.current().nextInt(100) < ch)) passed.add(i);
        }
        if (chooseOne && !passed.isEmpty()) {
            // 對齊 RSC BlockMenuUtil.pushItem 的 chooseOne 語義：首個通過概率滾動的輸出即產出並停止
            // （取 passed.get(0)），而非在倖存者中隨機。這樣後序輸出僅作「主產出概率失敗時的回退」，
            // 產出分佈與原版一致（影響 111 個 chooseOne 配方）。
            int pick = passed.get(0);
            passed.clear();
            passed.add(pick);
        }
        for (int i : passed) {
            ItemStack o = base[i];
            if (o == null) continue;
            ItemStack out = o.clone();
            if (multiplier > 1) out.setAmount(out.getAmount() * multiplier);
            int slot = (outSlots != null && i < outSlots.length) ? outSlots[i] : -1;
            // 綁定槽推入有剩餘時回退到自由槽，仍有剩餘則掉落在機器旁（對齊 AContainer 的溢出處理）
            ItemStack leftover = (slot >= 0) ? inv.pushItem(out, slot) : inv.pushItem(out, freeSlots);
            if (leftover != null && leftover.getType() != org.bukkit.Material.AIR) {
                ItemStack rest = inv.pushItem(leftover, freeSlots);
                if (rest != null && rest.getType() != org.bukkit.Material.AIR
                        && inv.getLocation().getWorld() != null) {
                    inv.getLocation().getWorld().dropItemNaturally(inv.getLocation(), rest);
                }
            }
        }
    }
}
