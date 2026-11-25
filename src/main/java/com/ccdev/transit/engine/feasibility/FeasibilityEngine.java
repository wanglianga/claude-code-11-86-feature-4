package com.ccdev.transit.engine.feasibility;

import com.ccdev.transit.engine.Deterministic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给定车队数下的规则时刻表可执行性仿真与报告。
 *
 * <p>仿真按运营日独立进行：每辆车维护“最早可再次发车时刻”，按纸面时刻顺序贪心分配；
 * 无车可在晚点容忍内接车即记 {@link TripOutcome#MISSED}（缺班），并在断点账中留痕。
 * 纸面车次齐全但仿真出现缺班时，{@code feasible} 强制为 FALSE——不得把纸面时刻表宣称可执行。</p>
 *
 * <p>两条估计路线：</p>
 * <ul>
 *   <li>{@code QUANTILE}：同线路同方向常态周期时间分布的分位数直接作为周转/恢复余量，
 *       逐方向独立取值，易审计，但无法表达多个极端路段在同一运营日的相关延误；</li>
 *   <li>{@code BLOCK_RESAMPLE}：以“运行日”为最小重采样单位，整日周期序列整体重放，
 *       保留同日路段拥堵相关性，代价是结论依赖代表性运行日。</li>
 * </ul>
 */
public final class FeasibilityEngine {

    public static final String QUANTILE = "QUANTILE";
    public static final String BLOCK_RESAMPLE = "BLOCK_RESAMPLE";

    /** 名义计划剖面（秒），用于余量缺口与分量级晚点归因。 */
    public record PlannedProfile(
            Map<String, Integer> plannedCycleByDirection,
            Map<String, ComponentSeconds> plannedComponentsByDirection,
            int plannedPulloutSeconds) {
    }

    public record ComponentSeconds(int running, int recovery, int turnback, int deadhead) {
        int cycle() {
            return running + recovery + turnback;
        }
    }

    public record Config(long lateToleranceSeconds, int resampleReplications, String seed) {
        public static Config defaults() {
            return new Config(300, 200, "FEAS-2026");
        }
    }

    public record Breakpoint(String tripCode, String serviceDate, String reason,
                             String detail, long delaySeconds) {
    }

    public record TripRow(String tripCode, String direction, String band,
                          long scheduledEpochSecond, long actualEpochSecond,
                          long delaySeconds, TripOutcome outcome, String reason) {
    }

    public record DayResult(LocalDate serviceDate, int weekIndex, int scheduledTrips,
                            int onTimeTrips, int lateTrips, int missedTrips,
                            int peakVehiclesNeeded, List<Breakpoint> breakpoints,
                            List<TripRow> tripRows) {
    }

    public record RouteReport(
            String routeCode,
            String method,
            int fleetSize,
            Integer holdoutWeek,
            int scheduledTrips,
            int onTimeTrips,
            int lateTrips,
            int missedTrips,
            double onTimeRate,
            double missedTripRate,
            int peakVehiclesNeeded,
            int minFeasibleFleet,
            boolean feasible,
            Map<String, Integer> breakpointCounts,
            List<Breakpoint> breakpoints,
            List<DayResult> perDay,
            Map<String, Object> methodConfig) {
    }

    // ----------------------------- 评估入口 -----------------------------

    /**
     * 分位数法。训练样本只取 {@code excluded=FALSE} 的常态观测；holdoutWeek 非空时，
     * 仅对该周时刻表做评估（节庆留出周），封路观测仍不进入分布。
     */
    public RouteReport evaluateQuantile(String route, int fleetSize, Integer holdoutWeek,
                                        List<CycleObservation> observations,
                                        List<ScheduledTrip> scheduled,
                                        PlannedProfile profile, double quantile, Config cfg) {
        UncertaintyEstimator est = new UncertaintyEstimator(observations);
        Map<String, Integer> quantileCycle = new LinkedHashMap<>();
        Map<String, Object> distAudit = new LinkedHashMap<>();
        for (String dir : List.of("OUTBOUND", "INBOUND")) {
            var allowance = est.quantileAllowance(route, dir,
                    profile.plannedCycleByDirection().get(dir), quantile);
            quantileCycle.put(dir, allowance.quantileCycleSeconds());
            distAudit.put(dir, Map.of(
                    "quantile", quantile,
                    "quantileCycleSeconds", allowance.quantileCycleSeconds(),
                    "plannedCycleSeconds", allowance.plannedCycleSeconds(),
                    "recoveryAllowanceSeconds", allowance.recoveryAllowanceSeconds(),
                    "sampleSize", allowance.sampleSize(),
                    "sampleMeanSeconds", Math.round(allowance.sampleMean()),
                    "sampleSdSeconds", Math.round(allowance.sampleSd())));
        }
        Map<String, Object> methodConfig = new LinkedHashMap<>();
        methodConfig.put("method", QUANTILE);
        methodConfig.put("quantile", quantile);
        methodConfig.put("trainingSamplesExcluded", est.excludedSampleCount(route));
        methodConfig.put("distributionByDirection", distAudit);
        methodConfig.put("limitation", "各方向分位数独立取值，多个极端路段在同一运营日的相关延误无法表达");
        methodConfig.put("holdoutWeek", holdoutWeek);

        CycleProvider provider = (trip, seqInDay) -> {
            int cycle = quantileCycle.get(trip.direction());
            ComponentSeconds planned = profile.plannedComponentsByDirection().get(trip.direction());
            // 分位数法不分解分量，晚点归因到周期缓冲不足
            return new CycleDraw(cycle, planned.running(), planned.recovery(), planned.turnback(),
                    profile.plannedPulloutSeconds(), false);
        };
        return runAcrossDays(route, QUANTILE, fleetSize, holdoutWeek, scheduled, provider,
                profile, cfg, methodConfig, null);
    }

    /**
     * 车辆块整日重采样法。重采样单位是常态运行日（整日序列整体抽取），
     * 保留同日相关延误；{@code replayHoldoutActual=TRUE} 时直接重放留出周真实观测作为对照基准。
     */
    public RouteReport evaluateResample(String route, int fleetSize, Integer holdoutWeek,
                                        boolean replayHoldoutActual,
                                        List<CycleObservation> observations,
                                        List<ScheduledTrip> scheduled,
                                        PlannedProfile profile, Config cfg) {
        UncertaintyEstimator est = new UncertaintyEstimator(observations);
        Map<String, UncertaintyEstimator.DayVector> vectors = est.dayVectors(route);
        List<UncertaintyEstimator.DayVector> normalDays = new ArrayList<>(vectors.values());
        if (normalDays.isEmpty()) {
            throw new IllegalStateException("线路 " + route + " 没有常态运行日可用于重采样");
        }
        Map<String, Object> methodConfig = new LinkedHashMap<>();
        methodConfig.put("method", BLOCK_RESAMPLE);
        methodConfig.put("resampleUnit", "整条运行日（车辆块整日周期序列），保留同日路段拥堵相关性");
        methodConfig.put("normalDayCount", normalDays.size());
        methodConfig.put("replicationsPerDay", cfg.resampleReplications());
        methodConfig.put("trainingSamplesExcluded", est.excludedSampleCount(route));
        methodConfig.put("holdoutWeek", holdoutWeek);
        methodConfig.put("replayHoldoutActual", replayHoldoutActual);
        methodConfig.put("limitation", "结论依赖代表性运行日；节庆/封路日被整体排除，不能从常态日重采样得到");

        if (replayHoldoutActual) {
            if (holdoutWeek == null) {
                throw new IllegalArgumentException("重放留出周真实观测时 holdoutWeek 必填");
            }
            // 直接用留出周观测（含封路极端值）逐日重放，作为地面真值对照
            Map<LocalDate, List<CycleObservation>> holdoutByDay = new LinkedHashMap<>();
            for (CycleObservation o : observations) {
                if (o.routeCode().equals(route) && o.weekIndex() == holdoutWeek) {
                    holdoutByDay.computeIfAbsent(o.serviceDate(), k -> new ArrayList<>()).add(o);
                }
            }
            CycleProvider provider = (trip, seqInDay) -> {
                List<CycleObservation> dayObs = holdoutByDay.get(trip.serviceDate());
                CycleObservation o = dayObs == null || seqInDay >= dayObs.size() ? null
                        : dayObs.get(seqInDay);
                if (o == null) {
                    ComponentSeconds p = profile.plannedComponentsByDirection().get(trip.direction());
                    return new CycleDraw(p.cycle(), p.running(), p.recovery(), p.turnback(),
                            profile.plannedPulloutSeconds(), true);
                }
                return new CycleDraw(o.cycleSeconds(), o.runningSeconds(), o.recoverySeconds(),
                        o.turnbackSeconds(), o.deadheadSeconds(), true);
            };
            methodConfig.put("note", "留出周真实观测重放（地面真值，含临时封路极端周期）");
            return runAcrossDays(route, BLOCK_RESAMPLE, fleetSize, holdoutWeek, scheduled, provider,
                    profile, cfg, methodConfig, holdoutByDay.keySet());
        }

        // 常态运行日重采样：每个仿真日的抽取由 SHA-256 哈希决定，可复现
        Map<LocalDate, Integer> ordinalByDate = new LinkedHashMap<>();
        scheduled.stream().map(ScheduledTrip::serviceDate).distinct().sorted()
                .forEach(d -> ordinalByDate.put(d, ordinalByDate.size()));
        CycleProvider provider = new CycleProvider() {
            @Override
            public CycleDraw draw(ScheduledTrip trip, int seqInDay) {
                int dayOrdinal = ordinalByDate.getOrDefault(trip.serviceDate(), 0);
                int rep = currentReplication();
                String key = "resample:" + route + ":day" + dayOrdinal + ":rep" + rep;
                double u = Deterministic.uniform01(cfg.seed(), key);
                UncertaintyEstimator.DayVector picked = normalDays.get((int) (u * normalDays.size()));
                CycleObservation o = seqInDay < picked.observations().size()
                        ? picked.observations().get(seqInDay) : null;
                if (o == null) {
                    ComponentSeconds p = profile.plannedComponentsByDirection().get(trip.direction());
                    return new CycleDraw(p.cycle(), p.running(), p.recovery(), p.turnback(),
                            profile.plannedPulloutSeconds(), false);
                }
                return new CycleDraw(o.cycleSeconds(), o.runningSeconds(), o.recoverySeconds(),
                        o.turnbackSeconds(), o.deadheadSeconds(), false);
            }
        };
        return runAcrossDays(route, BLOCK_RESAMPLE, fleetSize, holdoutWeek, scheduled, provider,
                profile, cfg, methodConfig, null);
    }

    // ----------------------------- 逐日贪心仿真 -----------------------------

    private record CycleDraw(int cycleSeconds, int runningSeconds, int recoverySeconds,
                             int turnbackSeconds, int deadheadSeconds, boolean actualObservation) {
    }

    @FunctionalInterface
    private interface CycleProvider {
        CycleDraw draw(ScheduledTrip trip, int seqInDay);
    }

    /** 重采样复制轮次（线程内仿真串行，使用可变计数器即可；引擎实例不跨线程共享）。 */
    private int replication = 0;

    private int currentReplication() {
        return replication;
    }

    private RouteReport runAcrossDays(String route, String method, int fleetSize, Integer holdoutWeek,
                                      List<ScheduledTrip> scheduled, CycleProvider provider,
                                      PlannedProfile profile, Config cfg,
                                      Map<String, Object> methodConfig,
                                      java.util.Set<LocalDate> onlyDays) {
        List<ScheduledTrip> scoped = scheduled.stream()
                .filter(t -> t.routeCode().equals(route))
                .filter(t -> holdoutWeek == null || t.weekIndex() == holdoutWeek)
                .filter(t -> onlyDays == null || onlyDays.contains(t.serviceDate()))
                .sorted(Comparator.comparingLong(ScheduledTrip::scheduledEpochSecond))
                .toList();
        if (scoped.isEmpty()) {
            throw new IllegalStateException("线路 " + route + " 在评估范围内没有计划车次（holdoutWeek=" + holdoutWeek + "）");
        }
        Map<LocalDate, List<ScheduledTrip>> byDay = new LinkedHashMap<>();
        for (ScheduledTrip t : scoped) {
            byDay.computeIfAbsent(t.serviceDate(), k -> new ArrayList<>()).add(t);
        }

        int replications = BLOCK_RESAMPLE.equals(method) && !Boolean.TRUE.equals(
                methodConfig.get("replayHoldoutActual")) ? cfg.resampleReplications() : 1;

        List<DayResult> representativeDays = null;
        int totalScheduled = 0, totalOnTime = 0, totalLate = 0, totalMissed = 0, peak = 0;
        List<Breakpoint> allBreakpoints = new ArrayList<>();

        for (int rep = 0; rep < replications; rep++) {
            replication = rep;
            List<DayResult> days = new ArrayList<>();
            int s = 0, ot = 0, lt = 0, ms = 0, pk = 0;
            List<Breakpoint> bps = new ArrayList<>();
            for (Map.Entry<LocalDate, List<ScheduledTrip>> e : byDay.entrySet()) {
                DayResult dr = simulateDay(e.getKey(), e.getValue(), fleetSize, provider, profile, cfg);
                days.add(dr);
                s += dr.scheduledTrips();
                ot += dr.onTimeTrips();
                lt += dr.lateTrips();
                ms += dr.missedTrips();
                pk = Math.max(pk, dr.peakVehiclesNeeded());
                bps.addAll(dr.breakpoints());
            }
            if (representativeDays == null) {
                representativeDays = days; // 展示第一轮（确定性哈希下可复现）
            }
            totalScheduled += s;
            totalOnTime += ot;
            totalLate += lt;
            totalMissed += ms;
            peak = Math.max(peak, pk);
            allBreakpoints.addAll(bps);
        }
        int denom = Math.max(1, replications);
        totalScheduled /= denom;
        totalOnTime /= denom;
        totalLate /= denom;
        totalMissed /= denom;

        int minFeasible = findMinFeasibleFleet(route, method, holdoutWeek, scheduled, provider,
                profile, cfg, scoped);

        double onTimeRate = totalScheduled == 0 ? 0 : (double) totalOnTime / totalScheduled;
        double missedRate = totalScheduled == 0 ? 0 : (double) totalMissed / totalScheduled;
        Map<String, Integer> bpCounts = new LinkedHashMap<>();
        for (Breakpoint b : allBreakpoints) {
            bpCounts.merge(b.reason(), 1, Integer::sum);
        }
        if (replications > 1) {
            bpCounts.replaceAll((k, v) -> v / replications);
            bpCounts.values().removeIf(v -> v <= 0);
        }
        boolean feasible = totalMissed == 0; // 缺班 > 0 时绝不允许宣称可执行
        return new RouteReport(route, method, fleetSize, holdoutWeek, totalScheduled,
                totalOnTime, totalLate, totalMissed, round4(onTimeRate), round4(missedRate),
                peak, minFeasible, feasible, bpCounts,
                replications > 1 ? averageBreakpoints(allBreakpoints, replications) : allBreakpoints,
                representativeDays, methodConfig);
    }

    /** 班前准备窗口（秒）：车辆从该窗口起点完成出场空驶后才能接首班。 */
    private static final long PRE_SERVICE_WINDOW_SECONDS = 1800;

    private DayResult simulateDay(LocalDate date, List<ScheduledTrip> dayTrips, int fleetSize,
                                  CycleProvider provider, PlannedProfile profile, Config cfg) {
        long windowStart = dayTrips.get(0).scheduledEpochSecond() - PRE_SERVICE_WINDOW_SECONDS;
        // 车辆在出场前位于车场；首次接车时再叠加该车辆当次出场空驶
        long[] availableAt = new long[fleetSize];
        boolean[] used = new boolean[fleetSize];
        java.util.Arrays.fill(availableAt, windowStart);

        int onTime = 0, late = 0, missed = 0;
        List<Breakpoint> breakpoints = new ArrayList<>();
        List<TripRow> rows = new ArrayList<>();
        List<long[]> intervals = new ArrayList<>();
        int seq = 0;

        for (ScheduledTrip trip : dayTrips) {
            CycleDraw draw = provider.draw(trip, seq);
            int vehicle = 0;
            for (int v = 1; v < fleetSize; v++) {
                if (availableAt[v] < availableAt[vehicle]) {
                    vehicle = v;
                }
            }
            long sched = trip.scheduledEpochSecond();
            // 车辆首次接车：可用性 = 班前窗口起点 + 本次出场空驶
            long earliest = used[vehicle]
                    ? availableAt[vehicle]
                    : windowStart + Math.max(profile.plannedPulloutSeconds(), draw.deadheadSeconds());
            long actualDep;
            TripOutcome outcome;
            String reason = null;

            if (earliest <= sched) {
                actualDep = sched;
                outcome = TripOutcome.ON_TIME;
                onTime++;
            } else if (earliest <= sched + cfg.lateToleranceSeconds()) {
                actualDep = earliest;
                outcome = TripOutcome.LATE;
                late++;
                reason = attributeLate(trip, draw, profile, earliest - sched);
                breakpoints.add(new Breakpoint(trip.tripCode(), date.toString(), reason,
                        lateDetail(trip, draw, profile), earliest - sched));
            } else {
                long delay = earliest - sched;
                outcome = TripOutcome.MISSED;
                missed++;
                String detail = "全部 " + fleetSize + " 辆车均在执行前序车次，最早可接车时刻晚于计划 "
                        + delay + "s（超过晚点容忍 " + cfg.lateToleranceSeconds() + "s），纸面车次丢班";
                breakpoints.add(new Breakpoint(trip.tripCode(), date.toString(),
                        BreakReasons.FLEET_SHORTAGE, detail, delay));
                rows.add(new TripRow(trip.tripCode(), trip.direction(), trip.periodBand(),
                        sched, -1, delay, outcome, BreakReasons.FLEET_SHORTAGE));
                seq++;
                continue;
            }

            // 出场空驶超计划导致的首班延迟单列归因
            if (!used[vehicle]
                    && draw.deadheadSeconds() > profile.plannedPulloutSeconds()
                    && outcome == TripOutcome.LATE) {
                breakpoints.add(new Breakpoint(trip.tripCode(), date.toString(),
                        BreakReasons.DEADHEAD_PULLOUT_DELAY,
                        "出场空驶 " + draw.deadheadSeconds() + "s 超过计划出场 "
                                + profile.plannedPulloutSeconds() + "s",
                        draw.deadheadSeconds() - profile.plannedPulloutSeconds()));
            }
            used[vehicle] = true;
            availableAt[vehicle] = actualDep + draw.cycleSeconds();
            intervals.add(new long[]{actualDep, actualDep + draw.cycleSeconds()});
            rows.add(new TripRow(trip.tripCode(), trip.direction(), trip.periodBand(),
                    sched, actualDep, actualDep - sched, outcome, reason));
            seq++;
        }

        return new DayResult(date, dayTrips.get(0).weekIndex(), dayTrips.size(), onTime, late, missed,
                peakOverlap(intervals), breakpoints, rows);
    }

    private String attributeLate(ScheduledTrip trip, CycleDraw draw, PlannedProfile profile, long delay) {
        ComponentSeconds planned = profile.plannedComponentsByDirection().get(trip.direction());
        if (draw.actualObservation()) {
            int runOver = Math.max(0, draw.runningSeconds() - planned.running());
            int recOver = Math.max(0, draw.recoverySeconds() - planned.recovery());
            int turnOver = Math.max(0, draw.turnbackSeconds() - planned.turnback());
            if (turnOver >= runOver && turnOver >= recOver && turnOver > 0) {
                return BreakReasons.TURNBACK_INSUFFICIENT;
            }
            if (recOver >= runOver && recOver > 0) {
                return BreakReasons.RECOVERY_OVERRUN;
            }
            if (runOver > 0) {
                return BreakReasons.RUNNING_OVERRUN;
            }
        }
        Integer plannedCycle = profile.plannedCycleByDirection().get(trip.direction());
        if (plannedCycle != null && draw.cycleSeconds() > plannedCycle) {
            return BreakReasons.CYCLE_BUFFER_SHORTFALL;
        }
        return BreakReasons.CYCLE_BUFFER_SHORTFALL;
    }

    private String lateDetail(ScheduledTrip trip, CycleDraw draw, PlannedProfile profile) {
        ComponentSeconds p = profile.plannedComponentsByDirection().get(trip.direction());
        return "方向=" + trip.direction()
                + "，周期=" + draw.cycleSeconds() + "s（计划 " + p.cycle() + "s）"
                + "，运行 " + draw.runningSeconds() + "/" + p.running()
                + "，恢复 " + draw.recoverySeconds() + "/" + p.recovery()
                + "，折返 " + draw.turnbackSeconds() + "/" + p.turnback();
    }

    private int findMinFeasibleFleet(String route, String method, Integer holdoutWeek,
                                     List<ScheduledTrip> scheduled, CycleProvider provider,
                                     PlannedProfile profile, Config cfg, List<ScheduledTrip> scoped) {
        // 上界：最忙单日的车次数（同一日内每个车次各配一辆车必然可行），避免按全网车次枚举
        int maxFleet = 0;
        Map<LocalDate, List<ScheduledTrip>> byDay = new LinkedHashMap<>();
        for (ScheduledTrip t : scoped) {
            List<ScheduledTrip> day = byDay.computeIfAbsent(t.serviceDate(), k -> new ArrayList<>());
            day.add(t);
            maxFleet = Math.max(maxFleet, day.size());
        }
        maxFleet = Math.min(maxFleet, 20); // 工程护栏：样例车队规模为 4，无需枚举到极端值
        for (int f = 1; f <= maxFleet; f++) {
            int savedRep = replication;
            int missed = 0;
            for (var e : byDay.entrySet()) {
                missed += simulateDay(e.getKey(), e.getValue(), f, provider, profile, cfg).missedTrips();
            }
            replication = savedRep;
            if (missed == 0) {
                return f;
            }
        }
        return maxFleet + 1;
    }

    private static int peakOverlap(List<long[]> intervals) {
        List<long[]> events = new ArrayList<>();
        for (long[] iv : intervals) {
            events.add(new long[]{iv[0], 1});
            events.add(new long[]{iv[1], -1});
        }
        events.sort((a, b) -> a[0] == b[0] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
        int cur = 0, peak = 0;
        for (long[] e : events) {
            cur += (int) e[1];
            peak = Math.max(peak, cur);
        }
        return peak;
    }

    private List<Breakpoint> averageBreakpoints(List<Breakpoint> all, int replications) {
        Map<String, Breakpoint> merged = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Breakpoint b : all) {
            String k = b.tripCode() + "|" + b.reason();
            counts.merge(k, 1, Integer::sum);
            merged.merge(k, b, (a, b2) -> new Breakpoint(a.tripCode(), a.serviceDate(), a.reason(),
                    a.detail(), Math.round((a.delaySeconds() + b2.delaySeconds()) / 2.0)));
        }
        List<Breakpoint> out = new ArrayList<>();
        for (Map.Entry<String, Breakpoint> e : merged.entrySet()) {
            if (counts.get(e.getKey()) >= Math.max(1, replications / 2)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
