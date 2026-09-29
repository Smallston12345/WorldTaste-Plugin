package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 內容加載編排：按依賴順序註冊 groups → recipe_types → 預加載展示物品 → 各內容文件。 */
public final class Setup {

    private Setup() {}

    /** 含有可被其它配方以 material_type:slimefun 引用的“物品”的文件（需預加載展示堆）。 */
    private static final String[] ITEM_FILES = {
        "items.yml", "machines.yml", "foods.yml", "mob_drops.yml", "geo_resources.yml",
        "recipe_machines.yml", "mb_machines.yml", "linked_recipe_machines.yml",
        "template_machines.yml", "workbenches.yml"
    };

    public static void loadAll() {
        long t = System.currentTimeMillis();
        // 「物品未找到」摺疊上報：加載開始清零、結尾彙總（未裝對應附屬時引用可達數千處，不逐條刷屏）
        MissingItems.reset();
        // 全局配置（config.yml）須最先加載：分組名是否追加「已禁用」、釀造內容是否註冊都取決於它
        WTConfig.load();
        GroupLoader.load();
        RecipeTypes.load();
        preloadDisplays();
        com.haiman233.worldtaste.behavior.Behaviors.loadData();
        ItemsLoader.load();
        FoodsLoader.load();
        // 釀造工藝總開關（config.yml brewing.enabled）：關閉時整條釀造玩法不加載——
        // 榨汁/酒窖配方、糖分表、果酒與甜度試紙物品全部跳過，分組按鈕保留並標註「已禁用」
        if (WTConfig.brewingEnabled) {
            // 榨汁盆配方（產物/投入物可引用已註冊物品，須在物品註冊後加載）
            JuicerLoader.load();
            // 糖分值配置（材料 id → 糖分，運行期讀取）
            SugarLoader.load();
            // 酒窖配方（cellar.yml：配方表 + 命名功能開關）
            CellarLoader.load();
            com.haiman233.worldtaste.machines.WineBottle.register(WT.group("ws_niangzaogongyi"));
            com.haiman233.worldtaste.machines.SweetnessPaper.register();
        }
        MobDropsLoader.load();
        MenuLoader.load();
        RecipeMachineLoader.load();
        WorkbenchLoader.load();
        MultiBlockLoader.load();
        TemplateLoader.load();
        GeoLoader.load();
        com.haiman233.worldtaste.behavior.Behaviors.registerListeners();
        // 異域花園酒精度聯動：啓動期探測並在日誌輸出聯動狀態（未裝異域花園時降級為風味文本）
        com.haiman233.worldtaste.hook.ExoticGardenHook.init();
        // R6：所有內容/行為文件加載完畢，釋放 Yaml 文件名緩存的解析樹（長穩：避免長期持有 ~MB 級解析對象樹）。
        // 經核查無 Loader 以字段持久持有 ConfigurationSection，registerListeners 也不再訪問 YAML，釋放安全。
        Yaml.clearCache();
        // R7：釋放頭顱貼圖(PlayerSkin)去重緩存（Read 僅加載期使用，運行期不再調 Read.item/recipe）。
        Read.clearSkinCache();
        // R9：釋放 preload 展示物品表（數千個 ItemStack）。經核查全部讀取方（Read.resolve 的
        // material_type:slimefun 回退、RegisterConditions 的 itemexist、各 Loader 的展示堆獲取）
        // 均在本次 loadAll 流程內，運行期無引用，可安全釋放（長穩省內存）。
        WT.preload.clear();
        // 「物品未找到」彙總：普通缺失摺疊為一行（特殊物品已在發現時單獨告警）
        MissingItems.report();
        WT.plugin.getLogger().info("基礎內容加載完成，耗時 " + (System.currentTimeMillis() - t) + "ms");
    }

    /** 第一遍：把各物品/機器的展示堆加入 WT.preload，使後續配方解析能跨文件按 id 引用。 */
    private static void preloadDisplays() {
        for (String file : ITEM_FILES) {
            YamlConfiguration y = Yaml.loadResource(WT.plugin, file);
            // 逐條 try/catch 故障隔離：Read.item 經 PlayerHead/PlayerSkin.fromURL|fromBase64|fromHashCode
            // 等路徑，單條壞展示數據可能拋異常；若無隔離會中止 preloadDisplays → loadAll → 其後
            // items/foods/機器等全部因 preload 查空而跳過，插件近乎空載啓用。
            for (String id : y.getKeys(false)) {
                try {
                    ConfigurationSection s = y.getConfigurationSection(id);
                    if (s == null) continue;
                    ConfigurationSection itemSec = s.getConfigurationSection("item");
                    if (itemSec == null) continue;
                    ItemStack display = Read.item(itemSec, false);
                    if (display != null) {
                        String effId = s.getString("id_alias", id).toUpperCase(java.util.Locale.ROOT);
                        WT.preload.put(effId, display);
                    }
                } catch (Exception e) {
                    WT.log("預加載展示物品 " + id + " 失敗，跳過: " + e);
                }
            }
        }
    }
}
