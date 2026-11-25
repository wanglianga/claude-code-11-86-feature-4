package com.ccdev.transit.engine.feasibility;

/** 单个计划车次的仿真结果。 */
public enum TripOutcome {
    /** 准点（实际发车不晚于计划 + 晚点容忍）。 */
    ON_TIME,
    /** 晚点但未丢班。 */
    LATE,
    /** 车辆周转不过来，纸面车次被丢掉（缺班）。 */
    MISSED
}
