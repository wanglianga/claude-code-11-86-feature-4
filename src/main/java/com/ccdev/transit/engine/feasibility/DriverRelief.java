package com.ccdev.transit.engine.feasibility;

import java.time.LocalDate;

/**
 * 司机换班点：时刻与接手车辆，供车辆块账解释周转断点中的“司机换班不足”。
 */
public record DriverRelief(
        String shiftCode,
        String driverCode,
        String routeCode,
        LocalDate serviceDate,
        long reliefEpochSecond,
        String reliefVehicleCode) {
}
