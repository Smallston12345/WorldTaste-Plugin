package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 機器配方補全：列出機器全部工作配方（產物代表，分頁），玩家選擇後自動從揹包
 * 將材料填入機器 GUI 的對應輸入槽（含大型配方的自定義綁定槽）。
 * <p>交互約定：<ul>
 * <li>左鍵點擊配方：補全該配方一份材料（可多次點擊在機器內逐份追加）；</li>
 * <li>右鍵點擊配方：像左鍵一樣逐份追加，但一次追加 {@link #FILL_GROUP_SIZE} 份（可多次點擊累加）；</li>
 * <li>左下角（45）：返回機器頁面；右下角（53）：翻頁（多頁時）。</li>
 * </ul>
 * 機器菜單在打開補全按鈕時直接傳入（不依賴準星）；無綁定輸入按 GUI 佈局順序
 * （槽位升序，12 起）填充，優先堆疊到已放同物品的槽，與 findMatch 映射一致。</p>
 */
public final class RecipeFillMenu {

    private static final int PER_PAGE = 45;
    private static final int SLOT_PAGE = 53;
    private static final int SLOT_BACK = 45;
    /** 右鍵補全的固定份數：一次補足該配方 N 份所需材料（可在此調整）。 */
    private static final int FILL_GROUP_SIZE = 8;

    private RecipeFillMenu() {}

    /** 打開配方選擇菜單。@param menu 玩家當前打開的機器菜單（補全按鈕所在），可能為 null 時走準星兜底 */
    public static void open(Player p, WTRecipeMachine machine, BlockMenu menu) {
        open(p, machine, menu, 0);
    }

    public static void open(Player p, WTRecipeMachine machine, BlockMenu menu, int page) {
        List<WTRecipe> recipes = machine.getRecipes();
        if (recipes.isEmpty()) {
            p.sendMessage(ChatColor.RED + "該機器沒有可補全的配方");
            return;
        }
        int pages = Math.max(1, (recipes.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        String title = ChatColor.stripColor(machine.getItemName()) + " · 選擇配方"
                + (pages > 1 ? " " + (pg + 1) + "/" + pages : "");
        ChestMenu gui = new ChestMenu(title);
        gui.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            gui.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        int start = pg * PER_PAGE;
        int shown = Math.min(PER_PAGE, recipes.size() - start);
        for (int i = 0; i < shown; i++) {
            final int index = start + i;
            final WTRecipe recipe = recipes.get(index);
            gui.addItem(i, describeRecipe(recipe, index), (pl, s, cursor, action) -> {
                if (action.isRightClicked()) {
                    // 右鍵：補全該配方固定 N 份
                    fillN(pl, machine, recipe, menu);
                } else {
                    // 左鍵：補全該配方一份（可多次點擊追加多份）
                    fill(pl, machine, recipe, menu);
                }
                return false;
            });
        }

        // 左下角（45）：返回機器頁面（關閉配方選擇，重新打開機器界面）
        gui.addItem(SLOT_BACK, backMachineItem(), (pl, s, cursor, action) -> {
            BlockMenu inv = resolveInv(pl, machine, menu);
            if (inv != null) inv.open(pl);
            return false;
        });

        // 右下角（53）：翻頁（多頁時）；單頁時留空
        if (pages > 1) {
            ItemStack pageBtn = pageItem("配方 " + (pg + 1) + "/" + pages);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + "左鍵：下一頁");
                lore.add(ChatColor.GRAY + "右鍵：上一頁");
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            gui.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                if (action.isRightClicked()) {
                    open(pl, machine, menu, pg - 1);
                } else {
                    open(pl, machine, menu, pg + 1);
                }
                return false;
            });
        }

        gui.open(p);
    }

    /** 左鍵：補全所選配方一份材料（每次點擊在當前槽位基礎上追加一份，可多次點擊堆疊多份）。 */
    private static void fill(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided) {
        int missing = fillOne(p, machine, r, provided);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + "材料已填充一份"
                : ChatColor.YELLOW + "填充完成，但缺少 " + missing + " 種材料");
    }

    /** 右鍵：像左鍵一樣逐份追加，但一次追加 {@link #FILL_GROUP_SIZE} 份材料（可多次點擊繼續累加）。 */
    private static void fillN(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided) {
        int missing = fillCore(p, machine, r, provided, FILL_GROUP_SIZE);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + "已補全該配方 " + FILL_GROUP_SIZE + " 份材料"
                : ChatColor.YELLOW + "補全完成，但缺少 " + missing + " 種材料");
    }

    /** 解析機器菜單（傳入或準星兜底），校驗歸屬。 */
    private static BlockMenu resolveInv(Player p, WTRecipeMachine machine, BlockMenu provided) {
        BlockMenu inv = provided;
        if (inv == null) {
            Block target = p.getTargetBlockExact(5);
            if (target != null) inv = BlockStorage.getInventory(target.getLocation());
        }
        if (inv == null || inv.getPreset() == null || inv.getPreset().getSlimefunItem() != machine) {
            p.sendMessage(ChatColor.RED + "補全失敗：請打開該機器（" + ChatColor.stripColor(machine.getItemName()) + "）後重試");
            return null;
        }
        return inv;
    }

    /**
     * 填充單個配方一份材料（增量語義：每次在當前槽位已有基礎上再追加一份），返回缺失材料數。
     */
    private static int fillOne(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided) {
        return fillCore(p, machine, r, provided, 1);
    }

    /** 填充核心：增量語義——在槽內已有基礎上追加 portions 份所需材料。返回缺失材料數（不發送消息）。 */
    private static int fillCore(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided, int portions) {
        BlockMenu inv = resolveInv(p, machine, provided);
        if (inv == null) return 0;
        int[] inputSlots = machine.getInputSlots();
        // 按 GUI 佈局順序（槽位數值升序，從上到下從左到右）遍歷——聲明順序可能不同（如恆温陳釀皿 [32,12,...]）
        int[] layoutSlots = inputSlots.clone();
        java.util.Arrays.sort(layoutSlots);
        boolean[] usedInput = new boolean[layoutSlots.length];
        int missing = 0;
        ItemStack[] input = r.getInput();
        for (int i = 0; i < input.length; i++) {
            ItemStack need = input[i];
            if (need == null) continue;
            int slot = r.inSlot(i);
            if (slot < 0) {
                // 無綁定輸入：優先堆疊到已放同物品且未滿的槽，其次空槽
                slot = findStackOrEmpty(inv, need, layoutSlots, usedInput);
            }
            if (slot < 0) {
                p.sendMessage(ChatColor.RED + "補全失敗：沒有空餘輸入槽");
                return missing;
            }
            ItemStack cur = inv.getItemInSlot(slot);
            if (cur != null && !cur.getType().isAir() && !SlimefunUtils.isItemSimilar(cur, need, true)) {
                p.sendMessage(ChatColor.YELLOW + "槽位 " + (slot + 1) + " 被其他物品佔用，跳過該材料");
                continue;
            }
            int have = (cur == null || cur.getType().isAir()) ? 0 : cur.getAmount();
            int capacity = (cur == null || cur.getType().isAir()) ? need.getMaxStackSize() : cur.getMaxStackSize();
            // 本次目標量：在已有基礎上追加 portions 份（左鍵 1 份、右鍵 FILL_GROUP_SIZE 份）
            int target = have + need.getAmount() * portions;
            if (have >= target) continue;
            int take = Math.min(target - have, capacity - have);
            if (take <= 0) continue;
            // 有多少拿多少：揹包不足時把現有部分也放入機器並提示差額，絕不吞掉玩家材料
            ItemStack collected = takeFromPlayer(p, need, take);
            if (collected == null || collected.getAmount() <= 0) {
                missing++;
                p.sendMessage(ChatColor.RED + "揹包缺少 " + displayName(need) + " ×" + take);
                continue;
            }
            int got = collected.getAmount();
            if (have == 0) {
                inv.replaceExistingItem(slot, collected);
            } else {
                ItemStack merged = cur.clone();
                merged.setAmount(have + got);
                inv.replaceExistingItem(slot, merged);
            }
            if (got < take) {
                missing++;
                p.sendMessage(ChatColor.RED + "材料不足：" + displayName(need) + " 還差 " + (take - got)
                        + " 個（已放入 " + got + " 個）");
            }
        }
        return missing;
    }

    /** 無綁定輸入槽選擇：優先「已放同物品且未滿」的槽（堆疊追加），其次空槽。 */
    private static int findStackOrEmpty(BlockMenu inv, ItemStack need, int[] layoutSlots, boolean[] usedInput) {
        // 1) 堆疊到已有同物品且未滿的槽
        for (int k = 0; k < layoutSlots.length; k++) {
            if (usedInput[k]) continue;
            ItemStack cur = inv.getItemInSlot(layoutSlots[k]);
            if (cur != null && !cur.getType().isAir()
                    && SlimefunUtils.isItemSimilar(cur, need, true)
                    && cur.getAmount() < cur.getMaxStackSize()) {
                usedInput[k] = true;
                return layoutSlots[k];
            }
        }
        // 2) 空槽
        for (int k = 0; k < layoutSlots.length; k++) {
            if (usedInput[k]) continue;
            ItemStack cur = inv.getItemInSlot(layoutSlots[k]);
            if (cur == null || cur.getType().isAir()) {
                usedInput[k] = true;
                return layoutSlots[k];
            }
        }
        return -1;
    }

    /** 從玩家揹包收集至多 amount 個匹配 need 的物品（有多少拿多少、全部扣除），返回實收堆；揹包一個都沒有時返回 null。 */
    private static ItemStack takeFromPlayer(Player p, ItemStack need, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack result = null;
        int left = amount;
        for (int k = 0; k < inv.getSize() && left > 0; k++) {
            ItemStack it = inv.getItem(k);
            if (it == null || it.getType().isAir()) continue;
            if (!SlimefunUtils.isItemSimilar(it, need, true)) continue;
            int take = Math.min(left, it.getAmount());
            if (take <= 0) continue;
            if (result == null) {
                result = it.clone();
                result.setAmount(take);
            } else {
                result.setAmount(result.getAmount() + take);
            }
            int rest = it.getAmount() - take;
            if (rest <= 0) inv.setItem(k, null);
            else {
                ItemStack reduced = it.clone();
                reduced.setAmount(rest);
                inv.setItem(k, reduced);
            }
            left -= take;
            if (left <= 0) break;
        }
        return result;
    }

    private static ItemStack describeRecipe(WTRecipe r, int index) {
        ItemStack[] out = r.getOutput();
        ItemStack icon = (out.length > 0 && out[0] != null) ? out[0].clone() : new ItemStack(Material.BARRIER);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "配方 " + (index + 1));
            lore.add(ChatColor.GRAY + "材料 " + countInputs(r) + " 項 · 耗時 " + (r.getTicks() / 2) + "s");
            lore.add(ChatColor.DARK_GRAY + "左鍵：補全該配方一份（可多次點擊追加）");
            lore.add(ChatColor.DARK_GRAY + "右鍵：補全該配方 " + FILL_GROUP_SIZE + " 份（可多次點擊追加）");
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static int countInputs(WTRecipe r) {
        int n = 0;
        for (ItemStack in : r.getInput()) {
            if (in != null) n++;
        }
        return n;
    }

    private static String displayName(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(stack.getItemMeta().getDisplayName());
        }
        return com.haiman233.worldtaste.util.ZhNames.orEnglish(stack.getType());
    }

    private static ItemStack pageItem(String name) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + name);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 左下角「返回機器」按鈕：關閉配方選擇，重新打開機器界面。 */
    private static ItemStack backMachineItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回機器");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "關閉配方選擇，回到機器界面");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
}
