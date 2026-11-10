// Vehicle.java
package com.autocare.server.vehicle;
import com.autocare.server.web.UtcTimes;

public record Vehicle(
        String vehicleId,
        String modelCode,
        String plateMasked,
        int mileage,
        String rowVersion,
        String createdAt,
        String updatedAt) {

    public static Vehicle from(VehicleEntity e) {
        return new Vehicle(
                String.valueOf(e.getId()),
                e.getModelCode(),
                e.getPlateMasked(),
                e.getMileage(),
                String.valueOf(e.getRowVersion()),
                UtcTimes.format(e.getCreatedAt()),
                UtcTimes.format(e.getUpdatedAt()));
    }
}
