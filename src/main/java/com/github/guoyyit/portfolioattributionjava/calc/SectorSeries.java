package com.github.guoyyit.portfolioattributionjava.calc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 估值序列。
 *
 * @param sectorValues   sectorId -> 每个日期轴位置上的行业市值（缺失位置为 0）
 * @param sectorPresence sectorId -> 每个日期轴位置上是否存在该行业的拆分记录；
 *                       行业市值为 0 是合法录入，存在性不能用市值是否为 0 判断
 */
public record SectorSeries(
        LocalDate[] dates,
        double[] totals,
        Map<String, double[]> sectorValues,
        List<CashFlowPoint> flows,
        Map<String, boolean[]> sectorPresence
) {

    /**
     * 便捷构造：默认每个行业在全部日期轴位置上都有拆分记录
     * （显式一次性计算与既有单元测试使用）。
     */
    public SectorSeries(LocalDate[] dates,
                        double[] totals,
                        Map<String, double[]> sectorValues,
                        List<CashFlowPoint> flows) {
        this(dates, totals, sectorValues, flows,
                fullPresence(dates, sectorValues));
    }

    private static Map<String, boolean[]> fullPresence(
            LocalDate[] dates, Map<String, double[]> sectorValues) {
        Map<String, boolean[]> presence = new LinkedHashMap<>();
        for (String sectorId : sectorValues.keySet()) {
            presence.put(sectorId, new boolean[dates == null ? 0 : dates.length]);
            boolean[] column = presence.get(sectorId);
            for (int i = 0; i < column.length; i++) {
                column[i] = true;
            }
        }
        return presence;
    }
}
