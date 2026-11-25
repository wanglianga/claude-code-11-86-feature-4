package com.ccdev.transit.service;

import com.ccdev.transit.engine.feasibility.CycleObservation;
import com.ccdev.transit.engine.feasibility.EmpiricalDistribution;
import com.ccdev.transit.engine.feasibility.FeasibilityEngine;
import com.ccdev.transit.engine.feasibility.ScheduledTrip;
import com.ccdev.transit.engine.feasibility.UncertaintyEstimator;
import com.ccdev.transit.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可执行性分析应用服务：把库内 6 周 AVL/时刻表/车辆块装载为引擎输入，
 * 运行分位数法与车辆块整日重采样法，持久化报告并提供审计查询。
 */
@Service
public class FeasibilityService {

    private static final Logger log = LoggerFactory.getLogger(FeasibilityService.class);
    public static final int FESTIVAL_HOLDOUT_WEEK = FeasibilitySeedService.FESTIVAL_WEEK;

    private final JdbcTemplate jdbc;
    private final FeasibilityEngine engine = new FeasibilityEngine();

    public FeasibilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------- 数据装载 -----------------------------

    public List<CycleObservation> loadObservations(long expId) {
        return jdbc.queryForList("""
                SELECT route_code,direction,service_date,week_index,trip_id,block_id,
                       running_seconds,recovery_seconds,turnback_seconds,deadhead_seconds,
                       cycle_seconds,excluded,exclude_reason
                FROM cycle_time_observation WHERE experiment_id=?
                ORDER BY route_code,service_date,trip_id
                """, expId).stream().map(m -> new CycleObservation(
                String.valueOf(m.get("route_code")),
                String.valueOf(m.get("direction")),
                ((java.sql.Date) m.get("service_date")).toLocalDate(),
                ((Number) m.get("week_index")).intValue(),
                ((Number) m.get("trip_id")).longValue(),
                m.get("block_id") == null ? null : ((Number) m.get("block_id")).longValue(),
                ((Number) m.get("running_seconds")).intValue(),
                ((Number) m.get("recovery_seconds")).intValue(),
                ((Number) m.get("turnback_seconds")).intValue(),
                ((Number) m.get("deadhead_seconds")).intValue(),
                ((Number) m.get("cycle_seconds")).intValue(),
                Boolean.TRUE.equals(m.get("excluded")),
                m.get("exclude_reason") == null ? null : String.valueOf(m.get("exclude_reason")))
        ).toList();
    }

    public List<ScheduledTrip> loadScheduled(long expId) {
        return jdbc.queryForList("""
                SELECT id,trip_code,route_code,direction,service_date,week_index,seq_in_day,
                       period_band,scheduled_departure,headway_seconds
                FROM feasibility_trip WHERE experiment_id=?
                ORDER BY route_code,scheduled_departure
                """, expId).stream().map(m -> new ScheduledTrip(
                ((Number) m.get("id")).longValue(),
                String.valueOf(m.get("trip_code")),
                String.valueOf(m.get("route_code")),
                String.valueOf(m.get("direction")),
                ((java.sql.Date) m.get("service_date")).toLocalDate(),
                ((Number) m.get("week_index")).intValue(),
                ((Number) m.get("seq_in_day")).intValue(),
                String.valueOf(m.get("period_band")),
                ((Timestamp) m.get("scheduled_departure")).toInstant().getEpochSecond(),
                ((Number) m.get("headway_seconds")).intValue())
        ).toList();
    }

    /** 名义计划剖面取自 AVL 分段的计划秒数（库内冻结，不由实测反推）。 */
    public FeasibilityEngine.PlannedProfile loadPlannedProfile(long expId, String route) {
        Map<String, Integer> cycleByDir = new LinkedHashMap<>();
        Map<String, FeasibilityEngine.ComponentSeconds> compByDir = new LinkedHashMap<>();
        int pullout = 420;
        for (String dir : List.of("OUTBOUND", "INBOUND")) {
            Map<String, Integer> planned = new LinkedHashMap<>();
            for (Map<String, Object> m : jdbc.queryForList("""
                    SELECT DISTINCT segment_type, planned_seconds
                    FROM avl_segment a JOIN feasibility_trip t ON t.id=a.trip_id
                    WHERE a.experiment_id=? AND t.route_code=? AND t.direction=?
                    """, expId, route, dir)) {
                planned.put(String.valueOf(m.get("segment_type")),
                        ((Number) m.get("planned_seconds")).intValue());
            }
            int run = planned.getOrDefault("RUNNING", 1080);
            int rec = planned.getOrDefault("TERMINAL_RECOVERY", 180);
            int turn = planned.getOrDefault("TURNBACK", 240);
            pullout = planned.getOrDefault("DEADHEAD", pullout);
            compByDir.put(dir, new FeasibilityEngine.ComponentSeconds(run, rec, turn, pullout));
            cycleByDir.put(dir, run + rec + turn);
        }
        return new FeasibilityEngine.PlannedProfile(cycleByDir, compByDir, pullout);
    }

    // ----------------------------- 分析入口 -----------------------------

    public Map<String, FeasibilityEngine.RouteReport> runBothMethods(
            long expId, String route, int fleetSize, Integer holdoutWeek, double quantile) {
        if (fleetSize <= 0) {
            throw new IllegalArgumentException("车队数必须为正整数");
        }
        if (quantile <= 0 || quantile >= 1) {
            throw new IllegalArgumentException("分位数必须在 (0,1) 开区间");
        }
        List<CycleObservation> obs = loadObservations(expId);
        List<ScheduledTrip> scheduled = loadScheduled(expId);
        FeasibilityEngine.PlannedProfile profile = loadPlannedProfile(expId, route);
        FeasibilityEngine.Config cfg = FeasibilityEngine.Config.defaults();

        FeasibilityEngine.RouteReport q = engine.evaluateQuantile(
                route, fleetSize, holdoutWeek, obs, scheduled, profile, quantile, cfg);
        // 重采样法：留出周同时给“常态日重采样预测”与“真实观测重放”两条口径
        FeasibilityEngine.RouteReport r = engine.evaluateResample(
                route, fleetSize, holdoutWeek, false, obs, scheduled, profile, cfg);
        FeasibilityEngine.RouteReport actual = holdoutWeek == null ? null
                : engine.evaluateResample(route, fleetSize, holdoutWeek, true,
                obs, scheduled, profile, cfg);

        persistReport(expId, q);
        persistReport(expId, r);
        if (actual != null) {
            persistReport(expId, actual);
        }
        Map<String, FeasibilityEngine.RouteReport> out = new LinkedHashMap<>();
        out.put(FeasibilityEngine.QUANTILE, q);
        out.put(FeasibilityEngine.BLOCK_RESAMPLE, r);
        if (actual != null) {
            out.put("HOLDOUT_ACTUAL_REPLAY", actual);
        }
        log.info("可执行性分析完成 route={} fleet={} holdout={}：分位数 onTime={} 缺班={}；重采样 onTime={} 缺班={}",
                route, fleetSize, holdoutWeek,
                q.onTimeRate(), q.missedTrips(), r.onTimeRate(), r.missedTrips());
        return out;
    }

    private void persistReport(long expId, FeasibilityEngine.RouteReport r) {
        Object replayFlag = r.methodConfig() == null ? null : r.methodConfig().get("replayHoldoutActual");
        String methodCode = Boolean.TRUE.equals(replayFlag) ? "BLOCK_RESAMPLE_REPLAY" : r.method();
        jdbc.update("""
                INSERT INTO feasibility_report(experiment_id,route_code,method,fleet_size,holdout_week,
                    on_time_rate,missed_trip_rate,scheduled_trips,on_time_trips,missed_trips,late_trips,
                    peak_vehicles_needed,feasible,breakpoints,method_config)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, expId, r.routeCode(), methodCode,
                r.fleetSize(), r.holdoutWeek(),
                r.onTimeRate(), r.missedTripRate(), r.scheduledTrips(), r.onTimeTrips(),
                r.missedTrips(), r.lateTrips(), r.peakVehiclesNeeded(), r.feasible(),
                Json.write(r.breakpoints()), Json.write(r.methodConfig()));
    }

    public List<Map<String, Object>> listReports(long expId, String route) {
        return jdbc.queryForList("""
                SELECT id,route_code,method,fleet_size,holdout_week,on_time_rate,missed_trip_rate,
                       scheduled_trips,on_time_trips,missed_trips,late_trips,peak_vehicles_needed,
                       feasible,breakpoints::text AS breakpoints,method_config::text AS method_config,created_at
                FROM feasibility_report WHERE experiment_id=? AND (? IS NULL OR route_code=?)
                ORDER BY route_code,created_at DESC, method
                """, expId, route, route);
    }

    // ----------------------------- 审计查询 -----------------------------

    /** 模块总览：车队、样例规模与节庆留出周。 */
    public Map<String, Object> overview() {
        Long expId = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE code = ?", Long.class, SeedService.SAMPLE_CODE);
        Map<String, Object> out = new LinkedHashMap<>();
        if (expId == null) {
            return out;
        }
        out.put("fleet", jdbc.queryForList("""
                SELECT route_code, count(*) AS vehicles, max(capacity) AS capacity
                FROM fleet_vehicle WHERE experiment_id=? GROUP BY route_code ORDER BY route_code
                """, expId));
        out.put("counts", jdbc.queryForList("""
                SELECT 'feasibility_trip' AS table_name, count(*) AS n FROM feasibility_trip WHERE experiment_id=?
                UNION ALL SELECT 'vehicle_block', count(*) FROM vehicle_block WHERE experiment_id=?
                UNION ALL SELECT 'driver_shift', count(*) FROM driver_shift WHERE experiment_id=?
                UNION ALL SELECT 'avl_segment', count(*) FROM avl_segment WHERE experiment_id=?
                UNION ALL SELECT 'cycle_observation', count(*) FROM cycle_time_observation WHERE experiment_id=?
                UNION ALL SELECT 'cycle_excluded', count(*) FROM cycle_time_observation
                  WHERE experiment_id=? AND excluded
                """, expId, expId, expId, expId, expId, expId));
        out.put("holdout", holdoutSummary(expId));
        out.put("policy", "纸面间隔满足不等于可执行：按给定车队数仿真周转，出现缺班即 feasible=FALSE");
        return out;
    }

    /** 分布审计：常态分位数、被排除的封路/节庆样本量与极值。 */
    public Map<String, Object> distributionAudit(long expId, String route, double quantile) {
        List<CycleObservation> all = loadObservations(expId);
        UncertaintyEstimator est = new UncertaintyEstimator(all);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("quantile", quantile);
        Map<String, Object> dirs = new LinkedHashMap<>();
        for (String dir : List.of("OUTBOUND", "INBOUND")) {
            EmpiricalDistribution dist = est.distribution(route, dir);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("normalSamples", dist.n());
            d.put("meanSeconds", Math.round(dist.mean()));
            d.put("sdSeconds", Math.round(dist.sd()));
            d.put("p50", Math.round(dist.median()));
            d.put("p" + Math.round(quantile * 100), Math.round(dist.quantile(quantile)));
            d.put("p95", Math.round(dist.quantile(0.95)));
            d.put("max", Math.round(dist.max()));
            dirs.put(dir, d);
        }
        out.put("normalDistribution", dirs);

        List<Map<String, Object>> excludedRows = new ArrayList<>();
        int closure = 0, festival = 0;
        for (CycleObservation o : est.excludedObservations(route)) {
            if ("TEMP_ROAD_CLOSURE".equals(o.excludeReason())) {
                closure++;
            } else {
                festival++;
            }
            excludedRows.add(Map.of(
                    "serviceDate", o.serviceDate().toString(),
                    "direction", o.direction(),
                    "cycleSeconds", o.cycleSeconds(),
                    "reason", o.excludeReason() == null ? "FESTIVAL_HOLDOUT" : o.excludeReason()));
        }
        out.put("excludedSamples", excludedRows.size());
        out.put("closureWindowSamples", closure);
        out.put("festivalOtherSamples", festival);
        out.put("excludedDetail", excludedRows.stream().limit(20).toList());
        out.put("policy", "excluded=TRUE 的节庆/封路观测不进入常态周期时间分布，封路时段不能被当成常态周期时间");
        return out;
    }

    public List<Map<String, Object>> listBlocks(long expId, String route, LocalDate date) {
        List<Map<String, Object>> blocks = jdbc.queryForList("""
                SELECT id,block_code,vehicle_code,route_code,service_date,block_start,block_end,shift_codes
                FROM vehicle_block WHERE experiment_id=? AND route_code=?
                AND (? IS NULL OR service_date=?)
                ORDER BY service_date,vehicle_code
                """, expId, route, date, date);
        for (Map<String, Object> b : blocks) {
            long blockId = ((Number) b.get("id")).longValue();
            b.put("trips", jdbc.queryForList("""
                    SELECT l.seq_in_block,l.shift_code,t.trip_code,t.direction,t.period_band,
                           t.scheduled_departure,t.status
                    FROM block_trip_link l JOIN feasibility_trip t ON t.id=l.trip_id
                    WHERE l.experiment_id=? AND l.block_id=? ORDER BY l.seq_in_block
                    """, expId, blockId));
        }
        return blocks;
    }

    public Map<String, Object> tripAvlDetail(long expId, String tripCode) {
        Map<String, Object> trip = jdbc.queryForMap(
                "SELECT * FROM feasibility_trip WHERE experiment_id=? AND trip_code=?", expId, tripCode);
        long tripId = ((Number) trip.get("id")).longValue();
        List<Map<String, Object>> segments = jdbc.queryForList("""
                SELECT segment_index,segment_type,from_stop,to_stop,direction,planned_seconds,
                       observed_seconds,anomaly_flag,anomaly_reason
                FROM avl_segment WHERE experiment_id=? AND trip_id=? ORDER BY segment_index
                """, expId, tripId);
        Map<String, Object> cycle = jdbc.queryForMap(
                "SELECT * FROM cycle_time_observation WHERE experiment_id=? AND trip_id=?", expId, tripId);
        Map<String, Object> link = jdbc.queryForList("""
                SELECT b.block_code,b.vehicle_code,l.seq_in_block,l.shift_code
                FROM block_trip_link l JOIN vehicle_block b ON b.id=l.block_id
                WHERE l.experiment_id=? AND l.trip_id=?
                """, expId, tripId).stream().findFirst().orElse(Map.of());
        return Map.of("trip", trip, "segments", segments, "cycle", cycle, "blockLink", link);
    }

    /** 节庆留出周摘要：封路窗口的极端周期与整周排除情况。 */
    public Map<String, Object> holdoutSummary(long expId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holdoutWeek", FESTIVAL_HOLDOUT_WEEK);
        out.put("closureWindow", FeasibilitySeedService.CLOSURE_DATE + " "
                + FeasibilitySeedService.CLOSURE_FROM + "-" + FeasibilitySeedService.CLOSURE_TO);
        out.put("routes", jdbc.queryForList("""
                SELECT route_code,
                       count(*) FILTER (WHERE excluded) AS excluded_samples,
                       count(*) FILTER (WHERE excluded AND exclude_reason='TEMP_ROAD_CLOSURE') AS closure_samples,
                       count(*) FILTER (WHERE NOT excluded) AS normal_samples,
                       max(cycle_seconds) FILTER (WHERE excluded AND exclude_reason='TEMP_ROAD_CLOSURE') AS closure_max_cycle,
                       max(cycle_seconds) FILTER (WHERE NOT excluded) AS normal_max_cycle
                FROM cycle_time_observation WHERE experiment_id=? AND week_index=?
                GROUP BY route_code ORDER BY route_code
                """, expId, FESTIVAL_HOLDOUT_WEEK));
        out.put("rule", "节庆周只有一周且含临时封路：整周 excluded=TRUE 单独留出，"
                + "两方案的常态分布/重采样运行日都不得包含该周数据");
        return out;
    }
}
