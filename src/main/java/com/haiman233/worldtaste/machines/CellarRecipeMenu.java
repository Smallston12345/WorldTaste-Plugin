package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.jeg.JegHook;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 酒窖配方展示菜單（佈局與榨汁盆配方頁一致，無混合材料頁）：
 * <ul>
 *   <li>概覽頁：酒窖管理器自身合成配方（標準 3x3，未配置則提示待定），右下角書本按鈕
 *       進入配方展示頁，門=返回指南；</li>
 *   <li>配方頁：背景為粘液 UI，綠/黃玻璃板環繞紅色配方區展示投入材料（份數體現為堆疊數），
 *       右側單位產物並標註產出份數（= 投入總份數）；底排釀造台=釀造於酒窖、時鐘=釀造時長、
 *       烈焰粉/惡魂之淚=是否可陳釀、門=返回（從機器進入時返回酒窖頁面，否則返回指南）、
 *       箭矢=翻頁（左鍵下一頁、右鍵上一頁）。</li>
 * </ul>
 */
public final class CellarRecipeMenu {

    /** 配方頁固定槽位。 */
    private static final int[] INGREDIENT_SLOTS = {20, 21, 22, 29, 30, 31}; // 紅色區
    private static final int SLOT_OUTPUT = 34;   // 右側產出
    private static final int SLOT_TYPE = 47;     // 釀造台：釀造於酒窖
    private static final int SLOT_DURATION = 49; // 時鐘：釀造時長
    private static final int SLOT_AGING = 51;    // 是否可陳釀
    private static final int SLOT_BACK = 52;     // 門：返回
    private static final int SLOT_PAGE = 53;     // 箭矢：翻頁

    /** 配方列表頁槽位：0~44 成品、45 返回指南、53 翻頁。 */
    private static final int SLOT_LIST_BACK = 45;
    private static final int SLOT_LIST_PAGE = 53;
    private static final int LIST_PAGE_SIZE = 45;

    private CellarRecipeMenu() {}

    /** 酒窖管理器自身配方概覽頁（指南/機器入口），右下角書本按鈕進入配方展示頁。 */
    public static void openOverview(Player p) {
        ChestMenu menu = baseMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY + "· 自身配方");
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem manager =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById("WT_JIUJIAO");
        if (manager == null) return;

        int[] craftSlots = {3, 4, 5, 12, 13, 14, 21, 22, 23};
        ItemStack[] craft = manager.getRecipe();
        boolean any = false;
        for (int i = 0; i < 9 && i < craft.length; i++) {
            if (craft[i] == null) continue;
            any = true;
            menu.addItem(craftSlots[i], craft[i].clone(), ChestMenuUtils.getEmptyClickHandler());
        }
        if (!any) {
            menu.addItem(13, noneItem(), ChestMenuUtils.getEmptyClickHandler());
        }
        // 機器圖標 + 產物位
        menu.addItem(16, machineIcon(manager), ChestMenuUtils.getEmptyClickHandler());
        // 返回指南 / 右下角書本進入配方展示
        menu.addItem(35, backToGuideItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(53, entryButton(), (pl, s, cursor, action) -> {
            openRecipeList(pl, 0, null);
            return false;
        });
        menu.open(p);
    }

    /** 書本入口按鈕（帶 PDC 標記，指南注入與概覽頁共用）。 */
    public static ItemStack entryButton() {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "酒窖配方");
            meta.getPersistentDataContainer().set(
                    com.haiman233.worldtaste.guide.CellarGuideListener.KEY_ENTRY,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * 配方列表頁：0~44 展示全部配方成品（45 個/頁），點擊成品進入對應配方詳情；
     * 45 返回指南，53 翻頁（左鍵下一頁/右鍵上一頁）。
     * cellar 為來源酒窖管理器方塊（詳情頁返回鍵回到該頁面；null = 從指南進入）。
     */
    public static void openRecipeList(Player p, int page, Block cellar) {
        List<CellarRecipe> recipes = CellarRecipe.all();
        if (recipes.isEmpty()) {
            p.sendMessage("§c暫無酒窖配方（cellar.yml）。");
            return;
        }
        int pages = (recipes.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;
        int idx = ((page % pages) + pages) % pages;

        ChestMenu menu = baseMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY
                + "· 配方列表 " + (idx + 1) + "/" + pages);
        int start = idx * LIST_PAGE_SIZE;
        for (int i = start; i < recipes.size() && i < start + LIST_PAGE_SIZE; i++) {
            CellarRecipe r = recipes.get(i);
            int recipeIndex = i;
            menu.addItem(i - start, listEntry(r), (pl, s, cursor, action) -> {
                openRecipes(pl, recipeIndex, cellar);
                return false;
            });
        }
        menu.addItem(SLOT_LIST_BACK, backToGuideItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(SLOT_LIST_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openRecipeList(pl, action.isRightClicked() ? idx - 1 : idx + 1, cellar);
            return false;
        });
        menu.open(p);
    }

    /** 配方列表條目：成品 + 點擊提示。 */
    private static ItemStack listEntry(CellarRecipe r) {
        ItemStack it = r.result.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.YELLOW + "點擊查看配方詳情");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * 固定配方頁：每頁展示一個配方，箭矢翻頁。
     * cellar 為來源酒窖管理器方塊（從酒窖頁面進入時返回鍵回到該頁面；null = 從指南進入）。
     */
    public static void openRecipes(Player p, int index, Block cellar) {
        List<CellarRecipe> recipes = CellarRecipe.all();
        if (recipes.isEmpty()) {
            p.sendMessage("§c暫無酒窖配方（cellar.yml）。");
            return;
        }
        int idx = ((index % recipes.size()) + recipes.size()) % recipes.size();
        CellarRecipe r = recipes.get(idx);

        ChestMenu menu = baseMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY
                + "· 配方 " + (idx + 1) + "/" + recipes.size());
        fillRecipeDeco(menu);

        // 紅色區：投入材料（份數體現為堆疊數）
        int i = 0;
        for (Map.Entry<String, Integer> en : r.ingredientRefs.entrySet()) {
            if (i >= INGREDIENT_SLOTS.length) break;
            menu.addItem(INGREDIENT_SLOTS[i], describeIngredient(en.getKey(), en.getValue()),
                    ChestMenuUtils.getEmptyClickHandler());
            i++;
        }
        // 右側產出：標註產出份數
        menu.addItem(SLOT_OUTPUT, describeOutput(r), ChestMenuUtils.getEmptyClickHandler());
        // 釀造於酒窖 + 釀造時長 + 陳釀標識
        menu.addItem(SLOT_TYPE, typeItem(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_DURATION, durationItem(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_AGING, agingIcon(r.aging), ChestMenuUtils.getEmptyClickHandler());
        // 返回配方列表；翻頁
        menu.addItem(SLOT_BACK, backItem(), (pl, s, cursor, action) -> {
            openRecipeList(pl, 0, cellar);
            return false;
        });
        menu.addItem(SLOT_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openRecipes(pl, action.isRightClicked() ? idx - 1 : idx + 1, cellar);
            return false;
        });
        menu.open(p);
    }

    private static ChestMenu baseMenu(String title) {
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        menu.setPlayerInventoryClickable(true); // 允許拿取揹包內物品
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }
        return menu;
    }

    /** 配方頁裝飾：綠/黃玻璃板環繞紅色配方區（與榨汁盆配方頁一致）。 */
    private static void fillRecipeDeco(ChestMenu menu) {
        pane(menu, 10, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 11, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 12, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 13, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 14, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 19, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 20, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 21, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 22, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 23, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 28, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 29, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 30, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 31, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 32, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 37, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 38, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 39, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 40, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 41, Material.GREEN_STAINED_GLASS_PANE);
    }

    private static void pane(ChestMenu menu, int slot, Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§r");
            it.setItemMeta(meta);
        }
        menu.addItem(slot, it, ChestMenuUtils.getEmptyClickHandler());
    }

    /** 投入材料：份數體現為堆疊數並寫明。 */
    private static ItemStack describeIngredient(String ref, int count) {
        ItemStack it = JuicerRecipe.refToItem(ref);
        it.setAmount(Math.min(64, Math.max(1, count)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "投入份數: " + ChatColor.YELLOW + count);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 產出：lore 標註產出份數與陳釀標識。 */
    private static ItemStack describeOutput(CellarRecipe r) {
        ItemStack it = r.result.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "產出份數: " + ChatColor.YELLOW + r.totalInput + " 單位");
            lore.add(ChatColor.GRAY + "每單位產出灌裝為 1 瓶");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 釀造台：釀造於酒窖管理器。 */
    private static ItemStack typeItem() {
        ItemStack it = new ItemStack(Material.BREWING_STAND);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "釀造於酒窖管理器");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "投入果汁（清水可稀釋）+ 酒麴後啓動釀造");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 時鐘：釀造時長（正式版 20~40 分鐘，當前測試模式立即完成）。 */
    private static ItemStack durationItem() {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "釀造時長");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "20~40 分鐘（隨機）");
            lore.add(ChatColor.GRAY + "完成後可陳化或直接灌裝出酒");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 陳釀標識：烈焰粉=可陳釀 / 惡魂之淚=不可陳釀。 */
    private static ItemStack agingIcon(boolean aging) {
        ItemStack it = new ItemStack(aging ? Material.BLAZE_POWDER : Material.GHAST_TEAR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(aging ? ChatColor.RED + "允許陳釀" : ChatColor.GRAY + "不可陳釀");
            List<String> lore = new ArrayList<>();
            lore.add(aging ? ChatColor.GRAY + "陳化模式下酒精度隨遊戲日增長"
                    : ChatColor.GRAY + "該產物無酒精度，不可陳化");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack machineIcon(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem manager) {
        ItemStack icon = manager.getItem().clone();
        ItemMeta im = icon.getItemMeta();
        if (im != null) {
            List<String> lore = im.getLore() == null ? new ArrayList<>() : new ArrayList<>(im.getLore());
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "機器本體");
            im.setLore(lore);
            icon.setItemMeta(im);
        }
        return icon;
    }

    private static ItemStack noneItem() {
        ItemStack none = new ItemStack(Material.BARRIER);
        ItemMeta nm = none.getItemMeta();
        if (nm != null) {
            nm.setDisplayName(ChatColor.YELLOW + "自身配方待定");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "該機器暫未配置合成配方");
            nm.setLore(lore);
            none.setItemMeta(nm);
        }
        return none;
    }

    private static ItemStack backToGuideItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回指南");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 門：返回配方列表。 */
    private static ItemStack backItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回配方列表");
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack pageItem() {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "翻頁");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "左鍵：下一頁");
            lore.add(ChatColor.GRAY + "右鍵：上一頁");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
}
