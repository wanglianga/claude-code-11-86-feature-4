package com.ccdev.transit.engine.feasibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 经验分布：样本分位数（线性插值）、均值、标准差、极值。
 * 全部来自显式样本，不假设正态，保证审计时可以逐个样本复核。
 */
public final class EmpiricalDistribution {

    private final List<Double> sorted;
    private final double mean;
    private final double sd;

    private EmpiricalDistribution(List<Double> sorted, double mean, double sd) {
        this.sorted = sorted;
        this.mean = mean;
        this.sd = sd;
    }

    public static EmpiricalDistribution of(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("经验分布至少需要 1 个样本");
        }
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        double sum = 0;
        for (double v : s) {
            sum += v;
        }
        double m = sum / s.size();
        double var = 0;
        for (double v : s) {
            var += (v - m) * (v - m);
        }
        double sdv = s.size() > 1 ? Math.sqrt(var / (s.size() - 1)) : 0;
        return new EmpiricalDistribution(Collections.unmodifiableList(s), m, sdv);
    }

    public int n() {
        return sorted.size();
    }

    public double mean() {
        return mean;
    }

    public double sd() {
        return sd;
    }

    public double min() {
        return sorted.get(0);
    }

    public double max() {
        return sorted.get(sorted.size() - 1);
    }

    public double median() {
        return quantile(0.5);
    }

    /**
     * 分位数：类型 7（与 numpy 默认一致），h=(n-1)q 后线性插值。
     */
    public double quantile(double q) {
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException("分位数必须在 [0,1]");
        }
        int n = sorted.size();
        if (n == 1) {
            return sorted.get(0);
        }
        double h = (n - 1) * q;
        int lo = (int) Math.floor(h);
        double frac = h - lo;
        if (lo + 1 >= n) {
            return sorted.get(n - 1);
        }
        return sorted.get(lo) * (1 - frac) + sorted.get(lo + 1) * frac;
    }

    public List<Double> samples() {
        return sorted;
    }
}
