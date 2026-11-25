package com.ccdev.transit.engine.feasibility;

import java.time.LocalDate;

/**
 * 规则时刻表上的一个计划车次（纸面车次）。时间用 epoch 秒，引擎本身不依赖时区。
 */
public record ScheduledTrip(
        long id,
        String tripCode,
        String routeCode,
        String direction,
        LocalDate serviceDate,
        int weekIndex,
        int seqInDay,
        String periodBand,
        long scheduledEpochSecond,
        int headwaySeconds) {
}
