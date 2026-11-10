package com.autocare.server.vehicle;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.command.CommandExecutor;
import com.autocare.server.command.CommandOutcome;
import com.autocare.server.command.CommandResult;
import com.autocare.server.command.CommandSpec;
import com.autocare.server.user.UserEntity;
import com.autocare.server.user.UserMapper;
import com.autocare.server.web.UtcTimes;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;


@Service
public class VehicleService {
    private final VehicleMapper vehicleMapper;
    private final CommandExecutor commandExecutor;
    private static final String PLATE_MASKED_ABSENT = "未提供";
    private final UserMapper userMapper;

    public VehicleService(VehicleMapper vehicleMapper, CommandExecutor commandExecutor,UserMapper userMapper) {
        this.commandExecutor = commandExecutor;
        this.vehicleMapper = vehicleMapper;
        this.userMapper = userMapper;
    }

    public CommandResult update(long actorId, String rawVehicleId, VehicleUpdateRequest body,
                                String key, String requestId) {
        assertOwnerRole(actorId);
        validateUpdateRequest(body);
        if (rawVehicleId == null || !rawVehicleId.matches("^[1-9][0-9]{0,18}$")) {
            throw ApiException.invalid(FieldErrors.invalidFormat(
                    "/vehicleId", "必须为无前导零的十进制正整数"));
        }
        long vehicleId;
        try {
            vehicleId = Long.parseLong(rawVehicleId);
        } catch (NumberFormatException ex) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/vehicleId", "超出支持的整数范围"));
        }
        if (vehicleMapper.findOwned(vehicleId, actorId) == null) {
            throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return commandExecutor.execute(
                CommandSpec.of("updateVehicle").pathResource("vehicleId", rawVehicleId),
                key,
                body,
                requestId,
                () -> applyUpdate(actorId, vehicleId, body),   // 下一轮填
                this::isOwnedBy);
    }

    private boolean isOwnedBy(long actorId, Long resourceId) {
        if (resourceId == null) {
            return false;
        }
        return vehicleMapper.findOwned(resourceId, actorId) != null;
    }

    private void validateUpdateRequest(VehicleUpdateRequest body) {
        if (!body.has("expectedRowVersion")) {
            throw ApiException.invalid(FieldErrors.required("/expectedRowVersion"));
        }
        // 数值形态必须在这一步拦下：Jackson 会把 JSON 数字 1 强制转成 "1"，不拦就等于
        // 放过了契约 §2.1 明令拒绝的写法（反例栏「0、数字3、"03" 拒绝」，§1.5 INVALID_TYPE）。
        if (body.hasWrongType("expectedRowVersion")) {
            throw ApiException.invalid(FieldErrors.invalidType(
                    "/expectedRowVersion", "期望版本必须是字符串形态的十进制整数，不接受 JSON 数值"));
        }
        if (body.getExpectedRowVersion() == null) {
            throw ApiException.invalid(FieldErrors.nullNotAllowed("/expectedRowVersion"));
        }
        if (!body.getExpectedRowVersion().matches("^[1-9][0-9]{0,9}$")) {
            throw ApiException.invalid(FieldErrors.invalidFormat(
                    "/expectedRowVersion", "必须为无前导零的十进制正整数，最多10位"));
        }
        if (!body.has("modelCode")
                && !body.has("plateNumber")
                && !body.has("mileage")) {
            throw ApiException.invalid("至少提供一个可修改字段");
        }
        if (body.has("mileage")) {
            Integer mileage = body.getMileage();
            if (mileage == null) {
                throw ApiException.invalid(FieldErrors.nullNotAllowed("/mileage"));
            }
            if (mileage < 0 || mileage > 9999999) {
                throw ApiException.invalid(FieldErrors.outOfRange(
                        "/mileage", "里程必须在0—9999999之间"));
            }
        }
        if (body.has("modelCode")) {
            if (body.getModelCode() == null) {
                throw ApiException.invalid(FieldErrors.nullNotAllowed("/modelCode"));
            }
            String modelCode = body.getModelCode();
            if (modelCode.length() < 1 || modelCode.length() > 64) {
                throw ApiException.invalid(FieldErrors.outOfRange(
                        "/modelCode", "车型编码长度必须为1—64"));
            }
        }
        if (body.has("plateNumber")) {
            if (body.getPlateNumber() == null) {
                throw ApiException.invalid(FieldErrors.nullNotAllowed("/plateNumber"));
            }
            String plateNumber = body.getPlateNumber();
            if (plateNumber.length() < 1 || plateNumber.length() > 16) {
                throw ApiException.invalid(FieldErrors.outOfRange("/plateNumber", "车牌长度必须为1—16"));
            }
        }
    }

    private CommandOutcome<Vehicle> applyUpdate(
            long actorId, long vehicleId, VehicleUpdateRequest body) {
        VehicleEntity current = vehicleMapper.findOwned(vehicleId, actorId);
        if (current == null) {
            throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
        }
        long expectedVersion = Long.parseLong(body.getExpectedRowVersion());
        current.setUpdatedAt(UtcTimes.nowUtc());
        if (expectedVersion != current.getRowVersion().longValue()) {
            throw ApiException.of(ErrorCode.RESOURCE_VERSION_CONFLICT)
                    .field(FieldErrors.versionMismatch(
                            "/expectedRowVersion", "期望版本与当前版本不一致"));
        }
        if (body.has("modelCode")) {
            current.setModelCode(body.getModelCode());
        }

        if (body.has("mileage")) {
            current.setMileage(body.getMileage());
        }

        if (body.has("plateNumber")) {
            String plateNumber = body.getPlateNumber();
            current.setPlateMaskSource(plateNumber);
            current.setPlateMasked(maskPlate(plateNumber));
        }
        int affected = vehicleMapper.updateOwned(current, expectedVersion);
        if (affected == 0) {
            VehicleEntity latest = vehicleMapper.findOwned(vehicleId, actorId);
            if (latest == null) {
                throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
            }
            throw ApiException.of(ErrorCode.RESOURCE_VERSION_CONFLICT)
                    .field(FieldErrors.versionMismatch(
                            "/expectedRowVersion", "期望版本与当前版本不一致"));
        }
        if (affected != 1) {
            throw new IllegalStateException("车辆更新影响行数异常");
        }
        VehicleEntity updated = vehicleMapper.findOwned(vehicleId, actorId);
        if (updated == null) {
            throw new IllegalStateException("车辆更新后未找到记录");
        }
        return CommandOutcome.ok(Vehicle.from(updated), vehicleId);
    }

    private String maskPlate(String plateNumber) {
        int length = plateNumber.codePointCount(0, plateNumber.length());
        if (length < 5) {
            return "***";
        }

        int prefixEnd = plateNumber.offsetByCodePoints(0, 2);
        int suffixStart = plateNumber.offsetByCodePoints(0, length - 2);

        return plateNumber.substring(0, prefixEnd)
                + "***"
                + plateNumber.substring(suffixStart);
    }

    public CommandResult create(long actorId, VehicleCreateRequest body,
                                String key, String requestId) {
        assertOwnerRole(actorId);
        validateCreateRequest(body);
        return commandExecutor.execute(CommandSpec.of("createVehicle"),
                key,
                body,
                requestId,
                () -> applyCreate(actorId, body),
                this::isOwnedBy);
    }

    private void validateCreateRequest(VehicleCreateRequest body) {
        if (!body.has("modelCode")) {
            throw ApiException.invalid(FieldErrors.required("/modelCode"));
        }
        if (body.getModelCode() == null) {
            throw ApiException.invalid(FieldErrors.nullNotAllowed("/modelCode"));
        }
        String modelCode = body.getModelCode();
        if (modelCode.length() < 1 || modelCode.length() > 64) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/modelCode", "车型编码长度必须为1—64"));
        }
        if (!body.has("mileage")) {
            throw ApiException.invalid(FieldErrors.required("/mileage"));
        }
        if (body.getMileage() == null) {
            throw ApiException.invalid(FieldErrors.nullNotAllowed("/mileage"));
        }
        Integer mileage = body.getMileage();
        if (mileage < 0 || mileage > 9999999) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/mileage", "里程必须在0—9999999之间"));
        }
        // 车牌是可选字段，但「不可 null（省略即不提供）」：显式 null 要单独报 400（§4.2 请求表）
        if (body.has("plateNumber") && body.getPlateNumber() == null) {
            throw ApiException.invalid(FieldErrors.nullNotAllowed("/plateNumber"));
        }
        if (body.getPlateNumber() != null
                && (body.getPlateNumber().length() < 1 || body.getPlateNumber().length() > 16)) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/plateNumber", "车牌长度必须为1—16"));
        }
    }

    private CommandOutcome<Vehicle> applyCreate(long actorId, VehicleCreateRequest body) {
        VehicleEntity entity = new VehicleEntity();
        entity.setOwnerId(actorId);
        entity.setModelCode(body.getModelCode());
        entity.setPlateMaskSource(body.getPlateNumber());
        entity.setPlateMasked(body.getPlateNumber() == null ?
                PLATE_MASKED_ABSENT :
                maskPlate(body.getPlateNumber()));
        entity.setMileage(body.getMileage());
        entity.setRowVersion(1);
        LocalDateTime now = UtcTimes.nowUtc();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        vehicleMapper.insert(entity);
        return CommandOutcome.created("/api/v1/vehicles/" + entity.getId(),
                Vehicle.from(entity),
                entity.getId());
    }

    public VehicleListResult list(long actorId, String rawPage, String rawPageSize,
                                  Set<String> queryParamNames) {
        assertOwnerRole(actorId);
        assertAllowedQueryParams(queryParamNames);
        Integer page;
        Integer pageSize;
        page = parsePaging(rawPage, "page", 1, 1, 10000);
        pageSize = parsePaging(rawPageSize, "pageSize", 20, 1, 100);
        int offset = (page - 1) * pageSize;
        List<Vehicle> items = vehicleMapper.listByOwner(actorId, pageSize, offset).stream().map(Vehicle::from).toList();
        Long total = vehicleMapper.countByOwner(actorId);
        return new VehicleListResult(items, page, pageSize, total);
    }

    /**
     * 列表只接受 {@code page} / {@code pageSize} 两个 query 参数（契约 §4.1：请求不接受
     * {@code ownerId} 等筛选字段，传了返回 400；§1.5 的子码 {@code NOT_ALLOWED} 就是
     * 「该筛选/字段在此场景被禁止（用他人身份做筛选）」）。
     *
     * <p><b>为什么必须主动查</b>：Spring 对 handler 没声明的 query 参数是<b>静默忽略</b>的。
     * 不查就等于默许客户端按别人的身份筛，而且它还会拿到 200——客户永远看不出自己传错了。
     */
    private void assertAllowedQueryParams(Set<String> names) {
        for (String name : names) {
            if (!"page".equals(name) && !"pageSize".equals(name)) {
                throw ApiException.invalid(FieldErrors.notAllowed(
                        "/" + name, "本接口不接受该筛选字段"));
            }
        }
    }

    private static int parsePaging(String raw, String name, int defaultValue, int min, int max) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;                       // 省略 = 取默认
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiException.invalid(FieldErrors.invalidFormat("/" + name, "必须为整数"));
        }
        if (value < min || value > max) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/" + name, "必须为 " + min + " 至 " + max + " 的整数"));
        }
        return value;
    }
    private void assertOwnerRole(long actorId) {
        UserEntity user = userMapper.findById(actorId);
        if (user == null || !user.isEnabled()) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (user.getRole() != Role.OWNER) {
            throw ApiException.of(ErrorCode.FORBIDDEN);
        }
    }
}
