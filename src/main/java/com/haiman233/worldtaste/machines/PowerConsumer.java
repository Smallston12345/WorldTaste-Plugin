package com.haiman233.worldtaste.machines;

/**
 * 聲明「運行時每刻耗電量」的機器。
 *
 * <p>Slimefun 的 {@link io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent}
 * 只暴露儲電容量（{@code getCapacity()}），並沒有耗電量接口。實現本接口後，
 * {@code ItemsLoader} 在註冊時會自動把「儲電容量 / 運行時耗電」兩行寫進物品 lore——
 * 數值直接取自機器類的常量，改 Java 常量即自動同步，不需要在 yml 裏再維護一份。</p>
 */
public interface PowerConsumer {

    /**
     * 運行時每刻耗電量（J/刻）。
     *
     * @return 耗電量；返回 0 表示不在 lore 中展示耗電行（只展示儲電容量）
     */
    int getConsumption();
}
