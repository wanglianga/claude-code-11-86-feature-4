package com.ccdev.transit.engine.feasibility;

/**
 * AVL 行程分段类型。
 * RUNNING：站间运行时间；TERMINAL_RECOVERY：终点恢复（落客/整备/待客）；
 * TURNBACK：折返（换端/换向）；DEADHEAD：空驶（出场/入场/跨 terminal 调度）。
 */
public enum SegmentType {
    RUNNING,
    TERMINAL_RECOVERY,
    TURNBACK,
    DEADHEAD
}
