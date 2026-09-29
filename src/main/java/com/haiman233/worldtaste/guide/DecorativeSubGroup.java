package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemState;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 裝飾分隔板組（groups.yml type: button，RSC 中 actions: none 的對應物）。
 *
 * <p>兩類裝飾物：
 * <ul>
 *   <li>玻璃板（材質 *_STAINED_GLASS_PANE / GLASS_PANE）：清洗為「普通原版玻璃板」
 *       （無名字/無 lore/無貼圖變化）；</li>
 *   <li>區段標籤（頭顱等其它材質，如「主題餐飲」）：保留原名字與頭顱貼圖，僅確保無 lore。</li>
 * </ul>
 * 兩者均打上 PDC 標記：原版指南路徑由 WTNestedGroup.openVanilla 渲染並綁定空點擊處理器；
 * JEG 指南路徑點擊時觸發 ItemGroupButtonClickEvent，由 JegGuideListener 依據該標記取消
 * 事件——JEG 的 EventBuilder.ifSuccess 語義為「已取消 = 點擊已處理」，菜單紋絲不動，
 * 既打不開也拿不走。另外覆寫 getItem(Player) 去掉 Slimefun 默認加上的
 * 「打開物品組」提示 lore（裝飾板不可打開，不需要該提示）。</p>
 *
 * <p>可見性（Slimefun 的 ItemGroup.isVisible 邏輯）：
 * <ol>
 *   <li>入口檢查 items 字段非空——靠構造時塞入的啞物品通過；</li>
 *   <li>遍歷 getItems() 調用 item.isDisabledIn(world)——未註冊物品在這裏會拋
 *       IllegalArgumentException，因此啞物品絕不能出現在 getItems() 結果裏：
 *       本類覆寫 getItems()，惰性挑一個已註冊、非隱藏、未禁用的真實物品返回。</li>
 * </ol>
 * 啞物品只進 items 字段、不註冊進 Slimefun 註冊表，搜索/作弊模式/指南均不可見。</p>
 */
public class DecorativeSubGroup extends SubItemGroup {

    private static NamespacedKey markKey;

    /** 清洗後的展示物品（玻璃板無名無 lore；頭顱標籤保留名字與貼圖）。 */
    private final ItemStack displayItem;

    /** 可見性循環用的真實物品（惰性解析 + 緩存）。 */
    private SlimefunItem cachedVisibilityItem;

    public DecorativeSubGroup(NamespacedKey key, NestedItemGroup parent, ItemStack display, int tier) {
        super(key, parent, prepareDisplay(display), tier);
        this.displayItem = prepareDisplay(display);
        addVisibilityDummy();
    }

    /** 覆寫掉 Slimefun 默認的「打開物品組」提示 lore——裝飾板不可打開，不需要該提示。 */
    @Override
    public ItemStack getItem(Player p) {
        return displayItem.clone();
    }

    /** 識別標記 key：JegGuideListener 據此取消 JEG 的物品組點擊事件。 */
    public static NamespacedKey markKey() {
        if (markKey == null) {
            markKey = new NamespacedKey(WT.plugin, "decorative_pane");
        }
        return markKey;
    }

    /**
     * 覆寫可見性遍歷：絕不返回未註冊的啞物品（isDisabledIn 會拋異常炸掉整頁渲染），
     * 改回一個已註冊的真實物品。解析失敗返回空列表（該組不顯示，但不會炸菜單）。
     */
    @Override
    public List<SlimefunItem> getItems() {
        SlimefunItem it = resolveVisibilityItem();
        return it == null ? List.of() : List.of(it);
    }

    private SlimefunItem resolveVisibilityItem() {
        if (cachedVisibilityItem != null && cachedVisibilityItem.getState() == ItemState.ENABLED) {
            return cachedVisibilityItem;
        }
        cachedVisibilityItem = null;
        for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
            if (item.getState() == ItemState.ENABLED && !item.isHidden()) {
                cachedVisibilityItem = item;
                break;
            }
        }
        return cachedVisibilityItem;
    }

    /**
     * 塞入可見性啞物品：僅讓 items 字段非空（isVisible 的入口檢查），
     * 不 register 進 Slimefun 註冊表；配合 getItems() 覆寫，它永遠不會被遍歷到。
     */
    private void addVisibilityDummy() {
        try {
            String id = getKey().getKey().toUpperCase(Locale.ROOT) + "_DECOR_DUMMY";
            SlimefunItemStack stack = new SlimefunItemStack(id, prepareDisplay(new ItemStack(Material.PAPER)));
            SlimefunItem dummy = new SlimefunItem(this, stack, RecipeType.NULL, new ItemStack[9]);
            add(dummy);
        } catch (Throwable t) {
            WT.log("裝飾組 " + getKey().getKey() + " 啞物品創建失敗，該玻璃板可能不顯示: " + t);
        }
    }

    /**
     * 展示物品清洗：玻璃板 → 無名字無 lore 的純佔位；
     * 其它（頭顱區段標籤等）→ 保留原名字與貼圖；兩者統一清 lore 並打 PDC 標記。
     */
    private static ItemStack prepareDisplay(ItemStack src) {
        ItemStack out = src == null ? new ItemStack(Material.WHITE_STAINED_GLASS_PANE) : src.clone();
        Material mat = out.getType();
        boolean isPane = mat == Material.GLASS_PANE || mat.name().endsWith("_STAINED_GLASS_PANE");
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            if (isPane) {
                meta.setDisplayName(null);
            }
            meta.setLore(null);
            meta.getPersistentDataContainer().set(markKey(), PersistentDataType.BYTE, (byte) 1);
            out.setItemMeta(meta);
        }
        return out;
    }
}