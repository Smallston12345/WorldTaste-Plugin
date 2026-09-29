package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Views;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 酒窖管理器機器頁面（粘液原生 {@link BlockMenuPreset} 體系，與本體機器同款打開方式）。
 *
 * <p>靜態佈局（背景/裝飾/按鈕與點擊處理器）由 {@link #setupMenu} 在 preset init 中繪製一次，
 * 每 BlockMenu 克隆共享；動態槽位（時鐘/清空/羊毛/液位線/酒麴展示/灌裝按鈕/命名牌）由
 * 10 tick 週期任務對<b>有查看者</b>的界面 {@code replaceExistingItem} 實時刷新。
 * 界面內容隨方塊數據持久化（Slimefun 原生機制）：玩家放入的瓶子/酒麴/成品關頁不丟，
 * 重開原樣還在，多玩家同時查看共享同一份界面內容。</p>
 *
 * <p>槽位佈局：槽 0 結構展示入口、槽 1 配方入口、槽 4 時鐘（模式/計時，點擊切換，
 * shift+右鍵切換自動陳化）、槽 7 黃板（污染清空；非運行態 shift+右鍵直接清空機內液體與
 * 已投入酒麴）、右列粉色液位線（24 單位，1 格 4 單位）、添料區 3×3 空槽 19-21/28-30/37-39
 * （投果汁：瓶 +1/桶 +3，返還空容器；清水可投入稀釋）、槽 13 酒麴放置位（投入後只消耗
 * 1 個，槽 22 顯示對應酒麴圖標）、槽 31 羊毛啓動/關閉（釀造關閉=報廢、陳化關閉=出酒）、
 * 出酒區 3×3 空槽 23-25/32-34/41-43（只出不進）、槽 40 灌裝槽（放原版玻璃瓶）、
 * 槽 49 煉藥鍋「點我裝瓶」、槽 45 命名牌。計時推進由管理器 ticker 調用 {@link #advance}。</p>
 */
public final class CellarMenu {

    private static final int SLOT_CLOCK = 4;
    private static final int SLOT_CLEAR = 7;
    private static final int SLOT_WOOL = 31;
    private static final int[] GAUGE = {8, 17, 26, 35, 44, 53};
    /** 酒麴投放位（玩家槽）：只接受酒麴，投入後只消耗 1 個。 */
    private static final int SLOT_YEAST_INPUT = 13;
    /** 酒麴展示位（只讀）：與 4 時鐘 / 31 羊毛 / 40 產物預覽同列。 */
    private static final int SLOT_YEAST = 22;
    private static final int[] INPUT_SLOTS = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    private static final int SLOT_FILL = 40; // 灌裝槽：留空，放入玻璃瓶即灌裝
    private static final int[] OUTPUT_SLOTS = {23, 24, 25, 32, 33, 34, 41, 42, 43};
    /** 灌裝槽按鈕（煉藥鍋「點我裝瓶」）。 */
    private static final int SLOT_FILL_DISPLAY = 49;
    /** 左下角命名牌按鈕：為酒窖命名（僅放置者，配置可關）。 */
    private static final int SLOT_NAMETAG = 45;
    private static final int[] GREEN_DECO = {10, 11, 12, 18, 27, 36, 46, 47, 48};
    private static final int[] RED_DECO = {14, 15, 16, 50, 51, 52};
    private static final long TICK_MS = 50;

    /** 正在命名的玩家 → 待命名的酒窖方塊位置（鐵砧打開時酒窖頁面會話已結束）。 */
    private static final Map<UUID, Location> PENDING_CELLAR = new HashMap<>();
    /** 正在命名的玩家 → 其命名鐵砧界面（打開後填入佔位物品）。 */
    private static final Map<UUID, org.bukkit.inventory.Inventory> NAMING = new HashMap<>();
    /** 已創建界面的酒窖（位置 → BlockMenu）：刷新任務只遍歷這些界面。 */
    private static final Map<Location, BlockMenu> MENUS = new HashMap<>();
    private static BukkitTask refreshTask;

    private CellarMenu() {}

    public static void register() {
        refreshTask = Bukkit.getScheduler().runTaskTimer(WT.plugin, CellarMenu::refreshAll, 10, 10);
        Bukkit.getPluginManager().registerEvents(new CloseListener(), WT.plugin);
    }

    /**
     * preset 靜態佈局：背景只覆蓋「非玩家槽位」（玩家槽留空，物品由 BlockMenu 原生持久化），
     * 全部交互槽註冊點擊處理器（處理器內經事件 holder 取 {@link BlockMenu} 定位方塊）。
     */
    static void setupMenu(ChestMenu menu) {
        menu.setEmptySlotsClickable(true);
        menu.setPlayerInventoryClickable(true);
        for (int i = 0; i < 54; i++) {
            if (isPlayerSlot(i)) continue;
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 多方塊結構展示入口（左上角）
        menu.addItem(0, pane(Material.SPYGLASS, "§b多方塊結構",
                List.of(leg("§7查看酒窖的 3×3×3 結構分層"), leg(""), leg(ChatColor.YELLOW + "點擊打開"))),
                handler((pl, bm, cur) -> {
                    CellarStructureMenu.open(pl, bm.getBlock());
                    return false;
                }));
        // 配方展示入口（結構按鈕旁）
        menu.addItem(1, pane(Material.BOOK, "§e酒窖配方",
                List.of(leg("§7查看酒窖可釀造的配方一覽"), leg(""), leg(ChatColor.YELLOW + "點擊打開"))),
                handler((pl, bm, cur) -> {
                    CellarRecipeMenu.openRecipeList(pl, 0, bm.getBlock());
                    return false;
                }));
        // 時鐘：Shift+右鍵切換自動陳化；普通點擊切換模式
        menu.addMenuClickHandler(SLOT_CLOCK, new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                   ClickAction action) {
                if (!(e.getInventory().getHolder() instanceof BlockMenu bm)) return false;
                Block manager = bm.getBlock();
                if (e.isShiftClick() && e.isRightClick()) toggleAutoAge(pl, manager);
                else toggleMode(pl, manager);
                paint(bm, manager);
                return false;
            }

            @Override
            public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                return false;
            }
        });
        // 清空按鈕：普通點擊僅在污染態生效。Shift+右鍵走 Bukkit 監聽（CSCoreLib 不一定帶上右鍵標記）。
        menu.addMenuClickHandler(SLOT_CLEAR, handler((pl, bm, cur) -> {
            clearContaminated(pl, bm.getBlock(), false);
            paint(bm, bm.getBlock());
            return false;
        }));
        menu.addMenuClickHandler(SLOT_WOOL, handler((pl, bm, cur) -> {
            Block manager = bm.getBlock();
            woolClick(pl, manager);
            paint(bm, manager);
            return false;
        }));
        // 灌裝槽按鈕（煉藥鍋圖標）：點擊灌裝一次
        menu.addMenuClickHandler(SLOT_FILL_DISPLAY, handler((pl, bm, cur) -> {
            Block manager = bm.getBlock();
            fillOne(pl, manager, bm);
            paint(bm, manager);
            return false;
        }));
        // 左下角命名牌：為酒窖命名（配置可關；僅放置者）
        if (com.haiman233.worldtaste.load.CellarLoader.cellarNameEnabled) {
            menu.addMenuClickHandler(SLOT_NAMETAG, handler((pl, bm, cur) -> {
                clickNameTag(pl, bm.getBlock());
                paint(bm, bm.getBlock());
                return false;
            }));
        }

        // 裝飾文本（綠/紅玻璃板標識）
        for (int slot : GREEN_DECO) {
            menu.addItem(slot, pane(Material.LIME_STAINED_GLASS_PANE, "§a添料區",
                    List.of(leg("§7添料槽：投入瓶裝/桶裝果汁"), leg("§7瓶 +1 單位，桶 +3 單位"))),
                    ChestMenuUtils.getEmptyClickHandler());
        }
        for (int slot : RED_DECO) {
            menu.addItem(slot, pane(Material.RED_STAINED_GLASS_PANE, "§c輸出槽",
                    List.of(leg("§7在這裏輸出產物"), leg("§7物品會保存在機器內"))),
                    ChestMenuUtils.getEmptyClickHandler());
        }

        // 添料區：果汁投料 + 出酒階段玻璃瓶灌裝；禁止果酒迴流，酒麴只能在 13 號槽投放
        for (int slot : INPUT_SLOTS) {
            menu.addMenuClickHandler(slot, handler((pl, bm, cursor) -> {
                Block manager = bm.getBlock();
                WineCellarState st = WineCellarState.get(manager);
                // 只攔「放入」：光標上有物品才算放入；空手提起、shift 取出一律放行。
                ItemStack incoming = (cursor != null && !cursor.getType().isAir()) ? cursor : null;
                // 運行中/已污染：放入的物品不會被吸收，會一直滯留槽內，直接攔截
                if (incoming != null && st.phase() != WineCellarState.Phase.IDLE
                        && st.phase() != WineCellarState.Phase.READY) {
                    pl.sendMessage("§c機器未處於待機/出酒狀態，不能放入物品！");
                    return false;
                }
                if (isWine(incoming)) {
                    pl.sendMessage("§c陳釀果酒不能再次放入酒窖！");
                    return false;
                }
                if (incoming != null && isJuice(incoming) && st.hasAlcohol()) {
                    pl.sendMessage("§c酒窖中已含酒精液體，不能投入果汁！");
                    return false;
                }
                if (isYeast(incoming)) {
                    pl.sendMessage("§c酒麴請放入酒麴槽位！");
                    return false;
                }
                // 液位餘量校驗：放不下的果汁/清水不收（滯留槽內不被吸收）
                if (incoming != null) {
                    String reject = capacityReject(st, incoming);
                    if (reject != null) {
                        pl.sendMessage(reject);
                        return false;
                    }
                }
                return true;
            }));
        }
        // 出酒區：只出不進（成品由灌裝槽輸出到此處，不接受玩家放入）
        for (int slot : OUTPUT_SLOTS) {
            menu.addMenuClickHandler(slot, handler((pl, bm, cursor) -> {
                if (cursor == null || cursor.getType().isAir()) return true;
                pl.sendMessage("§c你不能在這裏放置物品！");
                return false;
            }));
        }
        // 酒麴投放位：只接受酒麴，且每台機器限一種（投入後只消耗 1 個）
        menu.addMenuClickHandler(SLOT_YEAST_INPUT, handler((pl, bm, cursor) -> {
            if (cursor == null || cursor.getType().isAir()) return true;
            if (!isYeast(cursor)) {
                pl.sendMessage("§c酒麴槽只能放入酒麴！");
                return false;
            }
            Block manager = bm.getBlock();
            WineCellarState st = WineCellarState.get(manager);
            // 運行中/出酒階段加入酒麴不會被使用，只會滯留槽內
            if (st.phase() != WineCellarState.Phase.IDLE) {
                pl.sendMessage("§c只有待機狀態才能加入酒麴！");
                return false;
            }
            if (st.yeast() != null) {
                pl.sendMessage("§c該酒窖已加入過酒麴！");
                return false;
            }
            return true;
        }));
    }

    /** 新界面對象：登記進刷新表並立即繪製一次動態槽位。 */
    static void onNewInstance(BlockMenu menu, Block b) {
        MENUS.put(b.getLocation(), menu);
        paint(menu, b);
    }

    /** 重新打開某台酒窖的機器頁面（子頁面返回用）。 */
    public static void open(Player p, Block manager) {
        BlockMenu menu = me.mrCookieSlime.Slimefun.api.BlockStorage.getInventory(manager);
        if (menu != null) menu.open(p);
    }

    /** 簡單處理器適配：由 Bukkit 點擊事件解析出對應 BlockMenu（玩家槽校驗由調用方完成）。 */
    private interface MenuClick {
        boolean run(Player pl, BlockMenu menu, ItemStack cursor);
    }

    private static ChestMenu.AdvancedMenuClickHandler handler(MenuClick click) {
        return new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                   ClickAction action) {
                return e.getInventory().getHolder() instanceof BlockMenu menu
                        && click.run(pl, menu, cursor);
            }

            @Override
            public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                return true;
            }
        };
    }

    /** 玩家可自由存取的槽位（佈局時不放背景，物品隨方塊持久化）。 */
    private static boolean isPlayerSlot(int slot) {
        for (int s : INPUT_SLOTS) {
            if (s == slot) return true;
        }
        for (int s : OUTPUT_SLOTS) {
            if (s == slot) return true;
        }
        return slot == SLOT_YEAST_INPUT || slot == SLOT_FILL;
    }

    /** 是否為陳釀果酒（不允許再次投入酒窖）。 */
    private static boolean isWine(ItemStack it) {
        return it != null && !it.getType().isAir() && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer()
                        .has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE);
    }

    /** 是否為酒麴（在 {@link JuicerRecipe#yeastBonus} 加成表內的粘液物品）。 */
    private static boolean isYeast(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(it);
        return sf != null && JuicerRecipe.yeastBonus(sf.getId()) >= 0;
    }

    /** 是否為果汁容器（帶組成數據的瓶/桶裝產物，非果酒）。 */
    private static boolean isJuice(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta() || isWine(it)) return false;
        String c = it.getItemMeta().getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING);
        return c != null && !c.isEmpty();
    }

    /** 原版水瓶判定（藥水材質且基礎藥水類型為水）。 */
    private static boolean isWaterBottle(ItemStack it) {
        if (it == null || it.getType() != Material.POTION || !it.hasItemMeta()) return false;
        return it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm
                && org.bukkit.potion.PotionType.WATER == basePotionType(pm);
    }

    // --- 基礎藥水類型讀取（跨版本二進制兼容） ---
    // getBasePotionType 為 1.20.2+ API（1.20.0/1.20.1 調用會 NoSuchMethodError），
    // getBasePotionData 為 1.20.4- API（1.20.5+ 已移除，編譯期不可引用），兩條路徑全部走反射。
    private static final java.lang.reflect.Method M_BASE_POTION_TYPE =
            findMethod(org.bukkit.inventory.meta.PotionMeta.class, "getBasePotionType");
    private static final java.lang.reflect.Method M_BASE_POTION_DATA =
            findMethod(org.bukkit.inventory.meta.PotionMeta.class, "getBasePotionData");
    private static final java.lang.reflect.Method M_POTION_DATA_TYPE = findDataGetType();

    private static java.lang.reflect.Method findMethod(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static java.lang.reflect.Method findDataGetType() {
        try {
            return Class.forName("org.bukkit.potion.PotionData").getMethod("getType");
        } catch (Throwable e) {
            return null; // 1.20.5+ 已移除 PotionData
        }
    }

    /** 讀取藥水基礎藥水類型（跨版本；未設置時返回 null）。 */
    private static org.bukkit.potion.PotionType basePotionType(org.bukkit.inventory.meta.PotionMeta pm) {
        try {
            if (M_BASE_POTION_TYPE != null) {
                return (org.bukkit.potion.PotionType) M_BASE_POTION_TYPE.invoke(pm);
            }
            if (M_BASE_POTION_DATA != null && M_POTION_DATA_TYPE != null) {
                Object data = M_BASE_POTION_DATA.invoke(pm);
                return data == null ? null : (org.bukkit.potion.PotionType) M_POTION_DATA_TYPE.invoke(data);
            }
        } catch (Throwable ignored) {
            // 反射失敗按非水瓶處理
        }
        return null;
    }

    /** 啓動前電力校驗：管理器與温控器雙機蓄電均需 ≥ 各自每 tick 消耗。 */
    private static boolean hasEnoughPower(Block manager) {
        if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager wm)) {
            return false;
        }
        if (wm.getCharge(manager.getLocation()) < WineCellarManager.CONSUMPTION) return false;
        Block partner = CellarStructure.partner(manager, false);
        if (partner == null
                || !(me.mrCookieSlime.Slimefun.api.BlockStorage.check(partner)
                        instanceof TemperatureController ctrl)) {
            return false;
        }
        return ctrl.getCharge(partner.getLocation()) >= TemperatureController.CONSUMPTION;
    }

    /**
     * 校驗待放入的果汁/清水是否還有液位餘量，放不下時返回拒絕提示，可放返回 null。
     * （放不下的果汁/清水只會滯留槽內不被吸收，放入前就攔下）
     */
    private static String capacityReject(WineCellarState st, ItemStack incoming) {
        if (isJuice(incoming)) {
            boolean bucket = incoming.getItemMeta().getPersistentDataContainer()
                    .has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE);
            if (!st.canAccept(bucket ? 3 : 1)) {
                return "§c液位餘量不足，無法投入" + (bucket ? "桶裝" : "瓶裝") + "果汁！";
            }
        } else if (isVanilla(incoming, Material.WATER_BUCKET)) {
            if (!st.canAccept(3)) return "§c液位餘量不足，無法投入清水！";
        } else if (incoming.getType() == Material.POTION
                && isVanilla(incoming, Material.POTION) && isWaterBottle(incoming)) {
            if (!st.canAccept(1)) return "§c液位餘量不足，無法投入清水！";
        }
        return null;
    }

    /** 動態槽位刷新（時鐘/清空/羊毛/液位線/酒麴展示/灌裝按鈕/命名牌）。 */
    private static void paint(BlockMenu menu, Block manager) {
        scanSlots(menu, manager);
        WineCellarState st = WineCellarState.get(manager);
        menu.replaceExistingItem(SLOT_CLOCK, clockItem(st));
        menu.replaceExistingItem(SLOT_CLEAR, clearItem(st));
        menu.replaceExistingItem(SLOT_WOOL, woolItem(st));
        int filled = st.phase() == WineCellarState.Phase.CONTAMINATED
                ? GAUGE.length : (int) Math.ceil(st.units() / 4.0);
        int idx = 0;
        for (int i = GAUGE.length - 1; i >= 0; i--) {
            int slot = GAUGE[i];
            boolean full = idx < filled;
            idx++;
            if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
                menu.replaceExistingItem(slot, pane(Material.BLACK_STAINED_GLASS_PANE, "§8受污染的液體",
                        List.of(leg("§7點擊黃色玻璃板清空復原"))));
                continue;
            }
            if (full) {
                List<Component> lore = new ArrayList<>();
                lore.add(leg("§7液位: §e" + st.units() + "§7/" + WineCellarState.CAPACITY + " 單位"));
                if (st.hasAlcohol()) lore.add(leg("§c酒精度: §e" + fmt(st.alcohol()) + "°"));
                // 同種果汁合併顯示「倒入的材料份數」：每單位組成 × 批次單位數
                // （如 2 單位蘋果汁 → 蘋果×2；甜漿果每瓶 3 個，2 單位 → 甜漿果×6）
                Map<String, Double> merged = new LinkedHashMap<>();
                for (WineCellarState.Liquid lq : st.liquids()) {
                    for (Map.Entry<String, Double> e : lq.contents().entrySet()) {
                        merged.merge(e.getKey(), e.getValue() * lq.units(), Double::sum);
                    }
                }
                for (Map.Entry<String, Double> e : merged.entrySet()) {
                    // 用可翻譯組件拼接，客户端按語言文件渲染出中文物品名
                    lore.add(LegacyComponentSerializer.legacySection().deserialize("§b")
                            .append(JuicerRecipe.nameComponent(e.getKey()))
                            .append(Component.text(" ×" + JuicerRecipe.formatFraction(e.getValue()),
                                    net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                }
                if (st.waterUnits() > 0) {
                    lore.add(leg("§9清水 ×" + st.waterUnits()));
                }
                menu.replaceExistingItem(slot, pane(Material.PINK_STAINED_GLASS_PANE, "§d液位線", lore));
            } else {
                int free = Math.max(0, WineCellarState.CAPACITY - (full ? filled * 4 : st.units()));
                menu.replaceExistingItem(slot, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7液位線（空）",
                        List.of(leg("§7空餘容量: §e" + free + "§7 單位"))));
            }
        }
        menu.replaceExistingItem(SLOT_YEAST, yeastPane(st));
        menu.replaceExistingItem(SLOT_FILL_DISPLAY, fillPane());
        // 命名牌按鈕：實時刷新當前名稱展示（未開啓功能時保持背景板）
        if (com.haiman233.worldtaste.load.CellarLoader.cellarNameEnabled) {
            menu.replaceExistingItem(SLOT_NAMETAG, nameTagItem(st));
        }
    }

    /** 灌裝槽按鈕（煉藥鍋）：點擊灌裝一次。 */
    private static ItemStack fillPane() {
        return pane(Material.CAULDRON, "§e點我裝瓶",
                List.of(leg("§7在上方放入玻璃瓶後才能灌裝")));
    }

    /**
     * 酒麴位展示：未加入時顯示橙色佔位板（提示往 13 號酒麴槽投放）；
     * 已加入時顯示<b>該酒麴物品本身</b>（材質/頭顱與指南中的酒麴一致）。
     */
    private static ItemStack yeastPane(WineCellarState st) {
        if (st.yeast() == null) {
            return pane(Material.ORANGE_STAINED_GLASS_PANE, "§6酒麴投放口",
                    List.of(leg("§7請將酒麴放在上面")));
        }
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(st.yeast());
        ItemStack yeast = sf != null ? sf.getItem().clone() : new ItemStack(Material.BARRIER);
        yeast.setAmount(1);
        ItemMeta meta = yeast.getItemMeta();
        if (meta != null) {
            String name = meta.hasDisplayName() ? meta.getDisplayName()
                    : ChatColor.WHITE + st.yeast();
            meta.setDisplayName("§a已放入酒麴！");
            meta.setLore(List.of(name, ChatColor.GRAY + "釀造模式下每台機器只能放入一種酒麴"));
            yeast.setItemMeta(meta);
        }
        return yeast;
    }

    private static void scanSlots(BlockMenu menu, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        boolean changed = false;
        // 運行中/已污染時不再吸收任何投入物（放入路徑已在點擊處理器攔截，這裏兜底防禦）：
        boolean acceptInput = st.phase() == WineCellarState.Phase.IDLE
                || st.phase() == WineCellarState.Phase.READY;

        // 酒麴投放位（槽 13）：只吸收酒麴，每台機器限一種，每次只消耗 1 個
        if (acceptInput && st.yeast() == null) {
            ItemStack it = menu.getItemInSlot(SLOT_YEAST_INPUT);
            if (it != null && !it.getType().isAir() && isYeast(it)) {
                st.yeast(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(it).getId());
                if (it.getAmount() > 1) {
                    it.setAmount(it.getAmount() - 1);
                    menu.replaceExistingItem(SLOT_YEAST_INPUT, it);
                } else {
                    menu.replaceExistingItem(SLOT_YEAST_INPUT, null);
                }
                changed = true;
            }
        }
        // 已加入過酒麴：槽 13 裏多放的酒麴原樣保留（不吞），由玩家自行取回

        // 出酒階段：液位被灌裝耗盡後整池復位（含清除酒麴記錄）。否則酒麴會一直殘留在機器上，
        // 下一輪既無法更換酒麴，也會讓液位槽顯示異常。
        if (st.phase() == WineCellarState.Phase.READY) {
            if (st.units() <= 0) {
                st.clear();
                changed = true;
            }
        }

        for (int slot : acceptInput ? INPUT_SLOTS : new int[0]) {
            ItemStack it = menu.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) continue;
            // 帶 PDC 的容器優先判定：桶裝果汁是水桶材質，必須先於清水判定，
            // 否則果汁桶會被當成清水吸收
            if (it.hasItemMeta()) {
                var pdc = it.getItemMeta().getPersistentDataContainer();
                // 陳釀果酒禁止迴流：彈出到機器旁
                if (pdc.has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE)) {
                    drop(manager, it);
                    menu.replaceExistingItem(slot, null);
                    changed = true;
                    continue;
                }
                String contentsStr = pdc.get(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING);
                if (contentsStr != null && !contentsStr.isEmpty()) {
                    if (st.hasAlcohol()) {
                        drop(manager, it);
                        menu.replaceExistingItem(slot, null);
                        changed = true;
                        continue;
                    }
                    boolean bucket = pdc.has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE);
                    int add = bucket ? 3 : 1;
                    if (!st.canAccept(add)) continue;
                    Map<String, Double> contents = JuicerRecipe.parseContentsFractional(contentsStr);
                    int sugarPerUnit = pdc.getOrDefault(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, 0);
                    String juicer = pdc.get(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING);
                    st.addLiquid(contents, sugarPerUnit, add, juicer);
                    // 每輪只吸收 1 個：整堆投入時不能直接把槽位換成 1 個空容器（會吞掉其餘果汁）
                    if (it.getAmount() > 1) {
                        it.setAmount(it.getAmount() - 1);
                        menu.replaceExistingItem(slot, it);
                        giveContainer(menu, manager, bucket ? Material.BUCKET : Material.GLASS_BOTTLE);
                    } else {
                        menu.replaceExistingItem(slot, new ItemStack(bucket ? Material.BUCKET : Material.GLASS_BOTTLE));
                    }
                    manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
                    changed = true;
                    continue;
                }
            }
            // 清水：稀釋（水瓶 +1 / 水桶 +3 單位，無糖分；返回空容器）
            if (isVanilla(it, Material.WATER_BUCKET)) {
                if (st.canAccept(3)) {
                    st.addWater(3);
                    menu.replaceExistingItem(slot, new ItemStack(Material.BUCKET));
                    manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1f, 1f);
                    changed = true;
                }
                continue;
            }
            if (it.getType() == Material.POTION && isVanilla(it, Material.POTION) && isWaterBottle(it)) {
                if (st.canAccept(1)) {
                    st.addWater(1);
                    menu.replaceExistingItem(slot, new ItemStack(Material.GLASS_BOTTLE));
                    manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
                    changed = true;
                }
                continue;
            }
            // 酒麴不允許走添料區：原樣留着等玩家取回（點擊攔截為主，這裏防 shift 漏網）
            if (isYeast(it)) continue;
        }
        if (changed) st.save(manager);
    }

    /**
     * 出酒產物：鎖定了酒窖配方時輸出該配方的單位產物（帶 PDC 數據，並附加
     * wine-lore-format 渲染的信息行 + 豎向組成），否則輸出通用陳釀果酒。
     */
    private static ItemStack productItem(WineCellarState st, int perBottleSugar) {
        CellarRecipe cr = st.cellarRecipe() != null ? CellarRecipe.byKey(st.cellarRecipe()) : null;
        ItemStack out;
        if (cr == null) {
            out = WineBottle.create(st.alcohol(), perBottleSugar,
                    st.contentsOfAll(), st.allPlayers());
        } else {
            out = cr.result.clone();
            ItemMeta meta = out.getItemMeta();
            if (meta != null) {
                var pdc = meta.getPersistentDataContainer();
                pdc.set(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                        JuicerRecipe.joinContentsFractional(st.contentsOfAll()));
                if (st.allPlayers() != null && !st.allPlayers().isEmpty()) {
                    pdc.set(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                            String.join(",", st.allPlayers()));
                }
                pdc.set(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, perBottleSugar);
                if (cr.aging) {
                    pdc.set(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE, st.alcohol());
                }
                // 統一附加 wine-lore-format 渲染的信息行（組成物品開關 + 豎向顯示同時作用於配方酒）
                List<String> lore = meta.getLore() != null
                        ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                // 渲染 info：非陳釀型（aging=false）用 0 酒精度佔位，避免副產物無格式行
                double alcForLore = cr.aging ? st.alcohol() : 0.0;
                List<String> infoLines = renderWineInfo(JuicerRecipe.wineLoreFormat,
                        st.allPlayers(), st.contentsOfAll(), alcForLore);
                lore.addAll(infoLines);
                meta.setLore(lore);
                out.setItemMeta(meta);
            }
        }
        return appendCellarName(st, out);
    }

    /** 按 wine-lore-format 渲染成品酒信息行（豎向/橫向/隱藏三態由 JuicerRecipe 開關控制）。 */
    private static List<String> renderWineInfo(String format, java.util.Set<String> players,
                                               Map<String, Double> contents, double alcohol) {
        String playersText = players == null || players.isEmpty() ? "無" : String.join("、", players);
        List<String> contentsLines = JuicerRecipe.contentsLegacyList(contents);
        List<String> out = new ArrayList<>();
        if (JuicerRecipe.wineContentsVertical && contentsLines.size() >= 1
                && !contentsLines.get(0).equals("§7無")
                && !contentsLines.get(0).equals("§8[組成已隱藏]")) {
            String firstLine = format
                    .replace("%players%", playersText)
                    .replace("%contents%", "§b組成：")
                    .replace("%alcohol%", String.format("%.1f", alcohol));
            out.add(com.haiman233.worldtaste.util.Colors.c(firstLine));
            out.addAll(contentsLines);
        } else {
            String line = format
                    .replace("%players%", playersText)
                    .replace("%contents%", contentsLines.get(0))
                    .replace("%alcohol%", String.format("%.1f", alcohol));
            out.add(com.haiman233.worldtaste.util.Colors.c(line));
        }
        return out;
    }

    /** 命名的酒窖灌裝產物：在 lore 尾部追加酒窖名。 */
    private static ItemStack appendCellarName(WineCellarState st, ItemStack out) {
        if (st.cellarName() == null) return out;
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("§6來自酒窖：§r" + st.cellarName());
            meta.setLore(lore);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 左下角命名牌按鈕：顯示當前名稱與操作提示。 */
    private static ItemStack nameTagItem(WineCellarState st) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "酒窖命名");
            List<String> lore = new ArrayList<>();
            lore.add(st.cellarName() != null
                    ? ChatColor.GRAY + "當前名稱: §r" + st.cellarName()
                    : ChatColor.GRAY + "當前未命名");
            lore.add(ChatColor.YELLOW + "點擊為酒窖命名（支持顏色代碼）");
            lore.add(ChatColor.DARK_GRAY + "僅放置酒窖的玩家可以命名");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 點擊命名牌：校驗開關與歸屬後，打開虛擬鐵砧輸入名字（點擊「確定」即完成）。 */
    private static void clickNameTag(Player p, Block manager) {
        if (!com.haiman233.worldtaste.load.CellarLoader.cellarNameEnabled) {
            p.sendMessage("§c酒窖命名功能未開啓！");
            return;
        }
        WineCellarState st = WineCellarState.get(manager);
        if (st.ownerId() != null && !st.isOwner(p)) {
            p.sendMessage("§c只有放置該酒窖的玩家才能命名！");
            return;
        }
        if (NAMING.containsKey(p.getUniqueId())) {
            p.sendMessage("§c你已打開命名界面！");
            return;
        }
        PENDING_CELLAR.put(p.getUniqueId(), manager.getLocation());
        p.sendMessage("§e請在鐵砧上方輸入框輸入酒窖名字（支持 &6&l& 顏色代碼&e），"
                + "點擊右側「§a§l確定§e」完成命名；點擊左側「§c取消命名§e」或直接關閉界面取消。");
        // 下個 tick 再開鐵砧：當前正在處理酒窖頁面的點擊事件，立即切換界面可能被吞
        Bukkit.getScheduler().runTask(WT.plugin, () -> {
            if (!p.isOnline()) return;
            // InventoryView 在 1.21 由類改為接口，直接調用其方法跨版本會拋 IncompatibleClassChangeError，
            // 經反射工具（Views.top）取頂部容器
            org.bukkit.inventory.AnvilInventory ai = Views.top(p.openAnvil(null, true))
                    instanceof org.bukkit.inventory.AnvilInventory a ? a : null;
            if (ai == null) {
                PENDING_CELLAR.remove(p.getUniqueId());
                p.sendMessage("§c無法打開命名界面！");
                return;
            }
            // 虛擬鐵砧：免除重命名經驗消耗
            try {
                ai.setMaximumRepairCost(0);
            } catch (Throwable ignored) {
                // 舊 API 無此方法時忽略
            }
            ai.setRepairCost(0);
            // 第一槽位：取消命名（點擊即取消）
            org.bukkit.inventory.ItemStack cancelItem = new ItemStack(Material.PAPER);
            ItemMeta cm = cancelItem.getItemMeta();
            if (cm != null) {
                cm.setDisplayName("§c取消命名");
                List<String> lore = new ArrayList<>();
                lore.add("§7點擊此物品取消命名");
                lore.add(st.cellarName() != null
                        ? "§7當前名稱: §r" + st.cellarName() : "§7當前未命名");
                cm.setLore(lore);
                cancelItem.setItemMeta(cm);
            }
            ai.setItem(0, cancelItem);
            NAMING.put(p.getUniqueId(), ai);
            // 結果槽改名為「確定」（打字時會實時重算結果，用週期任務保持標籤）
            final String confirmName = "§a§l確定";
            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = Bukkit.getScheduler().runTaskTimer(WT.plugin, () -> {
                if (!p.isOnline() || NAMING.get(p.getUniqueId()) != ai
                        || Views.top(p.getOpenInventory()) != ai) {
                    holder[0].cancel();
                    return;
                }
                org.bukkit.inventory.ItemStack result = ai.getItem(2);
                if (result == null || result.getType().isAir()) return;
                ItemMeta rm = result.getItemMeta();
                if (rm == null || (rm.hasDisplayName() && confirmName.equals(rm.getDisplayName()))) {
                    return;
                }
                rm.setDisplayName(confirmName);
                rm.setLore(List.of("§7點擊確認命名"));
                result.setItemMeta(rm);
            }, 2L, 2L);
        });
    }

    /** 應用鐵砧輸入的酒窖名（主線程調用；名稱為空視為取消）。 */
    private static void applyCellarName(Player p, Block manager, String msg) {
        if (msg.isEmpty() || msg.equalsIgnoreCase("cancel")) {
            p.sendMessage("§7已取消命名。");
            return;
        }
        if (msg.length() > 32) {
            p.sendMessage("§c名字過長（最多 32 字符）！請重新點擊命名牌再試。");
            return;
        }
        WineCellarState st = WineCellarState.get(manager);
        st.setOwner(p); // 未記錄歸屬的舊機器：首個命名者視為所有者
        // 豎線是存檔分隔符，替換為空格防止破壞序列化
        String name = ChatColor.translateAlternateColorCodes('&', msg.replace('|', ' '));
        st.cellarName(name);
        st.save(manager);
        p.sendMessage("§a酒窖已命名為：§r" + name);
    }

    /** 點擊煉藥鍋圖標灌裝一次：消耗灌裝槽內 1 個玻璃瓶，果酒輸出到輸出槽。 */
    private static void fillOne(Player p, Block manager, BlockMenu menu) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c機器運行中，暫不可灌裝！");
            return;
        }
        if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c液體已污染，請先清空機器！");
            return;
        }
        if (st.phase() != WineCellarState.Phase.READY || st.units() <= 0) {
            p.sendMessage("§c尚無可灌裝的果酒（需先完成釀造/陳化）！");
            return;
        }
        ItemStack inSlot = menu.getItemInSlot(SLOT_FILL);
        if (inSlot == null || !isVanilla(inSlot, Material.GLASS_BOTTLE)) {
            p.sendMessage("§c缺少玻璃瓶！");
            return;
        }
        int out = firstEmpty(menu, OUTPUT_SLOTS);
        if (out < 0) {
            p.sendMessage("§c輸出槽已滿，無法繼續灌裝！");
            return;
        }
        int perBottleSugar = st.totalSugar() / Math.max(1, st.units());
        menu.replaceExistingItem(out, productItem(st, perBottleSugar));
        if (inSlot.getAmount() > 1) inSlot.setAmount(inSlot.getAmount() - 1);
        else menu.replaceExistingItem(SLOT_FILL, null);
        st.drainUnit();
        manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
        if (st.units() <= 0) {
            st.clear();
            st.save(manager);
            p.sendMessage("§a酒窖已灌裝完畢，已復原。");
            return;
        }
        st.save(manager);
    }

    private static int firstEmpty(BlockMenu menu, int[] slots) {
        for (int slot : slots) {
            ItemStack it = menu.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) return slot;
        }
        return -1;
    }

    private static void drop(Block manager, ItemStack it) {
        manager.getWorld().dropItemNaturally(manager.getLocation().add(0.5, 1.2, 0.5), it);
    }

    /**
     * 返還果汁的空容器（玻璃瓶/鐵桶）：優先塞進正在查看界面的玩家揹包，
     * 揹包滿或無人查看時掉在機器旁，避免整堆投料時空容器無處可放被吞掉。
     */
    private static void giveContainer(BlockMenu menu, Block manager, Material m) {
        ItemStack empty = new ItemStack(m);
        for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(menu.getInventory().getViewers())) {
            if (!(viewer instanceof Player pl) || !pl.isOnline()) continue;
            for (ItemStack rest : pl.getInventory().addItem(empty).values()) {
                drop(manager, rest);
            }
            return;
        }
        drop(manager, empty);
    }

    // ===== 計時推進（管理器 ticker 供電成功後調用）=====

    static void advance(WineCellarState st, Block manager) {
        st.elapsedMs(st.elapsedMs() + TICK_MS);
        boolean finished = false;
        if (st.mode() == WineCellarState.Mode.BREW) {
            if (st.elapsedMs() >= st.durationMs()) {
                finishBrew(st, manager);
                finished = true;
            }
        } else {
            // 防禦：異常數據（nextGrowthAt<=0）會導致while死循環，先校正
            if (st.nextGrowthAt() <= 0) st.nextGrowthAt(st.elapsedMs() + WineCellarState.GAME_DAY_MS);
            while (st.elapsedMs() >= st.nextGrowthAt()) {
                double g = JuicerRecipe.agingGrowthMin + ThreadLocalRandom.current().nextDouble()
                        * (JuicerRecipe.agingGrowthMax - JuicerRecipe.agingGrowthMin);
                st.alcohol(st.alcohol() * (1 + g));
                st.nextGrowthAt(st.nextGrowthAt() + WineCellarState.GAME_DAY_MS);
            }
        }
        // 每 5 分鐘或釀造完成時落盤一次（計時中無需高頻寫 BlockStorage）
        if (finished || System.currentTimeMillis() - st.lastSaveMs() >= 300_000L) {
            st.save(manager);
        }
    }

    private static void finishBrew(WineCellarState st, Block manager) {
        // 酒精度 = 總糖分 × 轉化比率 + 酒麴加成（清水不含糖分，稀釋只攤薄產出份數）
        double yeast = JuicerRecipe.yeastBonus(st.yeast());
        CellarRecipe cr = st.cellarRecipe() != null ? CellarRecipe.byKey(st.cellarRecipe()) : null;
        if (cr != null && !cr.aging) {
            st.alcohol(0); // 該配方不允許陳釀/不含酒精（副產物）
        } else {
            st.alcohol(st.totalSugar() * JuicerRecipe.sugarAlcoholRatio + yeast);
        }
        st.yeast(null); // 酒麴隨釀造消耗：完成後重置酒麴圖標與記錄
        manager.getWorld().playSound(manager.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Player placer = st.placerId() != null ? Bukkit.getPlayer(st.placerId()) : null;
        if (st.autoAge() && st.durationMs() > 0) {
            // 自動陳化：釀造完成直接切到陳化模式開始計時（每完整遊戲日增長）。
            // 測試模式（0 時長立即完成）跳過自動陳化，保證立即出產物。
            st.toggleMode();
            st.phase(WineCellarState.Phase.RUNNING);
            st.elapsedMs(0);
            st.nextGrowthAt(WineCellarState.GAME_DAY_MS);
            if (placer != null) placer.sendMessage("§a釀造已經完成！已自動開始陳化。");
        } else {
            st.phase(WineCellarState.Phase.READY);
            if (placer != null) {
                placer.sendMessage("§a釀造已經完成！耗時" + fmt(st.elapsedMs()) + "§a！");
            }
        }
    }

    private static void refreshAll() {
        Iterator<BlockMenu> it = MENUS.values().iterator();
        while (it.hasNext()) {
            BlockMenu menu = it.next();
            if (!menu.hasViewer()) continue;
            Block manager = menu.getBlock();
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager)) {
                it.remove();
                continue;
            }
            paint(menu, manager);
        }
    }

    private static final class CloseListener implements Listener {
        /**
         * 多方塊結構被破壞（任意部分）→ 直接清除機器數據（液體/酒麴/配方/槽位內容物/
         * 歸屬與酒窖名一併清空），並同步清空打開中的界面槽位。
         * 快速預篩：只有破壞點周圍存在釀造台時才深查。
         */
        @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onStructureBreak(BlockBreakEvent e) {
            Block b = e.getBlock();
            Material t = b.getType();
            if (t != Material.BREWING_STAND && t != Material.WAXED_COPPER_BLOCK
                    && t != Material.OAK_LOG && t != Material.OAK_PLANKS) return;
            Block manager = null;
            for (int dy = -1; dy <= 1 && manager == null; dy++) {
                for (int dz = -1; dz <= 1 && manager == null; dz++) {
                    for (int dx = -1; dx <= 1 && manager == null; dx++) {
                        Block m = b.getRelative(dx, dy, dz);
                        if (m.getType() != Material.BREWING_STAND) continue;
                        if (me.mrCookieSlime.Slimefun.api.BlockStorage.check(m) instanceof WineCellarManager
                                && CellarStructure.matches(m, false)) {
                            manager = m;
                        }
                    }
                }
            }
            if (manager == null) return;
            WineCellarManager.invalidateStructureCheck(manager);
            WineCellarState.get(manager).clearIdentity();
            WineCellarState.get(manager).save(manager);
            // 結構被破壞 → 機器數據一併清空，並移除內存緩存：原位重放新機器時從空狀態重建
            WineCellarState.remove(manager);
            BlockMenu menu = MENUS.remove(manager.getLocation());
            if (menu != null) {
                for (int slot : INPUT_SLOTS) menu.replaceExistingItem(slot, null);
                for (int slot : OUTPUT_SLOTS) menu.replaceExistingItem(slot, null);
                menu.replaceExistingItem(SLOT_YEAST_INPUT, null);
                menu.replaceExistingItem(SLOT_FILL, null);
            }
        }

        /** 命名鐵砧未確認就關閉：清理掛起狀態（確認/取消路徑已先行移除，此處兜底）。 */
        @EventHandler
        public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
            if (!(e.getPlayer() instanceof Player p)) return;
            if (NAMING.remove(p.getUniqueId()) != null) {
                PENDING_CELLAR.remove(p.getUniqueId());
            }
        }

        /** 玩家退出時清理命名流程的掛起狀態。 */
        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
            NAMING.remove(e.getPlayer().getUniqueId());
            PENDING_CELLAR.remove(e.getPlayer().getUniqueId());
        }

        /** 記錄酒窖歸屬（放置者，命名權限用）：延遲 1 tick 等粘液註冊完方塊數據。 */
        @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
            Block b = e.getBlockPlaced();
            if (b.getType() != Material.BREWING_STAND) return;
            Player placer = e.getPlayer();
            Bukkit.getScheduler().runTask(WT.plugin, () -> {
                if (b.getType() == Material.BREWING_STAND
                        && me.mrCookieSlime.Slimefun.api.BlockStorage.check(b) instanceof WineCellarManager) {
                    WineCellarState.get(b).setOwner(placer);
                }
            });
        }

        /** 命名鐵砧：點擊「確定」（產物槽）按輸入名應用；點擊「取消命名」（第一槽位）取消。 */
        @EventHandler
        public void onAnvilClick(InventoryClickEvent e) {
            if (!(e.getWhoClicked() instanceof Player p)) return;
            org.bukkit.inventory.Inventory anvil = NAMING.get(p.getUniqueId());
            if (anvil == null || e.getInventory() != anvil) return;
            e.setCancelled(true);
            e.setCursor(null); // 防止結果物品被客户端預測帶入光標
            if (e.getRawSlot() == 0) { // 取消命名
                NAMING.remove(p.getUniqueId());
                PENDING_CELLAR.remove(p.getUniqueId());
                p.closeInventory();
                p.sendMessage("§7已取消命名。");
                return;
            }
            if (e.getRawSlot() != 2) return; // 只認結果槽（確定）
            // 名字取鐵砧改名框文本（結果槽已改名為「確定」，不能讀物品名）
            String name = anvil instanceof org.bukkit.inventory.AnvilInventory ai
                    && ai.getRenameText() != null ? ai.getRenameText().trim() : null;
            NAMING.remove(p.getUniqueId());
            Location loc = PENDING_CELLAR.remove(p.getUniqueId());
            p.closeInventory();
            if (name == null || name.isEmpty()) {
                p.sendMessage("§7已取消命名（名稱為空）。");
                return;
            }
            if (loc == null) return;
            applyCellarName(p, loc.getBlock(), name);
        }

        /**
         * Shift+右鍵清空：CSCoreLib 菜單點擊不一定帶上「右鍵」標記，因此在 Bukkit 事件裏單獨處理。
         * 同時統一接管從揹包 shift 快速移入的路由（酒麴/果酒攔截，果汁/清水進添料區，玻璃瓶進灌裝槽）。
         */
        @EventHandler(ignoreCancelled = false)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (!(e.getInventory().getHolder() instanceof BlockMenu menu)) return;
            Block manager = menu.getBlock();
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager)) {
                return;
            }
            Inventory top = e.getInventory();

            if (e.getClickedInventory() == top && e.getRawSlot() == SLOT_CLEAR
                    && e.isShiftClick() && e.isRightClick()) {
                e.setCancelled(true);
                clearContaminated(p, manager, true);
                paint(menu, manager);
                return;
            }

            if (e.getClickedInventory() == top && isOutputSlot(e.getRawSlot())) {
                ItemStack cursor = e.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !e.isShiftClick()) {
                    e.setCancelled(true);
                    p.sendMessage("§c輸出槽不能放入物品！");
                    return;
                }
            }

            if (e.getClickedInventory() == top && isInputSlot(e.getRawSlot()) && isYeast(e.getCursor())) {
                e.setCancelled(true);
                p.sendMessage("§c酒麴請放入上方的「酒麴槽」（13 號）！");
                return;
            }

            if (e.isShiftClick() && e.getClickedInventory() != top) {
                // 從揹包快速移入：原版會按槽位順序落進酒麴槽/輸出槽等錯誤位置，這裏統一接管路由
                ItemStack moving = e.getCurrentItem();
                if (moving == null || moving.getType().isAir()) return;
                WineCellarState st = WineCellarState.get(manager);
                if (isYeast(moving)) {
                    e.setCancelled(true);
                    p.sendMessage("§c酒麴請放入上方的「酒麴槽」（13 號）！");
                } else if (isWine(moving)) {
                    e.setCancelled(true);
                    p.sendMessage("§c陳釀果酒不能再次放入酒窖！");
                } else if (isJuice(moving) && st.hasAlcohol()) {
                    e.setCancelled(true);
                    p.sendMessage("§c酒窖中已含酒精液體，不能投入果汁！");
                } else if (isJuice(moving) || isVanilla(moving, Material.WATER_BUCKET)
                        || (moving.getType() == Material.POTION
                            && isVanilla(moving, Material.POTION) && isWaterBottle(moving))) {
                    // 果汁/清水（水桶/水瓶）：統一引導進添料區，由掃描任務吸收
                    e.setCancelled(true);
                    if (st.phase() != WineCellarState.Phase.IDLE
                            && st.phase() != WineCellarState.Phase.READY) {
                        p.sendMessage("§c機器未處於待機/出酒狀態，不能放入物品！");
                        return;
                    }
                    int dest = firstEmpty(menu, INPUT_SLOTS);
                    if (dest < 0) {
                        p.sendMessage("§c添料區已滿，無法快速放入！");
                        return;
                    }
                    String reject = capacityReject(st, moving);
                    if (reject != null) {
                        p.sendMessage(reject);
                        return;
                    }
                    menu.replaceExistingItem(dest, moving.clone());
                    e.setCurrentItem(null);
                } else if (isVanilla(moving, Material.GLASS_BOTTLE)) {
                    // 玻璃瓶：直接進灌裝槽（40 號），點擊煉藥鍋圖標即可裝瓶
                    e.setCancelled(true);
                    if (st.phase() != WineCellarState.Phase.READY) {
                        p.sendMessage("§c只有出酒階段才能放入玻璃瓶！");
                        return;
                    }
                    ItemStack inFill = menu.getItemInSlot(SLOT_FILL);
                    if (inFill != null && !inFill.getType().isAir()) {
                        p.sendMessage("§c灌裝槽已有玻璃瓶！");
                        return;
                    }
                    menu.replaceExistingItem(SLOT_FILL, moving.clone());
                    e.setCurrentItem(null);
                } else {
                    e.setCancelled(true);
                    p.sendMessage("§c這個物品不能快速放入酒窖，請手持放入添料區！");
                }
            }
        }

        @EventHandler
        public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (!(e.getInventory().getHolder() instanceof BlockMenu menu)) return;
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(menu.getBlock())
                    instanceof WineCellarManager)) return;
            ItemStack cursor = e.getOldCursor();
            boolean yeast = isYeast(cursor);
            boolean bottle = isVanilla(cursor, Material.GLASS_BOTTLE);
            if (!yeast && !bottle) return;
            for (int slot : e.getRawSlots()) {
                if (slot >= 54) continue;
                if (yeast && isInputSlot(slot)) {
                    e.setCancelled(true);
                    p.sendMessage("§c酒麴請放入上方的「酒麴槽」（13 號）！");
                    return;
                }
                if (bottle && isOutputSlot(slot)) {
                    e.setCancelled(true);
                    p.sendMessage("§c輸出槽不能放入物品！");
                    return;
                }
            }
        }
    }

    private static boolean isInputSlot(int slot) {
        for (int s : INPUT_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    private static boolean isOutputSlot(int slot) {
        for (int s : OUTPUT_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    // ===== 交互 =====

    private static void toggleMode(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c機器運行中，無法切換模式！");
            return;
        }
        if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c請先點擊黃色玻璃板清空機器！");
            return;
        }
        st.toggleMode();
        st.save(manager);
    }

    /** 切換自動陳化（僅釀造模式、非運行態）：開啓後釀造完成直接進入陳化。 */
    private static void toggleAutoAge(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING
                || st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c機器運行中，無法切換自動陳化！");
            return;
        }
        if (st.mode() != WineCellarState.Mode.BREW) {
            p.sendMessage("§c只有釀造模式下才能設置自動陳化！");
            return;
        }
        st.autoAge(!st.autoAge());
        st.save(manager);
        p.sendMessage(st.autoAge()
                ? "§a已開啓自動陳化：釀造完成後自動開始陳化！"
                : "§7已關閉自動陳化。");
    }

    /**
     * 清空機器：普通點擊僅處理污染態；{@code force=true}（<b>非運行態 shift+右鍵</b>）
     * 直接排空機內已有液體並復原，方便玩家中途換料重來。
     *
     * <p>注意：這裏清的是<b>機器的液體與酒麴記錄</b>，不是刪除槽位裏的物品堆——
     * 添料區/酒麴槽中玩家尚未被吸收的物品屬於玩家物品，原樣保留在槽位中。</p>
     */
    private static void clearContaminated(Player p, Block manager, boolean force) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c機器運行中，無法清空！");
            return;
        }
        boolean contaminated = st.phase() == WineCellarState.Phase.CONTAMINATED;
        if (st.units() <= 0 && st.yeast() == null && !contaminated) {
            if (force) p.sendMessage("§7酒窖內沒有可清空的液體。");
            return;
        }
        if (!force && !contaminated) return;
        boolean hadYeast = st.yeast() != null;
        st.clear();
        st.save(manager);
        p.sendMessage(hadYeast ? "§a酒窖已清空復原，已一併清除酒麴。" : "§a酒窖已清空復原。");
    }

    private static void woolClick(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        switch (st.phase()) {
            case IDLE -> start(p, st, manager);
            case READY -> startAgeOrNothing(p, st, manager);
            case RUNNING -> {
                if (st.mode() == WineCellarState.Mode.BREW) {
                    st.contaminate();
                    p.sendMessage("§c液體全部報廢！");
                    manager.getWorld().playSound(manager.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                } else {
                    st.phase(WineCellarState.Phase.READY);
                    p.sendMessage("§a陳化已停止，可放置玻璃瓶出酒。");
                }
                st.save(manager);
            }
            default -> { }
        }
    }

    private static void start(Player p, WineCellarState st, Block manager) {
        if (!CellarStructure.matches(manager, false)) {
            p.sendMessage("§c多方塊結構不完整，無法啓動釀造！");
            return;
        }
        if (!hasEnoughPower(manager)) {
            p.sendMessage("§c電量不足！");
            return;
        }
        if (st.yeast() == null) {
            p.sendMessage("§c必須加入一種酒麴才能開始配方！");
            return;
        }
        if (st.juiceUnits() <= 0) {
            p.sendMessage("§c盆中只有清水，需要先投入果汁才能釀造！");
            return;
        }
        if (st.mode() == WineCellarState.Mode.AGE) {
            startAgeOrNothing(p, st, manager);
            return;
        }
        // 按材料份數匹配：池內各原料總份數（每單位果汁按組成折算材料數，如 1 單位
        // 甜漿果汁 = 3 份甜漿果），與配方 ingredient 數量成整數倍比例時優先按配方產出
        // （清水不參與匹配）
        Map<String, Double> portions = new LinkedHashMap<>();
        for (WineCellarState.Liquid lq : st.liquids()) {
            for (Map.Entry<String, Double> e : lq.contents().entrySet()) {
                portions.merge(e.getKey(), e.getValue() * lq.units(), Double::sum);
            }
        }
        CellarRecipe.MatchResult cm = CellarRecipe.match(portions);
        // 配方批次豁免 8 單位最低液位（1 倍配方可能只有幾單位）；通用果酒仍需 ≥8
        if (cm == null && st.units() < 8) {
            p.sendMessage("§c液位不足 8 單位，無法啓動！");
            return;
        }
        // 釀造時長：測試模式（cellar.yml options.test-mode）啓動後立即完成；
        // 正式時長 20~40 分鐘隨機
        long duration = com.haiman233.worldtaste.load.CellarLoader.testMode
                ? 0L
                : (20 + ThreadLocalRandom.current().nextInt(21)) * 60_000L;
        st.startRun(p, duration);
        st.setCellarRecipe(cm != null ? cm.recipe.key : null, cm != null ? cm.multiplier : 0);
        st.save(manager);
        p.sendMessage("§a釀造開始！"
                + (com.haiman233.worldtaste.load.CellarLoader.testMode
                        ? "§7（測試模式：立即完成）"
                        : "§7預計 " + fmt(duration) + " 完成")
                + (cm != null ? " §7配方: §e" + cm.recipe.key
                        + " §7(×" + cm.multiplier + "，產出 §e" + st.juiceUnits() + " 單位§7)"
                        : ""));
    }

    private static void startAgeOrNothing(Player p, WineCellarState st, Block manager) {
        if (st.mode() != WineCellarState.Mode.AGE) return;
        if (!CellarStructure.matches(manager, false)) {
            p.sendMessage("§c多方塊結構不完整，無法開始陳化！");
            return;
        }
        if (!hasEnoughPower(manager)) {
            p.sendMessage("§c電量不足！");
            return;
        }
        if (!st.hasAlcohol()) {
            p.sendMessage("§c沒有含酒精的液體，無法陳化！");
            return;
        }
        st.startRun(p, 0);
        st.nextGrowthAt(WineCellarState.GAME_DAY_MS);
        st.save(manager);
        p.sendMessage("§a陳化開始！每完整遊戲日酒精度按配置比例增長。");
    }

    // ===== 展示物品 =====

    private static ItemStack clockItem(WineCellarState st) {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(st.mode() == WineCellarState.Mode.BREW
                    ? ChatColor.GOLD + "釀造模式" : ChatColor.AQUA + "陳化模式");
            List<String> lore = new ArrayList<>();
            if (st.phase() == WineCellarState.Phase.RUNNING) {
                if (st.mode() == WineCellarState.Mode.BREW) {
                    lore.add(ChatColor.GRAY + "剩餘: " + ChatColor.YELLOW
                            + fmt(Math.max(0, st.durationMs() - st.elapsedMs())));
                } else {
                    // 只按完整遊戲日計數：不足 1 日的零頭不計入陳化天數、也不觸發酒精度增長
                    long days = st.elapsedMs() / WineCellarState.GAME_DAY_MS;
                    lore.add(ChatColor.GRAY + "已陳化: " + ChatColor.YELLOW + days + ChatColor.GRAY + " 遊戲日");
                    lore.add(ChatColor.DARK_GRAY + "不滿一個完整遊戲日不計入");
                }
                lore.add(ChatColor.GRAY + "電力不足時釀造/陳化直接失敗！");
            } else {
                lore.add(ChatColor.GRAY + "當前未運行");
            }
            lore.add("");
            lore.add(st.mode() == WineCellarState.Mode.BREW
                    ? ChatColor.GRAY + "釀造: 糖分×比率+酒麴 → 酒精度，20~40 分鐘"
                    : ChatColor.GRAY + "陳化: 每完整遊戲日酒精度按比例增長");
            if (st.mode() == WineCellarState.Mode.BREW) {
                lore.add(st.autoAge()
                        ? ChatColor.GREEN + "自動陳化: 已開啓（釀造完成後自動陳化）"
                        : ChatColor.GRAY + "自動陳化: 已關閉");
                lore.add(ChatColor.YELLOW + "Shift+右鍵切換自動陳化！");
            }
            lore.add(ChatColor.GRAY + "需要含酒精液體才能陳化");
            lore.add("");
            lore.add(ChatColor.YELLOW + "點擊切換模式");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack clearItem(WineCellarState st) {
        boolean contaminated = st.phase() == WineCellarState.Phase.CONTAMINATED;
        ItemStack it = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            if (st.phase() == WineCellarState.Phase.RUNNING) {
                meta.setDisplayName(ChatColor.YELLOW + "污染清空按鈕");
                lore.add(ChatColor.RED + "機器運行中，無法清空");
            } else {
                meta.setDisplayName(ChatColor.YELLOW + (contaminated ? "清空機器（復原）" : "污染清空按鈕"));
                lore.add(contaminated ? ChatColor.GRAY + "點擊清空污染液體並復原機器"
                        : ChatColor.DARK_GRAY + "液體報廢后用於復原機器");
                lore.add(ChatColor.YELLOW + "Shift + 右鍵：直接清空機內液體");
                lore.add(ChatColor.DARK_GRAY + "（一併清除已放入的酒麴）");
            }
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack woolItem(WineCellarState st) {
        Material m = st.phase() == WineCellarState.Phase.RUNNING ? Material.LIME_WOOL : Material.RED_WOOL;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            switch (st.phase()) {
                case IDLE -> {
                    meta.setDisplayName(ChatColor.RED + "啓動");
                    lore.add(ChatColor.GRAY + "液位: " + st.units() + "/" + WineCellarState.CAPACITY
                            + "（通用 ≥8，配方不限）");
                    lore.add(st.yeast() == null ? ChatColor.RED + "未加入酒麴" : ChatColor.GREEN + "酒麴已加入");
                    lore.add(ChatColor.GRAY + "模式: " + (st.mode() == WineCellarState.Mode.BREW ? "釀造" : "陳化"));
                    if (st.mode() == WineCellarState.Mode.AGE) {
                        lore.add(ChatColor.RED + "陳化需要含酒精的液體");
                    }
                    lore.add(ChatColor.YELLOW + "點擊啓動");
                }
                case RUNNING -> {
                    meta.setDisplayName(ChatColor.GREEN + (st.mode() == WineCellarState.Mode.BREW
                            ? "釀造中 · 點擊關閉" : "陳化中 · 點擊停止"));
                    lore.add(ChatColor.GRAY + "正在耗電運行（斷電=直接失敗報廢）");
                    if (st.mode() == WineCellarState.Mode.BREW) {
                        lore.add(ChatColor.RED + "釀造中關閉將報廢全部液體！");
                    } else {
                        lore.add(ChatColor.GRAY + "關閉後停止計時，可出酒");
                    }
                }
                case READY -> {
                    meta.setDisplayName(ChatColor.GREEN + "已完成 · 下方槽位放玻璃瓶灌裝");
                    lore.add(ChatColor.GRAY + "酒精度: " + fmt(st.alcohol()) + "°  液位: " + st.units() + " 單位");
                    if (st.mode() == WineCellarState.Mode.BREW) {
                        lore.add(ChatColor.GRAY + "切換陳化模式後點擊羊毛可開始陳化");
                    }
                }
                default -> meta.setDisplayName(ChatColor.DARK_RED + "液體已報廢");
            }
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack pane(Material m, String name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component leg(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private static String fmt(double alcohol) {
        return String.format("%.1f", alcohol);
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + "分" + (sec % 60) + "秒";
    }

    private static boolean isVanilla(ItemStack item, Material m) {
        return item != null && item.getType() == m
                && io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(item) == null;
    }
}
