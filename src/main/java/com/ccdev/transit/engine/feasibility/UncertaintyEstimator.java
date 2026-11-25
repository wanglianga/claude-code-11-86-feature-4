package com.ccdev.transit.engine.feasibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不确定性估计器：从同线路同方向的历史行程构造周期时间分布。
 *
 * <p>硬约束：{@code excluded=TRUE} 的观测（节庆留出周、封路/停运窗口）一律不进入常态分布，
 * 临时封路造成的极端周期不能被当成常态恢复余量。</p>
 *
 * <p>提供两条估计路线所需的分布输入：</p>
 * <ul>
 *   <li>{@link #quantileAllowance}：历史周期时间分位数直接配置恢复余量（易审计，
 *       但多个极端路段的相关延误无法表达——分位数按方向独立取，跨路段相关性被边际化）；</li>
 *   <li>{@link #dayVectors}：按车辆块/运营日组织的整日周期序列，供整日重采样模拟，
 *       保留同一运营日内各路段拥堵相关性，但依赖代表性运行日。</li>
 * </ul>
 */
public final class UncertaintyEstimator {

    /** key = route|direction。 */
    private final Map<String, List<CycleObservation>> normal = new LinkedHashMap<>();
    private final Map<String, List<CycleObservation>> excluded = new LinkedHashMap<>();

    public UncertaintyEstimator(List<CycleObservation> observations) {
        for (CycleObservation o : observations) {
            String key = key(o.routeCode(), o.direction());
            (o.excluded() ? excluded : normal).computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
    }

    public static String key(String route, String direction) {
        return route + "|" + direction;
    }

    /** 常态周期时间经验分布（已剔除留出周与异常窗口）。 */
    public EmpiricalDistribution distribution(String route, String direction) {
        List<CycleObservation> rows = normal.getOrDefault(key(route, direction), List.of());
        if (rows.isEmpty()) {
            throw new IllegalStateException("线路 " + route + " 方向 " + direction
                    + " 没有可用于常态分布的历史周期观测（可能全部被作为异常留出）");
        }
        List<Double> cycles = rows.stream().map(o -> (double) o.cycleSeconds()).toList();
        return EmpiricalDistribution.of(cycles);
    }

    /**
     * 分位数法恢复余量：取常态分布的 q 分位数周期作为计划周转时间，
     * 返回的余量 = 分位周期 - 名义计划周期（plannedCycle）。
     * 审计字段同时返回分位数值与样本量，余量配置可逐方向复核。
     */
    public QuantileAllowance quantileAllowance(String route, String direction,
                                               int plannedCycle, double q) {
        EmpiricalDistribution dist = distribution(route, direction);
        double quantileCycle = dist.quantile(q);
        int allowance = (int) Math.round(quantileCycle - plannedCycle);
        return new QuantileAllowance(route, direction, q, plannedCycle,
                (int) Math.round(quantileCycle), allowance, dist.n(), dist.mean(), dist.sd());
    }

    public record QuantileAllowance(
            String routeCode,
            String direction,
            double quantile,
            int plannedCycleSeconds,
            int quantileCycleSeconds,
            int recoveryAllowanceSeconds,
            int sampleSize,
            double sampleMean,
            double sampleSd) {
    }

    /**
     * 按运营日组织的整日周期序列（重采样法的最小单位是“运行日”而非单个行程），
     * 保留同日各路段/方向之间的拥堵相关性。仅含常态周（excluded 的日期整天剔除）。
     *
     * @return key=route|serviceDate 的周期序列（按计划顺序），value 中每个元素带方向
     */
    public Map<String, DayVector> dayVectors(String route) {
        Map<String, DayVector> byDay = new LinkedHashMap<>();
        for (List<CycleObservation> rows : normal.values()) {
            for (CycleObservation o : rows) {
                if (!o.routeCode().equals(route)) {
                    continue;
                }
                String dk = key(route, o.serviceDate().toString());
                DayVector dv = byDay.computeIfAbsent(dk,
                        k -> new DayVector(route, o.serviceDate(), o.weekIndex(), new ArrayList<>()));
                dv.observations().add(o);
            }
        }
        for (DayVector dv : byDay.values()) {
            dv.observations().sort((a, b) -> Long.compare(a.tripId(), b.tripId()));
        }
        return byDay;
    }

    /** 一个代表性运行日：该线路当天全部常态周期观测（保持车次顺序，含方向交替）。 */
    public record DayVector(String routeCode, java.time.LocalDate serviceDate, int weekIndex,
                            List<CycleObservation> observations) {
    }

    /** 被剔除的观测明细（审计：封路时段为何不进常态分布）。 */
    public List<CycleObservation> excludedObservations(String route) {
        List<CycleObservation> out = new ArrayList<>();
        for (List<CycleObservation> rows : excluded.values()) {
            for (CycleObservation o : rows) {
                if (o.routeCode().equals(route)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    public int normalSampleCount(String route, String direction) {
        return normal.getOrDefault(key(route, direction), List.of()).size();
    }

    public int excludedSampleCount(String route) {
        return excludedObservations(route).size();
    }
}
