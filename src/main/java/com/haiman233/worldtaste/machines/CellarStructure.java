package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 酒窖多方塊結構（3×3×3，y 軸從下到上，材質嚴格匹配不忽略方塊變體）：
 * <pre>
 * 底/頂層（相同）： 橡木木板  橡木原木  橡木木板        中層： 橡木原木  橡木木板  橡木原木
 *                  橡木原木  橡木原木  橡木原木               橡木木板   空(中心)  橡木木板
 *                  橡木木板  橡木原木  橡木木板               橡木原木  [管理器] [温控器]
 * </pre>
 * 中層非旋轉對稱（管理器/温控器位於一條邊），支持整體四個朝向放置（層內旋轉 90°×4），
 * 結構校驗從任一機器方塊錨定，按 4 朝向逐一嘗試。
 */
final class CellarStructure {

    /** 方塊碼。 */
    private static final byte AIR = 0;
    private static final byte PLANKS = 1;
    private static final byte LOG = 2;
    private static final byte MANAGER = 3;
    private static final byte CONTROLLER = 4;

    /** 結構佈局 layout[y][z][x]，y 從下到上；中心格 (1,1,1) 為中層空氣。 */
    private static final byte[][][] LAYOUT = {
        { // 底層
            {PLANKS, LOG, PLANKS},
            {LOG, LOG, LOG},
            {PLANKS, LOG, PLANKS}
        },
        { // 中層
            {LOG, PLANKS, LOG},
            {PLANKS, AIR, PLANKS},
            {LOG, MANAGER, CONTROLLER}
        },
        { // 頂層
            {PLANKS, LOG, PLANKS},
            {LOG, LOG, LOG},
            {PLANKS, LOG, PLANKS}
        }
    };

    private CellarStructure() {}

    /**
     * 校驗多方塊結構是否完整。
     *
     * @param anchor         錨點機器方塊（管理器或温控器）
     * @param fromController 錨點是否為温控器（false = 管理器）
     */
    static boolean matches(Block anchor, boolean fromController) {
        int mx = fromController ? 1 : 0; // 基準朝向的機器偏移（相對中層中心，z=+1 邊）
        int mz = 1;
        World w = anchor.getWorld();
        for (int r = 0; r < 4; r++) {
            int[] mo = rot(mx, mz, r);
            int cx = anchor.getX() - mo[0];
            int cz = anchor.getZ() - mo[1];
            int cy = anchor.getY() - 1; // 錨點位於中層
            if (checkRotation(w, cx, cy, cz, r)) return true;
        }
        return false;
    }

    /**
     * 結構完整時返回錨點機器的夥伴方塊（管理器↔温控器），否則 null。
     * 供管理器 ticker 定位温控器以協調雙機耗電。
     */
    static Block partner(Block anchor, boolean fromController) {
        int mx = fromController ? 1 : 0;
        int mz = 1;
        World w = anchor.getWorld();
        for (int r = 0; r < 4; r++) {
            int[] mo = rot(mx, mz, r);
            int cx = anchor.getX() - mo[0];
            int cz = anchor.getZ() - mo[1];
            int cy = anchor.getY() - 1;
            if (checkRotation(w, cx, cy, cz, r)) {
                int[] po = rot(fromController ? 0 : 1, fromController ? 1 : 1, r);
                return w.getBlockAt(cx + po[0], cy + 1, cz + po[1]);
            }
        }
        return null;
    }

    /** 按朝向 r 校驗以 (cx, cy, cz) 為中層中心的結構。 */
    private static boolean checkRotation(World w, int cx, int cy, int cz, int r) {
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    byte code = LAYOUT[y][z][x];
                    int[] rxz = rot(x - 1, z - 1, r);
                    Block bl = w.getBlockAt(cx + rxz[0], cy + y, cz + rxz[1]);
                    switch (code) {
                        case AIR:
                            if (!bl.isEmpty()) return false;
                            break;
                        case PLANKS:
                            if (bl.getType() != Material.OAK_PLANKS) return false;
                            break;
                        case LOG:
                            if (bl.getType() != Material.OAK_LOG) return false;
                            break;
                        case MANAGER:
                            if (!(BlockStorage.check(bl) instanceof WineCellarManager)) return false;
                            break;
                        case CONTROLLER:
                            if (!(BlockStorage.check(bl) instanceof TemperatureController)) return false;
                            break;
                        default:
                            return false;
                    }
                }
            }
        }
        return true;
    }

    /** (x, z) 繞中心旋轉 r×90°。 */
    private static int[] rot(int x, int z, int r) {
        return switch (r) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }
}
