package com.ccdev.transit.engine.feasibility;

/**
 * 一个 AVL 分段的计划/实测秒数与非常态标记。
 * anomalyFlag=TRUE 表示封路/停运等事件窗口内观测，不得进入常态周期时间分布。
 */
public record AvlSegment(
        int segmentIndex,
        SegmentType type,
        String direction,
        int plannedSeconds,
        int observedSeconds,
        boolean anomalyFlag,
        String anomalyReason) {

    public int overrunSeconds() {
        return Math.max(0, observedSeconds - plannedSeconds);
    }
}
