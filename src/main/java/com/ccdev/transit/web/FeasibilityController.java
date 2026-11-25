package com.ccdev.transit.web;

import com.ccdev.transit.engine.feasibility.FeasibilityEngine;
import com.ccdev.transit.service.FeasibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线路班次可执行性分析接口：
 * 规则时刻表/车辆块/AVL 分段/周期分布审计，以及两种不确定性估计路线的可行性报告。
 */
@Tag(name = "线路班次可执行性分析", description = "AVL 分段、车辆块排班账、周期时间分布与给定车队数可行性报告")
@RestController
@RequestMapping("/api/experiments/{id}/feasibility")
public class FeasibilityController {

    private final FeasibilityService service;

    public FeasibilityController(FeasibilityService service) {
        this.service = service;
    }

    @Operation(summary = "模块总览：车队、样例规模与节庆留出周")
    @GetMapping("/overview")
    public Map<String, Object> overview(@PathVariable long id) {
        Map<String, Object> m = service.overview();
        if (!m.containsKey("holdout")) {
            m.put("holdout", service.holdoutSummary(id));
        }
        return m;
    }

    @Operation(summary = "周期时间分布审计（含封路/节庆剔除明细）")
    @GetMapping("/distribution")
    public Map<String, Object> distribution(@PathVariable long id,
                                            @RequestParam String route,
                                            @RequestParam(defaultValue = "0.90") double quantile) {
        return service.distributionAudit(id, route, quantile);
    }

    @Operation(summary = "车辆块与司机换班排班账（可按日期过滤）")
    @GetMapping("/blocks")
    public List<Map<String, Object>> blocks(@PathVariable long id,
                                            @RequestParam String route,
                                            @RequestParam(required = false) LocalDate date) {
        return service.listBlocks(id, route, date);
    }

    @Operation(summary = "单车次 AVL 四段分解与周期时间观测")
    @GetMapping("/trips/{tripCode}")
    public Map<String, Object> tripAvl(@PathVariable long id, @PathVariable String tripCode) {
        return service.tripAvlDetail(id, tripCode);
    }

    @Operation(summary = "节庆封路留出周摘要（封路观测不进常态分布的核验依据）")
    @GetMapping("/holdout")
    public Map<String, Object> holdout(@PathVariable long id) {
        return service.holdoutSummary(id);
    }

    @Operation(summary = "运行两种估计路线的可行性报告（holdoutWeek=6 时额外返回留出周真实重放对照）")
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@PathVariable long id,
                                       @RequestParam String route,
                                       @RequestParam(defaultValue = "4") int fleetSize,
                                       @RequestParam(required = false) Integer holdoutWeek,
                                       @RequestParam(defaultValue = "0.90") double quantile) {
        Map<String, FeasibilityEngine.RouteReport> reports =
                service.runBothMethods(id, route, fleetSize, holdoutWeek, quantile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("fleetSize", fleetSize);
        out.put("holdoutWeek", holdoutWeek);
        out.put("quantile", quantile);
        out.put("reports", reports);
        out.put("auditNote", "QUANTILE=历史分位数余量（易审计、不表达路段相关延误）；"
                + "BLOCK_RESAMPLE=运行日整日重采样（保留同日拥堵相关、依赖代表性运行日）；"
                + "HOLDOUT_ACTUAL_REPLAY=留出周真实观测重放（含封路极端值，仅作对照）");
        return out;
    }

    @Operation(summary = "历史可行性报告留痕")
    @GetMapping("/reports")
    public List<Map<String, Object>> reports(@PathVariable long id,
                                             @RequestParam(required = false) String route) {
        return service.listReports(id, route);
    }
}
