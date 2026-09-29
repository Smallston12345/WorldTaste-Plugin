package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.items.ConsumableItem;
import com.haiman233.worldtaste.items.CropBlock;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 種植與放置保護（統一走 {@link BlockPlaceEvent}，不依賴 Slimefun 內部放置流程）：
 * <ol>
 *   <li>所有作物種子（{@link CropBlock}）放置校驗：顯式 plantOn 或按材質推斷的種植要求
 *       （耕地/靈魂沙/叢林原木/末地石等原版機制），不滿足則取消放置並提示。
 *       方向性作物（COCOA 等需側面附着的）必須以側面附着方式放置且附着方塊符合要求，
 *       地面式放置（如點在原木頂面）無效。</li>
 *   <li>食物（{@link ConsumableItem}）：普通右鍵=食用（此時取消放置，避免"吃+放"雙消耗），
 *       潛行右鍵=放置——放置時登記進 {@link BlockStorage}，挖掘時 Slimefun 掉落帶粘液數據的物品
 *       （持久化於 Slimefun 數據庫，爆炸/活塞/水流等破壞路徑也由 Slimefun 統一處理）。</li>
 *   <li>其他 NotPlaceable 裝飾：直接允許放置並登記（不限制潛行）。</li>
 * </ol>
 */
public final class PlantGuardListener implements Listener {

    public static final PlantGuardListener INSTANCE = new PlantGuardListener();

    private PlantGuardListener() {}

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item == null || item.getType().isAir()) {
            // 幽靈放置：手持物品已被消費（如手持 1 個食物食用後主手清空），客户端仍聲明放置——
            // Bukkit 會按客户端聲明放置一個無 NBT 的原版頭顱；正常放置必然手持物品，直接取消
            e.setCancelled(true);
            return;
        }
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf == null) {
            // 兜底：無 id 展示物品（如未註冊物品的機器/多方塊產物）按外觀反查註冊物品
            sf = com.haiman233.worldtaste.util.Stacks.findRegisteredByAppearance(item);
        }
        if (sf == null) return;

        // 作物種子：種植要求校驗（全部種子類生效）
        if (sf instanceof CropBlock crop) {
            List<Material> allowed = crop.getPlantOn();
            if (allowed != null && !allowed.isEmpty()) {
                Block block = e.getBlock();
                boolean ok;
                if (crop.isDirectionalCrop()) {
                    // 方向性作物（如 COCOA，facing 須指向附着的原木）：必須以側面附着方式放置
                    // 且附着方塊符合 plantOn；地面式放置（如點在原木頂面）無法附着，直接拒絕，
                    // 否則種子頭轉換為作物方塊後會因附着失效被原版反覆破壞
                    ok = block.getBlockData() instanceof Directional dir
                            && allowed.contains(block.getRelative(dir.getFacing().getOppositeFace()).getType());
                } else {
                    // 地面作物：僅要求腳下方塊符合 plantOn（點在支撐側面放置的懸空種子無效）
                    ok = allowed.contains(block.getRelative(BlockFace.DOWN).getType());
                }
                if (!ok) {
                    e.setCancelled(true);
                    Player p = e.getPlayer();
                    if (p != null) p.sendMessage("該作物只能種植在 " + names(allowed) + " 上！");
                }
            }
            return;
        }

        // 不可放置類物品
        if (sf instanceof NotPlaceable) {
            // 食物（頭顱材質可放置方塊）：普通右鍵（非潛行）已被食用邏輯消費，
            // 取消放置避免"吃+放"雙消耗與頭顱殘留；潛行右鍵=明確放置意圖，放行並登記
            // Slimefun 方塊（挖掘保留粘液數據）
            if (sf instanceof ConsumableItem) {
                if (!e.getPlayer().isSneaking()) {
                    e.setCancelled(true);
                    return;
                }
                BlockStorage.store(e.getBlock(), sf.getId());
                return;
            }
            // 其他不可放置裝飾：允許放置並登記
            BlockStorage.store(e.getBlock(), sf.getId());
        }
    }

    private static String names(List<Material> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(com.haiman233.worldtaste.util.ZhNames.orEnglish(list.get(i)));
        }
        return sb.toString();
    }
}