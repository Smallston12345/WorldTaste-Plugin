package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.load.Yaml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 行為數據註冊表：加載生成器產出的 data/*.yml（consumables/crops/fishing），
 * 供 {@link com.haiman233.worldtaste.items.ScriptItemFactory} 在註冊物品時查詢腳本對應的行為。
 */
public final class Behaviors {

    private Behaviors() {}

    public static final Map<String, ConsumableOpts> consumables = new HashMap<>();
    public static final Map<String, CropCfg> crops = new HashMap<>();
    /** foods.yml 帶 onEat 腳本(kind=eat)的食物：itemId -> opts */
    public static final Map<String, ConsumableOpts> foodOnEat = new HashMap<>();

    /** 讀取數據文件（須在物品註冊前調用）。 */
    public static void loadData() {
        loadConsumables();
        loadCrops();
        FishingListener.load();
    }

    /** 註冊 Bukkit 事件監聽器（須在所有物品註冊後調用）。 */
    public static void registerListeners() {
        org.bukkit.Bukkit.getPluginManager().registerEvents(FishingListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(CropListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(MobDropListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(BlockDrops.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(FoodConsumeListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(PlantGuardListener.INSTANCE, WT.plugin);
        // 動物奶桶擠取（空桶右鍵駱駝/馬/羊/驢）
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                com.haiman233.worldtaste.items.MilkBucketListener.INSTANCE, WT.plugin);
        // 釀造工藝總開關（config.yml brewing.enabled）：關閉時不掛載榨汁盆/酒窖相關監聽
        if (com.haiman233.worldtaste.load.WTConfig.brewingEnabled) {
            // 榨汁盆（跳躍踩踏/鐵砧砸落進度 + 容器領取）
            org.bukkit.Bukkit.getPluginManager().registerEvents(
                    com.haiman233.worldtaste.machines.JuicerBasin.Listener.INSTANCE, WT.plugin);
            // 榨汁盆指南入口注入（點擊榨汁盆配方頁注入配方展示按鈕，不依賴 JEG）
            com.haiman233.worldtaste.guide.JuicerGuideListener.register();
            // 酒窖指南入口（JEG 攔截 / 原版注入雙模式，點擊酒窖管理器進入配方展示頁）
            com.haiman233.worldtaste.guide.CellarGuideListener.register();
            // 酒窖管理器機器頁面（實時刷新任務 + 關閉返還監聽）
            com.haiman233.worldtaste.machines.CellarMenu.register();
        }
        // 紫頌作物定時狀態的區塊卸載兜底清理（普通作物零狀態，無需清理）
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                com.haiman233.worldtaste.items.CropBlock.ChorusStateCleanup.INSTANCE, WT.plugin);
        // JEG 大配方菜單（JEG 未安裝時靜默跳過）
        com.haiman233.worldtaste.jeg.JegGuideListener.register();
    }

    private static void loadConsumables() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "data/consumables.yml");
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            ConsumableOpts o = new ConsumableOpts();
            o.use = !"eat".equalsIgnoreCase(s.getString("kind", "use"));
            if (s.isSet("food")) o.food = s.getDouble("food");
            if (s.isSet("saturation")) o.saturation = s.getDouble("saturation");
            if (s.isSet("exhaustion")) o.exhaustion = s.getDouble("exhaustion");
            if (s.isSet("exhaustionSet")) o.exhaustionSet = s.getDouble("exhaustionSet");
            if (s.isSet("absorption")) o.absorption = s.getDouble("absorption");
            o.gameMode = s.getString("gameMode");
            if (s.isSet("remainingAirAdd")) o.remainingAirAdd = s.getInt("remainingAirAdd");
            if (s.isSet("foodSet")) o.foodSet = s.getInt("foodSet");
            if (s.isSet("saturationSet")) o.saturationSet = (float) s.getDouble("saturationSet");
            o.requireHungry = s.getBoolean("requireHungry", false);
            if (s.isSet("satRegen")) o.satRegen = s.getInt("satRegen");
            if (s.isSet("unsatRegen")) o.unsatRegen = s.getInt("unsatRegen");
            if (s.isSet("starvation")) o.starvation = s.getInt("starvation");
            if (s.isSet("maxAir")) o.maxAir = s.getInt("maxAir");
            if (s.isSet("remainingAir")) o.remainingAir = s.getInt("remainingAir");
            if (s.isSet("freezeTicks")) o.freezeTicks = s.getInt("freezeTicks");
            if (s.isSet("randomFood")) o.randomFood = s.getInt("randomFood");
            String offTool = s.getString("offhandTool");
            if (offTool != null && !offTool.isEmpty()) {
                o.offhandTool = Material.matchMaterial(offTool.trim().toUpperCase(java.util.Locale.ROOT));
            }
            o.consumeOffhand = s.getBoolean("consumeOffhand", false);
            if (s.isList("potions")) {
                for (Map<?, ?> pm : s.getMapList("potions")) {
                    Object t = pm.get("type");
                    Object d = pm.get("duration");
                    Object a = pm.get("amplifier");
                    if (t != null && d instanceof Number && a instanceof Number) {
                        o.potions.add(new Potion(t.toString(), ((Number) d).intValue(), ((Number) a).intValue()));
                    }
                }
            }
            o.message = s.getString("message");
            consumables.put(name, o);
        }
        WT.plugin.getLogger().info("行為數據: consumables=" + consumables.size());
    }

    private static void loadCrops() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "data/crops.yml");
        int skip = 0;
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            try {
                CropCfg c = new CropCfg();
                Material m = Material.matchMaterial(s.getString("material", "WHEAT"));
                c.material = m != null ? m : Material.WHEAT;
                c.maxAge = s.getInt("maxAge", 7);
                c.growMs = s.getLong("growMs", 120000L);
                if (s.isList("drops")) {
                    for (Map<?, ?> mm : s.getMapList("drops")) {
                        // 顯式校驗類型：缺 chance 或非數值曾導致 NPE/CCE 逃出 loadData、
                        // 連累其後的全部加載(items/foods/machines…)被 onEnable 頂層 catch 跳過。
                        Object id = mm.get("id");
                        Object ch = mm.get("chance");
                        if (id instanceof String && ch instanceof Number) {
                            c.drops.add(new CropDrop((String) id, ((Number) ch).doubleValue(), 0));
                        } else {
                            WT.log("crop " + name + " 的 drops 項缺少 id/chance，跳過該項");
                        }
                    }
                } else if (s.isList("weightedDrops")) {
                    for (Map<?, ?> mm : s.getMapList("weightedDrops")) {
                        Object id = mm.get("id");
                        Object w = mm.get("weight");
                        if (id instanceof String && w instanceof Number) {
                            double wv = ((Number) w).doubleValue();
                            c.drops.add(new CropDrop((String) id, 0, wv));
                            c.weightTotal += wv; // R8: load 期預算權重總和（對齊 R4）
                        } else {
                            WT.log("crop " + name + " 的 weightedDrops 項缺少 id/weight，跳過該項");
                        }
                    }
                    c.weighted = true;
                }
                // 可選種植要求：plantOn（單個材質名或列表），無效項跳過；空列表視為不限制
                if (s.isList("plantOn")) {
                    List<Material> list = new ArrayList<>();
                    for (String mat : s.getStringList("plantOn")) {
                        Material pm = Material.matchMaterial(mat);
                        if (pm != null) list.add(pm);
                    }
                    if (!list.isEmpty()) c.plantOn = list;
                } else if (s.isString("plantOn")) {
                    Material pm = Material.matchMaterial(s.getString("plantOn"));
                    if (pm != null) { c.plantOn = new ArrayList<>(); c.plantOn.add(pm); }
                }
                // 種子掉落配置：cropId 為該作物的種子物品 id（crops.yml 全量覆蓋）；
                // 未成熟破壞必掉 1 個種子；成熟破壞額外按 seedDropChance 概率掉 1..seedDropMax 個種子。
                c.cropId = s.getString("cropId");
                c.seedDropChance = s.getDouble("seedDropChance", 0.5);
                c.seedDropMax = Math.max(1, s.getInt("seedDropMax", 3));
                crops.put(name, c);
            } catch (Exception e) {
                WT.log("crop " + name + " 解析失敗，跳過: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("行為數據: crops=" + crops.size() + (skip > 0 ? ", 跳過 " + skip : ""));
    }

    /** 食物消耗參數（對應原 WT_eatConsumable opts，並擴展覆蓋獨立腳本的空氣/凍結/藥水等）。 */
    public static final class ConsumableOpts {
        public boolean use = true;
        public Double food;
        public Double saturation;
        public Double exhaustion;
        public Double exhaustionSet;
        public Double absorption;
        public String gameMode;
        public Integer remainingAirAdd;
        public Integer foodSet;
        public Float saturationSet;
        public boolean requireHungry;
        public Integer satRegen;
        public Integer unsatRegen;
        public Integer starvation;
        public Integer maxAir;
        public Integer remainingAir;
        public Integer freezeTicks;
        public Integer randomFood;
        public Material offhandTool;
        public boolean consumeOffhand;
        public final List<Potion> potions = new ArrayList<>();
        public String message;
    }

    public static final class Potion {
        public final String type;
        public final int duration;
        public final int amplifier;
        public Potion(String type, int duration, int amplifier) {
            this.type = type; this.duration = duration; this.amplifier = amplifier;
        }
    }

    /** 按作物材質推斷默認種植要求（原版機制）。顯式 plantOn 配置優先於推斷。 */
    static List<Material> inferPlantOn(Material m) {
        switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, MELON_STEM, PUMPKIN_STEM, PITCHER_CROP, TORCHFLOWER_CROP:
                return List.of(Material.FARMLAND);
            case NETHER_WART:
                return List.of(Material.SOUL_SAND, Material.SOUL_SOIL);
            case SUGAR_CANE:
                return List.of(Material.SAND, Material.RED_SAND, Material.DIRT, Material.GRASS_BLOCK);
            case CACTUS:
                return List.of(Material.SAND, Material.RED_SAND);
            case COCOA:
                return List.of(Material.JUNGLE_LOG, Material.JUNGLE_WOOD, Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_JUNGLE_WOOD);
            case SWEET_BERRY_BUSH:
                return List.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT, Material.MUD, Material.FARMLAND);
            default:
                return null;
        }
    }

    /** 作物參數（對應原 WT_setupCrop cfg）。 */
    public static final class CropCfg {
        public Material material;
        public int maxAge;
        public long growMs;
        public String stages;
        public final List<CropDrop> drops = new ArrayList<>();
        public boolean weighted = false;
        /** 種植要求：允許種植在其上的方塊列表；null 表示不限制（對應 crops.yml 可選 plantOn 字段）。 */
        public List<Material> plantOn;
        /** 種子物品 id（crops.yml cropId）：未成熟破壞必掉 1 個；成熟破壞按概率額外掉 1..seedDropMax 個。 */
        public String cropId;
        /** 成熟破壞時額外種子掉落的概率（默認 0.5；crops.yml seedDropChance 可調）。 */
        public double seedDropChance = 0.5;
        /** 成熟破壞時額外種子掉落的最大數量（1..seedDropMax 均勻隨機，默認 3；crops.yml seedDropMax 可調）。 */
        public int seedDropMax = 3;

        /** 實際種植要求：顯式 plantOn 優先，否則按材質推斷（原版機制）；null 表示不限制。 */
        public List<Material> resolvedPlantOn() {
            if (plantOn != null) return plantOn;
            return Behaviors.inferPlantOn(material);
        }
        /** 加權掉落的權重總和，load 期一次預算（對齊 R4 FishingListener.Bait.total）。
         *  CropBlock.onBreak 直接用此值，消除每次收穫對 drops 的求和（O(n)→O(1)）。僅 weighted 作物有意義。 */
        public double weightTotal = 0;
    }

    public static final class CropDrop {
        public final String id;
        public final double chance;
        public final double weight;
        public CropDrop(String id, double chance, double weight) {
            this.id = id; this.chance = chance; this.weight = weight;
        }
    }
}
