package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * 酒窖狀態機（按管理器方塊位置存儲）。
 *
 * <p>階段：IDLE（投料）→ RUNNING（釀造/陳化計時，雙機耗電）→ READY（出酒）；
 * RUNNING 中釀造模式手動關閉 → CONTAMINATED（液體報廢，黃色玻璃板清空後回 IDLE），
 * 陳化模式手動關閉 → READY（停止計時，可出酒）。</p>
 *
 * <p>液位：容量 24 單位（瓶裝 1/單位、桶裝 3/單位）。液體按「同組成 + 同單份糖分」合併為
 * 批次（累加單位數、並集榨汁師）；酒精度為整池強度值（每瓶一致）。狀態經 BlockStorage
 * 持久化，重啓後由 {@link #get} 惰性重建。</p>
 */
public final class WineCellarState {

    public static final int CAPACITY = 24;

    public enum Phase { IDLE, RUNNING, READY, CONTAMINATED }

    public enum Mode { BREW, AGE }

    /** 液體批次：同組成同糖分的單位合併（組成支持分數，如混合果汁每單位 2/3 蘋果）。 */
    public static final class Liquid {
        private final Map<String, Double> contents; // ref → 每單位數量
        private final int sugarPerUnit;
        private int units;
        private final Set<String> players;

        Liquid(Map<String, Double> contents, int sugarPerUnit, int units, Set<String> players) {
            this.contents = contents;
            this.sugarPerUnit = sugarPerUnit;
            this.units = units;
            this.players = players;
        }

        public Map<String, Double> contents() { return contents; }
        public int sugarPerUnit() { return sugarPerUnit; }
        public int units() { return units; }
        public Set<String> players() { return players; }

        String key() {
            return JuicerRecipe.joinContentsFractional(contents) + "|" + sugarPerUnit;
        }
    }

    private Phase phase = Phase.IDLE;
    private Mode mode = Mode.BREW;
    private long elapsedMs;
    private long durationMs;
    private double alcohol;
    private long nextGrowthAt;
    private String yeast;
    private String placerName;
    private UUID placerId;
    private final List<Liquid> liquids = new ArrayList<>();
    private int juiceUnits;  // 果汁單位數（不含清水）
    private int waterUnits;  // 清水單位數（稀釋用）
    private String cellarRecipe; // 鎖定的酒窖配方 key（null = 通用酒精流程）
    private int cellarMultiplier = 1;
    private boolean autoAge; // 自動陳化：釀造完成後直接進入陳化
    private String ownerName; // 放置酒窖的玩家（命名權限）
    private UUID ownerId;
    private String cellarName; // 玩家設定的酒窖名（顏色代碼已翻譯，null = 未命名）
    private long lastSaveMs; // 上次落盤時間（運行計時 5 分鐘節流用，不持久化）

    // ===== 訪問器 =====
    public Phase phase() { return phase; }
    public void phase(Phase phase) { this.phase = phase; }
    public Mode mode() { return mode; }
    public void toggleMode() { mode = mode == Mode.BREW ? Mode.AGE : Mode.BREW; }
    public long elapsedMs() { return elapsedMs; }
    public void elapsedMs(long v) { elapsedMs = v; }
    public long durationMs() { return durationMs; }
    public double alcohol() { return alcohol; }
    public void alcohol(double v) { alcohol = v; }
    public long nextGrowthAt() { return nextGrowthAt; }
    public void nextGrowthAt(long v) { nextGrowthAt = v; }
    public String yeast() { return yeast; }
    public void yeast(String yeast) { this.yeast = yeast; }
    public UUID placerId() { return placerId; }
    public int units() { return juiceUnits + waterUnits; }
    public int juiceUnits() { return juiceUnits; }
    public int waterUnits() { return waterUnits; }
    public String cellarRecipe() { return cellarRecipe; }
    public int cellarMultiplier() { return cellarMultiplier; }
    public boolean autoAge() { return autoAge; }
    public void autoAge(boolean v) { autoAge = v; }
    public long lastSaveMs() { return lastSaveMs; }
    public String ownerName() { return ownerName; }
    public UUID ownerId() { return ownerId; }
    public String cellarName() { return cellarName; }
    public void cellarName(String v) { cellarName = v; }

    /** 記錄酒窖歸屬（放置者）；已有歸屬時不覆蓋。 */
    public void setOwner(Player p) {
        if (ownerId != null || p == null) return;
        ownerId = p.getUniqueId();
        ownerName = p.getName();
    }

    /** 該玩家是否為酒窖歸屬者（未記錄歸屬時返回 false，由調用方決定回退策略）。 */
    public boolean isOwner(Player p) {
        return p != null && ownerId != null && ownerId.equals(p.getUniqueId());
    }

    /** 鎖定酒窖配方（釀造開始時比例匹配）。 */
    public void setCellarRecipe(String key, int multiplier) {
        this.cellarRecipe = key;
        this.cellarMultiplier = multiplier;
    }

    /** 加入清水（稀釋：不增加糖分，攤薄單位糖分；已含酒精時同步攤薄酒精度）。 */
    public void addWater(int n) {
        int before = units();
        waterUnits += n;
        int after = units();
        if (alcohol > 0 && before > 0 && after > before) {
            alcohol = alcohol * before / after;
        }
    }

    public List<Liquid> liquids() { return liquids; }
    public boolean hasAlcohol() { return alcohol > 0; }
    public boolean canAccept(int add) { return units() + add <= CAPACITY; }

    /** 遊戲日時長（陳化增長里程碑）。 */
    public static final long GAME_DAY_MS = 24 * 60_000L;

    /** 啓動運行（釀造傳目標時長，陳化傳 0），重置計時。 */
    public void startRun(Player p, long duration) {
        startedBy(p);
        phase = Phase.RUNNING;
        elapsedMs = 0;
        durationMs = duration;
        nextGrowthAt = GAME_DAY_MS;
    }

    /** 記錄啓動者。 */
    public void startedBy(Player p) {
        this.placerName = p.getName();
        this.placerId = p.getUniqueId();
    }

    /** 投入液體（同組成同糖分合並，榨汁師並集；組成支持分數）。 */
    public void addLiquid(Map<String, Double> contents, int sugarPerUnit, int add, String player) {
        String key = JuicerRecipe.joinContentsFractional(contents) + "|" + sugarPerUnit;
        for (Liquid lq : liquids) {
            if (lq.key().equals(key)) {
                lq.units += add;
                if (player != null && !player.isEmpty()) lq.players.add(player);
                juiceUnits += add;
                return;
            }
        }
        Set<String> players = new LinkedHashSet<>();
        if (player != null && !player.isEmpty()) players.add(player);
        liquids.add(new Liquid(new LinkedHashMap<>(contents), sugarPerUnit, add, players));        juiceUnits += add;
    }

    /** 出酒消耗 1 單位（優先消耗果汁單位，其次清水）。 */
    public void drainUnit() {
        for (Liquid lq : liquids) {
            if (lq.units > 0) {
                lq.units--;
                juiceUnits--;
                return;
            }
        }
        if (waterUnits > 0) waterUnits--;
    }

    /** 全部液體合併後的組成（ref → 每單位數量之和；支持分數）。 */
    public Map<String, Double> contentsOfAll() {
        Map<String, Double> all = new LinkedHashMap<>();
        for (Liquid lq : liquids) {
            for (Map.Entry<String, Double> e : lq.contents().entrySet()) {
                all.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
        return all;
    }

    /** 全部榨汁師並集。 */
    public Set<String> allPlayers() {
        Set<String> all = new LinkedHashSet<>();
        for (Liquid lq : liquids) all.addAll(lq.players);
        return all;
    }

    /** 整池總糖分 = Σ 單份糖分 × 單位數。 */
    public int totalSugar() {
        int total = 0;
        for (Liquid lq : liquids) total += lq.sugarPerUnit * lq.units;
        return total;
    }

    /** 清空液體與酒麴（污染恢復/出酒完畢）。 */
    public void clear() {
        liquids.clear();
        juiceUnits = 0;
        waterUnits = 0;
        yeast = null;
        alcohol = 0;
        elapsedMs = 0;
        durationMs = 0;
        nextGrowthAt = 0;
        phase = Phase.IDLE;
        placerName = null;
        placerId = null;
    }

    /** 徹底重置（多方塊結構被破壞）：連歸屬者與酒窖名一併清除，原位重放不殘留。 */
    public void clearIdentity() {
        clear();
        ownerName = null;
        ownerId = null;
        cellarName = null;
    }

    /** 釀造中報廢。 */
    public void contaminate() {
        phase = Phase.CONTAMINATED;
        liquids.clear();
        juiceUnits = 0;
        waterUnits = 0;
        yeast = null;
        alcohol = 0;
    }

    // ===== 註冊表與持久化 =====
    // 世界 → (打包座標 → 狀態)：熱路徑（管理器逐 tick 計時）零分配查詢，
    // 規避 Location 哈希（CraftWorld#hashCode 走世界 UUID）的開銷
    private static final Map<World, Map<Long, WineCellarState>> INDEX = new HashMap<>();

    /** 方塊座標打包為 long（x/z 26 位 + y 12 位）。 */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static WineCellarState get(Block b) {
        World w = b.getWorld();
        long k = key(b.getX(), b.getY(), b.getZ());
        Map<Long, WineCellarState> m = INDEX.get(w);
        if (m != null) {
            WineCellarState st = m.get(k);
            if (st != null) return st;
        }
        WineCellarState st = new WineCellarState();
        deserialize(st, BlockStorage.getLocationInfo(b.getLocation(), "wt-cellar-data"));
        INDEX.computeIfAbsent(w, x -> new HashMap<>()).put(k, st);
        return st;
    }

    public static void remove(Block b) {
        Map<Long, WineCellarState> m = INDEX.get(b.getWorld());
        if (m != null) m.remove(key(b.getX(), b.getY(), b.getZ()));
    }

    /**
     * 持久化當前狀態（每次變更後調用）。
     * V2 格式：版本標記 + 頭部 16 字段（到 cellarName）+ 各液體批次 4 字段（units|sugar|players|contents）。
     */
    public void save(Block b) {
        lastSaveMs = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("V2|")
          .append(phase.name()).append('|').append(mode.name()).append('|').append(elapsedMs)
          .append('|').append(durationMs).append('|').append(alcohol).append('|').append(nextGrowthAt)
          .append('|').append(yeast == null ? "" : yeast)
          .append('|').append(placerName == null ? "" : placerName)
          .append('|').append(placerId == null ? "" : placerId)
          .append('|').append(waterUnits)
          .append('|').append(cellarRecipe == null ? "" : cellarRecipe)
          .append('|').append(cellarMultiplier)
          .append('|').append(autoAge)
          .append('|').append(ownerName == null ? "" : ownerName)
          .append('|').append(ownerId == null ? "" : ownerId)
          .append('|').append(cellarName == null ? "" : cellarName);
        for (Liquid lq : liquids) {
            // 液體批次與頭部一樣用 '|' 分隔（整個串按 '|' 統一切分後按 4 字段一組解析；
            // 不能用 ';'——';' 不參與切分，首個批次的單位數會粘連到酒窖名字上）
            sb.append('|').append(lq.units).append('|').append(lq.sugarPerUnit).append('|')
              .append(String.join("~", lq.players)).append('|')
              .append(JuicerRecipe.joinContentsFractional(lq.contents));
        }
        BlockStorage.addBlockInfo(b, "wt-cellar-data", sb.toString());
    }

    private static void deserialize(WineCellarState st, String data) {
        if (data == null || data.isEmpty()) return;
        String[] parts = data.split("\\|", -1);
        try {
            if (parts.length >= 17 && parts[0].equals("V2")) {
                st.phase = Phase.valueOf(parts[1]);
                st.mode = Mode.valueOf(parts[2]);
                st.elapsedMs = Long.parseLong(parts[3]);
                st.durationMs = Long.parseLong(parts[4]);
                st.alcohol = Double.parseDouble(parts[5]);
                st.nextGrowthAt = Long.parseLong(parts[6]);
                st.yeast = parts[7].isEmpty() ? null : parts[7];
                st.placerName = parts[8].isEmpty() ? null : parts[8];
                st.placerId = parts[9].isEmpty() ? null : UUID.fromString(parts[9]);
                st.waterUnits = Integer.parseInt(parts[10]);
                st.cellarRecipe = parts[11].isEmpty() ? null : parts[11];
                st.cellarMultiplier = Integer.parseInt(parts[12]);
                st.autoAge = Boolean.parseBoolean(parts[13]);
                st.ownerName = parts[14].isEmpty() ? null : parts[14];
                st.ownerId = parts[15].isEmpty() ? null : UUID.fromString(parts[15]);
                // 舊版用 ';' 分隔液體段——';' 不參與切分，首個批次的單位數會粘連在名字後
                // （如「名字;2」）。檢測到粘連時丟棄液體段（舊數據無法無損還原），保住命名信息
                String nameField = parts[16];
                boolean glued = nameField.indexOf(';') >= 0;
                if (glued) nameField = nameField.substring(0, nameField.indexOf(';'));
                st.cellarName = nameField.isEmpty() ? null : nameField;
                if (parts.length > 17 && !glued) parseLiquids(st, parts, 17);
            } else {
                parseLegacy(st, parts);
            }
        } catch (Exception ex) {
            WT.log("酒窖狀態解析失敗，已重置: " + ex);
            st.phase = Phase.IDLE;
            st.mode = Mode.BREW;
            st.liquids.clear();
            st.juiceUnits = 0;
            st.waterUnits = 0;
            st.alcohol = 0;
            st.yeast = null;
            st.elapsedMs = 0;
            st.durationMs = 0;
            st.nextGrowthAt = 0;
            st.cellarRecipe = null;
            st.cellarMultiplier = 1;
        }
    }

    /** 從 from 下標起按 4 字段一批解析液體批次，並重算果汁單位數。 */
    private static void parseLiquids(WineCellarState st, String[] parts, int from) {
        int i = from;
        while (i + 4 <= parts.length) {
            Map<String, Double> contents = JuicerRecipe.parseContentsFractional(parts[i + 3]);
            Set<String> players = new LinkedHashSet<>();
            for (String n : parts[i + 2].split("~")) {
                if (!n.isEmpty()) players.add(n);
            }
            st.liquids.add(new Liquid(contents, Integer.parseInt(parts[i + 1]),
                    Integer.parseInt(parts[i]), players));
            i += 4;
        }
        st.juiceUnits = 0;
        // juiceUnits 由液體批次重新累加；waterUnits 不能清零——新格式已在前面解析出
        for (Liquid lq : st.liquids) st.juiceUnits += lq.units;
    }

    /** 舊格式（無版本標記）兼容解析：9 字段頭部，12 字段起含水單位/配方鎖定，13 字段起含自動陳化。 */
    private static void parseLegacy(WineCellarState st, String[] parts) {
        if (parts.length < 9) return;
        st.phase = Phase.valueOf(parts[0]);
        st.mode = Mode.valueOf(parts[1]);
        st.elapsedMs = Long.parseLong(parts[2]);
        st.durationMs = Long.parseLong(parts[3]);
        st.alcohol = Double.parseDouble(parts[4]);
        st.nextGrowthAt = Long.parseLong(parts[5]);
        st.yeast = parts[6].isEmpty() ? null : parts[6];
        st.placerName = parts[7].isEmpty() ? null : parts[7];
        st.placerId = parts[8].isEmpty() ? null : UUID.fromString(parts[8]);
        int i = 9;
        if (parts.length >= 12) {
            st.waterUnits = Integer.parseInt(parts[9]);
            st.cellarRecipe = parts[10].isEmpty() ? null : parts[10];
            st.cellarMultiplier = Integer.parseInt(parts[11]);
            i = 12;
        }
        if (parts.length >= 13) {
            st.autoAge = Boolean.parseBoolean(parts[12]);
            i = 13;
        }
        // 舊版 ';' 粘連缺陷：液體段單位數粘連在最後一個頭部字段上時，丟棄液體段保住頭部
        if (i > 9 && i < parts.length && parts[i - 1].indexOf(';') >= 0) return;
        parseLiquids(st, parts, i);
    }
}
