package com.autocare.server.vehicle;

import java.util.List;

public record VehicleListResult(List<Vehicle> items, int page, int pageSize, long total) {
}