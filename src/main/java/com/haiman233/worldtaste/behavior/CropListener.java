package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.items.CropBlock;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * 作物方塊破壞處理：
 * <ol>
 *   <li>破壞作物主格：禁用原版掉落，成熟則掉成品/種子（未成熟掉 1 種子）；2 格高作物（如瓶子草）
 *       同時清除上方殘留格；</li>
 *   <li>破壞 2 格高作物的上格：聯動破壞主格（掉成品/種子）並清數據；</li>
 *   <li>破壞種子附着的支撐方塊（耕地/終界石等）：聯動破壞上方作物；</li>
 *   <li>非玩家事件（爆炸、水衝、活塞推拉、火燒、耕地踩踏、實體頂替）：作物被波及/失去支撐時
 *       同樣按破壞處理（掉成品/種子）並刪除粘液數據——否則殘留數據會讓 tick 把方塊復原，
 *       出現「水衝不走、拉不倒」的假活死循環；</li>
 *   <li>生長限高：配置 maxAge 低於原版上限的作物（瓜莖等）長到成熟年齡後取消原版生長事件
 *       （BlockGrowEvent），停留在成熟階段，不進入原版滿齡行為（莖結果實）。</li>
 * </ol>
 */
public final class CropListener implements Listener {

    public static final CropListener INSTANCE = new CropListener();

    private CropListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        SlimefunItem sf = BlockStorage.check(b);
        if (sf instanceof CropBlock crop) {
            // 禁用原版掉落：作物方塊已被 tick 轉成 WHEAT 等原版材質，否則會額外掉原版作物/種子
            e.setDropItems(false);
            breakCrop(b, crop);
            return;
        }
        // 自身即其他粘液方塊（機器/裝飾等）：作物只種在原版方塊上（plantOn 限定原版材質），
        // 且 2 格高作物的上格不含粘液數據，故它既不可能是作物的上格殘留也不可能是支撐方塊，
        // 跳過下方/上方兩次數據查詢（粘液方塊破壞高頻路徑）
        if (sf != null) return;
        // 2 格高作物：破壞上格 → 聯動破壞主格。
        // 必須校驗「被破壞方塊與下方作物同材質」：該分支只服務 2 格高作物（上下同材質，如瓶子草）。
        // 否則玩家在單格作物（紫頌花/小麥等）上方放的裝飾方塊被破壞時，會把下方作物誤判為
        // 「2 格高作物的上格」而整株誤殺。
        Block below = b.getRelative(BlockFace.DOWN);
        if (below.getType() == b.getType()) {
            SlimefunItem belowSf = BlockStorage.check(below);
            if (belowSf instanceof CropBlock cropBelow) {
                e.setDropItems(false);
                breakCrop(below, cropBelow);
                return;
            }
        }
        // 支撐方塊被破壞 → 聯動破壞上方作物（保留支撐方塊自身的原版掉落）
        Block above = b.getRelative(BlockFace.UP);
        SlimefunItem aboveSf = BlockStorage.check(above);
        if (aboveSf instanceof CropBlock cropAbove) {
            breakCrop(above, cropAbove);
        }
    }

    /** 爆炸波及：被炸方塊的上/下若有作物（支撐被炸 / 2 格高上格被炸），聯動破壞並清數據。 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            cleanupAdjacent(b);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            cleanupAdjacent(b);
        }
    }

    /**
     * 流體沖刷（水流沖走作物、岩漿燒燬等）：被流體頂替的作物按非玩家破壞處理（掉成品/種子 + 清數據）。
     * 在事件階段（流體尚未流入）提前破壞並清數據，原版的流體破壞掉落便不會再發生，避免雙重掉落；
     * 若只清方塊不清數據，殘留數據會讓 tick 的恢復守衞把作物復原，出現「水衝不走」的死循環。
     */
    @EventHandler(ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        // 絕大多數流體步進的目標是空氣，先按材質短路再查數據
        if (e.getToBlock().getType().isAir()) return;
        cleanupCrop(e.getToBlock());
    }

    /** 活塞推動：被推方塊自身、其上方（支撐被推走）及推入目的地若有作物，按非玩家破壞處理。 */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        BlockFace dir = e.getDirection();
        for (Block b : e.getBlocks()) {
            cleanupPistonPath(b, dir);
        }
        // 活塞頭正前方：被頂替的作物（不可移動的作物不會出現在 getBlocks 裏）
        cleanupPistonPath(e.getBlock().getRelative(dir), dir);
    }

    /** 活塞拉動：被拉方塊自身、其上方（支撐被拉走）及拉入目的地若有作物，按非玩家破壞處理。 */
    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        BlockFace dir = e.getDirection();
        for (Block b : e.getBlocks()) {
            cleanupPistonPath(b, dir);
        }
        // 活塞頭位置：粘在頭上的不可移動作物被拉斷
        cleanupPistonPath(e.getBlock().getRelative(dir.getOppositeFace()), dir);
    }

    /** 檢查活塞推/拉路徑上的一個位置及其上下文，破壞其中的作物並清數據。 */
    private static void cleanupPistonPath(Block b, BlockFace dir) {
        cleanupCrop(b);                            // 作物被直接推/拉（原版破壞但不移動）
        cleanupCrop(b.getRelative(BlockFace.UP));  // 支撐方塊被移走，上方作物失去支撐
        cleanupCrop(b.getRelative(dir));           // 目的地已有作物，被推入/拉入頂替
    }

    /** 火燒作物：按非玩家破壞處理並清數據。 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        cleanupCrop(e.getBlock());
    }

    /** 耕地被踩踏成泥土：上方作物失去支撐，聯動破壞並清數據。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        // 方塊被實體直接頂替（如鐵砧/墜落方塊砸中作物位置）：按非玩家破壞處理
        SlimefunItem sf = BlockStorage.check(b);
        if (sf instanceof CropBlock crop) {
            breakCrop(b, crop);
            return;
        }
        if (b.getType() != Material.FARMLAND) return;
        // 耕地被踩踏成泥土：上方作物失去支撐，聯動破壞並清數據
        Block above = b.getRelative(BlockFace.UP);
        SlimefunItem aboveSf = BlockStorage.check(above);
        if (aboveSf instanceof CropBlock crop) {
            breakCrop(above, crop);
        }
    }

    /**
     * 原版生長攔截：限高作物（配置 maxAge 低於原版上限，如瓜莖 maxAge=6 < 原版 7，防原版結果實）
     * 長到配置成熟年齡後取消原版生長事件——隨機刻生長與骨粉催熟均走 BlockGrowEvent，
     * 作物停留在成熟階段，無需 tick 輪詢回退生長進度。
     * 附加：限高莖旁生成的原版果實（歷史遺留滿齡莖的掉落路徑）一併取消，防刷原版西瓜/南瓜。
     */
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        // 紫頌作物：原版隨機刻的枯萎分支（無法生長時原地變 age=5 枯花）走 BlockGrowEvent，
        // 一律取消——紫頌生長/枯萎完全由插件定時推進控制（生成分支走 BlockSpreadEvent，見 onSpread）。
        if (b.getType() == Material.CHORUS_FLOWER) {
            SlimefunItem sf0 = BlockStorage.check(b);
            if (sf0 instanceof CropBlock) {
                e.setCancelled(true);
                return;
            }
        }
        if (CropBlock.isClampedMaterial(b.getType())) {
            SlimefunItem sf = BlockStorage.check(b);
            if (sf instanceof CropBlock crop) {
                int clamp = crop.getClampMax();
                if (clamp >= 0 && b.getBlockData() instanceof Ageable a && a.getAge() >= clamp) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        // 果實生成事件（block=結果位，newState=瓜）：相鄰存在限高莖時取消
        Material newStateType = e.getNewState().getType();
        if (newStateType == Material.MELON || newStateType == Material.PUMPKIN) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = b.getRelative(f);
                if (!CropBlock.isClampedMaterial(n.getType())) continue;
                SlimefunItem sf = BlockStorage.check(n);
                if (sf instanceof CropBlock crop && crop.getClampMax() >= 0) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    /** 破壞作物主格：掉成品/種子、清數據、清除 2 格高作物的上格殘留。 */
    private static void breakCrop(Block b, CropBlock crop) {
        // 先按當前方塊狀態判定成熟並掉落（此時兩格齊全、age 可讀）。
        // 順序與物理都關鍵：
        //  · 若先移除 2 格高作物的上格，原版鄰塊更新會把主格判為無法存活而按原版戰利品破壞
        //    （泄漏瓶子草等原版物品），且主格變 AIR 後成熟判定失效、只會掉 1 個種子；
        //  · 移除兩格時關閉物理（applyPhysics=false），另一半不會被原版鄰塊更新帶掉落破壞。
        crop.onBreak(b);
        Block up = b.getRelative(BlockFace.UP);
        if (up.getType() == b.getType()) {
            up.setType(Material.AIR, false);
        }
        b.setType(Material.AIR, false);
        BlockStorage.clearBlockInfo(b);
    }

    private static void cleanupAdjacent(Block exploded) {
        cleanupCrop(exploded.getRelative(BlockFace.UP));    // 支撐被炸
        // 2 格高上格被炸（上下同材質才聯動；爆炸波及作物上方普通方塊時不誤殺下方作物）
        Block down = exploded.getRelative(BlockFace.DOWN);
        if (down.getType() == exploded.getType()) {
            cleanupCrop(down);
        }
    }

    /**
     * 原版紫頌生長源頭攔截（性能與視覺雙收益）：紫頌花隨機刻的移動式生長（上方/水平生成新花）
     * 走 BlockSpreadEvent（CraftBukkit 補丁 handleBlockSpreadEvent），且基座「變梗」的 setBlock
     * 只在事件未被取消時才執行——在此取消即讓原版什麼都不做，杜絕「原版生長 → 插件 tick 還原」
     * 的閃回與每株兩輪的方塊更新開銷。注意 BlockGrowEvent 攔不住該分支（它不是 Grow 事件）。
     */
    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        Block src = e.getSource();
        if (src.getType() != Material.CHORUS_FLOWER && src.getType() != Material.CHORUS_PLANT) return;
        SlimefunItem sf = BlockStorage.check(src);
        if (sf instanceof CropBlock) {
            e.setCancelled(true);
        }
    }
    private static void cleanupCrop(Block b) {
        SlimefunItem sf = BlockStorage.check(b);
        if (sf instanceof CropBlock crop) {
            breakCrop(b, crop);
        }
    }
}
