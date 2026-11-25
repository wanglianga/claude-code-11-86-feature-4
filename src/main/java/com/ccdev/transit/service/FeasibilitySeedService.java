package com.ccdev.transit.service;

import com.ccdev.transit.engine.Deterministic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 线路班次可执行性分析样例播种器（确定性，可重复）。
 *
 * <p>规模与题目一致：3 条线路 × 每线 4 辆车 × 6 周（周一至周五）AVL；
 * 第 6 周为节庆周，仅该周含一次临时封路（2026-08-12 早高峰，R1 主影响、R2 溢出），
 * 整周观测在周期时间账中标记 excluded=TRUE 单独留出，绝不进入常态分布。</p>
 *
 * <p>数据模型：规则时刻表按纸面 12/15 分钟间隔生成；车辆块按计划周期贪心编排（每线 4 块/日），
 * 块内 13:30 设司机换班点；AVL 把每个行程拆成运行/终点恢复/折返三段，首末班另记空驶。
 * 实测分量含“运营日共同冲击 + 车次级噪声”，使同日路段延误相关——重采样法才有相关性可保留。</p>
 */
@Service
@Order(21)
public class FeasibilitySeedService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FeasibilitySeedService.class);

    public static final LocalDate AVL_START = LocalDate.of(2026, 7, 6); // 周一
    public static final int WEEKS = 6;
    public static final int FLEET_PER_ROUTE = 4;
    public static final int FESTIVAL_WEEK = 6;
    public static final LocalDate CLOSURE_DATE = LocalDate.of(2026, 8, 12); // 第 6 周周三
    public static final LocalTime CLOSURE_FROM = LocalTime.of(8, 0);
    public static final LocalTime CLOSURE_TO = LocalTime.of(10, 0);

    private static final List<String> ROUTES = List.of("R1", "R2", "R3");
    private static final List<String> DIRECTIONS = List.of("OUTBOUND", "INBOUND");

    /** 名义计划剖面（秒）：运行/恢复/折返/出场空驶。 */
    private static final Map<String, Profile> PROFILES = Map.of(
            "R1", new Profile(1080, 120, 240, 420, 90, 70),
            "R2", new Profile(1140, 180, 240, 420, 105, 80),
            "R3", new Profile(1320, 240, 240, 480, 120, 90));

    private record Profile(int running, int recovery, int turnback, int pullout,
                           int tripNoise, int dayShock) {
        int cycle() {
            return running + recovery + turnback;
        }
    }

    private final JdbcTemplate jdbc;

    public FeasibilitySeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        Long expId = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE code = ?", Long.class, SeedService.SAMPLE_CODE);
        if (expId == null) {
            return;
        }
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM feasibility_trip WHERE experiment_id=?", Long.class, expId);
        if (existing != null && existing > 0) {
            log.info("可执行性样例已存在（{} 车次），跳过播种", existing);
            return;
        }
        seed(expId);
    }

    private void seed(long expId) {
        log.info("开始构造线路班次可执行性样例（3 线路 × 4 车 × 6 周 AVL，第 6 周节庆封路留出）...");
        seedFleet(expId);
        seedClosureEvent(expId);

        int trips = 0, segments = 0, cycles = 0, blocks = 0, shifts = 0, links = 0;
        for (String route : ROUTES) {
            Random rng = new Random(910000L + route.charAt(1));
            Profile profile = PROFILES.get(route);
            for (int week = 1; week <= WEEKS; week++) {
                for (int dow = 0; dow < 5; dow++) {
                    LocalDate date = AVL_START.plusDays((long) (week - 1) * 7 + dow);
                    List<TimetableSlot> slots = buildSlots(date, week);
                    DaySeedResult r = seedDay(expId, route, date, week, slots, profile, rng);
                    trips += r.trips;
                    segments += r.segments;
                    cycles += r.cycles;
                    blocks += r.blocks;
                    shifts += r.shifts;
                    links += r.links;
                }
            }
        }
        log.info("可执行性样例完成：车次 {}，车辆块 {}，司机班次 {}，块-车次连接 {}，AVL 分段 {}，周期观测 {}",
                trips, blocks, shifts, links, segments, cycles);
    }

    private void seedFleet(long expId) {
        for (String route : ROUTES) {
            for (int v = 1; v <= FLEET_PER_ROUTE; v++) {
                jdbc.update("""
                        INSERT INTO fleet_vehicle(experiment_id,vehicle_code,route_code,depot_code,capacity)
                        VALUES (?,?,?, 'DEPOT-CENTRAL', 80)
                        """, expId, route + "-V" + v, route);
            }
        }
    }

    private void seedClosureEvent(long expId) {
        jdbc.update("""
                INSERT INTO external_event(experiment_id,event_type,severity,title,start_ts,end_ts,
                    affected_routes,suspension,source)
                VALUES (?, 'CONSTRUCTION','RED',?,?,?, 'R1,R2', FALSE, '节庆巡游交通管制公告')
                """, expId, "节庆周临时封路：R1 走廊早高峰交通管制（巡游），R2 枢纽溢出",
                Timestamp.valueOf(CLOSURE_DATE.atTime(CLOSURE_FROM)),
                Timestamp.valueOf(CLOSURE_DATE.atTime(CLOSURE_TO)));
    }

    // ----------------------------- 规则时刻表 -----------------------------

    private record TimetableSlot(LocalDateTime ts, String direction, String band, int headway) {
    }

    private List<TimetableSlot> buildSlots(LocalDate date, int week) {
        List<TimetableSlot> out = new ArrayList<>();
        int[] dirCounter = {0};
        addBand(out, date, LocalTime.of(6, 30), LocalTime.of(6, 45), 15, dirCounter);
        addBand(out, date, LocalTime.of(7, 0), LocalTime.of(9, 0), 12, dirCounter);
        addBand(out, date, LocalTime.of(9, 15), LocalTime.of(16, 45), 15, dirCounter);
        addBand(out, date, LocalTime.of(17, 0), LocalTime.of(19, 0), 12, dirCounter);
        addBand(out, date, LocalTime.of(19, 15), LocalTime.of(20, 0), 15, dirCounter);
        return out;
    }

    private void addBand(List<TimetableSlot> slots, LocalDate date, LocalTime from, LocalTime to,
                         int headwayMin, int[] dirCounter) {
        LocalTime t = from;
        while (!t.isAfter(to)) {
            String band = (t.getHour() >= 7 && t.getHour() < 9) || (t.getHour() >= 17 && t.getHour() < 19)
                    ? (t.getHour() < 9 ? "AM_PEAK" : "PM_PEAK") : "OFF_PEAK";
            String direction = DIRECTIONS.get(dirCounter[0] % 2);
            slots.add(new TimetableSlot(date.atTime(t), direction, band, headwayMin * 60));
            dirCounter[0]++;
            t = t.plusMinutes(headwayMin);
        }
    }

    // ----------------------------- 单日播种 -----------------------------

    private record DaySeedResult(int trips, int segments, int cycles, int blocks,
                                 int shifts, int links) {
    }

    private DaySeedResult seedDay(long expId, String route, LocalDate date, int week,
                                  List<TimetableSlot> slots, Profile p, Random rng) {
        boolean festival = week == FESTIVAL_WEEK;
        boolean closureDay = date.equals(CLOSURE_DATE);
        double dayShock = pseudoNormal(route + "|day-shock|" + date);

        // 1) 按计划周期贪心编排 4 个车辆块
        long dayStart = date.atTime(6, 0).toEpochSecond(java.time.ZoneOffset.UTC);
        long[] avail = new long[FLEET_PER_ROUTE];
        for (int v = 0; v < FLEET_PER_ROUTE; v++) {
            avail[v] = date.atTime(6, 30).minusSeconds(p.pullout()).toEpochSecond(java.time.ZoneOffset.UTC);
        }
        @SuppressWarnings("unchecked")
        List<Long>[] blockTripIds = new List[FLEET_PER_ROUTE];
        List<Integer>[] blockSeq = new List[FLEET_PER_ROUTE];
        for (int v = 0; v < FLEET_PER_ROUTE; v++) {
            blockTripIds[v] = new ArrayList<>();
            blockSeq[v] = new ArrayList<>();
        }

        List<Long> tripIds = new ArrayList<>();
        List<TimetableSlot> tripSlots = new ArrayList<>();
        List<int[]> observedComponents = new ArrayList<>(); // [run,rec,turn,deadhead,closureExtra]
        int seqInDay = 0;
        for (TimetableSlot slot : slots) {
            long sched = slot.ts().toEpochSecond(java.time.ZoneOffset.UTC);
            int vehicle = 0;
            for (int v = 1; v < FLEET_PER_ROUTE; v++) {
                if (avail[v] < avail[vehicle]) {
                    vehicle = v;
                }
            }
            String tripCode = String.format("FT-%s-%s-%03d", route, date, seqInDay + 1);
            String status = "OPERATED";

            // 实测分量：运营日共同冲击（同日相关）+ 车次级噪声
            int run = p.running() + (int) Math.round(dayShock * p.dayShock())
                    + (int) Math.round(rng.nextGaussian() * p.tripNoise());
            int rec = p.recovery() + Math.max(0, (int) Math.round(rng.nextGaussian() * 45));
            int turn = p.turnback() + Math.max(0, (int) Math.round(rng.nextGaussian() * 40));
            int deadhead = p.pullout() + (int) Math.round(rng.nextGaussian() * 40);
            int closureExtra = 0;

            // 节庆周临时封路：仅封路时段、仅受影响线路，标记为非常态
            LocalTime tt = slot.ts().toLocalTime();
            boolean inClosure = closureDay && !tt.isBefore(CLOSURE_FROM) && tt.isBefore(CLOSURE_TO);
            if (inClosure && "R1".equals(route)) {
                closureExtra = 1200 + (int) (Deterministic.uniform01("closure-R1", tripCode) * 600);
                status = "LATE";
            } else if (inClosure && "R2".equals(route) && !tt.isBefore(LocalTime.of(9, 0))) {
                closureExtra = 360 + (int) (Deterministic.uniform01("closure-R2", tripCode) * 240);
                status = "LATE";
            }
            run = Math.max(480, run + closureExtra);

            Long tripId = jdbc.queryForObject("""
                    INSERT INTO feasibility_trip(experiment_id,trip_code,route_code,direction,service_date,
                        week_index,seq_in_day,period_band,scheduled_departure,headway_seconds,status)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                    """, Long.class, expId, tripCode, route, slot.direction(), java.sql.Date.valueOf(date),
                    week, seqInDay + 1, slot.band(), Timestamp.valueOf(slot.ts()), slot.headway(), status);

            tripIds.add(tripId);
            tripSlots.add(slot);
            observedComponents.add(new int[]{run, rec, turn, Math.max(180, deadhead), closureExtra});
            blockTripIds[vehicle].add(tripId);
            blockSeq[vehicle].add(seqInDay);
            avail[vehicle] = sched + p.cycle(); // 计划块编排用名义周期
            seqInDay++;
        }

        // 2) 车辆块 + 司机班次（每块 AM/PM 两班，13:30 换班）+ 连接账 + AVL 分段 + 周期观测
        LocalDateTime reliefTime = date.atTime(13, 30);
        int blocks = 0, shifts = 0, links = 0, segCount = 0, cycCount = 0;
        List<Object[]> segBatch = new ArrayList<>();
        List<Object[]> cycBatch = new ArrayList<>();

        for (int v = 0; v < FLEET_PER_ROUTE; v++) {
            String vehicle = route + "-V" + (v + 1);
            List<Long> ids = blockTripIds[v];
            if (ids.isEmpty()) {
                continue;
            }
            long firstSched = tripSlots.get(blockSeq[v].get(0)).ts()
                    .toEpochSecond(java.time.ZoneOffset.UTC);
            long lastSched = tripSlots.get(blockSeq[v].get(blockSeq[v].size() - 1)).ts()
                    .toEpochSecond(java.time.ZoneOffset.UTC);
            String blockCode = String.format("BLK-%s-%s-V%d", route, date, v + 1);
            Long blockId = jdbc.queryForObject("""
                    INSERT INTO vehicle_block(experiment_id,block_code,vehicle_code,route_code,service_date,
                        block_start,block_end,shift_codes)
                    VALUES (?,?,?,?,?,?,?,?) RETURNING id
                    """, Long.class, expId, blockCode, vehicle, route, java.sql.Date.valueOf(date),
                    Timestamp.valueOf(LocalDateTime.ofEpochSecond(firstSched, 0, java.time.ZoneOffset.UTC)),
                    Timestamp.valueOf(LocalDateTime.ofEpochSecond(lastSched, 0, java.time.ZoneOffset.UTC)),
                    "");
            blocks++;

            String amShift = String.format("SH-%s-%s-V%d-AM", route, date, v + 1);
            String pmShift = String.format("SH-%s-%s-V%d-PM", route, date, v + 1);
            String amDriver = String.format("D-%s-%d", route, (v + date.getDayOfWeek().getValue()) % 4 + 1);
            String pmDriver = String.format("D-%s-%d", route, (v + date.getDayOfWeek().getValue() + 1) % 4 + 1);
            jdbc.update("""
                    INSERT INTO driver_shift(experiment_id,shift_code,driver_code,route_code,service_date,
                        shift_start,shift_end,relief_vehicle_code,relief_ts)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, expId, amShift, amDriver, route, java.sql.Date.valueOf(date),
                    Timestamp.valueOf(date.atTime(6, 0)), Timestamp.valueOf(reliefTime),
                    vehicle, Timestamp.valueOf(reliefTime));
            jdbc.update("""
                    INSERT INTO driver_shift(experiment_id,shift_code,driver_code,route_code,service_date,
                        shift_start,shift_end,relief_vehicle_code,relief_ts)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, expId, pmShift, pmDriver, route, java.sql.Date.valueOf(date),
                    Timestamp.valueOf(reliefTime), Timestamp.valueOf(date.atTime(20, 30)),
                    vehicle, Timestamp.valueOf(reliefTime));
            shifts += 2;
            jdbc.update("UPDATE vehicle_block SET shift_codes=? WHERE id=?", amShift + "," + pmShift, blockId);

            int seqInBlock = 0;
            for (int idx = 0; idx < ids.size(); idx++) {
                long tripId = ids.get(idx);
                int globalSeq = blockSeq[v].get(idx);
                TimetableSlot slot = tripSlots.get(globalSeq);
                int[] comp = observedComponents.get(globalSeq);
                String shiftCode = slot.ts().toLocalTime().isBefore(LocalTime.of(13, 30)) ? amShift : pmShift;
                jdbc.update("""
                        INSERT INTO block_trip_link(experiment_id,block_id,trip_id,seq_in_block,shift_code)
                        VALUES (?,?,?,?,?)
                        """, expId, blockId, tripId, seqInBlock + 1, shiftCode);
                links++;
                jdbc.update("UPDATE feasibility_trip SET block_id=? WHERE id=?", blockId, tripId);
                seqInBlock++;

                boolean anomaly = comp[4] > 0;
                String anomalyReason = anomaly ? "TEMP_ROAD_CLOSURE" : null;
                int segIndex = 0;
                segBatch.add(new Object[]{expId, tripId, segIndex++, "RUNNING",
                        slot.direction(), p.running(), comp[0], anomaly, anomalyReason});
                segBatch.add(new Object[]{expId, tripId, segIndex++, "TERMINAL_RECOVERY",
                        slot.direction(), p.recovery(), comp[1], anomaly, anomalyReason});
                segBatch.add(new Object[]{expId, tripId, segIndex++, "TURNBACK",
                        slot.direction(), p.turnback(), comp[2], anomaly, anomalyReason});
                if (idx == 0) { // 出场空驶
                    segBatch.add(new Object[]{expId, tripId, segIndex++, "DEADHEAD",
                            slot.direction(), p.pullout(), comp[3], false, null});
                    segCount++;
                }
                if (idx == ids.size() - 1) { // 入场空驶
                    segBatch.add(new Object[]{expId, tripId, segIndex, "DEADHEAD",
                            slot.direction(), p.pullout(),
                            Math.max(180, p.pullout() + (int) Math.round(rng.nextGaussian() * 40)),
                            false, null});
                    segCount++;
                }
                segCount += 3; // 运行/终点恢复/折返

                int cycle = comp[0] + comp[1] + comp[2];
                String excludeReason = festival
                        ? (anomaly ? "TEMP_ROAD_CLOSURE" : "FESTIVAL_HOLDOUT") : null;
                cycBatch.add(new Object[]{expId, route, slot.direction(), java.sql.Date.valueOf(date),
                        week, tripId, blockId, comp[0], comp[1], comp[2], comp[3], cycle,
                        festival, excludeReason});
                cycCount++;
            }
        }

        jdbc.batchUpdate("""
                INSERT INTO avl_segment(experiment_id,trip_id,segment_index,segment_type,direction,
                    planned_seconds,observed_seconds,anomaly_flag,anomaly_reason)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, segBatch);
        jdbc.batchUpdate("""
                INSERT INTO cycle_time_observation(experiment_id,route_code,direction,service_date,week_index,
                    trip_id,block_id,running_seconds,recovery_seconds,turnback_seconds,deadhead_seconds,
                    cycle_seconds,excluded,exclude_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, cycBatch);
        return new DaySeedResult(tripIds.size(), segCount, cycCount, blocks, shifts, links);
    }

    /** 三个确定性均匀量近似标准正态（与实验播种器同口径），用于运营日共同冲击。 */
    private static double pseudoNormal(String key) {
        double u = 0;
        for (int i = 0; i < 3; i++) {
            u += Deterministic.uniform01("FEAS-DAY-SHOCK", key + "#" + i);
        }
        return (u - 1.5) / 0.5;
    }
}
