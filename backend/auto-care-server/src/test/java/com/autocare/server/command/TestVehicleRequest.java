package com.autocare.server.command;

/**
 * 测试作用域命令的请求体。
 *
 * <p>真实的 {@code createVehicle} DTO 要到 G1-02a 才出现（含全部字段校验）。这里只放摘要要用到的
 * 两个字段，形状与真接口一致——所以这条测试证明的是"将来接上真 DTO 也长这样"。
 */
public record TestVehicleRequest(String plateNumber, Integer mileage) {
}
