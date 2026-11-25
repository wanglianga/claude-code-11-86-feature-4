# 公交网络灰度实验服务（R1/R2/R3 虚构样例）

用 **Java 21（运行/构建于 JDK 25）+ Spring Boot 3.5 + PostgreSQL** 实现的可复现灰度实验后端与评审控制台。
目标不是比较试点前后平均值，而是在预登记的 **四周、约 50% 合格服务接受新规则** 设计下，估计**乘客每次完整旅程**的端到端候车时间与拥挤暴露变化；旅程包含首乘与换乘，未完成刷卡或中途改线一律保留为 `UNKNOWN`。

系统覆盖题目要求的完整闭环：

1. **实验登记冻结**：合格线路/班次、处理规则、随机种子、分配概率、目标旅程人群、主要/安全指标、试点周期，一次登记并计算指纹。
2. **固定网络图**：连接关系只来自**实验前**刷卡换乘比例（R1↔R2=40%，R3 基本不换乘），用并查集得到冻结簇，不使用试点结果临时拆并。
3. **两套随机方案**：
   - 线路—具体班次（日期级，线路×日期×时段分层，**720 个单元，严格 360/360**）；
   - 高换乘线路成簇后的簇—周方案（2 簇 × 4 周 = **8 个单元，严格 4/4**，每周两簇一中一控）。
4. **暴露账逐段关联**：计划分配 → GPS/AVL 实际运行 → 短线折返/停运/延误偏离 → 每段与完整旅程路径暴露。
5. **共同支持器**：时段 × OD 区间 × 天气 × 服务可达性，检查两臂加权样本；不满足只报覆盖缺口，不外推。
6. **估计器**：乘客人数加权 Hajek **ITT**、按随机化单元聚类的 CR1 区间、CACE/Wald-IV、跨线暴露率 Wilson 区间、簇方案 2⁴ 随机化推断（RI）。
7. **独立护栏**：投诉率、满载班次占比、滞留/未完成旅程占比；任一硬护栏超限即 `STOP_EXPANSION`，扩围结论 `BLOCKED`，服务端同时拒绝人工改判 `GO`。
8. **外部事件按事件时间关联**：暴雨停运、降雨、施工、临时改线均带事件窗口；投诉同时保存 `complaint_time` 与 `service_event_time`，统计只用后者。
9. **界面**：随机分配、暴露路径、共同支持、客流结果、**班次可执行性**、护栏、离线审阅、扩围证据等 11 个标签页；另含 Swagger UI / OpenAPI 3。
10. **线路班次可执行性分析（新增）**：短线折返可能让纸面间隔达标，却因往返周期、终点停站与车辆周转不足持续丢班。本模块用独立的 6 周 AVL 样例回答“给定车队数，规则时刻表能否按时执行”：
    - **AVL 四段分解**：每个行程拆成运行时间（RUNNING）、终点恢复（TERMINAL_RECOVERY）、折返（TURNBACK）与空驶（DEADHEAD，首末班车）；
    - **车辆排班账**：每线 4 辆车，按计划周期贪心编排车辆块（vehicle block），块内 13:30 设司机换班点，车次经 `block_trip_link` 连到车辆块与司机班次；
    - **不确定性估计器**：从同线路同方向历史行程形成周期时间经验分布；节庆封路周（第 6 周，仅一周且含 2026-08-12 早高峰临时封路）整周 `excluded=TRUE` 留出，**封路时段绝不进入常态周期时间分布**；
    - **两条估计路线**：① 历史周期时间分位数直接配置恢复余量（易审计，但多极端路段的相关延误无法表达）；② 按车辆块/运行日整日重采样模拟（保留同日拥堵相关性，但依赖代表性运行日）；
    - **可行性报告**：给定车队数计算按时完成比例、晚点/缺班数、峰值在用车、最小可行车队与断点原因（车辆周转不足、周期缓冲不足、运行/恢复/折返超时、出场空驶延误）；**只要出现缺班，`feasible` 强制为 FALSE，不得把纸面车次齐全宣称可执行**。

---

## 一、最快启动：Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- Compose 内 PostgreSQL 18：库/用户/密码均为 `ccdev`；为避免与宿主机已装 PostgreSQL 的 5432 冲突，宿主机映射为 **5433**（容器内仍是 5432）。
- 首次启动会自动执行 `schema.sql`（幂等建表）并写入确定性虚构样例。

> 说明：本交付环境内没有 `docker` 可执行程序，因此 Dockerfile/Compose 已按标准多阶段构建编写，但**镜像构建/Compose 启动未在本环境实际执行**；下列本地方式已被实际构建、运行与验证。

---

## 二、本地开发与启动（本容器已预装运行时）

默认 PATH 已指向预装版本；如需显式固定：

```bash
export JAVA_HOME=/opt/jdk-25
export PATH=$JAVA_HOME/bin:/opt/maven/bin:/opt/postgresql-18/bin:$PATH
```

### 1. 准备 PostgreSQL（已运行可跳过）

本容器自带 PostgreSQL 18，且已存在可连账号：

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1;'
```

若在全新机器上：用 PostgreSQL 18 建库建用户即可，或直接使用上面的 Docker Compose。连接参数可用环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` |
| `SERVER_PORT` | `8080` |

> `ccdev/ccdev` 仅为隔离开发库，不是生产配置。

### 2. 开发模式启动

```bash
mvn spring-boot:run
```

或打包后运行：

```bash
mvn -DskipTests package
java -jar target/transit-pilot.jar
```

启动后访问 <http://127.0.0.1:8080>，会自动建表并播种样例（幂等：检测到 `PILOT-R123-202606` 已存在则跳过）。

### 3. 完全重置样例

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
# 然后重启应用；Docker 方式可用 docker compose down -v && docker compose up --build
```

---

## 三、测试账号

表单登录页 <http://127.0.0.1:8080/login.html>，也可直接对 API 使用 HTTP Basic：

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `viewer` | `viewer123` | VIEWER | 全部 GET / 离线审阅 |
| `analyst` | `analyst123` | ANALYST | 额外可：登记实验、重算流水线、评估护栏 |
| `approver` | `approver123` | APPROVER | 额外可：形成/签署扩围结论（GO/HOLD/BLOCKED） |

例：

```bash
curl -u analyst:analyst123 http://127.0.0.1:8080/api/experiments
```

---

## 四、本题验证方式（含我实际执行过的结论）

### 1. 单元测试（实际执行 ✅）

```bash
mvn test
```

结果（最后一次实际运行）：

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：50% 平衡分配与确定性哈希、R1/R2 聚类/R3 独立、全部命名路径分类
（分流/同车普通对照/对照转乘处理/全程 T/全程 C/不执行/两类 UNKNOWN）、
聚类 ITT 公式、Wilson 区间与 RI 枚举，以及 8 个可执行性引擎用例
（封路/节庆留出不进常态分布、分位数法与重采样法差异、不足车队不得宣称可执行、
重采样保留同日相关延误、仿真确定性与断点归因）。

### 2. 端到端不变量脚本（实际执行 ✅）

先启动应用，再运行：

```bash
./verify.sh                 # 默认 http://127.0.0.1:8080
```

脚本仅依赖 `bash/curl/python3`（标准库），实际结果：

```
通过 55 项，失败 0 项
ALL_VERIFY_CHECKS_PASSED
```

核验内容包括：

- 两套分配总数与严格 50%（720=360/360；8=4/4）；
- 9 个 `J-DEMO-*` 命名旅程的完整路径、分配臂、cross/diverted 标记；
- `FULL_CONTROL` 中不存在任何 `cross_line=true`（交叉暴露不混入未暴露对照）；
- 两设计 ITT/CACE/RI/跨线率区间存在，且簇方案区间更宽；
- 共同支持缺口存在；投诉、滞留两条护栏超限；
- 投诉确有滞后提交且按 `service_event_time` 关联；
- analyst 不能调用扩围评估（403）；approver 得到 `BLOCKED`；护栏超限时人工 `GO` 被拒（409）；
- `/v3/api-docs`、`/swagger-ui/index.html`、前端页面与 `app.js` 均可访问；
- **可执行性专项（21 项）**：3 线路×4 车×6 周 AVL 播种规模；节庆周整周留出、封路极端周期不进常态分布；
  4 车常态周两方案零缺班可执行；R1 用 3 车评估留出周时常态两方案判可行而真实封路重放缺班 2 个、
  最小车队 3→4；2 车不足时纸面车次齐全但两方案均 `feasible=FALSE` 且缺班数不同；
  车辆块含司机换班与车次链；AVL 四段与留出标记；报告持久化留痕；OpenAPI 收录 7 个新接口。

### 3. 前端渲染冒烟（实际执行 ✅）

本环境没有浏览器，我用临时目录中的 `jsdom` 对**真实运行的后端**加载 `index.html + app.js`：
11 个标签页全部成功渲染；新增“班次可执行性”标签冒烟 10 项检查全部通过
（两方案报告、留出周真实重放、不足车队不可执行徽章、分布封路剔除、车辆块车次链、AVL 四段留出标记），
结果 `UI_SMOKE_OK`，无运行时错误。冒烟脚本在 `/tmp/uismoke/smoke-feas.cjs`（临时目录，不属于交付物）。

### 4. 我没有执行的验证

- 没有实际运行 `docker compose up --build`（该容器内无 `docker` 命令）；
  Docker 路径仅完成了文件编写与 YAML 自检，未声称已实际构建镜像。

---

## 五、虚构样例剧本与复现数字

固定种子 `TSP-PILOT-2026-06-01`，数据由 `SeedService` 用固定 `Random(20260601)` 与
`SHA-256(seed::unitKey)` 确定性生成；任何人重置库后重跑应得到一致数字。

- 网络：R1/R2 共享 `HUB-CENTRAL`，实验前边 R1→R2=0.40、R2→R1=0.38；R3 边比例 ≤0.02。
  冻结簇：`C-R1R2`、`C-R3`。
- 规模：**2477** 条旅程（含 9 个命名样例、22 条批量分流、46 条暴雨、约 2400 条普通合成）、
  **720** 个日期级计划班次及 GPS 实际运行、**45** 条投诉、**5** 个外部事件（4 周实验 4 个 + 可执行性节庆封路 1 个）。
- 可执行性样例（独立 6 周窗口 2026-07-06 起，与 4 周实验数据分表存放）：
  **3 线路 × 每线 4 车 × 30 个运营日 = 5310** 个规则时刻表车次、**360** 个车辆块、
  **720** 个司机班次（每块 AM/PM 两班，13:30 换班）、**16650** 条 AVL 分段、
  **5310** 条周期时间观测，其中第 6 周 **885** 条整周 `excluded=TRUE`（含 R1 封路窗口 9 条、
  R2 溢出 4 条；R1 封路最大周期 3160 秒）。节庆封路仅一周，不允许进入常态分布或重采样运行日。
- 关键事件：
  - `2026-06-02`：一个 R1 处理 AM 班次实际**短线折返**，22+1 条分流旅程改乘 R2 对照 AM1 班次（LF=1.17）；
    同车普通 R2 乘客 `J-DEMO-PURER2-01` 仍是纯净对照。
  - R1/PM3 的处理实例上司机多次不执行新规则（ITT 保留其处理臂，实际路径落为 `FULL_CONTROL`）。
  - `2026-06-15` 暴雨红色预警，R1/R2 早高峰停运：18 未完成刷卡 + 12 中途改线 + 16 可达性受损完成旅程。
  - 另有 `06-08` 降雨、`06-11` R3 施工、`06-19` R2 临时改线。投诉提交时间刻意滞后服务 1–6 天。

最近一次确定性流水线的主要结果（共同支持域，候车秒，负=改善）：

| 设计 | 估计量 | 估计 | 95% 区间 / p | 旅程数/单元 |
| --- | --- | --- | --- | --- |
| 班次随机 | ITT 候车 | **−66.7** | [−90.7, −42.8] | 2255 / 584 |
| 班次随机 | CACE/Wald | −86.1 | [−114.1, −58.1] | 2255 |
| 班次随机 | ITT 拥挤暴露 | −0.1 | [−1.4, 1.2]（不显著） | 2255 / 584 |
| 簇—周随机 | ITT 候车 | **−194.3** | [−235.1, −153.5]（CR1, df=7，偏乐观） | 2067 / 8 |
| 簇—周随机 | RI 置换 p | — | **p=0.177**（2⁴=16 置换；双侧最小可达 (1+2)/17≈0.176） | 2067 |
| 跨线暴露 | Wilson 率 | **18.3%** | [16.8%, 19.9%] | 2391 |

解读：簇内统一处理内部化了 R1/R2 换乘溢出，点估计更大；但只有 2 个簇、8 个簇—周单元，
渐近 CR1 区间偏窄且在少簇下反保守性不足，**随机化推断 p=0.177** 才是更诚实的不确定性证据——
这正是题目要求的“簇方案污染更小但可用随机化单元少、置信/分辨更弱”。

护栏最新值（独立于候车改善）：

| 护栏 | 观测 | 冻结阈值 | 结论 |
| --- | --- | --- | --- |
| 投诉率（/万乘客人次，按服务时间） | ≈145.9 | 45 | **BREACHED** |
| 滞留/未完成或候车 >1200s 占比 | 4.12% | 3% | **BREACHED** |
| LF≥1.0 满载班次占比 | 3.23% | 5% | OK（分流 R2 班次进入 Top 满载明细） |

因此扩围证据页与全局横幅均为 **BLOCKED**：即使平均候车显著改善，投诉与安全护栏超限仍独立阻止扩围。

### 可执行性分析实测结果（最后一次实际运行，R1 规则时刻表）

| 场景 | 分位数法（P90 余量） | 车辆块整日重采样 | 留出周真实重放（封路） |
| --- | --- | --- | --- |
| 4 车 · 常态周全量 | 按时率 100%，缺班 0，可行，最小车队 3 | 按时率 100%，缺班 0，可行，最小车队 3 | — |
| 3 车 · 节庆留出周 | 按时率 100%，缺班 0，**判可行（最小 3 车）** | 按时率 100%，缺班 0，**判可行（最小 3 车）** | 按时率 98.31%，**缺班 2、晚点 3，不可执行，最小车队 4** |
| 2 车 · 常态周全量 | 按时率 76.27%，**缺班 120**，不可执行 | 按时率 77.91%，**缺班 42**，不可执行 | — |

解读：

- 两方案对**同一规则时刻表**给出不同的缺班数（2 车下 120 vs 42），因为分位数法把各方向周期边际化，
  重采样法保留了运行日内路段拥堵相关性；
- 节庆周单独留出时，常态两方案都认为 3 车够用，但封路真实重放要求 **4 车**——封路时段被整体排除在常态估计外，
  不能当作常态周期时间；若按常态结论只配 3 车，真实封路日会丢 2 个纸面车次；
- 2 车场景下纸面车次一个不少，但仿真出现缺班，报告一律 `feasible=FALSE`，断点账明确记 `FLEET_SHORTAGE`。

---

## 六、主要 HTTP API

所有 `/api/**` 需登录（表单 Cookie 或 Basic）。完整交互文档：`/swagger-ui.html`。

| 方法/路径 | 作用 |
| --- | --- |
| `GET /api/me` | 当前账号与角色 |
| `GET/POST /api/experiments`、`GET /api/experiments/{id}` | 列表/登记/详情 |
| `POST /api/experiments/{id}/register` | 冻结簇并生成两套分配与指纹 |
| `GET /api/experiments/{id}/network` | 实验前边、冻结簇、两设计有效单元与选择依据 |
| `GET /api/experiments/{id}/assignments?design=...` | `TRIP_RANDOMIZED` / `CLUSTER_WEEK` 分配清单 |
| `POST /api/experiments/{id}/pipeline/run` | 用冻结数据重算暴露账→支持层→估计→护栏 |
| `GET /api/experiments/{id}/exposure` | 暴露台账（可按 pathLabel/q 过滤） |
| `GET /api/experiments/{id}/journeys/{code}` | 单旅程：卡片 + 逐段 GPS/臂/偏离 + 判定追踪 |
| `GET /api/experiments/{id}/support` | 共同支持分层与覆盖缺口 |
| `GET /api/experiments/{id}/analysis` | 全部估计结果 |
| `GET/POST /api/experiments/{id}/guardrails[/evaluate]` | 护栏规则/最新评估/重新评估 |
| `GET /api/experiments/{id}/expansion`、`POST .../expansion/evaluate|decide` | 扩围证据与结论（APPROVER） |
| `GET /api/experiments/{id}/review-bundle` | 离线审阅包（冻结配置+分配哈希+追踪+结果） |
| `GET /api/experiments/{id}/events`、`/complaints` | 外部事件时间线、投诉时间关联核验 |
| `GET /api/experiments/{id}/feasibility/overview` | 可执行性总览：车队、6 周样例规模、留出周 |
| `GET .../feasibility/holdout` | 节庆封路留出周摘要（剔除样本、封路极值） |
| `GET .../feasibility/distribution?route=&quantile=` | 同线路同方向常态周期时间分位数与剔除明细 |
| `GET .../feasibility/blocks?route=&date=` | 车辆块、车次链与司机换班账 |
| `GET .../feasibility/trips/{tripCode}` | 单车次 AVL 四段（运行/恢复/折返/空驶）与周期观测 |
| `POST .../feasibility/analyze?route=&fleetSize=&holdoutWeek=&quantile=` | 两方案可行性报告（留出周额外返回真实重放对照） |
| `GET .../feasibility/reports?route=` | 历史可行性报告留痕（含断点与方法参数 JSON） |

---

## 七、目录与实现说明

```
src/main/java/com/ccdev/transit/
  engine/            # 纯逻辑、无 Spring：确定性哈希、平衡分配、网络聚类、路径分类、统计/RI
  engine/feasibility/# 可执行性：AVL 分段模型、经验分布、不确定性估计器、车辆周转仿真与两方案报告
  service/           # 登记冻结、确定性播种器（实验+6 周可执行性样例）、分析流水线、扩围决策
  web/               # REST 控制器与 DTO
  config/ security/  # Spring Security 三角色、OpenAPI、jsonb 响应处理
src/main/resources/
  schema.sql         # 幂等 PostgreSQL 建表（含 fleet_vehicle/vehicle_block/avl_segment 等 8 张可执行性表）
  static/            # 原生 JS 单页控制台（login/index/app/styles，11 个标签页）
src/test/java/...    # 24 个纯 JUnit 测试（不依赖数据库/Docker），含 8 个可执行性引擎用例
verify.sh            # 55 项端到端不变量（含 21 项可执行性专项）
Dockerfile docker-compose.yml docker-settings.xml
```

关键口径：

- **分配臂 vs 实际暴露是两条轴**：ITT 永远按随机分配臂；司机不执行/短线折返不改变分配臂，只改变实际路径标签。
- **CROSS_MIXED 单独成类**：枢纽异臂搭乘、短线折返分流都进该类，绝不并入 `FULL_CONTROL`。
- **不把执行者子集叫随机效应**：实际路径/执行者均值以 `AS_TREATED_ASSOCIATION` 明确标注为观察性关联；因果口径只有 ITT 与带假设的 CACE。
- **未知保留**：`INCOMPLETE_TAP/MID_ROUTE_CHANGE` 不插补、不归臂、不进效应估计，只计数并进入安全护栏。
- **共同支持**：无支持层旅程不产生外推结论，仅在支持页与 `gaps` 中报告覆盖缺口。
- **时间基准**：天气/施工/改线与 `service_ts` 按窗口重叠关联；护栏投诉率按 `complaint.service_event_time` 归集。
- **可执行性不看纸面间隔看车辆周转**：仿真逐车维护最早可接车时刻，超过晚点容忍（300 秒）即丢班；
  缺班数 > 0 时 `feasible` 强制 FALSE，纸面车次齐全不构成可执行性证据。
- **封路不进常态**：节庆周（第 6 周）整周 `excluded=TRUE`，临时封路窗口另打 `anomaly_flag`；
  分位数分布与重采样运行日都只使用常态周，留出周仅能以“真实观测重放”作对照，不能回灌训练。
- **两方案口径互补不混用**：分位数法的余量按方向独立取值、可逐样本审计；
  重采样法以整条运行日为单位抽取、保留同日相关延误；二者对同一时刻表的差异本身就是要报告的结论。
