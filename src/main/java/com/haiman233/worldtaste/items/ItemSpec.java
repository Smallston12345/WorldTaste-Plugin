package com.haiman233.worldtaste.items;

import org.bukkit.configuration.ConfigurationSection;

/** 從物品段解析出的屬性集合（對齊 RSC ItemReader 讀取的字段）。 */
public class ItemSpec {

    public String id;
    public String script;
    public boolean placeable = false; // RSC 默認 unplaceable
    public boolean soulbound;
    public boolean antiWither;
    public String radiation;       // Radioactivity 枚舉名，null 表示無
    public Integer piglinChance;   // 0-100
    public Integer energyCapacity; // 電量容量
    public boolean vanilla;        // setUseableInWorkbench
    public String dropFrom;        // 方塊材質名
    public Integer dropChance;     // 0-100
    public int alcohol;            // 酒精度（聯動異域花園，0 表示非酒類）
    // 注：drop_amount 不在此緩存——ItemsLoader.register 直接用 parseAmountRange(s.getString("drop_amount")) 重解析
    // （支持 "1"/"1-3" 區間），故無需也無此字段（曾為死狀態，r50 移除）。

    public static ItemSpec from(String id, ConfigurationSection s) {
        ItemSpec spec = new ItemSpec();
        spec.id = id;
        spec.script = s.getString("script");
        if (spec.script != null) spec.script = spec.script.trim();
        spec.placeable = s.getBoolean("placeable", false);
        spec.soulbound = s.getBoolean("soulbound", false);
        spec.antiWither = s.getBoolean("anti_wither", false);
        spec.radiation = s.getString("radiation");
        if (s.isSet("piglin_trade_chance")) spec.piglinChance = s.getInt("piglin_trade_chance", 100);
        if (s.isSet("energy_capacity")) spec.energyCapacity = s.getInt("energy_capacity", 0);
        spec.vanilla = s.getBoolean("vanilla", false);
        spec.dropFrom = s.getString("drop_from");
        spec.dropChance = s.getInt("drop_chance", 100);
        spec.alcohol = s.getInt("alcohol", 0);
        return spec;
    }
}
