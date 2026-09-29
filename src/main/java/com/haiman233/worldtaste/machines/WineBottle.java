package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;｛
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 陳釀果酒（WT_WINE，內部物品，酒窖出酒產出，不在指南提供配方）。
 *
 * <p>每瓶攜帶 PDC：juice_wine 標記（禁止再次投入酒窖）、juice_alcohol（酒精度，每瓶一致）、
 * juice_contents（組成果汁）、juice_sugar、juice_players（榨汁師）。飲用（原版喝完藥水的
 * {@link PlayerItemConsumeEvent}）後按酒精度增加玩家酒精度（聯動異域花園醉酒效果）；
 * 原版飲用會自動返還玻璃瓶。</p>
 *
 * <p>注意：不能用右鍵 ItemUseHandler 實現飲用——POTION 材質右鍵會被原版喝藥流程接管，
 * 處理器不可靠；統一走 PlayerItemConsumeEvent。</p>
 */
public final class WineBottle extends SlimefunItem {

    private WineBottle(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                       org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }

    /** 註冊內部物品與飲用監聽（Setup 調用；無配方，僅酒窖產出）。 */
    public static void register(ItemGroup group) {
        if (group == null) {
            WT.log("酒窖果酒註冊失敗：物品組缺失");
            return;
        }
        ItemStack template = new ItemStack(Material.POTION);
        if (template.getItemMeta() instanceof PotionMeta pm) {
            pm.setColor(Color.fromRGB(110, 20, 40));
            pm.setDisplayName(Colors.c("&5&l陳釀果酒"));
            pm.setLore(List.of(Colors.c("&7酒窖陳釀的果酒，右鍵飲用")));
            template.setItemMeta(pm);
        }
        SlimefunItemStack stack = new SlimefunItemStack("WT_WINE", template);
        new WineBottle(group, stack, RecipeType.NULL, new ItemStack[0]).register(WT.plugin);
        Bukkit.getPluginManager().registerEvents(new DrinkListener(), WT.plugin);
    }

    /** 讀取果酒酒精度（非果酒返回 0）。 */
    public static int readAlcohol(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        Double v = meta.getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE);
        return v == null ? 0 : (int) Math.round(v);
    }

    /**
     * 構建一瓶果酒：基礎模板 + 酒窖信息 lore（wine-lore-format 渲染）+ PDC 數據
     * （wine 標記 / 酒精度 / 組成 / 糖分 / 榨汁師）。
     */
    public static ItemStack create(double alcohol, int sugar, Map<String, Double> contents,
                                   Set<String> players) {
        ItemStack out = preview(alcohol, sugar, contents, players);
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE, alcohol);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    JuicerRecipe.joinContentsFractional(contents));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 果酒預覽（不含 PDC 數據，酒窖出酒口展示用）。 */
    public static ItemStack preview(double alcohol, int sugar, Map<String, Double> contents,
                                    Set<String> players) {
        ItemStack out = new ItemStack(Material.POTION);
        ItemMeta meta = out.getItemMeta();
        if (meta instanceof PotionMeta pm) {
            pm.setColor(Color.fromRGB(110, 20, 40));
            pm.setDisplayName(Colors.c("&5&l陳釀果酒"));
            pm.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            // 與榨汁盆產物相同：整行 & 顏色碼經 Colors.c 後 setLore。
            // PotionMeta.lore(Component) 不會翻譯 juicer.yml 裏的 & 碼，會原樣顯示。
            List<String> lore = new ArrayList<>();
            lore.add(Colors.c("&7酒窖陳釀的果酒，右鍵飲用"));
            lore.addAll(renderInfo(JuicerRecipe.wineLoreFormat, players, contents, alcohol));
            pm.setLore(lore);
            out.setItemMeta(meta);
        }
        return out;
    }

    /**
     * 按 wine-lore-format 渲染果酒信息行（佔位符：%players%/%contents%/%alcohol%）。
     * 當 wine-contents-vertical=true 時，%contents% 會獨佔一行且每材料一行，
     * 因此返回多行（List），調用方須全部加入 lore。
     */
    private static List<String> renderInfo(String format, Set<String> players,
                                           Map<String, Double> contents, double alcohol) {
        String playersText = players == null || players.isEmpty() ? "無" : String.join("、", players);
        List<String> contentsLines = JuicerRecipe.contentsLegacyList(contents);
        List<String> out = new ArrayList<>();
        if (JuicerRecipe.wineContentsVertical && contentsLines.size() >= 1
                && !contentsLines.get(0).equals("§7無")
                && !contentsLines.get(0).equals("§8[組成已隱藏]")) {
            // 豎向模式：format 中 %contents% 替換為「組成」一行，然後每材料各自一行
            String firstLine = format
                    .replace("%players%", playersText)
                    .replace("%contents%", "§b組成：")
                    .replace("%alcohol%", String.format("%.1f", alcohol));
            out.add(Colors.c(firstLine));
            out.addAll(contentsLines);
        } else {
            // 橫向模式：保持原行為，%contents% 一次性替換為頓號分隔的一整行
            String line = format
                    .replace("%players%", playersText)
                    .replace("%contents%", contentsLines.get(0))
                    .replace("%alcohol%", String.format("%.1f", alcohol));
            out.add(Colors.c(line));
        }
        return out;
    }

    /** 組成果汁名（粘液顯示名保留自身顏色碼；原版用翻譯組件再轉舊式文本；分數顯示百分比）。 */
    private static String contentsLegacy(Map<String, Double> contents) {
        return JuicerRecipe.contentsLegacy(contents);
    }

    /** 飲用監聽：喝下果酒時按酒精度累加玩家酒精度（聯動異域花園）。 */
    private static final class DrinkListener implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onConsume(PlayerItemConsumeEvent e) {
            // 識別走自建 PDC 標記：酒瓶由普通 ItemStack 構建，不帶 Slimefun id，
            // getByItem 永遠返回 null，不能用 instanceof 判定
            ItemStack consumed = e.getItem();
            if (consumed == null || !consumed.hasItemMeta()) return;
            if (!consumed.getItemMeta().getPersistentDataContainer()
                    .has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE)) return;
            Player p = e.getPlayer();
            int alcohol = readAlcohol(consumed);
            if (alcohol > 0) ExoticGardenHook.addAlcoholDirect(p, alcohol);
        }
    }
}
