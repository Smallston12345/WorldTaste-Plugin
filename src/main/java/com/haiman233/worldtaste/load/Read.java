package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.skins.PlayerHead;
import io.github.thebusybiscuit.slimefun4.libraries.dough.skins.PlayerSkin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * 共享讀取器：把一個物品段（{@code material_type/material/name/lore/glow/amount}）解析為 {@link ItemStack}，
 * 以及把配方段（槽位 1..N）解析為 {@code ItemStack[]}。對應 RSC 的 {@code CommonUtils.readItem/readRecipe}。
 */
public final class Read {

    private Read() {}

    private static final Pattern HEX64 = Pattern.compile("^[0-9A-Fa-f]{64}$");

    /**
     * R7：頭顱貼圖（{@link PlayerSkin}）去重緩存，按類型分表（避免鍵拼接分配，且杜絕跨類型鍵碰撞）。
     * PlayerSkin 為不可變值，跨調用方共享安全；{@link PlayerHead#getItemStack} 每次仍新建獨立 ItemStack。
     * 實測全部內容文件 skull 解碼共 3203 次、其中僅 182 次（5.7%）為重複（如某些通用裝飾頭/配方槽複用），
     * 而 dough {@code PlayerSkin.fromHashCode/fromBase64/fromURL} 無內部緩存——每次含 MD5
     * （{@code UUID.nameUUIDFromBytes}）、JSON 拼接、Base64 編碼、URL 解析。去重省去這 182 次重複解碼。
     * 加載後由 {@link Setup#loadAll()} 調用 {@link #clearSkinCache()} 釋放（Read 僅加載期使用）。
     */
    private static final Map<String, PlayerSkin> HASH_SKINS = new HashMap<>();
    private static final Map<String, PlayerSkin> BASE64_SKINS = new HashMap<>();
    private static final Map<String, PlayerSkin> URL_SKINS = new HashMap<>();

    /** 讀取物品段。{@code countable=true} 時應用 amount。 */
    public static ItemStack item(ConfigurationSection s, boolean countable) {
        if (s == null) return null;
        String material = s.getString("material", "");
        if (material.isEmpty()) return null;

        String type = s.getString("material_type", "mc");
        String lower = material.toLowerCase(java.util.Locale.ROOT);
        // 自動識別覆蓋（與 RSC CommonUtils.readItem 一致，前綴判斷區分大小寫：base64 貼圖以小寫 ey 開頭）
        if (material.startsWith("ey") || material.startsWith("ew")) type = "skull";
        else if (lower.startsWith("http")) type = "skull_url";
        else if (HEX64.matcher(material).matches()) type = "skull_hash";

        ItemStack stack = resolve(type, material);
        if (stack == null) return null;

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = s.getString("name");
            if (name != null && !name.isEmpty()) meta.setDisplayName(Colors.c(name));
            List<String> lore = s.getStringList("lore");
            if (lore != null && !lore.isEmpty()) meta.setLore(Colors.c(lore));
            String color = s.getString("color");
            if (color != null && !color.isEmpty()) applyColor(meta, color);
            stack.setItemMeta(meta);
        }

        if (s.getBoolean("glow", false)) Stacks.glow(stack);
        if (countable) {
            int amt = s.getInt("amount", 1);
            if (amt > 0) stack.setAmount(Math.min(amt, stack.getMaxStackSize()));
        }
        return stack;
    }

    private static ItemStack resolve(String type, String material) {
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "none":
                return new ItemStack(Material.AIR);
            case "skull_hash": {
                PlayerSkin skin = HASH_SKINS.get(material);
                if (skin == null) { skin = PlayerSkin.fromHashCode(material); HASH_SKINS.put(material, skin); }
                return PlayerHead.getItemStack(skin);
            }
            case "skull":
            case "skull_base64": {
                PlayerSkin skin = BASE64_SKINS.get(material);
                if (skin == null) { skin = PlayerSkin.fromBase64(material); BASE64_SKINS.put(material, skin); }
                return PlayerHead.getItemStack(skin);
            }
            case "skull_url": {
                PlayerSkin skin = URL_SKINS.get(material);
                if (skin == null) { skin = PlayerSkin.fromURL(material); URL_SKINS.put(material, skin); }
                return PlayerHead.getItemStack(skin);
            }
            case "slimefun": {
                String id = material.toUpperCase(java.util.Locale.ROOT);
                // 優先使用已註冊物品（帶 slimefun id PDC）。
                SlimefunItem sf = SlimefunItem.getById(id);
                if (sf != null) return sf.getItem().clone();
                ItemStack pre = WT.preload.get(id);
                if (pre != null) {
                    // 前向引用（目標物品此刻尚未註冊，如 items.yml/foods.yml 引用 mob_drops.yml
                    // 的掉落物）：用 SlimefunItemStack 包一層補上 id PDC。裸展示堆缺 id 會導致：
                    // ① 指南配方頁點擊該材料 SlimefunItem.getByItem 查不到 → 不能跳轉到對應
                    // 材料頁；② 機器匹配 isItemSimilar 的兩端 id 比較失敗；③ 作為配方產物發放
                    // 是"有樣子無功能"的非粘液物品（多方塊機器輸出 bug）。補 id 後與註冊後的堆
                    // 按 id 等價（meta 細節差異不影響 id 比較與指南導航）。
                    return new SlimefunItemStack(id, pre);
                }
                // 未找到：摺疊上報（特殊物品立即告警，普通物品計數、加載結束彙總為一行），
                // 不再逐條刷屏——未裝對應附屬時此類引用可達數千處
                MissingItems.record(id);
                return new ItemStack(Material.STONE);
            }
            default: {
                Material m = matchMaterial(material);
                if (m == null) {
                    MissingItems.record("材質 " + material);
                    return new ItemStack(Material.STONE);
                }
                return new ItemStack(m);
            }
        }
    }

    private static Material matchMaterial(String name) {
        if (name == null) return null;
        Material m = Material.matchMaterial(name);
        if (m != null) return m;
        // 版本感知別名：材質改名/新增時，缺哪邊就用哪邊的等價物（老版本近似替代僅影響展示），
        // 別名目標也找不到時返回 null，由調用方按缺失物品處理（STONE 兜底 + 摺疊上報）
        switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "GRASS": return Material.matchMaterial("SHORT_GRASS");           // 1.20.3 改名
            case "SHORT_GRASS": return Material.matchMaterial("GRASS");           // <1.20.3 舊名
            case "SCUTE": return Material.matchMaterial("TURTLE_SCUTE");          // 1.20.5 改名
            case "TURTLE_SCUTE": return Material.matchMaterial("SCUTE");          // <1.20.5 舊名
            case "ARMADILLO_SCUTE": return Material.matchMaterial("RABBIT_HIDE"); // 1.20.5 新增，老版近似
            case "BREEZE_ROD": return Material.matchMaterial("BLAZE_ROD");        // 1.21 新增，老版近似
            case "WIND_CHARGE": return Material.matchMaterial("SNOWBALL");        // 1.21 新增，老版近似
            case "OMINOUS_BOTTLE": return Material.matchMaterial("GLASS_BOTTLE"); // 1.21 新增，老版近似
            case "CHAIN":
            case "IRON_CHAIN": {  // 1.21.4 改名：CHAIN→IRON_CHAIN，雙向兜底（內容寫哪個都行）
                Material chain = Material.matchMaterial("IRON_CHAIN");
                if (chain != null) return chain;
                return Material.matchMaterial("CHAIN");
            }
            default: return Material.matchMaterial(name.replace('-', '_'));
        }
    }

    /** 加載完成後釋放頭顱貼圖緩存（{@link Setup#loadAll()} 末尾調用；Read 僅加載期使用）。 */
    public static void clearSkinCache() {
        HASH_SKINS.clear();
        BASE64_SKINS.clear();
        URL_SKINS.clear();
    }

    /** 讀取配方段，槽位鍵 "1".."size"，產出長度為 size 的數組（空槽為 null）。 */
    public static ItemStack[] recipe(ConfigurationSection recipeSec, int size) {
        ItemStack[] out = new ItemStack[size];
        if (recipeSec == null) return out;
        for (int i = 0; i < size; i++) {
            ConfigurationSection slot = recipeSec.getConfigurationSection(String.valueOf(i + 1));
            if (slot != null) out[i] = item(slot, true);
        }
        return out;
    }

    /** 應用 "R,G,B" 顏色到皮革護甲/藥水等可染色 meta。 */
    private static void applyColor(ItemMeta meta, String color) {
        String[] rgb = color.split(",");
        if (rgb.length != 3) return;
        try {
            int r = Integer.parseInt(rgb[0].trim());
            int g = Integer.parseInt(rgb[1].trim());
            int b = Integer.parseInt(rgb[2].trim());
            Color c = Color.fromRGB(r, g, b);
            if (meta instanceof LeatherArmorMeta) ((LeatherArmorMeta) meta).setColor(c);
            else if (meta instanceof PotionMeta) ((PotionMeta) meta).setColor(c);
        } catch (RuntimeException ignored) {
            WT.log("顏色格式錯誤: " + color);
        }
    }
}
