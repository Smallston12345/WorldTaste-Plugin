package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 酒窖多方塊結構展示頁（對齊文明覆興的多方塊結構展示）：三層 3×3 網格
 * （頂層 / 中層 / 底層，y 軸從下到上），格子顯示對應方塊與名稱，
 * 中層中心的空氣格以灰色標識。返回鍵回到酒窖管理器頁面。
 */
public final class CellarStructureMenu {

    private CellarStructureMenu() {}

    /** 打開結構展示頁：cellar 為來源酒窖管理器方塊（返回鍵直接回到該頁面）。 */
    public static void open(Player p, Block cellar) {
        ChestMenu menu = new ChestMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY + "· 多方塊結構");
        menu.setEmptySlotsClickable(false);
        menu.setPlayerInventoryClickable(true);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 三個 3×3 網格：列 0-2 / 3-5 / 6-8，行 2-4（每列一格子組）
        // 頂層（行 2-4 的第一組）
        layer(menu, 0, new String[][]{
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"},
                {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"},
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"}});
        // 中層
        layer(menu, 1, new String[][]{
                {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"},
                {"橡木木板", "OAK_PLANKS"}, {"空氣", null}, {"橡木木板", "OAK_PLANKS"},
                {"橡木原木", "OAK_LOG"}, {"酒窖管理器", "BREWING_STAND"}, {"温度控制器", "WAXED_COPPER_BLOCK"}});
        // 底層（與頂層相同）
        layer(menu, 2, new String[][]{
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"},
                {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"},
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"}});

        // 層標籤
        menu.addItem(1, label(Material.OAK_PLANKS, "頂層（y=+1）"), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(4, label(Material.BREWING_STAND, "中層（y=0）"), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(7, label(Material.OAK_PLANKS, "底層（y=-1）"), ChestMenuUtils.getEmptyClickHandler());

        // 返回：直接回到來源酒窖頁面（子頁面打開時酒窖會話已結束，不能用會話表反查）
        menu.addItem(49, back(), (pl, s, cur, action) -> {
            if (cellar != null && me.mrCookieSlime.Slimefun.api.BlockStorage.check(cellar)
                    instanceof WineCellarManager) {
                CellarMenu.open(pl, cellar);
            } else {
                pl.closeInventory();
            }
            return false;
        });
        menu.open(p);
    }

    /** 在指定 3×3 網格組（group=0/1/2，對應列 0-2/3-5/6-8，行 2-4）繪製一層結構。 */
    private static void layer(ChestMenu menu, int group, String[][] cells) {
        int baseCol = group * 3;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                String[] cell = cells[row * 3 + col];
                String name = cell[0];
                Material m = cell[1] == null ? Material.STRUCTURE_VOID : Material.matchMaterial(cell[1]);
                int slot = (row + 1) * 9 + baseCol + col;
                ItemStack it = new ItemStack(m == null ? Material.STRUCTURE_VOID : m);
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GRAY + name);
                    it.setItemMeta(meta);
                }
                menu.addItem(slot, it, ChestMenuUtils.getEmptyClickHandler());
            }
        }
    }

    private static ItemStack label(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack back() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回酒窖");
            it.setItemMeta(meta);
        }
        return it;
    }
}
