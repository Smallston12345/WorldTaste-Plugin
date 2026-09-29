package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.WorldTastePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 從 jar 資源加載 YAML。
 *
 * <p>R6：加載期文件名緩存（parse-once）。{@code preloadDisplays} 與各 Loader 會訪問同一組內容文件
 * （items.yml/mb_machines.yml/… 共 10 個），舊實現每個文件被解析兩次（含 2.5MB 的 items.yml、1.8MB
 * 的 mb_machines.yml）。緩存後每個文件在單次加載中只解析一次，第二次訪問為 HashMap 命中。
 *
 * <p>安全性：經核查全部 Loader 僅讀取配置、從不寫入（無 {@code y.set/section.set}），故跨調用方共享同一
 * {@link YamlConfiguration} 實例行為等價。緩存為加載期單線程填充，與 {@code WT.preload} 等同為 HashMap。
 * 加載結束後由 {@link Setup#loadAll()} 調用 {@link #clearCache()} 釋放解析樹（長穩：避免長期持有 ~MB 級
 * 解析對象樹；經核查無 Loader 以字段形式持久持有 ConfigurationSection，釋放安全）。
 */
public final class Yaml {

    private Yaml() {}

    /** 加載期文件名緩存：filename -> 已解析配置（只讀共享，加載結束釋放）。 */
    private static final Map<String, YamlConfiguration> CACHE = new HashMap<>();

    public static YamlConfiguration loadResource(WorldTastePlugin plugin, String name) {
        YamlConfiguration cached = CACHE.get(name);
        if (cached != null) return cached;
        YamlConfiguration cfg = doLoad(plugin, name);
        CACHE.put(name, cfg);
        return cfg;
    }

    private static YamlConfiguration doLoad(WorldTastePlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                WT.log("資源缺失: " + name);
                return new YamlConfiguration();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            WT.log("讀取 " + name + " 失敗: " + e);
            return new YamlConfiguration();
        }
    }

    /** 加載完成後釋放解析樹緩存（{@link Setup#loadAll()} 末尾調用）。 */
    public static void clearCache() {
        CACHE.clear();
    }
}
