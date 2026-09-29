package com.haiman233.worldtaste.util;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

/**
 * 原版材質中文名錶：覆蓋玩家提示裏實際會出現的材質集合（作物種植要求、副手工具、
 * 釣魚產出兜底名等）。未收錄的材質回退為小寫枚舉名（與舊行為一致），不拋異常。
 *
 * <p>不用 Component.translatable 的原因：這些提示是純文本 legacy 消息（聊天欄/動作欄
 * 混用顏色碼），本服面向中文玩家，固定中文名即可；且 fishing.yml 等數據可隨時引用
 * 新材質，表外的回退保證不缺字。</p>
 */
public final class ZhNames {

    private ZhNames() {}

    private static final Map<String, String> MAP = new HashMap<>();

    private static void put(String key, String zh) {
        MAP.put(key, zh);
    }

    static {
        // 作物種植要求（Behaviors.inferPlantOn + crops.yml plantOn）
        put("FARMLAND", "耕地");
        put("SOUL_SAND", "靈魂沙");
        put("SOUL_SOIL", "靈魂土");
        put("SAND", "沙子");
        put("RED_SAND", "紅沙");
        put("DIRT", "泥土");
        put("GRASS_BLOCK", "草方塊");
        put("COARSE_DIRT", "砂土");
        put("PODZOL", "灰化土");
        put("ROOTED_DIRT", "纏根泥土");
        put("MUD", "泥巴");
        put("JUNGLE_LOG", "叢林原木");
        put("JUNGLE_WOOD", "叢林木");
        put("STRIPPED_JUNGLE_LOG", "去皮叢林原木");
        put("STRIPPED_JUNGLE_WOOD", "去皮叢林木");
        put("END_STONE", "終界石");
        put("MYCELIUM", "菌絲體");
        put("NETHERRACK", "地獄石");
        put("CRIMSON_NYLIUM", "緋紅菌巖");
        put("WARPED_NYLIUM", "詭異菌巖");
        put("SANDSTONE", "砂岩");
        put("CLAY", "黏土塊");
        put("GRAVEL", "沙礫");
        put("SNOW_BLOCK", "雪塊");
        put("ICE", "冰");
        put("OBSIDIAN", "黑曜石");
        put("STONE", "石頭");
        put("DEEPSLATE", "深板岩");
        put("MOSS_BLOCK", "苔蘚塊");
        // 作物本體（提示擴展備用）
        put("WHEAT", "小麥");
        put("CARROTS", "胡蘿蔔");
        put("POTATOES", "馬鈴薯");
        put("BEETROOTS", "甜菜根");
        put("NETHER_WART", "地獄疙瘩");
        put("SUGAR_CANE", "甘蔗");
        put("CACTUS", "仙人掌");
        put("COCOA", "可可豆");
        put("SWEET_BERRY_BUSH", "甜漿果叢");
        put("BAMBOO", "竹子");
        // 消耗品副手工具（data/consumables.yml offhandTool）
        put("FLINT_AND_STEEL", "打火石");
        put("SHEARS", "剪刀");
        // 釣魚產出（data/fishing.yml 引用的原版物品）
        put("LILY_PAD", "睡蓮");
        put("TADPOLE_BUCKET", "蝌蚪桶");
        put("AXOLOTL_BUCKET", "美西螈桶");
        put("SALMON", "生鮭魚");
        put("COD", "生鱈魚");
        put("PUFFERFISH", "河豚");
        put("TROPICAL_FISH", "熱帶魚");
        put("SEAGRASS", "海草");
        put("SEA_PICKLE", "海泡菜");
        put("KELP", "海帶");
        put("HEART_OF_THE_SEA", "海洋之心");
        put("NAUTILUS_SHELL", "鸚鵡螺殼");
        put("STRING", "線");
        put("BONE", "骨頭");
        put("INK_SAC", "墨囊");
        put("SPIDER_EYE", "蜘蛛眼");
        put("PRISMARINE_SHARD", "海晶碎片");
        put("SADDLE", "鞍");
        put("LEATHER", "皮革");
        put("ROTTEN_FLESH", "腐肉");
        put("GUNPOWDER", "火藥");
        put("SLIME_BALL", "黏液球");
        put("EGG", "雞蛋");
        put("PAPER", "紙");
        put("BOOK", "書");
        put("EXPERIENCE_BOTTLE", "附魔之瓶");
        put("NAME_TAG", "命名牌");
    }

    /** 材質中文名；未收錄返回 null（調用方可回退舊英文格式）。 */
    public static String of(Material m) {
        if (m == null) return null;
        return MAP.get(m.name());
    }

    /** 材質中文名；未收錄回退為小寫枚舉名（下劃線轉空格，與舊行為一致）。 */
    public static String orEnglish(Material m) {
        String zh = of(m);
        return zh != null ? zh : m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
