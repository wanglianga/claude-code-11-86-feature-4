package com.ccdev.transit.engine.feasibility;

/**
 * 断点原因码。纸面间隔满足要求不等于可执行：下列任一约束在仿真中触发都会留痕。
 */
public final class BreakReasons {

    /** 给定车队数下无车可调（车辆周转不足，直接丢班）。 */
    public static final String FLEET_SHORTAGE = "FLEET_SHORTAGE";
    /** 分位数法：方向周期分位余量仍小于计划停站/折返窗口，级联晚点。 */
    public static final String CYCLE_BUFFER_SHORTFALL = "CYCLE_BUFFER_SHORTFALL";
    /** 重采样法：运行时间段超计划是本次晚点的最大贡献分量。 */
    public static final String RUNNING_OVERRUN = "RUNNING_OVERRUN";
    /** 重采样法：终点恢复时间不足是最大贡献分量。 */
    public static final String RECOVERY_OVERRUN = "RECOVERY_OVERRUN";
    /** 重采样法：折返时间不足是最大贡献分量（短线折返场景的典型断点）。 */
    public static final String TURNBACK_INSUFFICIENT = "TURNBACK_INSUFFICIENT";
    /** 车辆到场时没有在岗司机/换班窗口覆盖。 */
    public static final String DRIVER_RELIEF_GAP = "DRIVER_RELIEF_GAP";
    /** 出场空驶（deadhead）超出车场松弛，首班延误。 */
    public static final String DEADHEAD_PULLOUT_DELAY = "DEADHEAD_PULLOUT_DELAY";

    private BreakReasons() {
    }
}
