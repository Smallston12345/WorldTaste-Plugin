package com.haiman233.worldtaste.guide;

import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.guide.SurvivalSlimefunGuide;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 嵌套物品組工廠。
 *
 * <p>為什麼用「匿名直接子類」：JEG（JustEnoughGuide）打開嵌套組時，只對
 * 「恰好是 NestedItemGroup 類型、或其匿名直接子類」的分組使用它自己的渲染
 * （底部收藏欄、實時搜索等完整增強），具名子類會被當普通分組渲染成近乎空白頁。
 * 因此本工廠創建 NestedItemGroup 的匿名子類，讓 JEG 正常接管嵌套菜單。</p>
 *
 * <p>裝飾分隔板（{@link DecorativeSubGroup}）作為普通 SubItemGroup 掛在組內：
 * JEG 渲染時正常顯示為普通原版玻璃板，點擊由 JegGuideListener 取消事件；
 * 原版指南（JEG 未接管、才走到覆寫的 open）由 {@link #openVanilla} 渲染，
 * 玻璃板綁定空點擊處理器——兩種指南下都打不開、拿不走。</p>
 */
public final class WTNestedGroup {

    private WTNestedGroup() {}

    /** 渲染條目：按註冊順序與 tier 排版。 */
    private record Entry(int seq, int tier, SubItemGroup group) {}

    /**
     * 創建嵌套組（匿名直接子類——JEG 以 getSuperclass() == NestedItemGroup
     * 且 isAnonymousClass() 識別並接管渲染；原版指南則虛分派到覆寫的 open）。
     */
    public static NestedItemGroup create(NamespacedKey key, ItemStack display, int tier) {
        List<Entry> entries = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        return new NestedItemGroup(key, display, tier) {
            @Override
            public void addSubGroup(SubItemGroup sub) {
                super.addSubGroup(sub);
                entries.add(new Entry(seq.getAndIncrement(), sub.getTier(), sub));
            }

            @Override
            public void removeSubGroup(SubItemGroup sub) {
                super.removeSubGroup(sub);
                entries.removeIf(e -> e.group() == sub);
            }

            @Override
            public void open(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
                // 只有原版 Slimefun 指南會走到這裏；JEG 用它自己的 openNestedItemGroup
                openVanilla(p, profile, mode, this, entries, 1);
            }
        };
    }

    /** 原版指南路徑的嵌套菜單渲染：真實子組可點擊，裝飾玻璃板僅佔位、點擊無效。 */
    private static void openVanilla(Player p, PlayerProfile profile, SlimefunGuideMode mode,
                                    NestedItemGroup group, List<Entry> entries, int page) {
        // 每次打開時解析渲染條目：不可見子組（Seasonal 等）跳過
        List<Entry> visible = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.group().isVisibleInNested(p)) continue;
            visible.add(e);
        }
        visible.sort(Comparator.comparingInt(Entry::tier).thenComparingInt(Entry::seq));

        if (mode == SlimefunGuideMode.SURVIVAL_MODE) {
            profile.getGuideHistory().add(group, page);
        }
        ChestMenu menu = new ChestMenu(Slimefun.getLocalization().getMessage(p, "guide.title.main"));
        SurvivalSlimefunGuide guide = (SurvivalSlimefunGuide) Slimefun.getRegistry().getSlimefunGuide(mode);
        menu.setEmptySlotsClickable(false);
        menu.addMenuOpeningHandler(SoundEffect.GUIDE_BUTTON_CLICK_SOUND::playFor);
        guide.createHeader(p, profile, menu);
        menu.addItem(1, ChestMenuUtils.getBackButton(p, "",
                ChatColor.GRAY + Slimefun.getLocalization().getMessage(p, "guide.back.guide")));
        menu.addMenuClickHandler(1, (pl, slot, item, action) -> {
            SlimefunGuide.openMainMenu(profile, mode, profile.getGuideHistory().getMainMenuPage());
            return false;
        });

        int perPage = 36;
        int pages = (visible.size() + perPage - 1) / perPage;
        int start = Math.max(0, perPage * (page - 1));
        int slot = 9;
        for (int i = start; i < visible.size() && slot < 45; i++, slot++) {
            SubItemGroup subgroup = visible.get(i).group();
            menu.addItem(slot, subgroup.getItem(p));
            if (subgroup instanceof DecorativeSubGroup) {
                // 裝飾分隔板：普通原版玻璃板佔位；綁定空點擊處理器——打不開、拿不走
                menu.addMenuClickHandler(slot, (pl, s, item, action) -> false);
            } else {
                menu.addMenuClickHandler(slot, (pl, s, item, action) -> {
                    SlimefunGuide.openItemGroup(profile, subgroup, mode, 1);
                    return false;
                });
            }
        }

        menu.addItem(46, ChestMenuUtils.getPreviousButton(p, page, pages));
        menu.addMenuClickHandler(46, (pl, s, item, action) -> {
            int prev = page - 1;
            if (prev != page && prev > 0) {
                openVanilla(p, profile, mode, group, entries, prev);
            }
            return false;
        });
        menu.addItem(52, ChestMenuUtils.getNextButton(p, page, pages));
        menu.addMenuClickHandler(52, (pl, s, item, action) -> {
            int next = page + 1;
            if (next != page && next <= pages) {
                openVanilla(p, profile, mode, group, entries, next);
            }
            return false;
        });
        menu.open(p);
    }
}