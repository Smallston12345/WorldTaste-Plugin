package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 榨汁盆配方（juicer.yml 定義，{@link JuicerLoader} 加載）。
 *
 * <p>配方投入物為「多材料 + 數量」多集（juicer.yml 的 {@code ingredient: [- id:數量]}，
 * id 為原版材質名或粘液物品 id），榨汁時盆內內容物與某配方的投入多集<b>完全一致</b>即鎖定
 * 該配方進入踩踏/鐵砧進度；全部配方都不匹配時，若內容物均屬於可混合列表（mix.recipes 中
 * 配方的投入物）則走混合榨汁。接取統一為玻璃瓶每次 1 份（水量 -1）、鐵桶滿盆一次接完，
 * 無 per-配方容器。</p>
 *
 * <p>頭部 mix 段：可混合配方列表 + 混合榨汁進度 + 瓶/桶產物模板。混合產物 lore 標註內容物
 * （含數量），內容物數據寫入產物 PDC（juice_contents / juice_bucket）持久保存，供後續功能
 * 讀取。</p>
 */
public final class JuicerRecipe {

    /** 產物內容物數據鍵（瓶/桶裝產物均寫入，後續功能可讀取）。 */
    public static final NamespacedKey KEY_ITEM_CONTENTS = new NamespacedKey(WT.plugin, "juice_contents");
    /** 桶裝產物標記鍵（存在即禁止倒出）。 */
    public static final NamespacedKey KEY_ITEM_BUCKET = new NamespacedKey(WT.plugin, "juice_bucket");
    /** 產物糖分鍵（瓶裝=單份糖分，桶裝=整批總糖分；供後續玩法讀取）。 */
    public static final NamespacedKey KEY_ITEM_SUGAR = new NamespacedKey(WT.plugin, "juice_sugar");
    /** 產物榨汁師鍵（逗號分隔玩家名；酒窖投入時保留榨汁者數據）。 */
    public static final NamespacedKey KEY_ITEM_PLAYERS = new NamespacedKey(WT.plugin, "juice_players");
    /** 陳釀果酒標記鍵（存在即禁止再次投入酒窖）。 */
    public static final NamespacedKey KEY_ITEM_WINE = new NamespacedKey(WT.plugin, "juice_wine");
    /** 陳釀果酒酒精度鍵（DOUBLE，每瓶一致）。 */
    public static final NamespacedKey KEY_ITEM_ALCOHOL = new NamespacedKey(WT.plugin, "juice_alcohol");

    /** 酒麴加成表（Slimefun id → 最終酒精度加成；不在表內 = 非酒麴）。 */
    private static final Map<String, Integer> YEAST_BONUS = Map.of(
            "YEAST_1", 0, "WT_JIUQU", 3, "YEAST_2", 6, "YEAST_3", 15, "YEAST_4", 30);

    /** 材料糖分表（ref → 糖分，sugar.yml 載入；未列出按 0）。 */
    private static final Map<String, Integer> SUGAR = new HashMap<>();

    // ===== 全局設置（juicer.yml 頭部，JuicerLoader 載入）=====
    /** 是否在踩踏產物 lore 末尾追加榨汁師（參與踩踏玩家名）。 */
    public static boolean playerNames = true;
    /** 榨汁信息 lore 行格式（支持 & 顏色代碼；佔位符 %playername% 玩家名、%time% 榨好時間）。 */
    public static String playerNamesFormat =
            "&8[&6榨汁盆&8] &7榨汁師: &f%playername% &7(&b%time%&7)";
    /** 是否啓用鐵砧損耗（false = 鐵砧砸落永不損壞）。 */
    public static boolean anvilDamage = false;
    /** 鐵砧損耗概率（1~10，實際概率 = 數值/10，每次砸落判定一次）。 */
    public static int anvilDamageChance = 3;
    /** 糖分→酒精度轉化比率（酒窖釀造：最終酒精度 = 總糖分 × 比率 + 酒麴加成）。 */
    public static double sugarAlcoholRatio = 0.5;
    /** 陳化每遊戲日酒精度增長百分比下限（實際取 min~max 隨機）。 */
    public static double agingGrowthMin = 0.05;
    /** 陳化每遊戲日酒精度增長百分比上限。 */
    public static double agingGrowthMax = 0.15;
    /** 陳釀果酒 lore 信息行格式（%players% 榨汁玩家、%contents% 組成果汁、%alcohol% 酒精度）。 */
    public static String wineLoreFormat =
            "&8[&6酒窖&8] &7榨汁師: &f%players% &7| &b%contents% &7| &c酒精度: &e%alcohol%°";
    /** 成品酒是否顯示組成物品（%contents% 佔位符渲染的內容）。 */
    public static boolean wineShowContents = true;
    /** 組成物品顯示方向：true = 豎向（每個材料一行），false = 橫向（頓號分隔一行）。 */
    public static boolean wineContentsVertical = false;

    private static final java.text.SimpleDateFormat TIME_FORMAT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");

    private static final Map<String, JuicerRecipe> RECIPES = new LinkedHashMap<>();

    /** 可混合配置（juicer.yml 頭部 mix 段）。 */
    public static final class MixCfg {
        /** 可混合物品 ref 集合（mix.recipes 材料列表，按配置順序）。 */
        public final Set<String> mixableRefs = new LinkedHashSet<>();
        /** 混合榨汁所需踩踏進度（player 型）。 */
        public int progress = 10;
        /** 瓶裝混合產物模板。 */
        public ItemStack bottleTemplate;
        /** 桶裝混合產物模板（禁止倒出）。 */
        public ItemStack bucketTemplate;
    }

    /** 混合配置；juicer.yml 未定義 mix 段時為 null（無混合玩法）。 */
    public static MixCfg mix;

    /** 比例匹配結果：內容物為某配方投入多集的 multiplier 倍。 */
    public static final class MatchResult {
        public final JuicerRecipe recipe;
        public final int multiplier;

        MatchResult(JuicerRecipe recipe, int multiplier) {
            this.recipe = recipe;
            this.multiplier = multiplier;
        }
    }

    public final String key;
    /** 投入物多集：ref(mc:XXX/sf:XXX) → 數量。 */
    public final Map<String, Integer> ingredientRefs;
    /** 投入物總數量（容量校驗與匹配預篩用）。 */
    public final int totalInput;
    /** 投入物展示堆（第一個投入物，榨汁中展示實體用）。 */
    public final ItemStack ingredientDisplay;
    /** true=允許玩家踩踏榨汁（每次跳躍 +1）。混合榨汁固定為玩家型。 */
    public final boolean playerType;
    /** true=允許鐵砧砸擊榨汁（每次砸落 +4）。 */
    public final boolean anvilType;
    /** 總進度（player 按次計 1，anvil 按次計 4）。 */
    public final int progress;
    /** 榨好後的接取份數（= 榨汁盆含水等級，1~3；瓶子每次接 1 份，桶滿時一次接完）。 */
    public final int yield;
    /** 每份產物模板（含數量）。 */
    public final ItemStack result;
    /** 桶裝形態模板（juicer.yml 可選 bucket 段；缺省由 result 自動生成「桶裝<名>」）。 */
    public ItemStack bucketForm;

    public JuicerRecipe(String key, Map<String, Integer> ingredientRefs, ItemStack ingredientDisplay,
                        boolean playerType, boolean anvilType, int progress, int yield, ItemStack result) {
        this.key = key;
        this.ingredientRefs = ingredientRefs;
        this.totalInput = sum(ingredientRefs);
        this.ingredientDisplay = ingredientDisplay;
        this.playerType = playerType;
        this.anvilType = anvilType;
        this.progress = progress;
        this.yield = yield;
        this.result = result;
    }

    private static int sum(Map<String, Integer> refs) {
        int t = 0;
        for (int n : refs.values()) t += n;
        return t;
    }

    public static void register(JuicerRecipe r) {
        RECIPES.put(r.key, r);
    }

    public static JuicerRecipe byKey(String key) {
        return key == null ? null : RECIPES.get(key);
    }

    public static int size() {
        return RECIPES.size();
    }

    /** 全部配方（註冊順序，供配方展示菜單翻頁）。 */
    public static java.util.List<JuicerRecipe> all() {
        return new ArrayList<>(RECIPES.values());
    }

    /**
     * 比例匹配：內容物多集為某配方投入多集的整數倍（k≥1，k=1 即完全一致）。
     * 多個候選取倍率最小者（最特異配方優先）。開始榨汁時據此鎖定配方，
     * 接取份數 = k × yield。
     */
    public static MatchResult matchProportional(Map<String, Integer> contents, int total) {
        MatchResult best = null;
        for (JuicerRecipe r : RECIPES.values()) {
            if (r.totalInput == 0 || total % r.totalInput != 0) continue;
            if (r.ingredientRefs.size() != contents.size()) continue;
            int k = total / r.totalInput;
            boolean ok = true;
            for (Map.Entry<String, Integer> e : contents.entrySet()) {
                if (e.getValue() != r.ingredientRefs.getOrDefault(e.getKey(), 0) * k) {
                    ok = false;
                    break;
                }
            }
            if (ok && (best == null || k < best.multiplier)) best = new MatchResult(r, k);
        }
        return best;
    }

    /** 子集匹配：內容物是否恰為某配方投入多集的子集（多材料配方逐步投入過程中的未完成態）。 */
    public static boolean isSubset(Map<String, Integer> contents, int total) {
        for (JuicerRecipe r : RECIPES.values()) {
            if (r.totalInput < total || r.ingredientRefs.size() < contents.size()) continue;
            if (subsetOf(contents, r.ingredientRefs)) return true;
        }
        return false;
    }

    private static boolean subsetOf(Map<String, Integer> sub, Map<String, Integer> sup) {
        for (Map.Entry<String, Integer> e : sub.entrySet()) {
            if (sup.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** 設置材料糖分（SugarLoader 載入）。 */
    public static void setSugar(String ref, int sugar) {
        if (ref != null && sugar > 0) SUGAR.put(ref, sugar);
    }

    /** 材料糖分（未列出按 0）。 */
    public static int sugarOf(String ref) {
        return SUGAR.getOrDefault(ref, 0);
    }

    /** 內容物多集的總糖分 = Σ 材料糖分 × 數量。 */
    public static int totalSugar(Map<String, Integer> contents) {
        int total = 0;
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            total += sugarOf(e.getKey()) * e.getValue();
        }
        return total;
    }

    /** 投入物是否屬於可混合列表。 */
    public static boolean isMixable(ItemStack item) {
        if (mix == null || item == null || item.getType().isAir()) return false;
        return mix.mixableRefs.contains(refOf(item));
    }

    /** 內容物是否全部可混合。 */
    public static boolean allMixable(Map<String, Integer> contents) {
        if (mix == null) return false;
        for (String ref : contents.keySet()) {
            if (!mix.mixableRefs.contains(ref)) return false;
        }
        return true;
    }

    /** 酒麴加成（非酒麴返回 -1）。 */
    public static int yeastBonus(String yeastId) {
        return yeastId == null ? -1 : YEAST_BONUS.getOrDefault(yeastId, -1);
    }

    /** 內容物多集是否合法（比例匹配 / 可混合 / 某配方子集，任一即可進入盆中）。 */
    public static boolean validContents(Map<String, Integer> contents, int total) {
        return matchProportional(contents, total) != null || allMixable(contents) || isSubset(contents, total);
    }

    /**
     * 構建每份產物：模板克隆；玩家踩踏配方在 player-names 開啓時於 lore 末尾按
     * player-names-format 追加榨汁信息行。
     *
     * <p>內容物 refs 必須寫入 PDC（{@link #KEY_ITEM_CONTENTS}，單份 = 配方投入多集）——
     * 酒窖 {@code CellarMenu} 靠它識別果汁並換算液位/糖分，缺失則該瓶果汁投入後不被吸收。
     * 與 {@link #buildBucketResult} 保持一致（兩者均為配方匹配的產物，桶裝取的是整批）。</p>
     */
    public ItemStack buildResult(Set<String> players, long completedAt, int sugar) {
        ItemStack out = result.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            appendJuiceInfo(meta, players, completedAt);
            meta.getPersistentDataContainer().set(KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    joinContents(ingredientRefs));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            meta.getPersistentDataContainer().set(KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            out.setItemMeta(meta);
        }
        return out;
    }

    /**
     * 構建桶裝形態產物（滿盆用桶一次接取全部）：統一水桶材質，名稱為「桶裝<產物名>」，
     * lore 與瓶裝一致（含榨汁信息行），內容物 refs 寫入 PDC 並帶 juice_bucket 標記
     * （禁止倒出）。juicer.yml 配方可用可選 bucket 段完全自定義外觀。
     */
    public ItemStack buildBucketResult(Set<String> players, long completedAt, int sugar) {
        boolean custom = bucketForm != null;
        ItemStack out = (custom ? bucketForm : result).clone();
        String name = null;
        if (!custom) {
            // 先取原名（含顏色碼），再統一切換為水桶材質
            ItemMeta origin = out.getItemMeta();
            if (origin != null) name = origin.getDisplayName();
            out.setType(Material.WATER_BUCKET);
        }
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            if (!custom) {
                meta.setDisplayName(bucketName(name));
            }
            appendJuiceInfo(meta, players, completedAt);
            meta.getPersistentDataContainer().set(KEY_ITEM_BUCKET, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    joinContents(ingredientRefs));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            meta.getPersistentDataContainer().set(KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 榨汁信息 lore 行（player-names 開啓時）：按格式渲染 %playername% / %time% 佔位符。 */
    private static void appendJuiceInfo(ItemMeta meta, Set<String> players, long completedAt) {
        if (!playerNames || players == null || players.isEmpty()) return;
        String fmt = playerNamesFormat == null || playerNamesFormat.isEmpty()
                ? "&8[&6榨汁盆&8] &7榨汁師: &f%playername%" : playerNamesFormat;
        StringBuilder names = new StringBuilder();
        for (String n : players) {
            if (names.length() > 0) names.append('、');
            names.append(n);
        }
        String time = completedAt > 0 ? TIME_FORMAT.format(new java.util.Date(completedAt)) : "--";
        String line = fmt.replace("%playername%", names.toString()).replace("%time%", time);
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add(Colors.c(line));
        meta.setLore(lore);
    }

    /** 在顯示名的顏色碼之後插入「桶裝」前綴（§6§l蘋果汁 → §6§l桶裝蘋果汁）。 */
    private static String bucketName(String legacyName) {
        if (legacyName == null) return "桶裝";
        int i = 0;
        while (i + 1 < legacyName.length() && legacyName.charAt(i) == '§') i += 2;
        return legacyName.substring(0, i) + "桶裝" + legacyName.substring(i);
    }

    /**
     * 構建混合產物（瓶裝 = 1 份；桶裝 = 整批，桶內每單位組成相同）。內容物按<b>每單位
     * 組成</b>記錄：批次內容物 ÷ 份數（支持分數，如 2/3），瓶/桶倒入酒窖時按單位數還原
     * 整批材料，不會憑空增多/減少；lore 內容行整數顯示 ×N、分數顯示百分比；內容物寫入
     * 產物 PDC（juice_contents），桶裝額外帶 juice_bucket 標記（交互時禁止倒出）。
     */
    public static ItemStack buildMixProduct(boolean bucket, Map<String, Integer> contents,
                                            Set<String> players, long completedAt, int sugar, int doses) {
        ItemStack template = bucket ? mix.bucketTemplate : mix.bottleTemplate;
        ItemStack out = template.clone();
        // 每單位組成 = 批次內容物 ÷ 份數
        int den = Math.max(1, doses);
        Map<String, Double> perUnit = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            perUnit.put(e.getKey(), (double) e.getValue() / den);
        }
        // lore 展示：桶裝 = 整批數量（×2）；瓶裝 = 每份組成（分數按百分比）
        Map<String, Double> display = new LinkedHashMap<>();
        if (bucket) {
            for (Map.Entry<String, Integer> e : contents.entrySet()) {
                display.put(e.getKey(), (double) e.getValue());
            }
        } else {
            display.putAll(perUnit);
        }
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            appendJuiceInfo(meta, players, completedAt);
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            meta.getPersistentDataContainer().set(KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            TextComponent.Builder line = Component.text().append(Component.text("內容: ", NamedTextColor.GRAY));
            boolean first = true;
            for (Map.Entry<String, Double> e : display.entrySet()) {
                if (!first) line.append(Component.text("、", NamedTextColor.GRAY));
                first = false;
                line.append(nameComponent(e.getKey()));
                line.append(Component.text(quantityText(e.getValue()), NamedTextColor.GRAY));
            }
            lore.add(line.build());
            meta.lore(lore);
            meta.getPersistentDataContainer().set(KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    joinContentsFractional(perUnit));
            if (bucket) meta.getPersistentDataContainer().set(KEY_ITEM_BUCKET, PersistentDataType.BYTE, (byte) 1);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 內容物多集序列化（ref:數量 逗號分隔；盆狀態持久化與產物 PDC 共用此格式）。 */
    public static String joinContents(Map<String, Integer> contents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** 內容物序列化（支持分數）：整數寫 n，非整數寫約分後的 a/b（如 2/3）。 */
    public static String joinContentsFractional(Map<String, Double> contents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : contents.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(formatFraction(e.getValue()));
        }
        return sb.toString();
    }

    /** 反序列化內容物多集（兼容無數量的舊格式，按 1 計）。 */
    public static Map<String, Integer> parseContents(String data) {
        Map<String, Integer> contents = new HashMap<>();
        if (data == null || data.isEmpty()) return contents;
        for (String entry : data.split(",")) {
            if (entry.isEmpty()) continue;
            int idx = entry.lastIndexOf(':');
            if (idx <= 0) {
                contents.merge(entry, 1, Integer::sum);
                continue;
            }
            String ref = entry.substring(0, idx);
            int count;
            try {
                count = Integer.parseInt(entry.substring(idx + 1));
            } catch (NumberFormatException ex) {
                count = 1;
            }
            if (count > 0) contents.merge(ref, count, Integer::sum);
        }
        return contents;
    }

    /** 反序列化內容物（支持整數 n 與分數 a/b，用於酒窖吸收與狀態解析）。 */
    public static Map<String, Double> parseContentsFractional(String data) {
        Map<String, Double> contents = new LinkedHashMap<>();
        if (data == null || data.isEmpty()) return contents;
        for (String entry : data.split(",")) {
            if (entry.isEmpty()) continue;
            int idx = entry.lastIndexOf(':');
            if (idx <= 0) {
                contents.merge(entry, 1.0, Double::sum);
                continue;
            }
            String ref = entry.substring(0, idx);
            String v = entry.substring(idx + 1);
            try {
                double val;
                int slash = v.indexOf('/');
                if (slash > 0) {
                    val = Double.parseDouble(v.substring(0, slash))
                            / Double.parseDouble(v.substring(slash + 1));
                } else {
                    val = Double.parseDouble(v);
                }
                if (val > 0) contents.merge(ref, val, Double::sum);
            } catch (NumberFormatException ignored) {
                contents.merge(ref, 1.0, Double::sum);
            }
        }
        return contents;
    }

    /** 數量格式化（PDC/液位）：整數 → "2"，非整數 → 約分分數 "2/3"。 */
    public static String formatFraction(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        for (int den = 2; den <= 1000; den++) {
            double num = v * den;
            if (Math.abs(num - Math.rint(num)) < 1e-6) {
                long n = Math.round(num);
                long g = gcd(Math.abs(n), den);
                return (n / g) + "/" + (den / g);
            }
        }
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 數量格式化（lore 展示）：整數 → "×2"，非整數 → 百分比 "×66.7%"（末位 0 捨去）。 */
    public static String quantityText(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return "×" + (long) Math.rint(v);
        double pct = v * 100;
        if (Math.abs(pct - Math.rint(pct)) < 0.05) return "×" + (long) Math.rint(pct) + "%";
        return "×" + String.format(java.util.Locale.ROOT, "%.1f", pct) + "%";
    }

    /**
     * 組成物品的單行文本（橫向模式）或多行文本（豎向模式）。
     * 當 wineShowContents=false 時，內容全部隱藏（返回 "•••" 之類佔位符）。
     * 返回的每一行都是包含前綴（如 "  • &b材料&7×1"）的完整字符串，無 Colors.c 翻譯。
     */
    public static List<String> contentsLegacyList(Map<String, Double> contents) {
        List<String> out = new ArrayList<>();
        if (!wineShowContents) {
            out.add("§8[組成已隱藏]");
            return out;
        }
        if (contents == null || contents.isEmpty()) {
            out.add("§7無");
            return out;
        }
        if (!wineContentsVertical) {
            out.add(contentsLegacy(contents));
            return out;
        }
        for (Map.Entry<String, Double> en : contents.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append("  §8• ");
            ItemStack it = refToItem(en.getKey());
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                sb.append(it.getItemMeta().getDisplayName());
            } else {
                sb.append(LegacyComponentSerializer.legacySection()
                        .serialize(nameComponent(en.getKey())));
            }
            sb.append("§7").append(quantityText(en.getValue()));
            out.add(sb.toString());
        }
        return out;
    }

    /** 組成果汁名（橫向頓號分隔；粘液顯示名保留自身顏色碼；原版翻譯組件再轉舊式文本）。 */
    public static String contentsLegacy(Map<String, Double> contents) {
        if (contents == null || contents.isEmpty()) return "無";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> en : contents.entrySet()) {
            if (sb.length() > 0) sb.append("、");
            ItemStack it = refToItem(en.getKey());
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                sb.append(it.getItemMeta().getDisplayName());
            } else {
                sb.append(LegacyComponentSerializer.legacySection()
                        .serialize(nameComponent(en.getKey())));
            }
            sb.append(quantityText(en.getValue()));
        }
        return sb.toString();
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** 內容物 ref（mc:APPLE / sf:ID）的本地化名稱組件：原版用翻譯鍵，粘液物品用其顯示名。 */
    public static Component nameComponent(String ref) {
        if (ref.startsWith("sf:")) {
            SlimefunItem sf = SlimefunItem.getById(ref.substring(3));
            if (sf != null) return sf.getItem().displayName();
            return Component.text(ref.substring(3), NamedTextColor.WHITE);
        }
        Material m = Material.matchMaterial(ref.substring(3));
        if (m != null) return Component.translatable(m.translationKey());
        return Component.text(ref, NamedTextColor.WHITE);
    }

    /** 物品堆的內容物 ref。 */
    public static String refOf(ItemStack item) {
        SlimefunItem sf = SlimefunItem.getByItem(item);
        return sf != null ? "sf:" + sf.getId() : "mc:" + item.getType().name();
    }

    /** 內容物 ref 對應的展示物品堆（榨汁中展示實體用）。 */
    public static ItemStack refToItem(String ref) {
        if (ref.startsWith("sf:")) {
            SlimefunItem sf = SlimefunItem.getById(ref.substring(3));
            if (sf != null) return sf.getItem().clone();
            return new ItemStack(Material.BARRIER);
        }
        Material m = Material.matchMaterial(ref.substring(3));
        return new ItemStack(m != null ? m : Material.BARRIER);
    }
}
