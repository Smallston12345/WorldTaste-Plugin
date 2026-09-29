package com.haiman233.worldtaste.util;

import java.lang.reflect.Method;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * {@code InventoryView} 跨版本工具（MC 1.21 起由抽象類改為接口）。
 *
 * <p>對 view 直接調用方法在另一側運行時會拋 {@link IncompatibleClassChangeError}
 * （編譯期按接口生成 invokeinterface、老服務端卻是抽象類，反之亦然），因此對 view 的
 * 方法調用一律經反射完成。Method 從公共的 {@link InventoryView} 類型解析，
 * 兩種版本形態下均可正常調用並緩存複用。</p>
 */
public final class Views {

    private static volatile Method topMethod;

    private Views() {}

    /**
     * 反射獲取 view 的頂部容器。
     *
     * @param view {@code Player#getOpenInventory()} / {@code openAnvil} 等返回的 view 對象
     * @return 頂部容器；view 為 null 或反射失敗時返回 null
     */
    public static Inventory top(Object view) {
        if (view == null) return null;
        try {
            Method m = topMethod;
            if (m == null) {
                m = InventoryView.class.getMethod("getTopInventory");
                topMethod = m;
            }
            return (Inventory) m.invoke(view);
        } catch (Throwable t) {
            return null;
        }
    }
}
