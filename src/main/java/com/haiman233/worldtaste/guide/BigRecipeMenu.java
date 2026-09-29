package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 大型配方展示菜單（對齊 LogiTech 配方展示思路）：
 * <ul>
 *   <li>第 0 頁 = 機器自身的合成配方（標準 3x3：材料 3-5/12-14/21-23，RecipeType 10，產物 16）；
 *       右下角（53）為「配方展示」按鈕——以機器配方補全同款形式列出該機器全部產物，點擊產物
 *       進入其專屬的大型配方展示頁；</li>
 *   <li>產物列表頁 = 每個產物一格（0..44，按主產物去重），45 返回合成配方頁、53 翻頁；</li>
 *   <li>產物配方頁 = 大型配方展示：材料區鋪滿 0..53（產物槽 24、機器圖標 8、返回 35、翻頁 53 除外），
 *       綁定槽直映、衝突/未綁定倒序補位；翻頁在該產物的配方集合內循環，返回 35 回產物列表；</li>
 *   <li>所有粘液材料點擊可跳轉到對應材料的指南頁（依賴配方堆攜帶 slimefun id PDC，
 *       見 {@code Read.resolve} 的前向引用修復）。</li>
 * </ul>
 */
public final class BigRecipeMenu {

    /** 工作配方頁固定槽位。 */
    private static final int SLOT_ICON = 8;      // 右上角：機器圖標
    private static final int SLOT_OUTPUT = 24;   // 產物
    private static final int SLOT_BACK = 35;     // 返回
    private static final int SLOT_PAGE = 53;     // 右下角：翻頁 / 配方展示入口

    private BigRecipeMenu() {}

    /** 是否為大型配方機器：任一工作配方的輸入項數超過 9（3x3 標準以上）。
     *  大型機器使用本大配方菜單；普通機器保留 JEG/Slimefun 默認配方展示。 */
    public static boolean isLargeRecipeMachine(WTRecipeMachine machine) {
        for (WTRecipe r : machine.getRecipes()) {
            int n = 0;
            for (ItemStack in : r.getInput()) if (in != null) n++;
            if (n > 9) return true;
        }
        return false;
    }

    /** 兼容入口：0=合成配方頁；i=按全局順序的第 i 個工作配方頁（不經產物列表）。 */
    public static void open(Player p, WTRecipeMachine machine, int index, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workCount = recipes.size();
        int total = workCount + 1; // 0 = 合成頁
        int idx = ((index % total) + total) % total;

        if (idx == 0) {
            openCraftPage(p, machine, backOpener);
        } else {
            int[] all = new int[workCount];
            for (int i = 0; i < workCount; i++) all[i] = i;
            openWorkPage(p, machine, all, idx - 1, false, backOpener);
        }
    }

    /** 合成配方頁：標準 3x3 佈局（對齊 LogiTech），53 = 配方展示（產物列表）。 */
    private static void openCraftPage(Player p, WTRecipeMachine machine, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workCount = recipes.size();
        String title = ChatColor.stripColor(machine.getItemName()) + " · 合成配方";
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        int[] craftSlots = {3, 4, 5, 12, 13, 14, 21, 22, 23};
        ItemStack[] craft = machine.getRecipe();
        for (int i = 0; i < 9 && i < craft.length; i++) {
            if (craft[i] == null) continue;
            ItemStack display = describeCraftInput(craft[i], i);
            menu.addItem(craftSlots[i], display, (pl, s, cursor, action) -> {
                navigateIngredient(pl, display, () -> openCraftPage(pl, machine, backOpener));
                return false;
            });
        }
        ItemStack rtIcon = machine.getRecipeType().toItem();
        menu.addItem(10, rtIcon != null ? rtIcon : ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(16, describeMachine(machine, null), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_ICON, infoItem("合成配方", "使用 " + recipeTypeName(machine) + " 合成該機器"), ChestMenuUtils.getEmptyClickHandler());
        // 返回（左下）
        menu.addItem(35, backItem(backOpener == null), (pl, s, cursor, action) -> {
            if (backOpener != null) backOpener.run();
            else JegHook.openGuide(pl);
            return false;
        });
        // 右下角：配方展示（產物列表，配方補全同款形式）
        if (workCount > 0) {
            menu.addItem(SLOT_PAGE, productsButton(workCount), (pl, s, cursor, action) -> {
                openProductList(pl, machine, 0, backOpener);
                return false;
            });
        }
        menu.open(p);
    }

    /** 產物列表頁（配方補全同款形式）：主產物去重後逐格展示，點擊進入該產物的配方頁。 */
    private static void openProductList(Player p, WTRecipeMachine machine, int page, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        // 按主產物身份去重（SF id 優先，否則材質+顯示名），記錄每產物命中的配方下標
        Map<String, ItemStack> products = new LinkedHashMap<>();
        Map<String, List<Integer>> indices = new LinkedHashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            ItemStack[] out = recipes.get(i).getOutput();
            ItemStack main = (out.length > 0 && out[0] != null) ? out[0] : null;
            if (main == null) continue;
            String key = productKey(main);
            products.putIfAbsent(key, main);
            indices.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        List<String> keys = new ArrayList<>(products.keySet());
        if (keys.isEmpty()) {
            open(p, machine, 0, backOpener);
            return;
        }

        int perPage = 45; // 0..44 放產物，45 返回，53 翻頁
        int pages = (keys.size() + perPage - 1) / perPage;
        int pg = ((page % pages) + pages) % pages;

        String title = ChatColor.stripColor(machine.getItemName()) + " · 產物配方 " + (pg + 1) + "/" + pages;
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }
        for (int i = 0; i < perPage; i++) {
            int k = pg * perPage + i;
            if (k >= keys.size()) break;
            String key = keys.get(k);
            ItemStack out = products.get(key);
            List<Integer> idxs = indices.get(key);
            menu.addItem(i, productEntry(out, idxs.size()), (pl, s, cursor, action) -> {
                int[] arr = new int[idxs.size()];
                for (int j = 0; j < arr.length; j++) arr[j] = idxs.get(j);
                openWorkPage(pl, machine, arr, 0, true, backOpener);
                return false;
            });
        }
        // 返回合成配方頁（45）
        menu.addItem(45, backItem(false), (pl, s, cursor, action) -> {
            openCraftPage(pl, machine, backOpener);
            return false;
        });
        // 翻頁（53）
        if (pages > 1) {
            ItemStack pageBtn = pageItem("翻頁 " + (pg + 1) + "/" + pages);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + "左鍵：下一頁");
                lore.add(ChatColor.GRAY + "右鍵：上一頁");
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            menu.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                openProductList(pl, machine, action.isRightClicked() ? pg - 1 : pg + 1, backOpener);
                return false;
            });
        }
        menu.open(p);
    }

    /** 大型配方展示頁：cycle = 可循環展示的配方下標（全局順序），fromList = 是否從產物列表進入。 */
    private static void openWorkPage(Player p, WTRecipeMachine machine, int[] cycle, int pos, boolean fromList, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workIdx = cycle[pos];
        WTRecipe r = recipes.get(workIdx);
        int workCount = recipes.size();
        String title = ChatColor.stripColor(machine.getItemName()) + " · 配方 " + (pos + 1) + "/" + cycle.length;
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 固定槽位
        boolean[] reserved = new boolean[54];
        reserved[SLOT_ICON] = reserved[SLOT_OUTPUT] = reserved[SLOT_BACK] = reserved[SLOT_PAGE] = true;

        // 材料區：綁定槽直映（跳過固定槽），未綁定/衝突從 52 倒序補位
        ItemStack[] input = r.getInput();
        boolean[] used = new boolean[54];
        for (int i = 0; i < input.length; i++) {
            if (input[i] == null) continue;
            int slot = r.inSlot(i);
            if (slot < 0 || slot >= 54 || reserved[slot]) slot = -1;
            if (slot < 0) {
                for (int s = 52; s >= 0; s--) {
                    if (!reserved[s] && !used[s]) { slot = s; break; }
                }
            }
            if (slot < 0 || used[slot]) continue;
            used[slot] = true;
            ItemStack display = describeInput(input[i], i);
            menu.addItem(slot, display, (pl, s, cursor, action) -> {
                navigateIngredient(pl, display, () -> openWorkPage(pl, machine, cycle, pos, fromList, backOpener));
                return false;
            });
        }

        // 產物：主產物放 24（多產物在 lore 提示）
        ItemStack[] output = r.getOutput();
        ItemStack mainOut = (output.length > 0 && output[0] != null) ? output[0] : new ItemStack(Material.BARRIER);
        menu.addItem(SLOT_OUTPUT, describeOutput(mainOut, r, output.length), ChestMenuUtils.getEmptyClickHandler());

        // 機器圖標（右上角）
        menu.addItem(SLOT_ICON, describeMachine(machine, r), ChestMenuUtils.getEmptyClickHandler());

        // 返回（46）：從產物列表進入 → 回產物列表；否則回上級/指南
        menu.addItem(SLOT_BACK, backItem(backOpener == null && !fromList), (pl, s, cursor, action) -> {
            if (fromList) openProductList(pl, machine, 0, backOpener);
            else if (backOpener != null) backOpener.run();
            else JegHook.openGuide(pl);
            return false;
        });

        // 翻頁（53）：在 cycle 內循環；單配方時為返回鍵
        if (cycle.length > 1) {
            ItemStack pageBtn = pageItem("配方 " + (pos + 1) + "/" + cycle.length);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + "左鍵：下一頁");
                lore.add(ChatColor.GRAY + "右鍵：上一頁");
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            menu.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                int next = action.isRightClicked() ? (pos - 1 + cycle.length) % cycle.length : (pos + 1) % cycle.length;
                openWorkPage(pl, machine, cycle, next, fromList, backOpener);
                return false;
            });
        } else {
            menu.addItem(SLOT_PAGE, backItem(backOpener == null && !fromList), (pl, s, cursor, action) -> {
                if (fromList) openProductList(pl, machine, 0, backOpener);
                else if (backOpener != null) backOpener.run();
                else JegHook.openGuide(pl);
                return false;
            });
        }

        menu.open(p);
    }

    /** 材料點擊導航：子機器 → 打開其大配方菜單；普通粘液物品 → 跳轉指南材料頁；原版材料不動。 */
    private static void navigateIngredient(Player pl, ItemStack display, Runnable reopenSelf) {
        SlimefunItem sf = SlimefunItem.getByItem(display);
        if (sf instanceof WTRecipeMachine sub) {
            BigRecipeMenu.open(pl, sub, 0, reopenSelf);
        } else if (sf != null) {
            // 材料堆帶 slimefun id PDC（Read.resolve 前向引用修復後所有配方材料均可識別）；
            // 經 facade 派發到當前指南實現（JEG 安裝時即 JEG 頁面），addToHistory 支持返回
            PlayerProfile.get(pl, profile -> SlimefunGuide.displayItem(profile, sf, true));
        }
        // 原版材料：無粘液頁可跳，菜單保持不動
    }

    /** 產物身份鍵：SF id 優先，否則材質+顯示名。 */
    private static String productKey(ItemStack out) {
        SlimefunItem sf = SlimefunItem.getByItem(out);
        if (sf != null) return "sf:" + sf.getId();
        String name = out.hasItemMeta() && out.getItemMeta().hasDisplayName()
                ? out.getItemMeta().getDisplayName() : "";
        return "mc:" + out.getType() + ":" + name;
    }

    private static String recipeTypeName(WTRecipeMachine machine) {
        try {
            return ChatColor.stripColor(machine.getRecipeType().toItem().getItemMeta().getDisplayName());
        } catch (Throwable t) {
            return machine.getRecipeType().getKey().getKey();
        }
    }

    private static ItemStack describeInput(ItemStack in, int index) {
        ItemStack clone = in.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "材料 " + (index + 1));
            if (clone.getAmount() > 1) lore.add(ChatColor.RED + "數量: " + clone.getAmount());
            SlimefunItem sf = SlimefunItem.getByItem(clone);
            if (sf instanceof WTRecipeMachine) lore.add(ChatColor.DARK_GRAY + "點擊打開該機器配方");
            else if (sf != null) lore.add(ChatColor.DARK_GRAY + "點擊查看獲取方式");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    private static ItemStack describeCraftInput(ItemStack in, int index) {
        ItemStack clone = in.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "合成材料 " + (index + 1));
            if (clone.getAmount() > 1) lore.add(ChatColor.RED + "數量: " + clone.getAmount());
            SlimefunItem sf = SlimefunItem.getByItem(clone);
            if (sf instanceof WTRecipeMachine) lore.add(ChatColor.DARK_GRAY + "點擊打開該機器配方");
            else if (sf != null) lore.add(ChatColor.DARK_GRAY + "點擊查看獲取方式");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    /** 產物列表條目：概率/數量 lore + 配方數提示。 */
    private static ItemStack productEntry(ItemStack out, int recipeCount) {
        ItemStack clone = out.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "產物");
            lore.add(ChatColor.GRAY + "共有 " + recipeCount + " 個配方");
            lore.add(ChatColor.YELLOW + "點擊查看配方");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    /** 主產物展示：概率/數量 lore，多產物在 lore 提示其餘。 */
    private static ItemStack describeOutput(ItemStack out, WTRecipe r, int totalOutputs) {
        ItemStack clone = out.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "產物 1");
            int ch = r.chance(0);
            if (ch < 100) lore.add(ChatColor.YELLOW + "概率: " + ch + "%");
            if (clone.getAmount() > 1) lore.add(ChatColor.RED + "數量: " + clone.getAmount());
            if (totalOutputs > 1) lore.add(ChatColor.DARK_GRAY + "另有 " + (totalOutputs - 1) + " 個產物");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    private static ItemStack describeMachine(WTRecipeMachine machine, WTRecipe r) {
        ItemStack icon = machine.getItem().clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            if (r != null) {
                lore.add(ChatColor.GRAY + "耗時: " + (r.getTicks() / 2) + "s");
                lore.add(ChatColor.DARK_GRAY + "在該機器中製作");
            } else {
                lore.add(ChatColor.DARK_GRAY + "機器本體（合成產物）");
            }
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack infoItem(String name, String desc) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + desc);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 合成配方頁右下角按鈕：進入產物列表（配方補全同款形式）。 */
    private static ItemStack productsButton(int workCount) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "配方展示");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "按產物瀏覽全部工作配方");
            lore.add(ChatColor.GRAY + "當前共 " + workCount + " 個配方");
            lore.add("");
            lore.add(ChatColor.YELLOW + "點擊打開產物列表");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
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

    private static ItemStack backItem(boolean toGuide) {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + (toGuide ? "返回指南" : "返回"));
            it.setItemMeta(meta);
        }
        return it;
    }
}
