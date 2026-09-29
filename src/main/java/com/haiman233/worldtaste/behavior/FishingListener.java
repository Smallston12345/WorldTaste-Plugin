package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.load.Yaml;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 百味釣竿釣魚（對齊原 diaoyu.js + wt_fishing.js）：主手持指定釣竿、副手持已知魚餌時，
 * 取消原掉落、消耗 1 魚餌、按權重隨機產出 1 個物品並拉向玩家。
 */
public final class FishingListener implements Listener {

    public static final FishingListener INSTANCE = new FishingListener();

    private static String rodId = "WT_BAIWEIDIAOGAN";
    private static final Map<String, Bait> baits = new HashMap<>();

    private FishingListener() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "data/fishing.yml");
        rodId = y.getString("rod", rodId);
        ConfigurationSection bs = y.getConfigurationSection("baits");
        baits.clear();
        if (bs != null) {
            for (String bait : bs.getKeys(false)) {
                List<Drop> drops = new ArrayList<>();
                for (Map<?, ?> m : bs.getMapList(bait)) {
                    Object id = m.get("id");
                    Object w = m.get("weight");
                    if (id instanceof String && w instanceof Number) {
                        drops.add(new Drop((String) id, ((Number) w).intValue()));
                    }
                }
                baits.put(bait, new Bait(drops));
            }
        }
        int total = baits.values().stream().mapToInt(b -> b.drops.size()).sum();
        WT.plugin.getLogger().info("行為數據: fishing rod=" + rodId + " baits=" + baits.size() + " drops=" + total);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || e.getCaught() == null) return;
        Player p = e.getPlayer();
        SlimefunItem rod = SlimefunItem.getByItem(p.getInventory().getItemInMainHand());
        if (rod == null || !rod.getId().equals(rodId)) return;
        SlimefunItem bait = SlimefunItem.getByItem(p.getInventory().getItemInOffHand());
        if (bait == null) return;
        Bait table = baits.get(bait.getId());
        if (table == null) return;

        // 先選並解析掉落物；無法解析（如未裝對應附屬）時不取消事件、不扣餌、保留原漁獲
        Drop d = select(table);
        if (d == null) return;
        ItemStack stack = resolve(d.id);
        if (stack == null) return;

        e.setCancelled(true);
        // 魚餌耗盡到 0 必須清空副手槽位：否則殘留 0 數量幽靈物品仍被識別為該魚餌，
        // 玩家可無消耗無限釣獲（複製漏洞）。
        Stacks.consumeOneInOffHand(p.getInventory());
        e.getCaught().remove();
        // 關鍵：取消 CAUGHT_FISH 後必須徹底移除魚鈎實體——
        // Paper 的 FishingHook.catchFish 在事件取消時不會自動收回鈎子：僅清"持有魚"狀態
        // 會讓鈎子殘留水中繼續 tick（浮漂不收回、舊鈎子持續觸發 CAUGHT_FISH），
        // 導致"連續收杆拋竿可多次收穫"；remove() 後玩家下次右鍵會自動復位 fishing 引用並拋新竿。
        e.getHook().remove();

        stack.setAmount(1);
        // 產物優先進揹包；揹包滿時以掉落物形態掉落在玩家位置
        java.util.Map<Integer, ItemStack> leftover = p.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            Item ent = p.getWorld().dropItem(p.getLocation().add(0, 1, 0), leftover.get(0));
            ent.setPickupDelay(2);
        }
        p.sendMessage("§b恭喜你釣到了 " + displayName(stack) + " §b*1");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    /** 按權重隨機選 1 個掉落。total 在 load 期預算（消除每次釣獲的求和）。 */
    private static Drop select(Bait table) {
        if (table.total <= 0) return null;
        double r = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * table.total;
        for (Drop d : table.drops) {
            r -= d.weight;
            if (r <= 0) return d;
        }
        return table.drops.get(table.drops.size() - 1);
    }

    private static ItemStack resolve(String id) {
        SlimefunItem sf = SlimefunItem.getById(id);
        if (sf != null) return sf.getItem().clone();
        Material m = Material.matchMaterial(id);
        return m == null ? null : new ItemStack(m);
    }

    private static String displayName(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return stack.getItemMeta().getDisplayName();
        }
        return com.haiman233.worldtaste.util.ZhNames.orEnglish(stack.getType());
    }

    private static final class Drop {
        final String id;
        final int weight;
        Drop(String id, int weight) { this.id = id; this.weight = weight; }
    }

    /** 一個魚餌的掉落表 + 預算權重總和（load 期一次計算，select 直接用，消除每次釣獲的求和）。 */
    private static final class Bait {
        final List<Drop> drops;
        final int total;
        Bait(List<Drop> drops) {
            this.drops = drops;
            int t = 0;
            for (Drop d : drops) t += d.weight;
            this.total = t;
        }
    }
}
