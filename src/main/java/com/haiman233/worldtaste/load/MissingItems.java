package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 加載期「物品未找到」的摺疊上報。
 *
 * <p>內容 YAML 裏有上萬處 {@code material_type: slimefun} 引用（大量指向美食家 {@code GN_*}、
 * {@code YEAST} 等其它附屬物品）。目標服務器未安裝對應附屬時，舊實現會為每一處引用打印一行
 * 「未找到粘液物品」警告，一次啓動刷出數千行日誌。</p>
 *
 * <p>本類把未命中按 id 去重計數（普通物品靜默），加載結束時由 {@link #report()} 彙總成一行；
 * 僅當缺失的是 {@link #SPECIAL} 中的「特殊物品」——即 Java 代碼硬引用、缺失即對應功能不可用的
 * 物品——才立即單條告警。</p>
 *
 * <p>僅加載期使用：{@link Setup#loadAll()} 開頭 {@link #reset()}、結尾 {@link #report()}。</p>
 */
public final class MissingItems {

    private MissingItems() {}

    /**
     * 特殊物品 id → 缺失影響説明。這些 id 被 Java 側硬引用（機器/交互/釀造核心），
     * 缺失不只是「配方變成佔位方塊」，而是對應功能整體不可用，故需要立即告警。
     */
    private static final Map<String, String> SPECIAL = Map.of(
            "WT_ZHAPEN", "榨汁盆（釀造工藝核心機器）",
            "WT_JIUJIAO", "酒窖管理器（釀造工藝核心機器）",
            "WT_WENDU", "温度控制器（酒窖温控）",
            "WT_GUOZHA", "果渣（榨汁產物）",
            "WT_SWEET_PAPER", "甜度試紙",
            "WT_WINE", "陳釀果酒（酒窖產物）",
            "WT_JIUQU", "酒麴（釀造加成）");

    /** 彙總展示的示例 id 上限（超出部分以「……等」收尾，避免彙總本身又刷屏）。 */
    private static final int MAX_EXAMPLES = 30;

    /** 未命中引用：id(大寫) -> 出現次數。 */
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    /** 特殊物品已告警集合（同一物品只告警一次）。 */
    private static final Set<String> WARNED_SPECIAL = new HashSet<>();

    /** 加載開始時清空上一輪狀態（{@link Setup#loadAll()} 調用）。 */
    public static void reset() {
        COUNTS.clear();
        WARNED_SPECIAL.clear();
    }

    /**
     * 記錄一次「物品未找到」。特殊物品立即告警（每個 id 只告警一次）；普通物品靜默計數，
     * 由 {@link #report()} 在加載結束時彙總為一行。
     *
     * <p>{@code id} 也可以帶前綴區分來源（如 {@code "材質 XXX"} 表示未知原版材質），
     * 僅用於彙總展示，特殊物品匹配按原樣大寫比較。</p>
     */
    public static void record(String id) {
        if (id == null || id.isEmpty()) return;
        String key = id.toUpperCase(Locale.ROOT);
        String what = SPECIAL.get(key);
        if (what != null) {
            if (WARNED_SPECIAL.add(key)) {
                WT.log("特殊物品缺失: " + key + "（" + what + "），相關功能將不可用！");
            }
            return;
        }
        COUNTS.merge(key, 1, Integer::sum);
    }

    /** 加載結束彙總：一行説明 + 最多 {@value #MAX_EXAMPLES} 個示例（帶出現次數）。 */
    public static void report() {
        if (COUNTS.isEmpty()) return;
        int total = 0;
        for (int n : COUNTS.values()) total += n;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
            if (shown >= MAX_EXAMPLES) {
                sb.append(" ……等共 ").append(COUNTS.size()).append(" 種");
                break;
            }
            if (shown > 0) sb.append(", ");
            sb.append(e.getKey()).append('×').append(e.getValue());
            shown++;
        }
        WT.log("有 " + COUNTS.size() + " 種被引用的物品未找到（共 " + total
                + " 處引用，對應配方/展示已用佔位方塊代替）：" + sb);
        COUNTS.clear();
    }
}
