package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 榨汁盆（machines.yml 中 script: zhapen，材質為榨汁盆）。
 *
 * <p>投入：空盆時玩家往盆裏扔物品（或手持物品右鍵盆投入）。盆內內容物為多集，容量上限 10
 * （不分類別，類似收納袋）。每次投入後重新評估形態——內容物與某配方投入多集完全一致則鎖定
 * 該配方（優先）；全部可混合（mix.recipes 列表配方的投入物）則進入混合榨汁；否則若為某配方
 * 的子集則繼續等待補料；都不滿足的物品拒絕投入（「這個物品不能榨汁！」），盆滿或配方已鎖定
 * 時再投入提示「這個榨汁盆中已經有物品了！」。</p>
 *
 * <p>榨汁：鎖定配方或混合形態的盆需經一次榨汁產出——player 型站盆內跳躍每次 +1（混合固定
 * 玩家型），anvil 型鐵砧砸盆頂每次 +4（鐵砧碎裂）。進度動作欄顯示「當前/總進度」。滿進度
 * 播放經驗音效、提示「已經榨好了！」，掉落「果渣」（數量=投入材料總數），盆轉為榨汁盆含水
 * （水量=剩餘接取份數）。未鎖定的等待補料形態踩踏無效。</p>
 *
 * <p>接取（統一劑量制，無 per-配方容器）：玻璃瓶每次接 1 份、水量 -1（配方產物含榨汁師
 * 名單；混合產物 lore 標註內容物清單）；鐵桶僅滿盆（水量=滿額份數）可一次接完並清空，
 * 未滿提示「這個盆不是滿的」。接完還原空盆。空手蹲下右鍵盆可清空全部內容物（丟棄不返還）。
 * 桶裝混合產物禁止倒出。</p>
 *
 * <p>狀態（內容物多集/進度/接取份數/榨汁師）經 {@link BlockStorage} 持久化，形態在載入時
 * 重新評估；產物內容物寫入 PDC（juice_contents/juice_bucket）。展示實體不落盤，缺失時由
 * tick 補生成。盆防炸。</p>
 */
public class JuicerBasin extends SlimefunItem {

    /** 物品 id（指南入口注入與展示菜單定位用）。 */
    public static final String ITEM_ID = "WT_ZHAPEN";

    private static final String KEY_RECIPE = "wt-juicer-recipe";
    private static final String KEY_PROGRESS = "wt-juicer-progress";
    private static final String KEY_PLAYERS = "wt-juicer-players";
    private static final String KEY_DOSES = "wt-juicer-doses";
    private static final String KEY_TIME = "wt-juicer-time";
    private static final String KEY_SUGAR = "wt-juicer-sugar";
    private static final String KEY_MIX = "wt-juicer-mix";
    /** 掉落物一次性提示標記（防止同一物品反覆刷消息）。 */
    private static final String TAG_WARNED = "wt-juicer-warned";
    /** 盆容量上限（不分類別的物品總數，類似收納袋）。 */
    private static final int MAX_CAPACITY = 10;
    /** 榨汁盆最大含水等級。 */
    private static final int MAX_WATER = 3;
    /** 果渣物品 id（榨汁完成按投入總數掉落）。 */
    private static final String POMACE_ID = "WT_GUOZHA";

    /** 投入評估結果。 */
    private static final int INSERT_OK = 0;
    private static final int INSERT_BUSY = 1;
    private static final int INSERT_INVALID = 2;

    /** 運行期狀態緩存（位置 → 狀態；空盆無條目，事件與 tick 均主線程訪問）。 */
    private static final Map<Location, BasinState> STATES = new HashMap<>();
    /** 位置索引（世界 → (打包座標 → 狀態)）：onMove 熱路徑免分配查詢，與 STATES 同步維護。 */
    private static final Map<World, Map<Long, BasinState>> STATE_INDEX = new HashMap<>();

    /** 方塊座標打包為 long（x/z 26 位 + y 12 位，覆蓋世界邊界與全高度）。 */
    private static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static void indexState(World w, int x, int y, int z, BasinState st) {
        STATE_INDEX.computeIfAbsent(w, k -> new HashMap<>()).put(posKey(x, y, z), st);
    }

    private static void unindexState(World w, int x, int y, int z) {
        Map<Long, BasinState> m = STATE_INDEX.get(w);
        if (m != null) m.remove(posKey(x, y, z));
    }

    /** 熱路徑零分配查詢。 */
    private static BasinState lookup(World w, int x, int y, int z) {
        Map<Long, BasinState> m = STATE_INDEX.get(w);
        return m == null ? null : m.get(posKey(x, y, z));
    }
    /** 已知空盆位置（無內容物負緩存：避免空盆每 tick 讀 BlockStorage）。 */
    private static final Set<Location> EMPTY_KNOWN = new HashSet<>();
    /** 投入掃描活躍標記（位置 → 掃描截止時間；附近有玩家投擲時激活快速掃描）。 */
    private static final Map<Location, Long> SCAN_UNTIL = new HashMap<>();
    /** 已輸出過首次 tick 日誌的位置（按世界分組打包座標，避免每 tick 哈希 Location）。 */
    public JuicerBasin(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe) {
        super(group, item, rt, recipe);
        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }

            @Override
            public void tick(Block b, SlimefunItem sf, Config data) {
                JuicerBasin.this.tick(b);
            }
        });
        addItemHandler(new BlockBreakHandler(true, true) {
            @Override
            public void onPlayerBreak(BlockBreakEvent e, ItemStack tool, List<ItemStack> drops) {
                BasinState st = STATES.remove(e.getBlock().getLocation());
                unindexState(e.getBlock().getWorld(), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
                // 空盆負緩存一併清除，防止原位重放新盆時狀態讀取被跳過
                EMPTY_KNOWN.remove(e.getBlock().getLocation());
                if (st != null) removeDisplays(st);
            }

            @Override
            public boolean isExplosionAllowed(Block b) {
                return false;
            }
        });
    }

    private void tick(Block b) {
        Material type = b.getType();
        if (type != Material.CAULDRON && type != Material.WATER_CAULDRON) {
            // 方塊被非玩家手段替換（無 BlockBreakHandler 路徑）：按破壞清理，防幽靈狀態
            BasinState st = STATES.remove(b.getLocation());
            unindexState(b.getWorld(), b.getX(), b.getY(), b.getZ());
            if (st != null) removeDisplays(st);
            if (BlockStorage.hasBlockInfo(b)) BlockStorage.clearBlockInfo(b);
            return;
        }
        BasinState st = state(b);
        if (st != null) {
            // 展示實體不落盤：缺失/數量不符（重啓/區塊卸載）時按內容物重建
            int expected = st.doses > 0 ? 0 : st.total;
            boolean broken = false;
            for (ItemDisplay d : st.displays) {
                if (!d.isValid()) {
                    broken = true;
                    break;
                }
            }
            if (st.displays.size() != expected || broken) refreshDisplays(b, st);
        }
        // 掉落物掃描：附近有玩家投擲時每 2 tick 快掃；否則每 50 tick 慢掃兜底（遠距離拋入場景）
        Long until = SCAN_UNTIL.get(b.getLocation());
        boolean active = until != null && until >= System.currentTimeMillis();
        if (until != null && !active) SCAN_UNTIL.remove(b.getLocation());
        if (!active && b.getWorld().getGameTime() % 50 != 0) return;
        Collection<Entity> dropped = b.getWorld().getNearbyEntities(
                b.getLocation().add(0.5, 0.45, 0.5), 0.45, 0.45, 0.45, en -> en instanceof org.bukkit.entity.Item);
        for (Entity en : dropped) {
            org.bukkit.entity.Item item = (org.bukkit.entity.Item) en;
            int verdict = canInsert(st, JuicerRecipe.refOf(item.getItemStack()));
            if (verdict == INSERT_BUSY) {
                reject(item, "§c這個榨汁盆中已經有物品了！");
                continue;
            }
            if (verdict == INSERT_INVALID) {
                reject(item, "§c這個物品不能榨汁！");
                continue;
            }
            if (consumeDropped(item)) doInsert(b, st, item.getItemStack());
            st = STATES.get(b.getLocation());
        }
    }

    /**
     * 投入評估：已開始榨汁（鎖定）/已榨好待接取/容量滿 → BUSY；加入後既非配方比例匹配、
     * 非全可混合、也非某配方子集 → INVALID；否則 OK（投入階段不鎖定，開始榨汁時才鎖定）。
     */
    private static int canInsert(BasinState st, String ref) {
        if (st != null) {
            if (st.started || st.doses > 0 || st.total >= MAX_CAPACITY) return INSERT_BUSY;
        }
        Map<String, Integer> temp = st == null ? new LinkedHashMap<>() : new LinkedHashMap<>(st.contents);
        temp.merge(ref, 1, Integer::sum);
        return JuicerRecipe.validContents(temp, st == null ? 1 : st.total + 1)
                ? INSERT_OK : INSERT_INVALID;
    }

    /** 掉落物消耗 1 個（全部消耗則移除實體）。 */
    private static boolean consumeDropped(org.bukkit.entity.Item item) {
        int amt = item.getItemStack().getAmount();
        if (amt <= 1) {
            item.remove();
        } else {
            item.getItemStack().setAmount(amt - 1);
        }
        return true;
    }

    /** 掉落物一次性提示：優先提示投擲者；來源未知（漏斗/容器彈出等）時提示附近玩家。 */
    private static void reject(org.bukkit.entity.Item item, String msg) {
        if (item.getScoreboardTags().contains(TAG_WARNED)) return;
        item.addScoreboardTag(TAG_WARNED);
        Player thrower = item.getThrower() != null ? Bukkit.getPlayer(item.getThrower()) : null;
        if (thrower != null) {
            thrower.sendMessage(msg);
            return;
        }
        for (Player near : item.getWorld().getNearbyEntitiesByType(Player.class, item.getLocation(), 4)) {
            near.sendMessage(msg);
        }
    }

    /** 執行投入：內容物 +1、持久化並重建展示實體。調用前須 canInsert == OK（投入不鎖定配方）。 */
    private static void doInsert(Block b, BasinState st, ItemStack stack) {
        String ref = JuicerRecipe.refOf(stack);
        if (st == null) {
            st = new BasinState();
            STATES.put(b.getLocation(), st);
            indexState(b.getWorld(), b.getX(), b.getY(), b.getZ(), st);
        }
        EMPTY_KNOWN.remove(b.getLocation());
        st.contents.merge(ref, 1, Integer::sum);
        st.total++;
        BlockStorage.addBlockInfo(b, KEY_MIX, JuicerRecipe.joinContents(st.contents));
        // 展示實體不在此重建：tick 的數量校驗（displays.size() != expected）會自動補齊，
        // 整堆連續投入時合併為一次重建（此前每次投 入都全拆全建，是熱點開銷）
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
    }

    /**
     * 開始榨汁（首次踩踏/鐵砧砸落時鎖定形態）：內容物為某配方投入多集的整數倍 → 鎖定該配方
     * （接取份數 = 倍率 × yield）；全部可混合 → 混合榨汁（水量恆 3 份）；否則（等待補料）
     * 返回 false 不開始。
     */
    private static boolean tryStartJuice(BasinState st) {
        if (st.started) return true;
        JuicerRecipe.MatchResult m = JuicerRecipe.matchProportional(st.contents, st.total);
        if (m != null) {
            st.recipe = m.recipe;
            st.multiplier = m.multiplier;
        } else if (JuicerRecipe.allMixable(st.contents)) {
            st.mixFlag = true;
        } else {
            return false;
        }
        st.started = true;
        return true;
    }

    /** 惰性讀取狀態：內存緩存缺失時從 BlockStorage 重建（重啓/首次訪問）。空盆返回 null。 */
    private static BasinState state(Block b) {
        Location l = b.getLocation();
        BasinState st = STATES.get(l);
        if (st != null) return st;
        if (EMPTY_KNOWN.contains(l)) return null; // 已知空盆：跳過存儲讀取
        String mixData = BlockStorage.getLocationInfo(l, KEY_MIX);
        if (mixData == null || mixData.isEmpty()) {
            EMPTY_KNOWN.add(l);
            return null;
        }
        st = new BasinState();
        st.contents.putAll(JuicerRecipe.parseContents(mixData));
        st.total = 0;
        for (int n : st.contents.values()) st.total += n;
        if (st.total == 0) {
            clearStateKeys(b);
            return null;
        }
        st.progress = parseInt(BlockStorage.getLocationInfo(l, KEY_PROGRESS));
        String names = BlockStorage.getLocationInfo(l, KEY_PLAYERS);
        if (names != null && !names.isEmpty()) {
            for (String n : names.split(",")) {
                if (!n.isEmpty()) st.players.add(n);
            }
        }
        st.doses = parseInt(BlockStorage.getLocationInfo(l, KEY_DOSES));
        st.completedAt = parseLong(BlockStorage.getLocationInfo(l, KEY_TIME));
        st.totalSugar = parseInt(BlockStorage.getLocationInfo(l, KEY_SUGAR));
        // 進行中/已榨好的批次恢復鎖定形態（純累積批次保持未鎖定，開始榨汁時再鎖）
        if (st.progress > 0 || st.doses > 0) tryStartJuice(st);
        st.dosesMax = st.recipe != null ? st.recipe.yield * st.multiplier : MAX_WATER;
        STATES.put(l, st);
        indexState(b.getWorld(), b.getX(), b.getY(), b.getZ(), st);
        return st;
    }

    private static void clearStateKeys(Block b) {
        EMPTY_KNOWN.add(b.getLocation());
        BlockStorage.addBlockInfo(b, KEY_RECIPE, null);
        BlockStorage.addBlockInfo(b, KEY_PROGRESS, null);
        BlockStorage.addBlockInfo(b, KEY_PLAYERS, null);
        BlockStorage.addBlockInfo(b, KEY_DOSES, null);
        BlockStorage.addBlockInfo(b, KEY_MIX, null);
        BlockStorage.addBlockInfo(b, KEY_TIME, null);
        BlockStorage.addBlockInfo(b, KEY_SUGAR, null);
    }

    /** 還原空盆：清狀態/展示實體/持久化數據並復原為無水榨汁盆。 */
    private static void resetBasin(Block b, BasinState st) {
        removeDisplays(st);
        unindexState(b.getWorld(), b.getX(), b.getY(), b.getZ());
        STATES.remove(b.getLocation());
        clearStateKeys(b);
        b.setType(Material.CAULDRON);
    }

    /** 盆水量同步：0 = 無水空鍋，>0 = 裝水榨汁盆（上限 3）。 */
    private static void syncLevel(Block b, int level) {
        if (level <= 0) {
            b.setType(Material.CAULDRON);
            return;
        }
        b.setType(Material.WATER_CAULDRON);
        if (b.getBlockData() instanceof Levelled lv) {
            lv.setLevel(Math.min(MAX_WATER, level));
            b.setBlockData(lv);
        }
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String joinPlayers(BasinState st) {
        return String.join(",", st.players);
    }

    /** 當前榨汁所需總進度：鎖定配方用其 progress，混合用 mix.progress。 */
    private static int progressTotal(BasinState st) {
        return st.recipe != null ? st.recipe.progress
                : (JuicerRecipe.mix != null ? JuicerRecipe.mix.progress : 1);
    }

    /** 榨汁進度推進：水花迸濺 + 寫進度，滿時完成。 */
    private static void advance(Block b, BasinState st, int add, Player feedback) {
        // 每次 +1（踩踏）/+4（鐵砧）：盆沿水花迸濺一次
        b.getWorld().spawnParticle(Particle.SPLASH,
                b.getLocation().add(0.5, 0.9, 0.5), 12, 0.4, 0.02, 0.4, 0);
        b.getWorld().spawnParticle(Particle.FALLING_WATER,
                b.getLocation().add(0.5, 0.8, 0.5), 8, 0.45, 0.05, 0.45, 0);
        int total = progressTotal(st);
        st.progress = Math.min(st.progress + add, total);
        BlockStorage.addBlockInfo(b, KEY_PROGRESS, String.valueOf(st.progress));
        if (st.progress >= total) {
            complete(b, st, feedback);
        } else if (feedback != null) {
            feedback.sendActionBar(Component.text(st.progress, NamedTextColor.YELLOW)
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(total, NamedTextColor.AQUA)));
        }
    }

    /** 榨好：寫入接取份數（配方按倍率放大、混合恆 3 份）、掉落果渣（投入總數）、水量同步並提示。 */
    private static void complete(Block b, BasinState st, Player feedback) {
        st.doses = st.recipe != null ? st.recipe.yield * st.multiplier : MAX_WATER;
        st.dosesMax = st.doses;
        st.completedAt = System.currentTimeMillis();
        st.totalSugar = JuicerRecipe.totalSugar(st.contents);
        BlockStorage.addBlockInfo(b, KEY_DOSES, String.valueOf(st.doses));
        BlockStorage.addBlockInfo(b, KEY_TIME, String.valueOf(st.completedAt));
        BlockStorage.addBlockInfo(b, KEY_SUGAR, String.valueOf(st.totalSugar));
        syncLevel(b, st.doses);
        // 原料已成汁：收起內容物展示，以水位表現
        removeDisplays(st);
        // 果渣：按投入材料總數量掉落
        SlimefunItem pomace = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(POMACE_ID);
        if (pomace != null && st.total > 0) {
            ItemStack out = pomace.getItem().clone();
            out.setAmount(Math.min(st.total, out.getMaxStackSize()));
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.6, 0.5), out);
        }
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        if (feedback != null) feedback.sendActionBar(Component.text("已經榨好了！", NamedTextColor.GREEN));
    }

    /** 盆附近最近的玩家（完成/鐵砧進度反饋用），盆周邊 2 格內無人返回 null。 */
    private static Player nearbyPlayer(Block b) {
        Collection<Player> ps = b.getWorld().getNearbyEntitiesByType(Player.class, b.getLocation().add(0.5, 0.5, 0.5), 2);
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : ps) {
            double d = p.getLocation().distanceSquared(b.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** 發放物品，揹包滿則掉落在玩家腳下。 */
    private static void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
    }

    /** 蹲下空手左鍵預覽：內容物清單（本地化名稱×數量）+ 容量 + 當前狀態。 */
    private static Component preview(BasinState st) {
        Component prefix = Component.text("[榨汁盆] ", NamedTextColor.GOLD);
        if (st == null || st.contents.isEmpty()) {
            return prefix.append(Component.text("盆內空空如也", NamedTextColor.GRAY));
        }
        net.kyori.adventure.text.TextComponent.Builder msg = net.kyori.adventure.text.Component.text()
                .append(prefix)
                .append(Component.text("內容物: ", NamedTextColor.GRAY));
        boolean first = true;
        for (Map.Entry<String, Integer> en : st.contents.entrySet()) {
            if (!first) msg.append(Component.text("、", NamedTextColor.GRAY));
            first = false;
            msg.append(JuicerRecipe.nameComponent(en.getKey()));
            if (en.getValue() > 1) msg.append(Component.text("×" + en.getValue(), NamedTextColor.GRAY));
        }
        msg.append(Component.text("（" + st.total + "/" + MAX_CAPACITY + "）", NamedTextColor.DARK_GRAY));
        int sugar = JuicerRecipe.totalSugar(st.contents);
        if (sugar > 0) msg.append(Component.text(" 糖分:" + sugar, NamedTextColor.LIGHT_PURPLE));
        String status;
        if (st.doses > 0) {
            status = "已榨好，剩餘 " + st.doses + " 份";
        } else if (st.started) {
            status = (st.isMix() ? "混合榨汁中 " : "榨汁中 ") + st.progress + "/" + progressTotal(st);
        } else {
            status = "等待投料";
        }
        return msg.append(Component.text(" " + status, NamedTextColor.YELLOW)).build();
    }

    /** 原版物品判定（排除同材質的粘液物品）。 */
    private static boolean isVanilla(ItemStack item, Material m) {
        return item != null && item.getType() == m
                && io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(item) == null;
    }

    private static ItemDisplay spawnItemDisplay(World world, Location loc, ItemStack item, float scale, float yaw) {
        return world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setPersistent(false);
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationY(yaw),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    /**
     * 按內容物重建展示實體：每單位物品一個，環形散佈在盆內並隨機朝向/高低差，模擬自然堆放；
     * 單件時居中稍大。已榨好批次（doses > 0）原料已成汁，不再展示，以水位表現。
     */
    private static void refreshDisplays(Block b, BasinState st) {
        removeDisplays(st);
        if (st.doses > 0 || st.total <= 0) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = Math.min(st.total, MAX_CAPACITY);
        float scale = n == 1 ? 0.7f : 0.6f;
        double baseAngle = rnd.nextDouble() * Math.PI * 2;
        int i = 0;
        for (Map.Entry<String, Integer> en : st.contents.entrySet()) {
            ItemStack item = JuicerRecipe.refToItem(en.getKey());
            for (int k = 0; k < en.getValue() && i < n; k++, i++) {
                double angle = baseAngle + Math.PI * 2 * i / n;
                double radius = n == 1 ? 0 : 0.18;
                Location loc = new Location(b.getWorld(),
                        b.getX() + 0.5 + Math.cos(angle) * radius,
                        b.getY() + 0.26 + rnd.nextDouble() * 0.08,
                        b.getZ() + 0.5 + Math.sin(angle) * radius);
                st.displays.add(spawnItemDisplay(b.getWorld(), loc, item, scale,
                        (float) (rnd.nextDouble() * Math.PI * 2)));
            }
        }
    }

    private static void removeDisplays(BasinState st) {
        for (ItemDisplay d : st.displays) {
            d.remove();
        }
        st.displays.clear();
    }

    /** 盆狀態：內容物多集 + 形態（鎖定配方 / 混合 / 等待補料）+ 榨汁與接取進度。 */
    private static final class BasinState {
        /** 內容物多集：ref(mc:XXX/sf:XXX) → 數量（按投入順序）。 */
        final Map<String, Integer> contents = new LinkedHashMap<>();
        int total;
        /** 是否已開始榨汁（開始後鎖定形態，拒絕再投入）。 */
        boolean started;
        /** 鎖定的配方（開始榨汁時按比例匹配鎖定），null = 混合/等待補料。 */
        JuicerRecipe recipe;
        /** 鎖定配方的倍率（內容物 = 倍率 × 配方投入多集），接取份數 = 倍率 × yield。 */
        int multiplier = 1;
        /** 混合形態（開始榨汁時未匹配配方但全部可混合；水量恆為 3 份）。 */
        boolean mixFlag;
        int progress;
        /** 剩餘接取份數（榨好後 >0）。 */
        int doses;
        /** 本次批次的滿額份數（桶滿判定）。 */
        int dosesMax;
        /** 榨好時間（epoch millis，產物 lore 的 %time% 佔位符用）。 */
        long completedAt;
        /** 本批次總糖分（Σ 材料糖分 × 數量，榨好時定格）。 */
        int totalSugar;
        final LinkedHashSet<String> players = new LinkedHashSet<>();
        /** 盆內物品展示實體（每單位一個；不落盤，缺失時由 tick 重建）。 */
        final List<ItemDisplay> displays = new ArrayList<>();

        boolean isMix() {
            return started && recipe == null && mixFlag;
        }

        boolean ready() {
            return doses > 0;
        }
    }

    /** 榨汁盆事件監聽：跳躍踩踏進度 / 鐵砧砸落進度 / 投入與接取 / 清空 / 桶裝產物禁倒出。 */
    public static final class Listener implements org.bukkit.event.Listener {

        public static final Listener INSTANCE = new Listener();

        /** 已武裝玩家（站在可榨汁的盆內），跳起越出盆格時記一次踩踏。 */
        private final Set<UUID> armed = new HashSet<>();
        /** 內容物預覽冷卻（玩家 → 上次提示時間戳，0.5 秒防刷屏）。 */
        private final Map<UUID, Long> previewCooldown = new HashMap<>();
        /** 材料不足提示冷卻（玩家 → 上次提示時間戳，1 秒防動作欄刷屏）。 */
        private final Map<UUID, Long> hintCooldown = new HashMap<>();

        private Listener() {}

        /**
         * 玩家型榨汁踩踏檢測（Paper 1.21.1+ 已移除 PlayerJumpEvent，改用移動事件）：
         * 腳部方塊在盆內且未開始榨汁 → 先鎖定形態（比例匹配配方 / 混合；等待補料則動作欄
         * 提示並跳過，帶 1 秒節流）；可踩踏盆 → 武裝；已武裝玩家腳部越到盆上方一格
         * （一次跳躍恰好穿越一次）→ +1 並解除武裝，落回盆內重新武裝。已榨好盆不響應踩踏。
         */
        @EventHandler(ignoreCancelled = true)
        public void onMove(PlayerMoveEvent e) {
            if (STATES.isEmpty()) return;
            Location feet = e.getTo();
            if (feet == null) return;
            Location from = e.getFrom();
            // 視角轉動不處理（座標未變）
            if (from.getX() == feet.getX() && from.getY() == feet.getY() && from.getZ() == feet.getZ()) return;
            Player p = e.getPlayer();
            World w = feet.getWorld();
            int bx = feet.getBlockX(), by = feet.getBlockY(), bz = feet.getBlockZ();
            BasinState st = lookup(w, bx, by, bz);
            if (st != null && st.doses <= 0
                    && BlockStorage.check(w.getBlockAt(bx, by, bz)) instanceof JuicerBasin) {
                if (!st.started) {
                    if (!tryStartJuice(st)) {
                        long now = System.currentTimeMillis();
                        Long last = hintCooldown.get(p.getUniqueId());
                        if (last == null || now - last >= 1000) {
                            hintCooldown.put(p.getUniqueId(), now);
                            p.sendActionBar(Component.text("材料不足，無法開始榨汁", NamedTextColor.RED));
                        }
                    }
                    return;
                }
                // 混合榨汁與允許踩踏的配方響應跳躍；純鐵砧型配方不響應
                if (st.isMix() || st.recipe == null || st.recipe.playerType) {
                    armed.add(p.getUniqueId());
                }
                return;
            }
            if (armed.remove(p.getUniqueId())) {
                BasinState target = lookup(w, bx, by - 1, bz);
                if (target != null && target.doses <= 0 && target.started) {
                    Block below = w.getBlockAt(bx, by - 1, bz);
                    if (BlockStorage.check(below) instanceof JuicerBasin
                            && (target.isMix() || target.recipe == null || target.recipe.playerType)) {
                        target.players.add(p.getName());
                        BlockStorage.addBlockInfo(below, KEY_PLAYERS, joinPlayers(target));
                        below.getWorld().playSound(below.getLocation().add(0.5, 0.5, 0.5),
                                Sound.ENTITY_SLIME_JUMP, 1f, 1f);
                        advance(below, target, 1, p);
                    }
                }
            }
        }

        /**
         * 鐵砧配方：鐵砧落到盆頂每次 +4（未開始榨汁的盆同時鎖定形態；混合榨汁不響應鐵砧）。
         * 鐵砧損耗按 anvil-damage 配置：關閉則永不損壞（留在盆頂）；開啓則每次砸落按
         * anvil-damage-chance/10 概率降低一檔耐久（ANVIL→CHIPPED→DAMAGED→碎裂）。
         */
        @EventHandler(ignoreCancelled = true)
        public void onAnvilLand(EntityChangeBlockEvent e) {
            if (!(e.getEntity() instanceof FallingBlock fb)) return;
            Material m = fb.getBlockData().getMaterial();
            if (m != Material.ANVIL && m != Material.CHIPPED_ANVIL && m != Material.DAMAGED_ANVIL) return;
            Block basin = e.getBlock().getRelative(BlockFace.DOWN);
            BasinState st = STATES.get(basin.getLocation());
            if (st == null || st.doses > 0) return;
            if (!(BlockStorage.check(basin) instanceof JuicerBasin)) return;
            if (!st.started && !tryStartJuice(st)) return;
            if (st.recipe == null || !st.recipe.anvilType) return;
            // 落點方塊在事件回調內改動不安全：下一 tick 處理鐵砧損耗
            Block landed = e.getBlock();
            Bukkit.getScheduler().runTask(WT.plugin, () -> {
                if (landed.getType() != m) return;
                if (!JuicerRecipe.anvilDamage) return;
                if (ThreadLocalRandom.current().nextInt(10)
                        >= JuicerRecipe.anvilDamageChance) return;
                BlockFace face = landed.getBlockData() instanceof org.bukkit.block.data.Directional d
                        ? d.getFacing() : BlockFace.NORTH;
                Material next;
                switch (m) {
                    case ANVIL -> next = Material.CHIPPED_ANVIL;
                    case CHIPPED_ANVIL -> next = Material.DAMAGED_ANVIL;
                    default -> next = Material.AIR;
                }
                if (next == Material.AIR) {
                    landed.setType(Material.AIR);
                    landed.getWorld().playSound(landed.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 1f, 1f);
                } else {
                    Material nm = next;
                    landed.setBlockData(nm.createBlockData(d ->
                            ((org.bukkit.block.data.Directional) d).setFacing(face)));
                    landed.getWorld().playSound(landed.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 0.5f, 1.2f);
                }
            });
            advance(basin, st, 4, nearbyPlayer(basin));
        }

        /**
         * 蹲下 + 空手左鍵盆：聊天框提示內容物與狀態（0.5 秒冷卻防刷屏），同時取消挖掘，
         * 防止按住左鍵誤破壞盆體（拆除請持物或不蹲下）。
         */
        @EventHandler
        public void onPreview(PlayerInteractEvent e) {
            if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
            if (e.getHand() != EquipmentSlot.HAND || !e.getPlayer().isSneaking()) return;
            ItemStack hand = e.getItem();
            if (hand != null && !hand.getType().isAir()) return;
            Block b = e.getClickedBlock();
            if (b == null) return;
            Material t = b.getType();
            if (t != Material.CAULDRON && t != Material.WATER_CAULDRON) return;
            BasinState st = STATES.get(b.getLocation());
            if (st == null && !(BlockStorage.check(b) instanceof JuicerBasin)) return;
            e.setCancelled(true);
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            Long last = previewCooldown.get(p.getUniqueId());
            if (last != null && now - last < 500) return;
            previewCooldown.put(p.getUniqueId(), now);
            p.sendMessage(preview(st));
        }

        /** 桶裝產物禁止倒出（任何右鍵使用一律取消；水桶材質預過濾減少 meta 讀取）。 */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onBucketPour(PlayerInteractEvent e) {
            ItemStack item = e.getItem();
            if (item == null || item.getType() != Material.WATER_BUCKET || !item.hasItemMeta()) return;
            if (!item.getItemMeta().getPersistentDataContainer()
                    .has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE)) return;
            e.setCancelled(true);
        }

        /** 投入與接取：空盆可投入；有內容盆可續投/榨汁後按劑量接取；空手蹲下右鍵清空。 */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onInteract(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            Block b = e.getClickedBlock();
            if (b == null) return;
            Material t = b.getType();
            if (t != Material.CAULDRON && t != Material.WATER_CAULDRON) return;
            BasinState st = STATES.get(b.getLocation());
            if (st == null) {
                // 空盆：主手持物右鍵投入（等同扔入），無匹配則提示
                if (e.getHand() != EquipmentSlot.HAND) return;
                if (!(BlockStorage.check(b) instanceof JuicerBasin)) return;
                ItemStack hand = e.getItem();
                if (hand == null || hand.getType().isAir()) return;
                insertFromHand(e, b, hand);
                return;
            }
            if (!(BlockStorage.check(b) instanceof JuicerBasin)) return;
            e.setCancelled(true);
            Player p = e.getPlayer();
            // 空手蹲下右鍵：清空盆內內容物（丟棄不返還），盆還原為空
            if (p.isSneaking() && e.getHand() == EquipmentSlot.HAND
                    && (e.getItem() == null || e.getItem().getType().isAir())) {
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_GENERIC_SPLASH, 1f, 1f);
                resetBasin(b, st);
                return;
            }
            // 已榨好：劑量制接取
            if (st.doses <= 0) {
                // 未榨好：可繼續投入（鎖定配方/滿盆拒絕）
                if (e.getHand() == EquipmentSlot.HAND) {
                    ItemStack hand = e.getItem();
                    if (hand != null && !hand.getType().isAir()) insertFromHand(e, b, hand);
                }
                return;
            }
            ItemStack hand = e.getItem();
            // 瓶子：接取 1 份，水量 -1
            if (isVanilla(hand, Material.GLASS_BOTTLE)) {
                int perDose = st.dosesMax > 0 ? st.totalSugar / st.dosesMax : 0;
                give(p, st.recipe != null
                        ? st.recipe.buildResult(st.players, st.completedAt, perDose)
                        : JuicerRecipe.buildMixProduct(false, st.contents, st.players, st.completedAt, perDose,
                                st.dosesMax));
                consumeOne(p, e.getHand());
                st.doses--;
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
                if (st.doses <= 0) {
                    resetBasin(b, st);
                } else {
                    BlockStorage.addBlockInfo(b, KEY_DOSES, String.valueOf(st.doses));
                    syncLevel(b, st.doses);
                }
                return;
            }
            // 桶：僅滿盆（水量=滿額份數）一次接取全部並清空
            if (isVanilla(hand, Material.BUCKET)) {
                if (st.doses < st.dosesMax) {
                    p.sendMessage("§c這個盆不是滿的");
                    return;
                }
                if (st.recipe != null) {
                    // 桶裝形態產物（lore 與瓶裝一致，禁止倒出；糖分為整批總量）
                    give(p, st.recipe.buildBucketResult(st.players, st.completedAt, st.totalSugar));
                } else {
                    give(p, JuicerRecipe.buildMixProduct(true, st.contents, st.players, st.completedAt,
                            st.totalSugar, st.dosesMax));
                }
                consumeOne(p, e.getHand());
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_FILL, 1f, 1f);
                resetBasin(b, st);
            }
        }

        /** 主手持物右鍵投入：評估通過則消耗 1 個並加入盆中，否則按原因提示。 */
        private static void insertFromHand(PlayerInteractEvent e, Block b, ItemStack hand) {
            int verdict = canInsert(STATES.get(b.getLocation()), JuicerRecipe.refOf(hand));
            if (verdict == INSERT_BUSY) {
                e.getPlayer().sendMessage("§c這個榨汁盆中已經有物品了！");
                return;
            }
            if (verdict == INSERT_INVALID) {
                e.getPlayer().sendMessage("§c這個物品不能榨汁！");
                return;
            }
            consumeOne(e.getPlayer(), EquipmentSlot.HAND);
            doInsert(b, STATES.get(b.getLocation()), hand);
        }

        /**
         * 玩家投擲物品：激活 8 格內榨汁盆的快速掉落物掃描（10 秒窗口）。
         * 空置盆平時不做實體查詢，僅在有投擲行為時短暫高頻掃描。
         */
        @EventHandler
        public void onDrop(PlayerDropItemEvent e) {
            Location l = e.getItemDrop().getLocation();
            long until = System.currentTimeMillis() + 10_000;
            for (Location bl : STATES.keySet()) {
                if (bl.getWorld() == l.getWorld() && bl.distanceSquared(l) <= 64) {
                    SCAN_UNTIL.put(bl, until);
                }
            }
            for (Location bl : EMPTY_KNOWN) {
                if (bl.getWorld() == l.getWorld() && bl.distanceSquared(l) <= 64) {
                    SCAN_UNTIL.put(bl, until);
                }
            }
        }

        /** 從指定手消耗 1 個物品（到 0 清空槽位，防幽靈物品）。 */
        private static void consumeOne(Player p, EquipmentSlot slot) {
            PlayerInventory inv = p.getInventory();
            ItemStack held = inv.getItem(slot);
            if (held == null) return;
            if (held.getAmount() <= 1) inv.setItem(slot, null);
            else held.setAmount(held.getAmount() - 1);
        }
    }
}
