package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.machines.JuicerRecipe;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 榨汁盆配方展示菜單（佈局對齊需求圖，菜單代碼參考終焉廚鍋大配方展示）：
 * <ul>
 *   <li>固定配方頁：背景為粘液 UI，綠/黃玻璃板裝飾；紅色區展示投入材料（多數量直接
 *       體現在物品堆疊數上），右側產出並標註產出份數；底排鐵砧=榨汁方式（踩踏/砸擊）、
 *       重錘=所需次數（數量即次數）、附魔金蘋果=混合材料頁入口、門=返回指南、
 *       箭矢=翻頁（左鍵下一頁、右鍵上一頁）；</li>
 *   <li>混合材料頁：粉玻璃板描邊，首排中間為「這些都能榨汁哦」附魔金蘋果標籤，
 *       其餘空位鋪可混合材料，底排門/箭矢同上。</li>
 * </ul>
 */
public final class JuicerMenu {

    /** 固定配方頁槽位。 */
    private static final int[] INGREDIENT_SLOTS = {20, 21, 22, 29, 30, 31}; // 紅色區
    private static final int SLOT_OUTPUT = 34;   // 右側產出
    private static final int SLOT_TYPE = 47;     // 榨汁方式（踩踏/鐵砧）
    private static final int SLOT_COUNT = 49;    // 重錘：所需次數
    private static final int SLOT_BACK = 52;     // 門：返回配方列表
    private static final int SLOT_PAGE = 53;     // 箭矢：翻頁

    /** 配方列表頁槽位：0~44 成品、45 返回指南、49 混合材料入口、53 翻頁。 */
    private static final int SLOT_LIST_BACK = 45;
    private static final int SLOT_LIST_MIX = 49;
    private static final int SLOT_LIST_PAGE = 53;
    private static final int LIST_PAGE_SIZE = 45;

    /** 混合材料頁槽位。 */
    private static final int SLOT_MIX_LABEL = 4;  // 首排中間標籤（頂排上移一格，替換粉玻璃板）
    private static final int MIX_PAGE_SIZE = 28;  // 每頁材料數（內部空位）

    /** 重錘圖標：MACE 為 1.21+ 新增材質，舊版本服務端無此材質，回退鐵砧（榨汁方式之一為鐵砧砸擊）。 */
    private static final Material COUNT_ICON =
            Material.matchMaterial("MACE") != null ? Material.matchMaterial("MACE") : Material.ANVIL;

    private JuicerMenu() {}

    /**
     * 榨汁盆自身配方概覽頁（JEG 指南點擊榨汁盆時替代默認展示）：標準 3x3 展示其合成配方
     * （當前未配置則留空），右下角金蘋果按鈕進入配方展示頁。
     */
    public static void openBasinOverview(Player p) {
        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY + "· 自身配方");
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem basin =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(
                        com.haiman233.worldtaste.machines.JuicerBasin.ITEM_ID);
        if (basin == null) return;

        int[] craftSlots = {3, 4, 5, 12, 13, 14, 21, 22, 23};
        ItemStack[] craft = basin.getRecipe();
        boolean any = false;
        for (int i = 0; i < 9 && i < craft.length; i++) {
            if (craft[i] == null) continue;
            any = true;
            ItemStack display = craft[i].clone();
            menu.addItem(craftSlots[i], display, ChestMenuUtils.getEmptyClickHandler());
        }
        if (!any) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta nm = none.getItemMeta();
            if (nm != null) {
                nm.setDisplayName(ChatColor.YELLOW + "自身配方待定");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "該機器暫未配置合成配方");
                nm.setLore(lore);
                none.setItemMeta(nm);
            }
            menu.addItem(13, none, ChestMenuUtils.getEmptyClickHandler());
        }
        // 機器圖標 + 產物位
        ItemStack icon = basin.getItem().clone();
        ItemMeta im = icon.getItemMeta();
        if (im != null) {
            List<String> lore = im.getLore() == null ? new ArrayList<>() : new ArrayList<>(im.getLore());
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "機器本體");
            im.setLore(lore);
            icon.setItemMeta(im);
        }
        menu.addItem(16, icon, ChestMenuUtils.getEmptyClickHandler());
        // 返回指南 / 右下角書本進入配方列表
        menu.addItem(35, backItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(53, entryButton(), (pl, s, cursor, action) -> {
            openRecipeList(pl, 0);
            return false;
        });
        menu.open(p);
    }

    /** 金蘋果入口按鈕（帶 PDC 標記，指南注入與概覽頁共用）。 */
    public static ItemStack entryButton() {
        ItemStack it = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "榨汁盆配方");
            meta.getPersistentDataContainer().set(JuicerGuideListener.KEY_ENTRY,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * 配方列表頁：0~44 展示全部配方成品（45 個/頁），點擊成品進入對應配方詳情；
     * 45 返回指南，49 混合材料頁入口（配置了 mix 段時），53 翻頁（左鍵下一頁/右鍵上一頁）。
     */
    public static void openRecipeList(Player p, int page) {
        List<JuicerRecipe> recipes = JuicerRecipe.all();
        if (recipes.isEmpty()) {
            openMix(p, 0);
            return;
        }
        int pages = (recipes.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;
        int idx = ((page % pages) + pages) % pages;

        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY
                + "· 配方列表 " + (idx + 1) + "/" + pages);
        int start = idx * LIST_PAGE_SIZE;
        for (int i = start; i < recipes.size() && i < start + LIST_PAGE_SIZE; i++) {
            JuicerRecipe r = recipes.get(i);
            int recipeIndex = i;
            menu.addItem(i - start, listEntry(r), (pl, s, cursor, action) -> {
                openRecipes(pl, recipeIndex);
                return false;
            });
        }
        menu.addItem(SLOT_LIST_BACK, backItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        if (JuicerRecipe.mix != null) {
            menu.addItem(SLOT_LIST_MIX, mixEntry(), (pl, s, cursor, action) -> {
                openMix(pl, 0);
                return false;
            });
        }
        menu.addItem(SLOT_LIST_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openRecipeList(pl, action.isRightClicked() ? idx - 1 : idx + 1);
            return false;
        });
        menu.open(p);
    }

    /** 配方列表條目：成品 + 點擊提示。 */
    private static ItemStack listEntry(JuicerRecipe r) {
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

    /** 固定配方頁：每頁展示一個配方，箭矢翻頁。 */
    public static void openRecipes(Player p, int index) {
        List<JuicerRecipe> recipes = JuicerRecipe.all();
        if (recipes.isEmpty()) {
            openMix(p, 0);
            return;
        }
        int idx = ((index % recipes.size()) + recipes.size()) % recipes.size();
        JuicerRecipe r = recipes.get(idx);

        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY
                + "· 配方 " + (idx + 1) + "/" + recipes.size());
        fillRecipeDeco(menu);

        // 紅色區：投入材料（數量體現為物品堆疊數）
        int i = 0;
        for (Map.Entry<String, Integer> en : r.ingredientRefs.entrySet()) {
            if (i >= INGREDIENT_SLOTS.length) break;
            menu.addItem(INGREDIENT_SLOTS[i], describeIngredient(en.getKey(), en.getValue()),
                    ChestMenuUtils.getEmptyClickHandler());
            i++;
        }
        // 右側產出：標註產出份數
        menu.addItem(SLOT_OUTPUT, describeOutput(r), ChestMenuUtils.getEmptyClickHandler());
        // 榨汁方式 + 所需次數
        menu.addItem(SLOT_TYPE, r.playerType && r.anvilType ? typeBoth()
                : r.anvilType ? typeAnvil() : typeStomp(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_COUNT, countItem(r.progress), ChestMenuUtils.getEmptyClickHandler());
        // 返回配方列表 + 翻頁
        menu.addItem(SLOT_BACK, backToListItem(), (pl, s, cursor, action) -> {
            openRecipeList(pl, 0);
            return false;
        });
        menu.addItem(SLOT_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openRecipes(pl, action.isRightClicked() ? idx - 1 : idx + 1);
            return false;
        });
        menu.open(p);
    }

    /** 混合材料頁：粉框 + 標籤 + 可混合材料（翻頁）。 */
    public static void openMix(Player p, int index) {
        List<ItemStack> mats = new ArrayList<>();
        if (JuicerRecipe.mix != null) {
            for (String ref : JuicerRecipe.mix.mixableRefs) {
                ItemStack it = JuicerRecipe.refToItem(ref);
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                    lore.add("");
                    lore.add(ChatColor.GRAY + "可投入榨汁盆混合榨汁");
                    meta.setLore(lore);
                    it.setItemMeta(meta);
                }
                mats.add(it);
            }
        }
        int pages = Math.max(1, (mats.size() + MIX_PAGE_SIZE - 1) / MIX_PAGE_SIZE);
        int idx = ((index % pages) + pages) % pages;

        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY
                + "· 混合材料 " + (idx + 1) + "/" + pages);
        fillPinkDeco(menu);

        // 首排中間：標籤
        ItemStack label = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        ItemMeta lm = label.getItemMeta();
        if (lm != null) {
            lm.setDisplayName(Colors.c("&e這&b些&2都&a能&6榨&d汁&e哦"));
            label.setItemMeta(lm);
        }
        menu.addItem(SLOT_MIX_LABEL, label, ChestMenuUtils.getEmptyClickHandler());

        // 內部空位：可混合材料（每頁 27 個）
        int[] slots = interiorSlots();
        for (int i = 0; i < MIX_PAGE_SIZE; i++) {
            int pageIdx = idx * MIX_PAGE_SIZE + i;
            if (pageIdx >= mats.size()) break;
            menu.addItem(slots[i], mats.get(pageIdx), ChestMenuUtils.getEmptyClickHandler());
        }

        menu.addItem(SLOT_BACK, backToListItem(), (pl, s, cursor, action) -> {
            openRecipeList(pl, 0);
            return false;
        });
        menu.addItem(SLOT_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openMix(pl, action.isRightClicked() ? idx - 1 : idx + 1);
            return false;
        });
        menu.open(p);
    }

    /** 內部材料槽位（行 1-4 × 列 1-7）。 */
    private static int[] interiorSlots() {
        int[] slots = new int[MIX_PAGE_SIZE];
        int i = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[i++] = row * 9 + col;
            }
        }
        return slots;
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

    /** 固定配方頁裝飾：綠/黃玻璃板環繞紅色配方區（對齊需求圖）。 */
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

    /** 混合材料頁裝飾：粉玻璃板描邊。 */
    private static void fillPinkDeco(ChestMenu menu) {
        for (int col = 0; col < 9; col++) {
            if (col == SLOT_MIX_LABEL) continue; // 頂排中間由標籤佔據
            pane(menu, col, Material.PINK_STAINED_GLASS_PANE);
            pane(menu, 45 + col, Material.PINK_STAINED_GLASS_PANE);
        }
        for (int row = 1; row <= 4; row++) {
            pane(menu, row * 9, Material.PINK_STAINED_GLASS_PANE);
            pane(menu, row * 9 + 8, Material.PINK_STAINED_GLASS_PANE);
        }
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

    /** 投入材料：數量體現為堆疊數並寫明。 */
    private static ItemStack describeIngredient(String ref, int count) {
        ItemStack it = JuicerRecipe.refToItem(ref);
        it.setAmount(Math.min(64, Math.max(1, count)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "投入數量: " + ChatColor.YELLOW + count);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 產出：lore 標註榨好後可接取的份數。 */
    private static ItemStack describeOutput(JuicerRecipe r) {
        ItemStack it = r.result.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "產出份數: " + ChatColor.YELLOW + r.yield + " 份");
            lore.add(ChatColor.GRAY + "瓶子每次接取 1 份，桶需滿盆一次接完");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 榨汁方式：玩家踩踏（跳躍）或鐵砧砸擊。 */
    private static ItemStack typeStomp() {
        ItemStack it = new ItemStack(Material.LEATHER_BOOTS);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "榨汁方式: 玩家踩踏");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "站在盆內跳躍踩踏（每次 +1）");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack typeAnvil() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "榨汁方式: 鐵砧砸擊");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "鐵砧砸在盆頂（每次 +4）");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 雙壓榨方式：踩踏與鐵砧砸擊均可（鐵砧圖標 + 雙行説明）。 */
    private static ItemStack typeBoth() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "榨汁方式: 踩踏 / 鐵砧砸擊");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "站在盆內跳躍踩踏（每次 +1）");
            lore.add(ChatColor.GRAY + "或鐵砧砸在盆頂（每次 +4）");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 重錘：數量即所需榨汁次數。 */
    private static ItemStack countItem(int progress) {
        ItemStack it = new ItemStack(COUNT_ICON);
        it.setAmount(Math.min(64, Math.max(1, progress)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "所需次數");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "踩踏/砸擊 " + ChatColor.YELLOW + progress + ChatColor.GRAY + " 次");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack mixEntry() {
        ItemStack it = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "混合榨汁材料");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "查看哪些材料可以混合榨汁");
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "點擊打開");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack backItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回指南");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 門：返回配方列表（詳情頁/混合材料頁用）。 */
    private static ItemStack backToListItem() {
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
