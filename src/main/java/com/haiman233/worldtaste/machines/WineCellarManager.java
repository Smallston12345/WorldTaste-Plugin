package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
import java.util.Map;

/**
 * 酒窖管理器（machines.yml 中 script: jiujiao，材質釀造台）：酒窖多方塊核心。
 *
 * <p>儲電 500J，耗電 100J/t（僅多方塊結構完整且蓄電充足時自 charge 扣除，
 * 電網會自動向其補電）。右鍵經 {@link BlockMenuPreset} 原生打開機器頁面
 * （{@link CellarMenu}，結構與本體機器一致；結構不完整時頁面內可查看多方塊結構展示）。
 * 運行中雙機（管理器/温控器）任一蓄電不足 → 釀造/陳化直接失敗產生廢液。</p>
 *
 * <p>耗電量經 {@link PowerConsumer} 暴露，註冊時自動寫入物品 lore。</p>
 */
public class WineCellarManager extends SlimefunItem implements EnergyNetComponent, PowerConsumer {

    public static final int CAPACITY = 500;
    public static final int CONSUMPTION = 100;

    /** 結構校驗緩存：partner() 一次調用替代逐 tick 的 matches()+partner() 雙重 3×3×3 掃描。 */
    private record StructureCheck(long expireAtTick, Block partner, TemperatureController ctrl) {}

    private static final Map<World, Map<Long, StructureCheck>> CHECKS = new HashMap<>();

    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }

    /** 結構被破壞時失效該位置的校驗緩存（防止緩存窗口內向已拆除方塊寫電）。 */
    public static void invalidateStructureCheck(Block b) {
        Map<Long, StructureCheck> m = CHECKS.get(b.getWorld());
        if (m != null) m.remove(key(b));
    }

    /** 緩存式結構校驗：結構完整緩存 20 tick，不完整緩存 10 tick（補建後儘快恢復計時）。 */
    private static StructureCheck structureCheck(Block b) {
        World w = b.getWorld();
        long now = w.getGameTime();
        Map<Long, StructureCheck> m = CHECKS.get(w);
        StructureCheck v = m == null ? null : m.get(key(b));
        if (v == null || v.expireAtTick() <= now) {
            Block partner = CellarStructure.partner(b, false);
            TemperatureController ctrl = partner != null
                    && me.mrCookieSlime.Slimefun.api.BlockStorage.check(partner) instanceof TemperatureController c
                    ? c : null;
            v = new StructureCheck(now + (ctrl != null ? 20 : 10), ctrl != null ? partner : null, ctrl);
            CHECKS.computeIfAbsent(w, x -> new HashMap<>()).put(key(b), v);
        }
        return v;
    }

    public WineCellarManager(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                             org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);

        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }

            @Override
            public void tick(Block b, SlimefunItem sf, Config data) {
                WineCellarState st = WineCellarState.get(b);
                if (st.phase() != WineCellarState.Phase.RUNNING) return;
                StructureCheck check = structureCheck(b);
                if (check.ctrl() == null) return;
                Block partner = check.partner();
                TemperatureController ctrl = check.ctrl();
                // 斷電：釀造/陳化直接失敗，液體報廢（結構被拆仍為暫停，破壞事件本身會清數據）
                if (getCharge(b.getLocation()) < CONSUMPTION
                        || ctrl.getCharge(partner.getLocation())
                                < TemperatureController.CONSUMPTION) {
                    boolean brew = st.mode() == WineCellarState.Mode.BREW;
                    st.contaminate();
                    st.save(b);
                    b.getWorld().playSound(b.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                    Player placer = st.placerId() != null ? Bukkit.getPlayer(st.placerId()) : null;
                    if (placer != null) {
                        placer.sendMessage("§c酒窖電力中斷，" + (brew ? "釀造" : "陳化")
                                + "失敗，液體已全部報廢！");
                    }
                    return;
                }
                // 雙機電力協調：本輪從管理器與温控器各扣除消耗
                removeCharge(b.getLocation(), CONSUMPTION);
                ctrl.removeCharge(partner.getLocation(), TemperatureController.CONSUMPTION);
                CellarMenu.advance(st, b);
            }
        });

        // 機器頁面（粘液原生 BlockMenuPreset：右鍵打開、界面內容隨方塊持久化、多玩家共享查看）。
        // 本分支 BlockMenuPreset 構造即自注冊進註冊表（不經過 addItemHandler）
        new BlockMenuPreset(item.getItemId(), ChatColor.GOLD + "酒窖管理器") {
            @Override
            public void init() {
                CellarMenu.setupMenu(this);
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                return true;
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return new int[0];
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                CellarMenu.onNewInstance(menu, b);
            }
        };
    }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return CAPACITY;
    }

    @Override
    public int getConsumption() {
        return CONSUMPTION;
    }
}
