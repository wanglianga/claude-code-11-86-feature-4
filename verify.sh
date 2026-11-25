#!/usr/bin/env bash
# 灰度实验服务关键不变量验证。
# 用法：./verify.sh [BASE_URL]
# 依赖：bash、curl、python3（仅用标准库）
set -u
BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
PYBIN="$(command -v python3 || true)"
if [ -z "$PYBIN" ] && [ -x /opt/python-3.12/bin/python3 ]; then PYBIN=/opt/python-3.12/bin/python3; fi
[ -n "$PYBIN" ] || { echo "未找到 python3"; exit 1; }

echo "==> 等待服务健康：$BASE"
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "$BASE/api/health" 2>/dev/null | grep -q '"db":"UP"'; then
    echo "    health UP"; break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "服务未就绪"; exit 1; fi
done

BASE="$BASE" "$PYBIN" - <<'PY'
import base64, json, os, sys, urllib.request, urllib.error

BASE = os.environ["BASE"].rstrip("/")
fails, passes = [], []

def req(method, path, user="analyst", pwd="analyst123", body=None, raw=False):
    r = urllib.request.Request(BASE + path, method=method)
    token = base64.b64encode(f"{user}:{pwd}".encode()).decode()
    r.add_header("Authorization", "Basic " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=20) as resp:
            txt = resp.read().decode()
            return resp.status, (txt if raw else json.loads(txt or "{}"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt

def check(name, cond, detail=""):
    (passes if cond else fails).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + ("" if cond or not detail else f"  -> {detail}"))

# 1. 实验存在
st, exp = req("GET", "/api/experiments/by-code/PILOT-R123-202606")
check("样例实验已登记", st == 200 and exp.get("id"), str(exp)[:200])
eid = exp["id"]

# 2. 两套分配严格 50%
for design, total in [("TRIP_RANDOMIZED", 720), ("CLUSTER_WEEK", 8)]:
    st, a = req("GET", f"/api/experiments/{eid}/assignments?design={design}")
    check(f"{design} 总数={total}", a.get("total") == total, str(a.get("total")))
    check(f"{design} 处理/对照各半", a.get("treatment") == total // 2
          and a.get("control") == total // 2
          and abs(a.get("treatmentShare", 0) - 0.5) < 1e-9,
          f"{a.get('treatment')}/{a.get('control')} share={a.get('treatmentShare')}")

# 3. 命名旅程完整路径标记
expected = {
    "J-DEMO-DIVERT-01": ("CROSS_MIXED", "TREATMENT", True, True),
    "J-DEMO-PURER2-01": ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-CROSS-01":  ("CROSS_MIXED", "CONTROL", True, False),
    "J-DEMO-FULLT-01":  ("FULL_TREATMENT", "TREATMENT", False, False),
    "J-DEMO-FULLC-01":  ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-NONEXEC-01": ("FULL_CONTROL", "TREATMENT", False, False),
    "J-DEMO-INCOMPLETE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-MIDCHANGE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-STORM-01": ("UNKNOWN", None, False, False),
}
for code, (path, arm, cross, diverted) in expected.items():
    st, d = req("GET", f"/api/experiments/{eid}/journeys/{code}")
    led = d.get("ledger") or {}
    ok = st == 200 and led.get("path_label") == path
    if arm:
        ok &= led.get("assigned_arm_trip") == arm
    if cross:
        ok &= bool(led.get("cross_line"))
    if diverted:
        ok &= bool(led.get("diverted"))
    check(f"{code} => {path}" + (f"/{arm}" if arm else ""), ok,
          f"got {led.get('path_label')} arm={led.get('assigned_arm_trip')} "
          f"cross={led.get('cross_line')} divert={led.get('diverted')}")

# 4. 交叉暴露不混入纯净对照
st, fc = req("GET", f"/api/experiments/{eid}/exposure?pathLabel=FULL_CONTROL&limit=500")
rows = fc.get("rows", [])
bad = [r["journey_code"] for r in rows if r.get("cross_line")]
check("FULL_CONTROL 中没有任何 cross_line=true 旅程", not bad, str(bad[:5]))
check("CROSS_MIXED 数量 > 0",
      any(c["path_label"] == "CROSS_MIXED" and c["n"] > 50 for c in fc.get("counts", [])))

# 5. 分析结果：两设计 ITT + RI + CACE
st, an = req("GET", f"/api/experiments/{eid}/analysis")
res = an.get("results", [])
def find(**kw):
    for r in res:
        if all(r.get(k) == v for k, v in kw.items()):
            return r
    return None
itt_t = find(design_type="TRIP_RANDOMIZED", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
itt_c = find(design_type="CLUSTER_WEEK", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
check("班次方案 ITT(候车) 存在且为负效应", bool(itt_t) and itt_t["estimate"] < 0, str(itt_t))
check("簇周方案 ITT(候车) 存在且为负、效应更大",
      bool(itt_c) and itt_c["estimate"] < itt_t["estimate"], str(itt_c and itt_c["estimate"]))
check("簇周方案 95% 区间宽于班次方案",
      (itt_c["ci_high"] - itt_c["ci_low"]) > (itt_t["ci_high"] - itt_t["ci_low"]),
      f"cluster width={itt_c['ci_high']-itt_c['ci_low']:.1f} vs "
      f"trip width={itt_t['ci_high']-itt_t['ci_low']:.1f}")
ri = find(design_type="CLUSTER_WEEK", estimator_code="RI_PERMUTATION")
check("簇周随机化推断 RI p 值存在（16 置换）", bool(ri) and 0 < ri["p_value"] <= 1)
cace = find(estimator_code="CACE_WALD")
check("CACE/Wald 估计存在", bool(cace) and cace["estimate"] < 0)
crossr = find(estimator_code="CROSS_RATE")
check("跨线暴露率带 Wilson 区间", bool(crossr) and crossr["ci_low"] < crossr["estimate"] < crossr["ci_high"])

# 6. 共同支持缺口存在（暴雨层不能外推）
st, sup = req("GET", f"/api/experiments/{eid}/support")
check("识别出无共同支持的缺口层", int(sup["gaps"]["gap_strata_trip"]) > 0
      or int(sup["gaps"]["gap_strata_cluster"]) > 0, str(sup["gaps"]))

# 7. 护栏：投诉与滞留超限
st, gg = req("GET", f"/api/experiments/{eid}/guardrails")
latest = {g["rule_code"]: g for g in gg.get("latest", [])}
check("投诉护栏超限", latest.get("COMPLAINT_RATE_MAX", {}).get("breached") is True)
check("滞留/停运安全护栏超限", latest.get("STRANDING_RATE_MAX", {}).get("breached") is True)
cmp_rule = latest.get("COMPLAINT_RATE_MAX", {})
cmp_detail = cmp_rule.get("detail", {})
if isinstance(cmp_detail, str):
    cmp_detail = json.loads(cmp_detail)
check("投诉按 service_event_time 关联且存在滞后提交",
      cmp_detail.get("timeBasis", "").startswith("service_event_time")
      and int(cmp_detail.get("complaintsLoggedOnLaterDate", 0)) > 0)

# 8. 权限：analyst 不能执行扩围结论
st, _ = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="analyst")
check("analyst 调用扩围评估被 403 拒绝", st == 403, f"status={st}")

# 9. approver 评估 -> BLOCKED；人工 GO 被拒
st, dec = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="approver", pwd="approver123")
check("approver 评估后结论为 BLOCKED", st == 200 and dec.get("decision") == "BLOCKED",
      f"status={st} decision={dec.get('decision') if isinstance(dec,dict) else dec}")
st, body = req("POST", f"/api/experiments/{eid}/expansion/decide",
               user="approver", pwd="approver123",
               body={"decision": "GO", "reason": "try override"})
check("护栏超限时人工 GO 被服务端拒绝(409)", st == 409, f"status={st}")

# 10. OpenAPI / Swagger / 前端
for path in ["/v3/api-docs", "/swagger-ui/index.html", "/index.html", "/login.html", "/app.js"]:
    try:
        with urllib.request.urlopen(BASE + path, timeout=10) as r:
            ok = r.status == 200
    except Exception as e:
        ok = False
    check(f"可访问 {path}", ok)

# 11. 线路班次可执行性分析
st, fov = req("GET", "/api/experiments/1/feasibility/overview")
fleet = {f["route_code"]: f["vehicles"] for f in fov.get("fleet", [])}
check("可执行性样例：3 线路每线 4 辆车", fleet == {"R1": 4, "R2": 4, "R3": 4}, str(fleet))
fcounts = {c["table_name"]: c["n"] for c in fov.get("counts", [])}
check("6 周 AVL 与车辆块已播种", fcounts.get("vehicle_block") == 360
      and fcounts.get("avl_segment", 0) > 10000 and fcounts.get("cycle_observation") == 5310,
      str(fcounts))
check("节庆留出观测存在且非零", fcounts.get("cycle_excluded", 0) > 0)

st, fh = req("GET", "/api/experiments/1/feasibility/holdout")
hr = {r["route_code"]: r for r in fh.get("routes", [])}
check("留出周=6 且整周排除（常态样本为 0）", fh.get("holdoutWeek") == 6
      and all(r["normal_samples"] == 0 and r["excluded_samples"] > 0 for r in hr.values()))
check("封路窗口只影响 R1/R2 且 R1 极端周期显著", hr["R1"]["closure_samples"] > 0
      and hr["R3"]["closure_samples"] == 0
      and hr["R1"]["closure_max_cycle"] > 2400, str(hr))

st, dist = req("GET", "/api/experiments/1/feasibility/distribution?route=R1&quantile=0.9")
normal_max = max(d["max"] for d in dist["normalDistribution"].values())
check("封路极端周期不进入常态分布", dist["excludedSamples"] > 0
      and normal_max < hr["R1"]["closure_max_cycle"],
      f"normal_max={normal_max} closure_max={hr['R1']['closure_max_cycle']}")
check("分布审计报告封路/节庆剔除明细", dist["closureWindowSamples"] > 0
      and dist["festivalOtherSamples"] > 0)

st, an4 = req("POST", "/api/experiments/1/feasibility/analyze?route=R1&fleetSize=4&quantile=0.90")
r4 = an4["reports"]
check("4 车常态周：两方案均判定可执行且零缺班",
      r4["QUANTILE"]["feasible"] is True and r4["BLOCK_RESAMPLE"]["feasible"] is True
      and r4["QUANTILE"]["missedTrips"] == 0 and r4["BLOCK_RESAMPLE"]["missedTrips"] == 0)
check("两方案都返回最小可行车队与峰值在用车",
      2 <= r4["QUANTILE"]["minFeasibleFleet"] <= 4
      and r4["QUANTILE"]["peakVehiclesNeeded"] <= 4)

st, an3h = req("POST", "/api/experiments/1/feasibility/analyze?route=R1&fleetSize=3&holdoutWeek=6&quantile=0.90")
r3h = an3h["reports"]
check("留出周：常态两方案预测可行，但真实封路重放缺班且不可执行",
      r3h["QUANTILE"]["feasible"] is True and r3h["BLOCK_RESAMPLE"]["feasible"] is True
      and r3h["HOLDOUT_ACTUAL_REPLAY"]["feasible"] is False
      and r3h["HOLDOUT_ACTUAL_REPLAY"]["missedTrips"] > 0)
check("留出周两方案车辆需求出现差异（真实重放要求更多车）",
      r3h["HOLDOUT_ACTUAL_REPLAY"]["minFeasibleFleet"]
      > r3h["BLOCK_RESAMPLE"]["minFeasibleFleet"])
check("封路断点归因到运行超时/车辆周转",
      set(r3h["HOLDOUT_ACTUAL_REPLAY"]["breakpointCounts"]).intersection(
          {"FLEET_SHORTAGE", "RUNNING_OVERRUN"}))

st, an2 = req("POST", "/api/experiments/1/feasibility/analyze?route=R1&fleetSize=2&quantile=0.90")
r2 = an2["reports"]
check("不足车队：纸面车次齐全但两方案均 feasible=FALSE",
      r2["QUANTILE"]["feasible"] is False and r2["BLOCK_RESAMPLE"]["feasible"] is False
      and r2["QUANTILE"]["scheduledTrips"] == r2["BLOCK_RESAMPLE"]["scheduledTrips"]
      and r2["QUANTILE"]["missedTrips"] > 0 and r2["BLOCK_RESAMPLE"]["missedTrips"] > 0)
check("不足车队断点包含 FLEET_SHORTAGE",
      "FLEET_SHORTAGE" in r2["QUANTILE"]["breakpointCounts"]
      and "FLEET_SHORTAGE" in r2["BLOCK_RESAMPLE"]["breakpointCounts"])
check("两方案对同一时刻表给出不同缺班数",
      r2["QUANTILE"]["missedTrips"] != r2["BLOCK_RESAMPLE"]["missedTrips"],
      f"q={r2['QUANTILE']['missedTrips']} r={r2['BLOCK_RESAMPLE']['missedTrips']}")

st, blocks = req("GET", "/api/experiments/1/feasibility/blocks?route=R1&date=2026-08-12")
check("封路日 4 个车辆块且块内含司机换班与车次链",
      len(blocks) == 4 and all(len(b["trips"]) > 5 and "," in b["shift_codes"] for b in blocks))

st, avl = req("GET", "/api/experiments/1/feasibility/trips/FT-R1-2026-08-12-007")
seg_types = {s["segment_type"] for s in avl["segments"]}
check("AVL 行程拆成运行/恢复/折返（首末含空驶）",
      {"RUNNING", "TERMINAL_RECOVERY", "TURNBACK"}.issubset(seg_types))
check("留出周车次周期观测 excluded=TRUE", avl["cycle"]["excluded"] is True)
check("车次已连接到车辆块与司机班次",
      bool(avl["blockLink"].get("block_code")) and bool(avl["blockLink"].get("shift_code")))

st, reps = req("GET", "/api/experiments/1/feasibility/reports?route=R1")
check("可行性报告已持久化留痕（含量方法与重放）",
      len(reps) >= 6 and {r["method"] for r in reps}
      >= {"QUANTILE", "BLOCK_RESAMPLE", "BLOCK_RESAMPLE_REPLAY"})

st, api_docs = req("GET", "/v3/api-docs")
check("OpenAPI 收录可执行性接口",
      any("feasibility" in p for p in api_docs.get("paths", {})))

print(f"\n通过 {len(passes)} 项，失败 {len(fails)} 项")
if fails:
    print("失败项：" + "; ".join(fails))
    sys.exit(1)
print("ALL_VERIFY_CHECKS_PASSED")
PY