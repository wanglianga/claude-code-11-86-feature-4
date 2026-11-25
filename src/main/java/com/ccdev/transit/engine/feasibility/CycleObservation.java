package com.ccdev.transit.engine.feasibility;

import java.time.LocalDate;

/**
 * 同线路同方向一个完整历史行程的周期时间分解。
 * cycle = running + recovery + turnback（空驶 deadhead 单列，不计入周转周期）。
 * excluded=TRUE 的行（节庆留出/封路窗口）不参与常态分布拟合。
 */
public record CycleObservation(
        String routeCode,
        String direction,
        LocalDate serviceDate,
        int weekIndex,
        long tripId,
        Long blockId,
        int runningSeconds,
        int recoverySeconds,
        int turnbackSeconds,
        int deadheadSeconds,
        int cycleSeconds,
        boolean excluded,
        String excludeReason) {

    public static int cycleOf(int running, int recovery, int turnback) {
        return running + recovery + turnback;
    }
}
