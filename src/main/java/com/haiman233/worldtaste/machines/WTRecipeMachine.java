package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Views;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 電力配方機器（recipe_machines.yml）。繼承 {@link AContainer} 複用能量/進度/GUI 基建，
 * 自定義 {@link #findNextRecipe} 與 {@link #tick} 以支持 {@link WTRecipe} 的概率產出與 noConsume。
 */
public class WTRecipeMachine extends AContainer implements RecipeDisplayItem {

    private final int[] inputSlots;
    private final int[] outputSlots;
    /** 輸入槽 GUI 索引 → 在 inputSlots 數組中的位置（-1=非輸入槽）。僅依賴 inputSlots（不變量），構造期一次預算，
     *  避免每 tick 在 findMatch 裏 new HashMap + Integer 裝箱（輸出阻塞機器每 tick 觸發，分配壓力可觀）。 */
    private final int[] posBySlot;
    private final List<WTRecipe> recipes;
    private final MenuDef menu;
    private final boolean hideAll;
    private final ItemStack progressBar;
    /** 是否啓用 SF-id 預篩（僅純-SF 機器為 true）。見 {@link #computeSfPrune}。 */
    private final boolean sfPrune;
    /** 進行中配方（按方塊），完成時取此處的 WTRecipe 做概率滾動。 */
    private final Map<org.bukkit.Location, WTRecipe> active = new ConcurrentHashMap<>();

    public WTRecipeMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                           int[] inputSlots, int[] outputSlots, List<WTRecipe> recipes,
                           int capacity, int consumption, int speed, MenuDef menu, boolean hideAll) {
        super(group, item, rt, recipe);
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.posBySlot = buildPosBySlot(inputSlots);
        this.recipes = recipes;
        this.sfPrune = computeSfPrune(recipes);
        this.menu = menu;
        this.hideAll = hideAll;
        // 默認進度條用打火石（可損壞物品）：Slimefun 的 updateProgressbar 會以耐久條 + 進度百分比 + 剩餘
        // 時間呈現，比靜態玻璃板更醒目（對齊 Slimefun 本體電力機器 ElectricSmeltery 等）。菜單配置的
        // progressbar 物品仍優先（menus.yml 每機器可覆蓋）。
        this.progressBar = (menu != null && menu.progressItem != null)
                ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL);
        setProcessingSpeed(Math.max(1, speed));
        setCapacity(Math.max(1, capacity));
        setEnergyConsumption(Math.max(1, Math.min(consumption, Math.max(1, capacity))));
        // AContainer 在 super() 中已用 this::constructMenu 建過 preset（此時字段尚未賦值），
        // 這裏字段就緒後重建 preset（覆蓋前一個），並補設進度條。
        createPreset(this, getInventoryTitle(), this::constructMenu);
        getMachineProcessor().setProgressBar(progressBar);
    }

    @Override
    public int[] getInputSlots() { return inputSlots; }

    /** 全部配方（供大配方菜單展示）。 */
    public List<WTRecipe> getRecipes() { return recipes; }

    /** 校驗當前機器輸入能否匹配任一配方（配方補全後的驗證，不消耗）。 */
    public boolean canMatch(BlockMenu inv) {
        return findMatch(inv) != null;
    }

    @Override
    public int[] getOutputSlots() { return outputSlots; }

    @Override
    public String getMachineIdentifier() { return getId(); }

    @Override
    public ItemStack getProgressBar() { return progressBar; }

    @Override
    protected void registerDefaultRecipes() { /* 配方由本類自行管理 */ }

    private int progressSlot() {
        return (menu != null && menu.progressSlot >= 0) ? menu.progressSlot : 22;
    }

    /** 額外的可交互槽位（不會被背景填充阻擋），子類（如模板機器）可覆蓋以加入模板槽等。 */
    protected java.util.Set<Integer> extraFunctionalSlots() {
        return java.util.Collections.emptySet();
    }

    @Override
    protected void constructMenu(BlockMenuPreset preset) {
        // super() 階段會提前調用一次（字段為 null），此時跳過；由構造器末尾重建 preset 時再真正構建。
        if (inputSlots == null || outputSlots == null) return;
        Set<Integer> placed = new HashSet<>();
        // 裝飾（來自菜單）
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
                preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
                placed.add(e.getKey());
            }
        }
        int pslot = progressSlot();
        // 功能槽
        Set<Integer> functional = new HashSet<>();
        for (int s : inputSlots) functional.add(s);
        for (int s : outputSlots) functional.add(s);
        if (pslot >= 0) functional.add(pslot);
        // 背景填充剩餘槽位
        java.util.Set<Integer> extra = extraFunctionalSlots();
        // 尺寸必須覆蓋所有功能槽(input/output/progress/extra)與裝飾槽：
        // BlockMenuPreset 按“已放置物品”自動定尺寸，而 output 槽只掛 click handler、不放置物品。
        // 若某功能槽超出自動尺寸(如機器無對應菜單、或菜單裝飾未覆蓋該槽)，運行期
        // getItemInSlot/consumeItem 會越界。此處按最大槽位向上取整到 9 的倍數(且 ≤54 揹包上限)。
        int declared = (menu != null && menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : inputSlots) maxSlot = Math.max(maxSlot, s);
        for (int s : outputSlots) maxSlot = Math.max(maxSlot, s);
        if (pslot >= 0) maxSlot = Math.max(maxSlot, pslot);
        for (int s : extra) maxSlot = Math.max(maxSlot, s);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        for (int i = 0; i < size; i++) {
            if (!functional.contains(i) && !placed.contains(i) && !extra.contains(i)) {
                preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
            }
        }
        // 配方補全按鈕（自定義：可完整選擇機器工作配方；JEG 配方補全僅按物品展示配方，大型配方不完整）
        int fillSlot = -1;
        for (int s : new int[]{53, 52, 8, 17, 26, 35, 44, 7, 16, 25, 43, 51, 0}) {
            if (!functional.contains(s) && !placed.contains(s) && !extra.contains(s)) { fillSlot = s; break; }
        }
        if (fillSlot < 0) {
            for (int s2 = 0; s2 < 54; s2++) {
                if (!functional.contains(s2) && !placed.contains(s2) && !extra.contains(s2)) { fillSlot = s2; break; }
            }

        // GUI 全被功能/裝飾佔滿（如恆温陳釀皿 54 格全滿）：覆蓋一個邊緣裝飾槽（僅裝飾顯示，不影響功能）
        if (fillSlot < 0) {
            for (int s3 : new int[]{53, 52, 8, 17, 26, 35, 44, 43, 51, 0}) {
                if (placed.contains(s3) && !functional.contains(s3) && !extra.contains(s3)) { fillSlot = s3; break; }
            }
        }
        }
        // 配方補全按鈕：所有配方機器顯示。小型機器原用 JEG 配方補全書，但 JEG Build 205
        // 對綁定槽配方存在循環左移缺陷（填充順序與 GUI 佈局不一致），統一改用自定義精確補全
        if (fillSlot >= 0) {
            preset.addItem(fillSlot, fillButton(), (player, s, cursor, action) -> {
                me.mrCookieSlime.Slimefun.api.inventory.BlockMenu bm = null;
                // InventoryView 在 1.21 由類改為接口，不能直接調用其方法，經反射工具取頂部容器
                org.bukkit.inventory.Inventory openTop = Views.top(player.getOpenInventory());
                if (openTop != null) {
                    org.bukkit.inventory.InventoryHolder holder = openTop.getHolder();
                    if (holder instanceof me.mrCookieSlime.Slimefun.api.inventory.BlockMenu blockMenu) {
                        bm = blockMenu;
                    }
                }
                com.haiman233.worldtaste.guide.RecipeFillMenu.open(player, WTRecipeMachine.this, bm);
                return false;
            });
        }

        // 進度佔位
        if (pslot >= 0 && !placed.contains(pslot)) {
            preset.addItem(pslot, progressBar, ChestMenuUtils.getEmptyClickHandler());
        }
        // 輸出槽允許取出
        for (int i : outputSlots) {
            // 輸出槽：允許取出（左鍵提起 / shift 批量取出）、禁止放入。
            // 必須用 AdvancedMenuClickHandler：普通 MenuClickHandler 的第三參數是 e.getCurrentItem()
            // （槽內物品），左鍵提起會被誤判為放入而取消；Advanced 版本才能拿到玩家光標
            // e.getCursor()（空手點擊 = AIR → 放行提起）。
            preset.addMenuClickHandler(i, new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e, org.bukkit.entity.Player player,
                                       int slot, org.bukkit.inventory.ItemStack cursor, me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }

                @Override
                public boolean onClick(org.bukkit.entity.Player player, int slot, org.bukkit.inventory.ItemStack cursor,
                                       me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }
            });
        }
    }

    @Override
    protected BlockBreakHandler onBlockBreak() {
        return new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = BlockStorage.getInventory(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), inputSlots);
                    inv.dropItems(b.getLocation(), outputSlots);
                    // 額外功能槽（如模板機器的模板槽）內容物也掉落，避免被吞
                    java.util.Set<Integer> extra = extraFunctionalSlots();
                    if (!extra.isEmpty()) {
                        int[] extraSlots = extra.stream().mapToInt(Integer::intValue).toArray();
                        inv.dropItems(b.getLocation(), extraSlots);
                    }
                }
                active.remove(b.getLocation());
                getMachineProcessor().endOperation(b);
            }
        };
    }

    @Override
    protected void tick(Block b) {
        BlockMenu inv = BlockStorage.getInventory(b.getLocation());
        if (inv == null) return;
        int pslot = progressSlot();
        CraftingOperation op = getMachineProcessor().getOperation(b);
        if (op == null) {
            MachineRecipe next = findNextRecipe(inv);
            if (next instanceof WTRecipe) {
                op = new CraftingOperation(next);
                getMachineProcessor().startOperation(b, op);
                active.put(b.getLocation(), (WTRecipe) next);
                getMachineProcessor().updateProgressBar(inv, pslot, op);
            }
            return;
        }
        if (!takeCharge(b.getLocation())) return;
        if (!op.isFinished()) {
            getMachineProcessor().updateProgressBar(inv, pslot, op);
            op.addProgress(getSpeed());
            return;
        }
        WTRecipe r = active.remove(b.getLocation());
        if (r != null) {
            pushRecipeOutputs(b, inv, r);
        }
        inv.replaceExistingItem(pslot, progressBar);
        getMachineProcessor().endOperation(b);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) {
        return matchRecipes(inv, recipes);
    }

    /**
     * 把已完成配方的產出推入機器（默認推入 outputSlots）。抽取為可覆蓋鈎子，供子類（如
     * {@link WTTemplateMachine} 的 {@code moreOutputIfMoreTemplates}）按需放大產出。
     */
    protected void pushRecipeOutputs(Block b, BlockMenu inv, WTRecipe r) {
        r.pushOutputs(inv, outputSlots);
    }

    /** 匹配結果：命中的配方 + 各輸入項選中的輸入槽下標（在 {@link #inputSlots} 中的位置）。消耗前保持有效。 */
    protected static final class Match {
        final WTRecipe recipe;
        final int[] chosen;
        Match(WTRecipe recipe, int[] chosen) { this.recipe = recipe; this.chosen = chosen; }
    }

    /** 在給定配方列表中匹配輸入（供模板機器按當前模板篩選後複用）。僅匹配與校驗，不消耗輸入。 */
    protected Match findMatch(BlockMenu inv, List<WTRecipe> recipeList) {
        int[] slots = inputSlots;
        int slotCount = slots.length;
        ItemStack[] slotItems = new ItemStack[slotCount];
        // posOf 已在構造期預算為 posBySlot（不變量），此處不再每 tick 重建 HashMap。
        for (int s = 0; s < slotCount; s++) {
            ItemStack it = inv.getItemInSlot(slots[s]);
            slotItems[s] = (it == null) ? null : ItemStackWrapper.wrap(it);
        }
        // 所有輸入槽為空時不可能命中任何配方（註冊配方至少含 1 個非空輸入）：
        // 直接返回，避免空閒機器每 tick 白遍歷全部配方（含昂貴的 isItemSimilar，高負載下顯著省 TPS）。
        boolean anyInput = false;
        for (ItemStack si : slotItems) {
            if (si != null) { anyInput = true; break; }
        }
        if (!anyInput) return null;
        // SF-id 預篩（僅純-SF 機器啓用，見 sfPrune）：每 tick 對每個非空輸入槽解析一次 SF id（讀 PDC），
        // 隨後用廉價必要條件 idCertainlyMismatch 跳過「兩端均 SF 且 id 不同」者——此類 isItemSimilar 必返回
        // false（其 both-SF 分支按 id 比較），故跳過不改變匹配結果，僅省去昂貴的 isItemSimilar（內含 2× getByItem）。
        // sfPrune=false 時 slotSfId=null，下方 !(sfPrune && ...) 短路為 true，行為與優化前逐字一致（零迴歸）。
        String[] slotSfId = sfPrune ? resolveSlotSfIds(slotItems) : null;
        // 本配方匹配過程中已被佔用的輸入槽（同一物理槽不允許被多個配方位消耗，除非總量足夠——
        // 現實現保守禁止共享，防止消耗越過實際存量導致吞物品）。
        boolean[] claimed = new boolean[slotCount];
        for (WTRecipe recipe : recipeList) {
            ItemStack[] inputs = recipe.getInput();
            int n = inputs.length;
            int[] chosen = new int[n];
            int matched = 0;
            boolean failed = false;
            java.util.Arrays.fill(claimed, false);
            for (int i = 0; i < n; i++) {
                chosen[i] = -1;
                ItemStack need = inputs[i];
                if (need == null) { matched++; continue; }
                int bound = recipe.inSlot(i);
                if (bound >= 0) {
                    // 綁定到指定槽：僅檢查該槽（posBySlot 覆蓋 0..53 GUI 槽；越界或非輸入槽 → -1 → failed）
                    int pos = (bound < posBySlot.length) ? posBySlot[bound] : -1;
                    if (pos < 0 || claimed[pos]) { failed = true; break; }
                    ItemStack in = slotItems[pos];
                    if (in != null && in.getAmount() >= need.getAmount()
                            && !(sfPrune && idCertainlyMismatch(slotSfId[pos], recipe.inputSfId(i)))
                            && (recipe.inputDamage(i) > 0
                                    ? toolSimilarIgnoreDamage(in, need)
                                    : SlimefunUtils.isItemSimilar(in, need, true))) {
                        chosen[i] = pos;
                        claimed[pos] = true;
                        matched++;
                    } else { failed = true; break; }
                } else {
                    // 無綁定輸入：在所有可滿足且未被本配方其它項佔用的槽中選「餘量最小」的（best-fit），
                    // 把大堆留給需要更多材料的輸入項——修復同物品多條輸入（如 [A×1, A×2]）在
                    // 兩條輸入都貪心命中同一槽後被 distinct 去重整條誤殺的問題
                    int best = -1;
                    int bestAmount = Integer.MAX_VALUE;
                    for (int s = 0; s < slotCount; s++) {
                        if (claimed[s]) continue;
                        ItemStack in = slotItems[s];
                        if (in == null || in.getAmount() < need.getAmount()
                                || (sfPrune && idCertainlyMismatch(slotSfId[s], recipe.inputSfId(i)))
                                || !(recipe.inputDamage(i) > 0
                                        ? toolSimilarIgnoreDamage(in, need)
                                        : SlimefunUtils.isItemSimilar(in, need, true))) {
                            continue;
                        }
                        if (in.getAmount() < bestAmount) {
                            best = s;
                            bestAmount = in.getAmount();
                            if (bestAmount == need.getAmount()) break; // 完全貼合，不可能更小
                        }
                    }
                    if (best >= 0) {
                        chosen[i] = best;
                        claimed[best] = true;
                        matched++;
                    } else { failed = true; break; }
                }
            }
            if (failed || matched != n) continue;
            int distinct = 0;
            for (int i = 0; i < n; i++) {
                boolean dup = false;
                for (int j = 0; j < i; j++) if (chosen[i] == chosen[j]) { dup = true; break; }
                if (!dup) distinct++;
            }
            if (distinct != n) continue;
            // 輸出放不下時跳過本配方嘗試下一個（而非整體放棄）：不同配方的輸出項可能不同，
            // 某項輸出放不下不應阻塞輸出項不同的其它可合成配方。
            if (!InvUtils.fitAll(inv.toInventory(), recipe.getOutput(), outputSlots)) continue;
            // tick 可能異步執行：匹配用的是快照，消耗前對選中槽位的實時內容再校驗，避免競態吞錯物品
            boolean stillValid = true;
            for (int i = 0; i < n; i++) {
                if (recipe.isNoConsume(i) || chosen[i] < 0) continue;
                ItemStack live = inv.getItemInSlot(slots[chosen[i]]);
                ItemStack need = inputs[i];
                if (live == null || live.getAmount() < need.getAmount()
                        || !(recipe.inputDamage(i) > 0
                                ? toolSimilarIgnoreDamage(live, need)
                                : SlimefunUtils.isItemSimilar(live, need, true))) {
                    stillValid = false;
                    break;
                }
            }
            if (!stillValid) continue;
            return new Match(recipe, chosen);
        }
        return null;
    }

    /**
     * 耐久類工具輸入比較：忽略損耗值（受損釣竿仍匹配新釣竿模板）。
     * 同材質（粘液物品按 id 一致）即匹配；模板帶顯示名時要求名稱一致。
     */
    private static boolean toolSimilarIgnoreDamage(ItemStack in, ItemStack need) {
        if (in == null || in.getType() != need.getType()) return false;
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfNeed =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(need);
        if (sfNeed != null) {
            io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfIn =
                    io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(in);
            return sfIn != null && sfIn.getId().equals(sfNeed.getId());
        }
        if (need.hasItemMeta() && need.getItemMeta().hasDisplayName()) {
            org.bukkit.inventory.meta.ItemMeta m = in.getItemMeta();
            return m != null && m.hasDisplayName()
                    && m.getDisplayName().equals(need.getItemMeta().getDisplayName());
        }
        return true;
    }
    /** 用本機器的全部配方匹配（不消耗）。 */
    protected Match findMatch(BlockMenu inv) {
        return findMatch(inv, recipes);
    }

    /** 構造期預算 inputSlots 的「GUI 槽 → 數組位置」查表（54 覆蓋整個揹包尺寸，-1=非輸入槽）。 */
    private static int[] buildPosBySlot(int[] inputSlots) {
        int[] pos = new int[54];
        java.util.Arrays.fill(pos, -1);
        for (int s = 0; s < inputSlots.length; s++) {
            int slot = inputSlots[s];
            if (slot >= 0 && slot < pos.length) pos[slot] = s;
        }
        return pos;
    }

    /** 預解析各非空輸入槽的 SF id（讀 PDC，每 tick 每槽一次）。僅 sfPrune=true 時調用。 */
    private static String[] resolveSlotSfIds(ItemStack[] slotItems) {
        String[] ids = new String[slotItems.length];
        for (int s = 0; s < slotItems.length; s++) {
            ItemStack it = slotItems[s];
            if (it != null && it.hasItemMeta()) {
                ids[s] = io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getItemDataService()
                        .getItemData(it.getItemMeta()).orElse(null);
            }
        }
        return ids;
    }

    /**
     * 廉價必要條件預篩：兩端均為已解析 SF 物品且 id 不同時返回 true（即「確定不匹配」，可安全跳過 isItemSimilar）。
     * <p>安全性依據 {@link SlimefunUtils#isItemSimilar}：當 item 與 sfitem 均為已註冊 SF 物品且 id 不同時，
     * 其 both-SF 分支（REF SlimefunUtils.java:363-366）必返回 false。其餘情形（任一為原版、id 相同、或無 PDC）
     * 一律返回 false（不跳過），仍交 isItemSimilar 定奪，完整保留 DistinctiveItem/meta 等邊界語義與 RSC 保真度。
     */
    private static boolean idCertainlyMismatch(String inId, String needId) {
        return inId != null && needId != null && !inId.equals(needId);
    }

    /**
     * 閘門：是否啓用 SF-id 預篩。僅當「≥2 配方 且 所有配方的所有非空輸入均為已註冊 SF 物品」時為 true。
     * <p>純-SF 機器（如頭顱類）的每 tick 掃描代價由 getByItem 主導，預篩可將其從 O(配方數) 次昂貴比較降至
     * O(命中) 次；而原版/混合機器的代價由廉價的類型短路主導，預篩無收益反增解析開銷，故關閉（零迴歸）。
     */
    private static boolean computeSfPrune(List<WTRecipe> recipes) {
        if (recipes == null || recipes.size() < 2) return false;
        for (WTRecipe recipe : recipes) {
            ItemStack[] inputs = recipe.getInput();
            for (int i = 0; i < inputs.length; i++) {
                if (inputs[i] != null && recipe.inputSfId(i) == null) return false;
            }
        }
        return true;
    }

    /** 消耗已匹配配方的輸入（跳過 noConsume 項與未佔用槽位；damage&gt;0 的工具類輸入按耐久損耗）。 */
    protected void consumeMatch(BlockMenu inv, Match m) {
        if (m == null) return;
        ItemStack[] inputs = m.recipe.getInput();
        for (int i = 0; i < inputs.length; i++) {
            if (m.recipe.isNoConsume(i) || m.chosen[i] < 0) continue;
            int damage = m.recipe.inputDamage(i);
            if (damage > 0) {
                damageTool(inv, inputSlots[m.chosen[i]], damage);
            } else {
                inv.consumeItem(inputSlots[m.chosen[i]], inputs[i].getAmount());
            }
        }
    }

    /**
     * 工具類輸入按耐久消耗：每次合成扣 damage 點耐久而不消耗物品本身（如捕魚野獵網的各類釣竿）。
     * 耐久耗盡時工具損壞消失；不可損耗（無耐久上限/無法破壞）的物品既不消耗也不損耗，避免吞工具。
     * 不可按耐久損耗的物品缺省語義為「不消耗」而非「整件消耗」——工具在機器內是長期駐留的生產資料。
     */
    private static void damageTool(BlockMenu inv, int slot, int damage) {
        ItemStack item = inv.getItemInSlot(slot);
        if (item == null || item.getType().isAir()) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable damageable)
                || item.getType().getMaxDurability() <= 0 || meta.isUnbreakable()) {
            return;
        }
        int newDamage = damageable.getDamage() + damage;
        if (newDamage >= item.getType().getMaxDurability()) {
            // 耐久耗盡：工具損壞消失
            inv.replaceExistingItem(slot, null);
            return;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = item.clone();
        updated.setItemMeta(damageable);
        inv.replaceExistingItem(slot, updated);
    }

    /** 匹配並消耗輸入（tick 路徑：操作會在機器內暫存，沒電也不會丟輸入）。 */
    protected MachineRecipe matchRecipes(BlockMenu inv, List<WTRecipe> recipeList) {
        Match m = findMatch(inv, recipeList);
        if (m == null) return null;
        consumeMatch(inv, m);
        return m.recipe;
    }

    /** 指南展示配方緩存。recipes 在構造後不變，展示列表亦不變，首次調用預算並緩存（同 R2「提升不變量」原則：
     *  避免每次打開指南都 new ArrayList + 重建）。 */
    private List<ItemStack> displayRecipesCache;

    @Override
    public List<ItemStack> getDisplayRecipes() {
        if (displayRecipesCache != null) return displayRecipesCache;
        List<ItemStack> out = new ArrayList<>();
        if (!hideAll) {
            for (WTRecipe r : recipes) {
                ItemStack[] in = r.getInput();
                ItemStack[] res = r.getOutput();
                ItemStack primaryOut = (res.length > 0 && res[0] != null)
                        ? res[0] : new ItemStack(Material.BARRIER);
                boolean any = false;
                // 大型配方完整展示（對齊 LogiTech RecipeDisplay）：每個非空輸入生成 [材料 → 產物] 對
                for (ItemStack i : in) {
                    if (i != null) {
                        out.add(i.clone());
                        out.add(describeOutput(primaryOut, r));
                        any = true;
                    }
                }
                if (!any) {
                    out.add(new ItemStack(Material.BARRIER));
                    out.add(describeOutput(primaryOut, r));
                }
            }
        }
        displayRecipesCache = out;
        return out;
    }

    /** 展示副本：克隆輸出並附加耗時 lore（僅展示用，不影響實際產出）。 */

    /** 配方補全按鈕。 */
    private static ItemStack fillButton() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BOOK);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GREEN + "配方補全");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(org.bukkit.ChatColor.GRAY + "選擇完整配方後自動從揹包填充材料");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack describeOutput(ItemStack out, WTRecipe r) {
        ItemStack clone = out.clone();
        org.bukkit.inventory.meta.ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            java.util.List<String> lore = meta.getLore() != null
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(org.bukkit.ChatColor.GRAY + "耗時: " + (r.getTicks() / 2) + "s");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }
}
