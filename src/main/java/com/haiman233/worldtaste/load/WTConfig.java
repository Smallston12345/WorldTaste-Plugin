package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 全局配置（config.yml）：玩法總開關等與內容無關的開關項。
 *
 * <p>加載順序：優先讀取 {@code plugins/WorldTaste/config.yml}（首次啓動由
 * {@code saveResource} 從 jar 釋放默認文件），讀取失敗或文件缺失時回退 jar 內置配置。
 * 這樣管理員既能直接改插件目錄下的文件，也不必擔心文件被刪壞導致插件起不來。</p>
 *
 * <p>目前僅一項：釀造工藝總開關 {@code brewing.enabled}。關閉時見
 * {@link #blockItem}——釀造分組下的物品不再註冊，但分組按鈕保留並標註「已禁用」。</p>
 */
public final class WTConfig {

    private WTConfig() {}

    /** 配置文件名（jar 內置 + 插件數據目錄同名）。 */
    private static final String FILE = "config.yml";

    /** 釀造工藝分組 id（groups.yml 的 ws_niangzaogongyi）。 */
    public static final String BREWING_GROUP = "ws_niangzaogongyi";

    /**
     * 釀造工藝「已禁用」佔位説明頁 id（items.yml）。
     *
     * <p>必須保留這一個可見物品的緣由：Slimefun 的 {@code ItemGroup#isVisible} 對「一個物品
     * 都沒有」的分組直接返回 false，而 {@code SubItemGroup#isVisibleInNested} 走的正是它。
     * 釀造玩法關閉後若把分組內容全部屏蔽，分組即成空組、按鈕被指南整條過濾掉，玩家看不到
     * 既定的「已禁用」標註。故關閉時註冊這一個佔位説明頁（開啓時跳過）保住按鈕。</p>
     */
    public static final String BREWING_DISABLED_NOTICE = "WT_NIANZAO_DISABLED";

    /**
     * 釀造工藝總開關（brewing.enabled）。
     *
     * <p>缺省值與 config.yml 默認保持一致——**默認 true（默認開啓釀造玩法）**，
     * 不需要該玩法的服主可在 plugins/WorldTaste/config.yml 裏改為 false。</p>
     */
    public static boolean brewingEnabled = true;

    /** 關閉時追加在分組名後的字樣（brewing.disabled-suffix）。 */
    public static String disabledSuffix = "&r&c&l已禁用";

    /**
     * 讀取配置。必須在 {@link GroupLoader#load()} 之前調用——分組名是否追加
     * 「已禁用」取決於本開關。
     */
    public static void load() {
        YamlConfiguration y = null;
        File file = new File(WT.plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            try {
                WT.plugin.saveResource(FILE, false);
            } catch (Exception e) {
                WT.log("釋放默認 " + FILE + " 失敗（將使用 jar 內置配置）: " + e);
            }
        }
        if (file.exists()) {
            try {
                y = YamlConfiguration.loadConfiguration(file);
            } catch (Exception e) {
                WT.log("讀取 plugins/WorldTaste/" + FILE + " 失敗，回退 jar 內置配置: " + e);
                y = null;
            }
        }
        if (y == null) y = Yaml.loadResource(WT.plugin, FILE);

        brewingEnabled = y.getBoolean("brewing.enabled", true);
        disabledSuffix = y.getString("brewing.disabled-suffix", "&r&c&l已禁用");
        if (disabledSuffix == null) disabledSuffix = "";

        WT.plugin.getLogger().info("config.yml: 釀造工藝 " + (brewingEnabled ? "已啓用" : "已禁用"));
    }

    /** 釀造玩法是否已關閉。 */
    public static boolean brewingDisabled() {
        return !brewingEnabled;
    }

    /** 分組 id 是否為釀造工藝分組。 */
    public static boolean isBrewingGroup(String groupId) {
        return groupId != null && BREWING_GROUP.equalsIgnoreCase(groupId.trim());
    }

    /**
     * 該物品是否應被跳過註冊：僅當釀造玩法關閉且該物品屬於釀造工藝（分組或顯式 id）時返回 true。
     *
     * <p>判定以分組為主（釀造內容全部掛在 ws_niangzaogongyi 下），{@code itemId} 僅作
     * 兜底——物品本體由 WineBottle 等 Java 側註冊時也能一併攔下。</p>
     *
     * <p>例外：{@link #BREWING_DISABLED_NOTICE} 佔位説明頁不受本攔截影響，由調用方按其
     * 「僅在關閉時註冊」的特殊語義單獨處理。</p>
     */
    public static boolean blockItem(String groupId, String itemId) {
        if (!brewingDisabled()) return false;
        if (BREWING_DISABLED_NOTICE.equalsIgnoreCase(itemId)) return false;
        if (isBrewingGroup(groupId)) return true;
        return itemId != null && (itemId.equalsIgnoreCase("WT_WINE") || itemId.equalsIgnoreCase("WT_ZHAPEN")
                || itemId.equalsIgnoreCase("WT_JIUJIAO") || itemId.equalsIgnoreCase("WT_WENDU"));
    }

    /**
     * 給分組展示物品追加「已禁用」字樣（僅釀造分組且玩法關閉時生效）。
     *
     * <p>追加內容先 {@code &r} 復位：分類名末尾常帶 {@code &k}（亂碼）等格式碼，
     * 直接拼接會把「已禁用」也一起染色/亂碼。</p>
     */
    public static ItemStack applyDisabledSuffix(String groupKey, ItemStack display) {
        if (display == null || disabledSuffix.isEmpty()) return display;
        if (!brewingDisabled() || !isBrewingGroup(groupKey)) return display;
        ItemStack out = display.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) return display;
        String base = meta.hasDisplayName() ? meta.getDisplayName() : "";
        meta.setDisplayName(base + " " + Colors.c(disabledSuffix));
        out.setItemMeta(meta);
        return out;
    }
}
