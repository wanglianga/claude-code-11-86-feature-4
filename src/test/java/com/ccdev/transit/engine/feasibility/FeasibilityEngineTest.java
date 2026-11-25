package com.ccdev.transit.engine.feasibility;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可执行性引擎核验：
 * 1) 封路/节庆留出观测不进常态分布；
 * 2) 分位数法与车辆块整日重采样法对同一规则时刻表给出不同的按时完成比例/缺班；
 * 3) 不足车队下纸面车次齐全也必须 feasible=FALSE；
 * 4) 重采样保留同日相关延误（极端运行日造成级联缺班，边际分位数表达不了）；
 * 5) 仿真确定性可复现；断点原因可审计。
 */
class FeasibilityEngineTest {

    private static final String ROUTE = "RT";
    private static final LocalDate DAY1 = LocalDate.of(2026, 7, 6);
    private static final LocalDate HOLDOUT = LocalDate.of(2026, 8, 12);

    private FeasibilityEngine.PlannedProfile profile() {
        // 名义周期 840s（运行 600 + 恢复 120 + 折返 120），出场空驶 300s
        var comp = new FeasibilityEngine.ComponentSeconds(600, 120, 120, 300);
        return new FeasibilityEngine.PlannedProfile(
                java.util.Map.of("OUTBOUND", 840, "INBOUND", 840),
                java.util.Map.of("OUTBOUND", comp, "INBOUND", comp), 300);
    }

    private record Fixture(List<ScheduledTrip> trips, List<CycleObservation> obs) {
    }

    /** 构造 nDays 个常态日 + 1 个节庆留出日；每天 tripsPerDay 班，间隔 headwaySec。 */
    private Fixture build(int nDays, int tripsPerDay, int headwaySec,
                          int normalCycle, int shockCycleEvery, int shockCycle,
                          int holdoutCycle, long idStart) {
        List<ScheduledTrip> trips = new ArrayList<>();
        List<CycleObservation> obs = new ArrayList<>();
        long id = idStart;
        for (int d = 0; d < nDays; d++) {
            LocalDate date = DAY1.plusDays(d);
            int week = d / 5 + 1;
            boolean shockDay = shockCycleEvery > 0 && d % shockCycleEvery == shockCycleEvery - 1;
            for (int k = 0; k < tripsPerDay; k++) {
                int cycle = shockDay ? shockCycle : normalCycle + (k % 3) * 10;
                trips.add(new ScheduledTrip(id, "T-" + date + "-" + k, ROUTE,
                        k % 2 == 0 ? "OUTBOUND" : "INBOUND", date, week, k + 1,
                        k < 4 ? "AM_PEAK" : "OFF_PEAK",
                        date.atTime(6, 30).toEpochSecond(java.time.ZoneOffset.UTC) + (long) k * headwaySec,
                        headwaySec));
                obs.add(cycleObs(id++, date, week, cycle, false, null));
            }
        }
        // 节庆留出日：封路极端周期，整周 excluded
        int holdoutWeek = 6;
        for (int k = 0; k < tripsPerDay; k++) {
            LocalDate date = HOLDOUT;
            trips.add(new ScheduledTrip(id, "T-" + date + "-" + k, ROUTE,
                    k % 2 == 0 ? "OUTBOUND" : "INBOUND", date, holdoutWeek, k + 1,
                    k < 4 ? "AM_PEAK" : "OFF_PEAK",
                    date.atTime(6, 30).toEpochSecond(java.time.ZoneOffset.UTC) + (long) k * headwaySec,
                    headwaySec));
            String reason = k < 8 ? "TEMP_ROAD_CLOSURE" : "FESTIVAL_HOLDOUT";
            obs.add(cycleObs(id++, date, holdoutWeek,
                    k < 8 ? holdoutCycle : normalCycle + 60, true, reason));
        }
        return new Fixture(trips, obs);
    }

    private CycleObservation cycleObs(long tripId, LocalDate date, int week, int cycle,
                                      boolean excluded, String reason) {
        int run = Math.max(300, cycle - 240);
        return new CycleObservation(ROUTE, tripId % 2 == 0 ? "OUTBOUND" : "INBOUND",
                date, week, tripId, null, run, 120, 120, 0, cycle, excluded, reason);
    }

    private FeasibilityEngine.Config cfg() {
        return new FeasibilityEngine.Config(300, 200, "TEST-SEED");
    }

    @Test
    void closureAndFestivalObservationsAreExcludedFromNormalDistribution() {
        Fixture f = build(10, 12, 600, 840, -1, 0, 1800, 1);
        UncertaintyEstimator est = new UncertaintyEstimator(f.obs());
        // 常态样本 = 10 天 × 12 班 × 2 方向各半；留出 12 条全部被排除
        assertEquals(60, est.normalSampleCount(ROUTE, "OUTBOUND"));
        assertEquals(12, est.excludedSampleCount(ROUTE));
        EmpiricalDistribution dist = est.distribution(ROUTE, "OUTBOUND");
        // 封路极端值 1800s 不允许出现在常态分布中
        assertTrue(dist.max() < 1800, "封路极端周期不得进入常态分布，实际 max=" + dist.max());
        // 留出日整天不进入重采样运行日集合
        assertTrue(est.dayVectors(ROUTE).values().stream()
                .noneMatch(v -> v.serviceDate().equals(HOLDOUT)));
    }

    @Test
    void quantileMethodProducesAuditableAllowance() {
        Fixture f = build(10, 12, 600, 840, -1, 0, 1800, 1);
        UncertaintyEstimator est = new UncertaintyEstimator(f.obs());
        var a = est.quantileAllowance(ROUTE, "OUTBOUND", 840, 0.90);
        assertTrue(a.recoveryAllowanceSeconds() >= 0);
        assertEquals(60, a.sampleSize());
        // 余量必须来自常态分布，而不是封路值
        assertTrue(a.quantileCycleSeconds() < 1800);
    }

    @Test
    void insufficientFleetIsNeverDeclaredFeasibleEvenWithFullPaperTimetable() {
        // 间隔 600s、周期 840s：1 辆车周转不过来，2 辆可以
        Fixture f = build(10, 12, 600, 840, -1, 0, 1800, 1);
        FeasibilityEngine engine = new FeasibilityEngine();
        FeasibilityEngine.RouteReport oneCar = engine.evaluateQuantile(
                ROUTE, 1, null, f.obs(), f.trips(), profile(), 0.90, cfg());
        FeasibilityEngine.RouteReport twoCar = engine.evaluateQuantile(
                ROUTE, 2, null, f.obs(), f.trips(), profile(), 0.90, cfg());

        assertTrue(oneCar.missedTrips() > 0, "1 辆车必须出现缺班");
        assertFalse(oneCar.feasible(), "缺班>0 时不得宣称可执行");
        assertEquals(0, twoCar.missedTrips());
        assertTrue(twoCar.feasible());
        // 纸面车次数量在两种车队数下完全一致——纸面齐全不等于可执行
        assertEquals(oneCar.scheduledTrips(), twoCar.scheduledTrips());
        assertTrue(oneCar.breakpointCounts().containsKey(BreakReasons.FLEET_SHORTAGE));
        assertEquals(2, oneCar.minFeasibleFleet());
    }

    @Test
    void twoMethodsDisagreeOnHoldoutWeekVehiclesAndMissedTrips() {
        // 每 2 个常态日插入一个同日高拥堵日（相关延误），节庆日封路极端
        Fixture f = build(10, 12, 600, 840, 2, 1400, 2800, 1);
        FeasibilityEngine engine = new FeasibilityEngine();

        // 留出周：常态日重采样预测 vs 留出周真实重放
        FeasibilityEngine.RouteReport resample = engine.evaluateResample(
                ROUTE, 2, 6, false, f.obs(), f.trips(), profile(), cfg());
        FeasibilityEngine.RouteReport replay = engine.evaluateResample(
                ROUTE, 2, 6, true, f.obs(), f.trips(), profile(), cfg());
        FeasibilityEngine.RouteReport quantile = engine.evaluateQuantile(
                ROUTE, 2, 6, f.obs(), f.trips(), profile(), 0.90, cfg());

        // 封路真实重放的按时完成比例最低、缺班最多
        assertTrue(replay.onTimeRate() < resample.onTimeRate(),
                "封路真实重放应比常态重采样更差：replay=" + replay.onTimeRate()
                        + " resample=" + resample.onTimeRate());
        assertTrue(replay.missedTrips() >= resample.missedTrips());
        // 两方案对同一规则时刻表给出不同口径
        assertRatesDiffer(quantile, resample);
        // 最小可行车队数：真实封路日要求更多车辆
        assertTrue(replay.minFeasibleFleet() >= resample.minFeasibleFleet());
        // 任何方案只要有缺班就不得宣称可执行
        if (replay.missedTrips() > 0) {
            assertFalse(replay.feasible());
        }
    }

    @Test
    void blockResampleCapturesCorrelatedBadDayThatMarginalQuantileMisses() {
        // 常态日中一半为顺畅日(840)、一半为全天高拥堵日(1100，同日各路段相关)
        Fixture f = build(10, 12, 600, 840, 2, 1400, 2800, 1);
        FeasibilityEngine engine = new FeasibilityEngine();
        FeasibilityEngine.Config c = cfg();

        FeasibilityEngine.RouteReport quantile = engine.evaluateQuantile(
                ROUTE, 2, null, f.obs(), f.trips(), profile(), 0.50, c);
        FeasibilityEngine.RouteReport resample = engine.evaluateResample(
                ROUTE, 2, null, false, f.obs(), f.trips(), profile(), c);

        // 边际中位数把好坏日稀释，分位数法判断 2 车可行；
        // 重采样会抽到全天高拥堵日并产生级联缺班
        assertEquals(0, quantile.missedTrips(), "边际分位数无法表达同日相关延误");
        assertTrue(resample.missedTrips() > 0,
                "重采样应复现高拥堵运行日的级联缺班，实际 missed=" + resample.missedTrips());
        assertFalse(resample.feasible());
    }

    @Test
    void resampleSimulationIsDeterministicAcrossRuns() {
        Fixture f = build(10, 12, 600, 840, 2, 1400, 2800, 1);
        FeasibilityEngine engine = new FeasibilityEngine();
        FeasibilityEngine.RouteReport a = engine.evaluateResample(
                ROUTE, 2, null, false, f.obs(), f.trips(), profile(), cfg());
        FeasibilityEngine.RouteReport b = engine.evaluateResample(
                ROUTE, 2, null, false, f.obs(), f.trips(), profile(), cfg());
        assertEquals(a.onTimeRate(), b.onTimeRate());
        assertEquals(a.missedTrips(), b.missedTrips());
        assertEquals(a.onTimeTrips(), b.onTimeTrips());
    }

    @Test
    void lateTripsCarryComponentBreakpointReasons() {
        // 留出周真实重放：封路运行时间超计划，断点应落到运行超时分摊原因
        Fixture f = build(10, 12, 600, 840, -1, 0, 1800, 1);
        FeasibilityEngine engine = new FeasibilityEngine();
        FeasibilityEngine.RouteReport replay = engine.evaluateResample(
                ROUTE, 2, 6, true, f.obs(), f.trips(), profile(), cfg());
        List<String> reasons = replay.breakpoints().stream()
                .map(FeasibilityEngine.Breakpoint::reason).distinct().toList();
        assertTrue(reasons.contains(BreakReasons.FLEET_SHORTAGE)
                || reasons.contains(BreakReasons.RUNNING_OVERRUN)
                || reasons.contains(BreakReasons.TURNBACK_INSUFFICIENT)
                || reasons.contains(BreakReasons.RECOVERY_OVERRUN));
    }

    @Test
    void onTimeRateAccountingSumsCorrectly() {
        Fixture f = build(4, 10, 900, 840, -1, 0, 1800, 1);
        FeasibilityEngine engine = new FeasibilityEngine();
        FeasibilityEngine.RouteReport r = engine.evaluateQuantile(
                ROUTE, 3, null, f.obs(), f.trips(), profile(), 0.90, cfg());
        assertEquals(r.scheduledTrips(),
                r.onTimeTrips() + r.lateTrips() + r.missedTrips());
        assertEquals(0.0, r.missedTripRate(), 1e-9);
        assertTrue(r.onTimeRate() <= 1.0);
    }

    private void assertRatesDiffer(FeasibilityEngine.RouteReport a, FeasibilityEngine.RouteReport b) {
        // 两路线设计目的不同：至少在按时率或缺班数之一上可观察到差异
        assertTrue(a.onTimeRate() != b.onTimeRate() || a.missedTrips() != b.missedTrips(),
                "两种估计路线应给出可区分的可行性口径");
    }
}
