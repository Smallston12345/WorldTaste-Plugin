package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.behavior.Behaviors.CropCfg;
import com.haiman233.worldtaste.behavior.Behaviors.CropDrop;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 作物方塊（machines.yml 中 script 為 seed/* 的物品）。
 *
 * <p>生長進度完全採用原版機制：種子放置後由 tick 轉為作物材質並歸零 age，其後由原版隨機刻
 * 自然生長（骨粉催熟同樣生效）；成熟與否在破壞時按原版 age 是否滿檔判定，插件不再按時間
 * 推進生長階段。</p>
 *
 * <p>唯一例外是紫頌花（CHORUS_FLOWER）：原版紫頌只會"移動式"生長——每次生長把原位變成梗、
 * 在新格生成 age+1 的花，原版機制無法讓紫頌花原地加齡。故對紫頌作物保留按 growMs 的定時
 * 推進（含成熟標記持久化與內存緩存，區塊卸載時兜底清理）。原版隨機刻的移動式生長與枯萎
 * 由 {@code CropListener} 在事件源頭取消（BlockSpreadEvent / BlockGrowEvent），本類每 8 tick
 * 的上方掃描僅作兜底清理歷史遺留，不再承擔攔截職責（此前「原版生長 → tick 還原」會造成
 * 視覺閃回與每株兩輪的方塊更新開銷）。</p>
 */
public class CropBlock extends SlimefunItem {

    /** 紫頌定時生長的階段比例表（僅 CHORUS_FLOWER 材質使用）。 */
    private static final double[] SMALL_STEPS = {1/10d, 1/6d, 1/3d, 0.5, 2/3d, 5/6d, 1d, 7/6d};
    /** 水平四向（方向性作物附着掃描用）。 */
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    /** 紫頌定時生長的持久化鍵（BlockStorage，隨 Slimefun 數據庫落盤）：生長起點時間戳 / 成熟標記。 */
    private static final String KEY_START = "wt-crop-start";
    private static final String KEY_GROWN = "wt-crop-grown";

    private final CropCfg cfg;
    /** 預算的生長閾值 growMs*SMALL_STEPS[i]（不變量，double 保精確語義）。構造期一次計算，僅紫頌作物使用。 */
    private final double[] growMsSteps;
    /** 紫頌定時生長的內存狀態（按位置全局共享；僅 CHORUS_FLOWER 材質讀寫，普通作物零狀態，
     *  age 即全部生長狀態）。條目隨破壞/重種/方塊移除清除，並在區塊卸載時兜底清理
     *  （{@link ChorusStateCleanup}），防止被外部手段移除的作物永久殘留。tick 與破壞事件均在
     *  主線程，無需併發容器。 */
    private static final Map<Location, Long> lastUse = new HashMap<>();
    private static final Set<Location> grown = new HashSet<>();
    /** 方塊當前階段緩存：僅階段變化時才寫方塊，避免每 tick getState()/setBlockData() 的對象開銷（spark 熱點優化）。 */
    private static final Map<Location, Integer> stage = new HashMap<>();
    /** 原版生長年齡上限壓回值：僅當配置 maxAge 低於原版上限時 ≥0（如瓜莖 maxAge=6 < 原版 7）。
     *  此類作物長到該年齡後由 {@code CropListener} 取消原版生長事件（隨機刻/骨粉均走 BlockGrowEvent），
     *  使其停留在成熟階段、不再觸發原版滿齡行為（莖結果實）；
     *  -1 表示無限制（絕大多數作物 maxAge=原版上限，零額外開銷）。 */
    private final int clampMax;
    /** 配置 maxAge 低於原版上限的作物材質集合（BlockGrowEvent 攔截的材質級預篩）。
     *  加載期單線程填充、運行期只讀，無需併發容器。 */
    private static final java.util.Set<Material> CLAMPED_MATERIALS = java.util.EnumSet.noneOf(Material.class);
    /** 作物方塊是否帶方向（如 COCOA 需附着在原木側面），構造期由材質 BlockData 判定。 */
    private final boolean directionalCrop;

    public CropBlock(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe, CropCfg cfg) {
        super(group, item, rt, recipe);
        this.cfg = cfg;
        this.growMsSteps = new double[SMALL_STEPS.length];
        for (int i = 0; i < SMALL_STEPS.length; i++) growMsSteps[i] = cfg.growMs * SMALL_STEPS[i];
        int computedClamp = -1;
        boolean directional = false;
        try {
            org.bukkit.block.data.BlockData data = cfg.material.createBlockData();
            directional = data instanceof Directional;
            if (data instanceof Ageable a) {
                if (cfg.maxAge < a.getMaximumAge()) computedClamp = Math.max(0, cfg.maxAge);
            } else {
                // 原版生長要求材質可加齡（Ageable）：非 Ageable 材質的作物永遠無法成熟，加載期給出告警
                com.haiman233.worldtaste.WT.log("作物 " + getId() + " 的材質 " + cfg.material
                        + " 不支持原版生長（非 Ageable），該作物將無法成熟");
            }
        } catch (Throwable ignored) {
            // 異常材質（如 air）交由運行期 type 守衞兜底
        }
        this.clampMax = computedClamp;
        this.directionalCrop = directional;
        if (computedClamp >= 0) {
            CLAMPED_MATERIALS.add(cfg.material);
        }
    }

    /** 是否為紫頌作物（唯一需要定時推進生長的材質）。 */
    private boolean isChorus() {
        return cfg.material == Material.CHORUS_FLOWER;
    }

    /** 作物方塊是否帶方向（如 COCOA 需附着在原木側面）。 */
    public boolean isDirectionalCrop() {
        return directionalCrop;
    }

    /** 原版生長年齡上限（-1=無限制）；{@code CropListener} 據此在 BlockGrowEvent 裏取消越限生長。 */
    public int getClampMax() {
        return clampMax;
    }

    /** 材質級預篩：該材質是否存在限高作物（BlockGrowEvent 高頻路徑的第一道廉價過濾）。 */
    public static boolean isClampedMaterial(Material m) {
        return CLAMPED_MATERIALS.contains(m);
    }

    /** 種植要求：顯式 plantOn 優先，否則按材質推斷（原版機制）；null 表示不限制。 */
    public List<Material> getPlantOn() {
        return cfg.resolvedPlantOn();
    }

    /** 不由 Slimefun 框架掉落種子本身（僅由 CropListener 在成熟時掉落作物/種子，對齊原腳本）。 */
    @Override
    public List<ItemStack> getDrops() {
        return java.util.Collections.emptyList();
    }

    @Override
    public void preRegister() {
        super.preRegister();
        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }
            @Override
            public void tick(Block b, SlimefunItem item, Config data) {
                CropBlock.this.tick(b);
            }
        });
    }

    private void tick(Block b) {
        Material type = b.getType();
        boolean isSeedHead = (type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD);
        if (type != cfg.material && !isSeedHead) {
            // 仍登記為我們的作物（玩家未破壞）時，可能是原版機制替換了方塊
            // （如紫頌隨機生長/甘蔗物理變化）：恢復作物材質繼續生長，避免誤註銷；
            // 否則視為被移除（耕地破壞、爆炸、踩踏等），清理狀態並註銷，
            // 避免幽靈 tick 把 AIR 設回作物刷原版種子。
            if (BlockStorage.hasBlockInfo(b)) {
                // 恢復材質後按周邊支撐修正方向性作物的朝向；若在該位置已無法存活
                //（支撐被移走等），按非玩家破壞處理並清數據，避免復活 → 破壞死循環
                b.setType(cfg.material);
                applyFacing(b, null);
                if (!b.getBlockData().isSupported(b)) {
                    discardUnsupportable(b);
                    return;
                }
            } else {
                purge(b.getLocation());
                BlockStorage.clearBlockInfo(b);
                return;
            }
        }
        if (isSeedHead) {
            // 剛種下/重放：轉為作物材質並歸零 age，其後交給原版隨機刻生長
            if (isChorus()) {
                // 重置紫頌定時狀態（防同位置重放秒熟）
                Location l = b.getLocation();
                grown.remove(l);
                lastUse.remove(l);
                stage.remove(l);
                BlockStorage.addBlockInfo(b, KEY_START, null);
                BlockStorage.addBlockInfo(b, KEY_GROWN, null);
            }
            setStage(b, 0);
            return;
        }
        if (isChorus()) {
            tickChorus(b);
            return;
        }
        // 普通作物：生長完全交給原版隨機刻（含骨粉催熟），無 tick 邏輯。
        // maxAge 低於原版上限的限高作物（如瓜莖）由 CropListener 的 BlockGrowEvent 攔截在
        // 成熟年齡上，無需 tick 輪詢回退生長進度。
    }

    /** 紫頌作物定時生長（原版無法讓紫頌花原地加齡，見類註釋）。 */
    private void tickChorus(Block b) {
        // 原版生長已在 CropListener 事件源頭取消（Spread/Grow），tick 無需任何兜底掃描。
        Location l = b.getLocation();
        if (grown.contains(l)) return;
        long now = System.currentTimeMillis();
        Long last = lastUse.get(l);
        if (last == null) {
            // 內存狀態缺失（重啓/新放置）：從持久化恢復；成熟標記優先，避免重新計時
            if (BlockStorage.getLocationInfo(l, KEY_GROWN) != null) {
                grown.add(l);
                return;
            }
            String saved = BlockStorage.getLocationInfo(l, KEY_START);
            if (saved != null) {
                try { last = Long.parseLong(saved); } catch (NumberFormatException ignored) { last = null; }
            }
            if (last == null) {
                lastUse.put(l, now);
                BlockStorage.addBlockInfo(b, KEY_START, String.valueOf(now));
                stage.remove(l);
                setStage(b, 0);
                return;
            }
            lastUse.put(l, last);
        }
        long elapsed = now - last;
        int target = cfg.maxAge;
        for (int i = 0; i < growMsSteps.length; i++) {
            if (elapsed < growMsSteps[i]) {
                target = (i > 0) ? (int) Math.floor(cfg.maxAge * ((double) i / SMALL_STEPS.length)) : -1;
                break;
            }
        }
        if (target >= 0) {
            setStageIfChanged(l, b, target);
            if (target == cfg.maxAge) {
                grown.add(l);
                BlockStorage.addBlockInfo(b, KEY_GROWN, "1");
                // 成熟外觀 = 原版枯萎紫頌花（CHORUS_FLOWER age=5 貼圖即枯萎態）：
                // 強制寫一次（stage 緩存可能在重放/恢復時與方塊實際 age 脱節）
                setStage(b, cfg.maxAge);
            }
        }
    }

    private void setStage(Block b, int age) {
        if (b.getType() != cfg.material) {
            // 種子頭 → 作物方塊轉換：先記錄種子頭放置時的附着方向，轉換後按其設置朝向
            // （如 COCOA 的 facing 須指向附着的原木），否則作物會因附着失效被原版反覆破壞
            BlockFace support = seedSupportFace(b);
            b.setType(cfg.material);
            applyFacing(b, support);
            if (!b.getBlockData().isSupported(b)) {
                // 轉換後在該位置無法存活（方向性作物四周無附着支撐、地面作物懸空等）：
                // 清數據並退還種子，避免「轉換 → 原版破壞 → tick 復活」的無限循環
                discardUnsupportable(b);
                return;
            }
        }
        BlockState st = b.getState();
        if (st.getBlockData() instanceof Ageable a) {
            int target = Math.min(age, a.getMaximumAge());
            if (a.getAge() != target) {
                a.setAge(target);
                st.setBlockData(a);
                st.update(true);
            }
        }
    }

    /** 種子頭（玩家頭）的附着方向：牆式頭朝外放置，附着面在其朝向的反方向；地面式頭無附着信息，返回 null。 */
    private static BlockFace seedSupportFace(Block b) {
        if (b.getBlockData() instanceof Directional dir) {
            return dir.getFacing().getOppositeFace();
        }
        return null;
    }

    /**
     * 方向性作物（如 COCOA，facing 須指向附着的原木）轉換/恢復後設置朝向：
     * 優先用種子頭記錄的附着方向，缺信息或指向非支撐方塊時按 plantOn 水平掃描兜底。
     * 非方向性作物或無附着限制的作物直接跳過。
     */
    private void applyFacing(Block b, BlockFace support) {
        if (!directionalCrop || !(b.getBlockData() instanceof Directional dir)) return;
        List<Material> allowed = cfg.resolvedPlantOn();
        if (allowed == null || allowed.isEmpty()) return;
        BlockFace target = null;
        if (support != null && support.getModY() == 0
                && allowed.contains(b.getRelative(support).getType())) {
            target = support;
        } else {
            for (BlockFace f : HORIZONTAL_FACES) {
                if (allowed.contains(b.getRelative(f).getType())) { target = f; break; }
            }
        }
        if (target != null && dir.getFacing() != target) {
            dir.setFacing(target);
            b.setBlockData(dir);
        }
    }

    /** 作物在當前位置無法存活（懸空/無附着支撐）：清數據並退還 1 個種子，避免「復活 → 原版破壞」死循環。 */
    private void discardUnsupportable(Block b) {
        purge(b.getLocation());
        BlockStorage.clearBlockInfo(b);
        dropSeed(b, 1, 1.0, java.util.concurrent.ThreadLocalRandom.current());
        // 與 CropListener.breakCrop 一致：先上格後主格、關閉物理移除，
        // 防止另一半被原版鄰塊更新按原版戰利品破壞（泄漏瓶子草等原版物品）
        Block up = b.getRelative(BlockFace.UP);
        if (up.getType() == b.getType()) {
            up.setType(Material.AIR, false);
        }
        b.setType(Material.AIR, false);
    }

    /** 作物是否成熟：原版 age 達到 min(配置 maxAge, 原版上限)。生長與骨粉催熟均由原版寫 age，破壞時以此判定。 */
    private boolean isNaturallyMature(Block b) {
        if (b.getType() != cfg.material) return false;
        BlockState st = b.getState();
        if (st.getBlockData() instanceof Ageable a) {
            return a.getAge() >= Math.min(cfg.maxAge, a.getMaximumAge());
        }
        return false;
    }

    /** 階段緩存寫入：目標年齡與緩存一致時跳過方塊寫操作（spark 熱點優化，行為不變）。 */
    private void setStageIfChanged(Location l, Block b, int age) {
        Integer cur = stage.get(l);
        if (cur != null && cur.intValue() == age) return;
        setStage(b, age);
        stage.put(l, age);
    }

    /** 破壞時調用：成熟（原版 age 滿檔，或紫頌定時成熟標記）則掉落作物/種子，否則掉 1 個種子。返回是否處理過。 */
    public boolean onBreak(Block b) {
        Location l = b.getLocation();
        boolean wasGrown = isNaturallyMature(b);
        if (isChorus()) {
            // 成熟標記兜底：紫頌基座可能被原版"移動式生長"重置過 age，以標記為準
            wasGrown = grown.remove(l) || wasGrown;
            lastUse.remove(l);
            stage.remove(l);
        }
        // 與項目其餘隨機邏輯一致使用 ThreadLocalRandom（免分配；破壞事件在主線程）
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        if (!wasGrown) {
            // 未成熟破壞：必掉 1 個對應種子（cropId 缺失時從掉落表回退找含 SEED 的項）
            dropSeed(b, 1, 1.0, rnd);
            return false;
        }
        List<CropDrop> drops = cfg.drops;
        if (cfg.weighted && !drops.isEmpty()) {
            // 對齊 FishingListener.select 的健壯加權選擇：
            //   · total<=0（權重全非正的髒數據）時不產出，避免 rnd.nextDouble()*total 為負後邏輯錯亂；
            //   · 兜底選末項，保證 total>0 時浮點邊界/末項權重為 0 仍至少產出一個掉落（原實現循環走完會什麼都不掉）。
            // R8：total 改用 CropCfg.weightTotal（load 期預算），消除每次收穫對 drops 的求和（O(n)→O(1)，對齊 R4）。
            double total = cfg.weightTotal;
            if (total <= 0) return true;
            double r = rnd.nextDouble() * total;
            CropDrop picked = drops.get(drops.size() - 1);
            for (CropDrop d : drops) {
                r -= d.weight;
                if (r <= 0) { picked = d; break; }
            }
            dropItem(b, picked.id);
        } else {
            for (CropDrop d : drops) {
                if (rnd.nextDouble() < d.chance) dropItem(b, d.id);
            }
        }
        // 成熟破壞：原有掉落不變，額外按 seedDropChance 概率掉 1..seedDropMax 個種子
        dropSeed(b, 1 + rnd.nextInt(Math.max(1, cfg.seedDropMax)), cfg.seedDropChance, rnd);
        return true;
    }

    private void dropItem(Block b, String id) {
        SlimefunItem sf = SlimefunItem.getById(id);
        ItemStack stack;
        if (sf != null) stack = sf.getItem();
        else {
            Material m = Material.matchMaterial(id);
            if (m == null) {
                com.haiman233.worldtaste.WT.log("作物 " + getId() + " 的掉落物無法解析: " + id);
                return;
            }
            stack = new ItemStack(m);
        }
        b.getWorld().dropItemNaturally(b.getLocation(), stack.clone());
    }

    /** 掉落作物種子：cropId 優先，缺失時從掉落表回退找含 SEED 的項；chance&lt;1 時按概率判定。 */
    private void dropSeed(Block b, int count, double chance, java.util.Random rnd) {
        if (chance < 1.0 && rnd.nextDouble() >= chance) return;
        String seedId = cfg.cropId;
        if (seedId == null) {
            for (CropDrop d : cfg.drops) {
                if (d.id != null && d.id.contains("SEED")) { seedId = d.id; break; }
            }
        }
        if (seedId == null) return;
        SlimefunItem sf = SlimefunItem.getById(seedId);
        ItemStack stack = (sf != null) ? sf.getItem().clone() : null;
        if (stack == null) {
            Material m = Material.matchMaterial(seedId);
            if (m == null) return;
            stack = new ItemStack(m);
        }
        stack.setAmount(count);
        b.getWorld().dropItemNaturally(b.getLocation(), stack);
    }

    /** 清理一個位置的全部紫頌定時狀態。 */
    private static void purge(Location l) {
        lastUse.remove(l);
        grown.remove(l);
        stage.remove(l);
    }

    private static boolean inChunk(Location l, World w, int cx, int cz) {
        return l.getWorld() == w && (l.getBlockX() >> 4) == cx && (l.getBlockZ() >> 4) == cz;
    }

    /**
     * 區塊卸載兜底清理：在區塊未加載期間被外部手段（WorldEdit、/fill、跨區塊爆炸等）移除的
     * 紫頌作物不會再被 tick（也就不會走清理路徑），其內存條目會在區塊卸載時在此清除，
     * 避免按位置累積泄漏。普通作物零狀態，無需清理。
     */
    public static final class ChorusStateCleanup implements Listener {

        /** 單例（狀態為 CropBlock 的靜態表，經靜態方法清理）。 */
        public static final ChorusStateCleanup INSTANCE = new ChorusStateCleanup();

        private ChorusStateCleanup() {}

        @EventHandler
        public void onChunkUnload(ChunkUnloadEvent e) {
            Chunk c = e.getChunk();
            World w = c.getWorld();
            int cx = c.getX();
            int cz = c.getZ();
            lastUse.keySet().removeIf(l -> inChunk(l, w, cx, cz));
            grown.removeIf(l -> inChunk(l, w, cx, cz));
            stage.keySet().removeIf(l -> inChunk(l, w, cx, cz));
        }
    }
}
