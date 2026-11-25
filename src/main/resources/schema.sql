-- 公交灰度实验服务 PostgreSQL schema（幂等，可重复启动）

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT,
    role          TEXT NOT NULL CHECK (role IN ('VIEWER','ANALYST','APPROVER'))
);

CREATE TABLE IF NOT EXISTS experiment (
    id                  BIGSERIAL PRIMARY KEY,
    code                TEXT UNIQUE NOT NULL,
    name                TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT/REGISTERED/RUNNING/PAUSED/STOPPED/COMPLETED
    active_design       TEXT NOT NULL DEFAULT 'TRIP_RANDOMIZED',
    seed                TEXT NOT NULL,
    assignment_prob     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    saturation          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    pilot_weeks         INT NOT NULL DEFAULT 4,
    start_date          DATE,
    end_date            DATE,
    target_population   TEXT,
    treatment_rules     TEXT,   -- JSON：处理规则（缩短间隔/短线折返）
    primary_metrics     TEXT,   -- JSON 数组
    safety_metrics      TEXT,   -- JSON 数组
    network_config      TEXT,   -- JSON：换乘边阈值等
    analysis_config     TEXT,   -- JSON：共同支持最小样本等
    registration_hash   TEXT,
    registered_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS route (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS stop_ (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL DEFAULT 'NORMAL', -- NORMAL/HUB
    UNIQUE (experiment_id, code)
);

-- 实验前刷卡换乘比例（冻结，不允许用试点结果重算）
CREATE TABLE IF NOT EXISTS pre_transfer_edge (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    from_route         TEXT NOT NULL,
    to_route           TEXT NOT NULL,
    transfer_journeys  INTEGER NOT NULL,
    total_journeys     INTEGER NOT NULL,
    ratio              DOUBLE PRECISION NOT NULL,
    frozen             BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, from_route, to_route)
);

-- 依据实验前网络一次性冻结的线路簇
CREATE TABLE IF NOT EXISTS route_cluster (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    cluster_code  TEXT NOT NULL,
    route_codes   TEXT NOT NULL, -- 逗号分隔，排序
    frozen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, cluster_code)
);

-- 随机化单元与分配结果（同一实验保存两套设计的分配）
CREATE TABLE IF NOT EXISTS assignment_unit (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type       TEXT NOT NULL, -- TRIP_RANDOMIZED / CLUSTER_WEEK
    unit_key          TEXT NOT NULL,
    stratum           TEXT NOT NULL,
    label             TEXT,
    route_codes       TEXT NOT NULL,
    cluster_code      TEXT,
    week_index        INTEGER,
    hash_value        TEXT NOT NULL,
    arm               TEXT NOT NULL, -- TREATMENT / CONTROL
    inclusion_prob    DOUBLE PRECISION NOT NULL,
    UNIQUE (experiment_id, design_type, unit_key)
);
CREATE INDEX IF NOT EXISTS idx_assignment_exp ON assignment_unit(experiment_id, design_type);

-- 计划班次（日期级实例，保留计划分配）
CREATE TABLE IF NOT EXISTS service_trip (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code         TEXT NOT NULL,
    template_key      TEXT NOT NULL, -- TRIP:R1:T02
    cluster_week_key  TEXT NOT NULL, -- CW:C12:W1
    route_code        TEXT NOT NULL,
    service_date      DATE NOT NULL,
    week_index        INTEGER NOT NULL,
    period_band       TEXT NOT NULL,
    seq_in_band       INTEGER NOT NULL DEFAULT 1,
    planned_departure TIMESTAMPTZ NOT NULL,
    od_band           TEXT,
    capacity          INTEGER NOT NULL DEFAULT 80,
    eligible          BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, trip_code)
);
CREATE INDEX IF NOT EXISTS idx_trip_lookup ON service_trip(experiment_id, route_code, service_date, period_band);

-- 实际 GPS/AVL 运行与偏离（实际执行状态）
CREATE TABLE IF NOT EXISTS actual_service_run (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL UNIQUE REFERENCES service_trip(id) ON DELETE CASCADE,
    gps_run_code        TEXT NOT NULL,
    actual_departure    TIMESTAMPTZ,
    actual_arrival      TIMESTAMPTZ,
    headway_seconds     INTEGER,
    rule_executed       BOOLEAN NOT NULL DEFAULT TRUE,
    deviation           TEXT NOT NULL DEFAULT 'NONE', -- NONE/SHORT_TURN/SUSPENDED/OVERTIME/DELAY
    short_turn_at_stop  TEXT,
    diverted_to_route   TEXT,
    delay_seconds       INTEGER NOT NULL DEFAULT 0,
    observed_lf         DOUBLE PRECISION,
    source              TEXT NOT NULL DEFAULT 'GPS_AVL'
);

-- 天气/施工/临时改线事件，按事件发生时间关联服务
CREATE TABLE IF NOT EXISTS external_event (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    event_type     TEXT NOT NULL, -- WEATHER_RAINSTORM/WEATHER_RAIN/CONSTRUCTION/ROUTE_CHANGE
    severity       TEXT,
    title          TEXT NOT NULL,
    start_ts       TIMESTAMPTZ NOT NULL,
    end_ts         TIMESTAMPTZ NOT NULL,
    affected_routes TEXT NOT NULL DEFAULT '*',
    suspension     BOOLEAN NOT NULL DEFAULT FALSE,
    source         TEXT
);

-- 刷卡完整旅程（未完成刷卡/中途改线保留为非 COMPLETE 状态）
CREATE TABLE IF NOT EXISTS card_journey (
    id                        BIGSERIAL PRIMARY KEY,
    experiment_id             BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code              TEXT NOT NULL UNIQUE,
    week_index                INTEGER NOT NULL,
    service_date              DATE NOT NULL,
    service_ts                TIMESTAMPTZ NOT NULL,
    period_band               TEXT NOT NULL,
    origin_stop               TEXT,
    hub_stop                  TEXT,
    dest_stop                 TEXT,
    od_band                   TEXT NOT NULL,
    route_pattern             TEXT NOT NULL, -- R1ONLY/R2ONLY/R1R2/R3ONLY/...
    weather_class             TEXT NOT NULL DEFAULT 'NORMAL',
    accessibility             BOOLEAN NOT NULL DEFAULT TRUE,
    status                    TEXT NOT NULL, -- COMPLETE/INCOMPLETE_TAP/MID_ROUTE_CHANGE
    passenger_count           INTEGER NOT NULL DEFAULT 1,
    anchor_unit_key           TEXT,
    anchor_arm_trip           TEXT,
    cluster_week_key          TEXT,
    cluster_code              TEXT,
    anchor_arm_cluster        TEXT,
    wait_seconds              DOUBLE PRECISION,
    first_wait_seconds        DOUBLE PRECISION,
    transfer_wait_seconds     DOUBLE PRECISION NOT NULL DEFAULT 0,
    crowd_exposure            DOUBLE PRECISION,
    severe_crowd_minutes      DOUBLE PRECISION,
    max_load_factor           DOUBLE PRECISION,
    -- 同一目标总体在“簇—周”方案下按同一 DGP 构造的可复现实现结果
    wait_cluster_seconds      DOUBLE PRECISION,
    crowd_cluster_exposure    DOUBLE PRECISION,
    severe_crowd_cluster_min  DOUBLE PRECISION,
    complaint_count           INTEGER NOT NULL DEFAULT 0,
    is_demo                   BOOLEAN NOT NULL DEFAULT FALSE,
    note                      TEXT
);
CREATE INDEX IF NOT EXISTS idx_journey_exp ON card_journey(experiment_id);
CREATE INDEX IF NOT EXISTS idx_journey_stratum ON card_journey(experiment_id, period_band, od_band, weather_class, accessibility);

CREATE TABLE IF NOT EXISTS journey_leg (
    id             BIGSERIAL PRIMARY KEY,
    journey_id     BIGINT NOT NULL REFERENCES card_journey(id) ON DELETE CASCADE,
    leg_index      INTEGER NOT NULL,
    route_code     TEXT NOT NULL,
    trip_id        BIGINT REFERENCES service_trip(id),
    actual_run_id  BIGINT REFERENCES actual_service_run(id),
    board_stop     TEXT,
    alight_stop    TEXT,
    is_transfer    BOOLEAN NOT NULL DEFAULT FALSE,
    unit_key       TEXT,
    planned_arm    TEXT,
    rule_effective BOOLEAN,
    deviation      TEXT,
    leg_exposure   TEXT NOT NULL, -- TREATMENT/CONTROL/CROSS/UNKNOWN
    wait_seconds   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ride_minutes   DOUBLE PRECISION NOT NULL DEFAULT 0,
    load_factor    DOUBLE PRECISION,
    note           TEXT
);
CREATE INDEX IF NOT EXISTS idx_leg_journey ON journey_leg(journey_id);

-- 暴露账：每旅程完整路径一行
CREATE TABLE IF NOT EXISTS exposure_ledger (
    journey_id            BIGINT PRIMARY KEY REFERENCES card_journey(id) ON DELETE CASCADE,
    experiment_id         BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code          TEXT NOT NULL,
    status                TEXT NOT NULL,
    path_label            TEXT NOT NULL, -- FULL_TREATMENT/FULL_CONTROL/CROSS_MIXED/UNKNOWN
    cluster_path_label    TEXT NOT NULL, -- CLUSTER_TREATMENT/CLUSTER_CONTROL/CLUSTER_INTERNAL_MIX/UNKNOWN
    treatment_legs        INTEGER NOT NULL DEFAULT 0,
    control_legs          INTEGER NOT NULL DEFAULT 0,
    cross_legs            INTEGER NOT NULL DEFAULT 0,
    unknown_legs          INTEGER NOT NULL DEFAULT 0,
    assigned_arm_trip     TEXT,
    assigned_arm_cluster  TEXT,
    effective_treatment   BOOLEAN NOT NULL DEFAULT FALSE,
    cross_line            BOOLEAN NOT NULL DEFAULT FALSE,
    diverted              BOOLEAN NOT NULL DEFAULT FALSE,
    path_probability      DOUBLE PRECISION,
    path_detail           TEXT,
    rebuilt_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ledger_path ON exposure_ledger(experiment_id, path_label);

CREATE TABLE IF NOT EXISTS support_stratum (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    stratum_key      TEXT NOT NULL,
    period_band      TEXT,
    od_band          TEXT,
    weather_class    TEXT,
    accessibility    BOOLEAN,
    n_total          INTEGER,
    weighted_total   DOUBLE PRECISION,
    n_t_trip         INTEGER,
    n_c_trip         INTEGER,
    w_t_trip         DOUBLE PRECISION,
    w_c_trip         DOUBLE PRECISION,
    n_t_cluster      INTEGER,
    n_c_cluster      INTEGER,
    w_t_cluster      DOUBLE PRECISION,
    w_c_cluster      DOUBLE PRECISION,
    supported_trip   BOOLEAN,
    supported_cluster BOOLEAN,
    gap_reason       TEXT,
    UNIQUE (experiment_id, stratum_key)
);

CREATE TABLE IF NOT EXISTS analysis_result (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type   TEXT NOT NULL,
    estimator_code TEXT NOT NULL, -- ITT / CACE_WALD / PATH_MEAN / CROSS_RATE / RI ...
    metric_code   TEXT NOT NULL,  -- wait_seconds / crowd_exposure ...
    scope         TEXT NOT NULL,  -- COMMON_SUPPORT / ALL_ELIGIBLE / PATH_*
    estimate      DOUBLE PRECISION,
    se            DOUBLE PRECISION,
    ci_low        DOUBLE PRECISION,
    ci_high       DOUBLE PRECISION,
    p_value       DOUBLE PRECISION,
    n_journeys    INTEGER,
    n_units       INTEGER,
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_result_exp ON analysis_result(experiment_id);

CREATE TABLE IF NOT EXISTS guardrail_rule (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    metric_source TEXT NOT NULL,
    comparator    TEXT NOT NULL DEFAULT 'GT', -- GT/LT
    threshold     DOUBLE PRECISION NOT NULL,
    min_sample    INTEGER NOT NULL DEFAULT 30,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS guardrail_evaluation (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    rule_code     TEXT NOT NULL,
    observed      DOUBLE PRECISION,
    threshold     DOUBLE PRECISION,
    sample_n      INTEGER,
    breached      BOOLEAN NOT NULL,
    action        TEXT NOT NULL, -- OK / WARN / STOP_EXPANSION / INSUFFICIENT_EVIDENCE
    detail        TEXT,
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ge_exp ON guardrail_evaluation(experiment_id);

CREATE TABLE IF NOT EXISTS expansion_decision (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    decision      TEXT NOT NULL, -- GO / HOLD / BLOCKED
    reason        TEXT,
    evidence      TEXT,
    decided_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS complaint (
    id                   BIGSERIAL PRIMARY KEY,
    experiment_id        BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    complaint_code       TEXT,
    journey_id           BIGINT REFERENCES card_journey(id) ON DELETE SET NULL,
    route_code           TEXT,
    category             TEXT,
    severity             TEXT,
    complaint_time       TIMESTAMPTZ NOT NULL,  -- 投诉提交时间（可滞后服务数日）
    service_event_time   TIMESTAMPTZ NOT NULL,  -- 实际服务发生时间（统计关联基准）
    channel              TEXT
);
CREATE INDEX IF NOT EXISTS idx_complaint_service_time ON complaint(experiment_id, service_event_time);

CREATE TABLE IF NOT EXISTS load_count (
    id             BIGSERIAL PRIMARY KEY,
    actual_run_id BIGINT REFERENCES actual_service_run(id) ON DELETE CASCADE,
    trip_id        BIGINT REFERENCES service_trip(id) ON DELETE CASCADE,
    observed_at    TIMESTAMPTZ,
    passengers     INTEGER,
    capacity       INTEGER,
    load_factor    DOUBLE PRECISION
);

-- ============================ 线路班次可执行性分析 ============================
-- 该模块独立于 4 周实验的 service_trip/actual_service_run，自带 6 周规则时刻表与 AVL，
-- 避免把可执行性样例计入实验护栏与效应估计口径。

-- 车辆台账（样例：每线路 4 辆）
CREATE TABLE IF NOT EXISTS fleet_vehicle (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_code   TEXT NOT NULL,          -- R1-V1 ...
    route_code     TEXT NOT NULL,
    depot_code     TEXT NOT NULL,
    capacity       INTEGER NOT NULL DEFAULT 80,
    UNIQUE (experiment_id, vehicle_code)
);

-- 司机排班（司机换班点挂在车辆块上）
CREATE TABLE IF NOT EXISTS driver_shift (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    shift_code     TEXT NOT NULL,         -- R1-S-001
    driver_code    TEXT NOT NULL,
    route_code     TEXT NOT NULL,
    service_date   DATE NOT NULL,
    shift_start    TIMESTAMPTZ NOT NULL,
    shift_end      TIMESTAMPTZ NOT NULL,
    relief_vehicle_code TEXT,             -- 换班时接手的车辆
    relief_ts      TIMESTAMPTZ,           -- 司机换班时刻
    UNIQUE (experiment_id, shift_code)
);
CREATE INDEX IF NOT EXISTS idx_shift_route_date ON driver_shift(experiment_id, route_code, service_date);

-- 车辆块账：一辆车一个运营日内的连续车次链（含换班接续）
CREATE TABLE IF NOT EXISTS vehicle_block (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    block_code     TEXT NOT NULL,         -- BLK-R1-20260706-V1
    vehicle_code   TEXT NOT NULL,
    route_code     TEXT NOT NULL,
    service_date   DATE NOT NULL,
    block_start    TIMESTAMPTZ NOT NULL,
    block_end      TIMESTAMPTZ NOT NULL,
    shift_codes    TEXT NOT NULL DEFAULT '', -- 逗号分隔，体现司机换班
    UNIQUE (experiment_id, block_code)
);
CREATE INDEX IF NOT EXISTS idx_block_route_date ON vehicle_block(experiment_id, route_code, service_date);

-- 可执行性分析专用规则时刻表（6 周，周一至周五）
CREATE TABLE IF NOT EXISTS feasibility_trip (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code      TEXT NOT NULL,         -- FT-R1-20260706-001
    route_code     TEXT NOT NULL,
    direction      TEXT NOT NULL,         -- OUTBOUND/INBOUND
    service_date   DATE NOT NULL,
    week_index     INTEGER NOT NULL,      -- 1..6
    seq_in_day     INTEGER NOT NULL,
    period_band    TEXT NOT NULL,         -- AM_PEAK/PM_PEAK/OFF_PEAK
    scheduled_departure TIMESTAMPTZ NOT NULL,
    headway_seconds INTEGER NOT NULL,
    status         TEXT NOT NULL DEFAULT 'OPERATED', -- OPERATED/LATE/MISSED/SUSPENDED
    block_id       BIGINT REFERENCES vehicle_block(id) ON DELETE SET NULL,
    UNIQUE (experiment_id, trip_code)
);
CREATE INDEX IF NOT EXISTS idx_ftrip_route_day ON feasibility_trip(experiment_id, route_code, service_date);

-- 车辆块与计划车次的连接账（车辆排班账把车次连接到车辆块与司机换班）
CREATE TABLE IF NOT EXISTS block_trip_link (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    block_id       BIGINT NOT NULL REFERENCES vehicle_block(id) ON DELETE CASCADE,
    trip_id        BIGINT NOT NULL REFERENCES feasibility_trip(id) ON DELETE CASCADE,
    seq_in_block   INTEGER NOT NULL,
    shift_code     TEXT,
    UNIQUE (experiment_id, trip_id)
);

-- AVL 行程分段：运行时间 / 终点恢复 / 折返 / 空驶
CREATE TABLE IF NOT EXISTS avl_segment (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_id        BIGINT NOT NULL REFERENCES feasibility_trip(id) ON DELETE CASCADE,
    segment_index  INTEGER NOT NULL,
    segment_type   TEXT NOT NULL,         -- RUNNING/TERMINAL_RECOVERY/TURNBACK/DEADHEAD
    from_stop      TEXT,
    to_stop        TEXT,
    direction      TEXT NOT NULL,         -- OUTBOUND/INBOUND
    planned_seconds  INTEGER NOT NULL,
    observed_seconds INTEGER NOT NULL,
    anomaly_flag   BOOLEAN NOT NULL DEFAULT FALSE, -- 封路/停运等非常态观测
    anomaly_reason TEXT,
    UNIQUE (experiment_id, trip_id, segment_index)
);
CREATE INDEX IF NOT EXISTS idx_avl_route_day ON avl_segment(experiment_id, segment_type, anomaly_flag);

-- 周期时间观测：同线路同方向历史完整行程（运行+终点恢复+折返，空驶单列），供不确定性估计器建分布
CREATE TABLE IF NOT EXISTS cycle_time_observation (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    route_code     TEXT NOT NULL,
    direction      TEXT NOT NULL,
    service_date   DATE NOT NULL,
    week_index     INTEGER NOT NULL,
    trip_id        BIGINT NOT NULL REFERENCES feasibility_trip(id) ON DELETE CASCADE,
    block_id       BIGINT REFERENCES vehicle_block(id) ON DELETE SET NULL,
    running_seconds    INTEGER NOT NULL,
    recovery_seconds   INTEGER NOT NULL,
    turnback_seconds   INTEGER NOT NULL,
    deadhead_seconds   INTEGER NOT NULL DEFAULT 0,
    cycle_seconds      INTEGER NOT NULL, -- 运行+恢复+折返（不含空驶）
    excluded       BOOLEAN NOT NULL DEFAULT FALSE, -- TRUE=节庆/封路留出，不进常态分布
    exclude_reason TEXT
);
CREATE INDEX IF NOT EXISTS idx_cycle_route_dir ON cycle_time_observation(experiment_id, route_code, direction, excluded);

-- 可行性报告（两种估计路线各一行，可重复生成、留痕审计）
CREATE TABLE IF NOT EXISTS feasibility_report (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    route_code     TEXT NOT NULL,
    method         TEXT NOT NULL,         -- QUANTILE / BLOCK_RESAMPLE
    fleet_size     INTEGER NOT NULL,
    holdout_week   INTEGER,               -- 留出周（节庆封路周）；NULL 表示全量
    on_time_rate   DOUBLE PRECISION NOT NULL,
    missed_trip_rate DOUBLE PRECISION NOT NULL,
    scheduled_trips INTEGER NOT NULL,
    on_time_trips  INTEGER NOT NULL,
    missed_trips   INTEGER NOT NULL,
    late_trips     INTEGER NOT NULL DEFAULT 0,
    peak_vehicles_needed INTEGER NOT NULL,
    feasible       BOOLEAN NOT NULL,      -- 缺班=0 才允许 TRUE
    breakpoints    TEXT,                  -- JSON 数组：断点原因明细
    method_config  TEXT,                  -- JSON：分位数/重采样参数，易审计
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_feas_exp ON feasibility_report(experiment_id, route_code, method);
