package com.haiman233.worldtaste.hook;

import com.haiman233.worldtaste.WT;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 異域花園（ExoticGarden·複合花園 fork）酒精度聯動。
 *
 * <p>異域花園的酒精系統：{@code ExoticGarden.drunkPlayers}（玩家名 → PlayerAlcohol）按玩家累計
 * 酒精度，每 6 秒衰減 1 點，達到 100 進入醉酒（反胃/走路打晃/胡言亂語）。其酒類物品飲用時經
 * {@code CustomWine#restoreHunger} 累加數值並按閾值提示半醉/醉酒。</p>
 *
 * <p>本插件不硬依賴異域花園（plugin.yml 僅 softdepend），故全部走反射：物品註冊期把
 * items.yml 的 alcohol 數值登記到 {@link #alcoholValues}，消耗品飲用時經
 * {@link #onDrink} 累加到異域花園並復刻其半醉/醉酒提示。異域花園未安裝或其酒精系統
 * 缺失時靜默降級（酒精度 lore 僅作風味文本）。</p>
 */
public final class ExoticGardenHook {

    private ExoticGardenHook() {}

    /** Slimefun 物品 id -> 酒精度（items.yml alcohol 字段，ItemsLoader 註冊期寫入）。 */
    private static final Map<String, Integer> alcoholValues = new HashMap<>();

    private static volatile boolean probed;
    private static boolean available;

    private static Field instanceField;
    private static Field drunkPlayersField;
    private static Method initPlayerData;
    private static Method sendDrunkMessage;
    private static Method addAlcohol;
    private static Method getAlcohol;

    /** 登記酒精度（物品 id 與註冊 id 一致；重複註冊以最後一次為準）。 */
    public static void register(String itemId, int alcohol) {
        if (itemId != null && alcohol > 0) alcoholValues.put(itemId, alcohol);
    }

    public static int registeredCount() {
        return alcoholValues.size();
    }

    /** 啓動期探測異域花園酒精系統並輸出聯動日誌（結果緩存，運行期 available/onDrink 複用）。 */
    public static void init() {
        available();
    }

    /** 異域花園酒精系統是否可用（首次調用探測並緩存）。 */
    public static boolean available() {
        if (!probed) {
            synchronized (ExoticGardenHook.class) {
                if (!probed) {
                    try {
                        probe();
                    } finally {
                        probed = true;
                    }
                }
            }
        }
        return available;
    }

    private static void probe() {
        if (Bukkit.getPluginManager().getPlugin("ExoticGarden") == null) {
            WT.plugin.getLogger().info("未檢測到異域花園，酒精度聯動未啓用");
            return;
        }
        try {
            Class<?> eg = Class.forName("io.github.thebusybiscuit.exoticgarden.ExoticGarden");
            Class<?> pa = Class.forName("io.github.thebusybiscuit.exoticgarden.PlayerAlcohol");
            instanceField = eg.getField("instance");
            drunkPlayersField = eg.getField("drunkPlayers");
            initPlayerData = eg.getMethod("initPlayerData", Player.class);
            sendDrunkMessage = eg.getMethod("sendDrunkMessage", Player.class);
            addAlcohol = pa.getMethod("addAlcohol", int.class);
            getAlcohol = pa.getMethod("getAlcohol");
            available = true;
            WT.plugin.getLogger().info("已聯動異域花園酒精度系統（" + alcoholValues.size() + " 種酒類飲品）");
        } catch (Throwable t) {
            available = false;
            // 異域花園已安裝但酒精系統不可用（異常 fork/結構變更）：一次性告警便於排查
            WT.log("檢測到異域花園但酒精系統反射失敗，酒精度聯動未啓用: " + t);
        }
    }

    /**
     * 飲用酒類後累加異域花園酒精度（按物品 id 查表）。
     * 非酒類物品（無登記數值）或聯動不可用時為無副作用空操作。
     */
    public static void onDrink(Player p, String itemId) {
        Integer amount = itemId == null ? null : alcoholValues.get(itemId);
        if (amount == null || amount <= 0) return;
        addAlcoholDirect(p, amount);
    }

    /**
     * 直接累加玩家酒精度（酒窖果酒飲用等），並按異域花園閾值提示
     * （對齊 CustomWine：50~99 半醉、>=100 醉酒+胡言亂語）。
     */
    public static void addAlcoholDirect(Player p, int amount) {
        if (amount <= 0 || !available()) return;
        try {
            Object eg = instanceField.get(null);
            if (eg == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> drunk = (Map<String, Object>) drunkPlayersField.get(null);
            Object pa = drunk.get(p.getName());
            if (pa == null) {
                // 玩家數據缺失（異域花園未初始化該玩家）：走其官方加載路徑補齊
                initPlayerData.invoke(eg, p);
                pa = drunk.get(p.getName());
                if (pa == null) return;
            }
            addAlcohol.invoke(pa, amount);
            int now = (Integer) getAlcohol.invoke(pa);
            if (now < 100 && now > 50) {
                p.sendMessage("§8[§a異域花園§8] §e你已經半醉了，請適度飲酒！");
            } else if (now >= 100) {
                p.sendMessage("§8[§a異域花園§8] §e你醉了！可以嘗試食用一些可以§b解酒§e的消耗品");
                sendDrunkMessage.invoke(null, p);
            }
        } catch (Throwable t) {
            WT.log("異域花園酒精度累加失敗: " + t);
        }
    }
}
