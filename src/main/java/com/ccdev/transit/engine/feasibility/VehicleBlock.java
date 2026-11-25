package com.ccdev.transit.engine.feasibility;

import java.time.LocalDate;
import java.util.List;

/**
 * 车辆块：一辆车在一个运营日内执行的连续车次链。
 * 一辆车的块可以跨司机换班点（shiftCodes 记录先后接手的班次），
 * 因此车辆周转约束与司机工时约束在同一条链上同时可审计。
 */
public record VehicleBlock(
        long id,
        String blockCode,
        String vehicleCode,
        String routeCode,
        LocalDate serviceDate,
        List<Long> tripIds,
        List<String> shiftCodes,
        long blockStartEpochSecond,
        long blockEndEpochSecond) {

    /** 司机换班次数（块内出现的不同班次数 - 1）。 */
    public int reliefCount() {
        long distinct = shiftCodes.stream().filter(s -> s != null && !s.isBlank()).distinct().count();
        return (int) Math.max(0, distinct - 1);
    }
}
