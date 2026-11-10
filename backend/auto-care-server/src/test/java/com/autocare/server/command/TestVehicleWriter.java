package com.autocare.server.command;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试作用域的业务动作：往 {@code t_vehicle} 插一行，交回 201 + 相对 Location + 业务主键。
 *
 * <p><b>为什么用 JdbcTemplate 不写 Mapper</b>：本片不实现车辆业务（那是 G1-02a），
 * 这里只需要一个"真的写库、真的会被回滚"的动作。JdbcTemplate 和 MyBatis 走同一个 DataSource，
 * 所以它<b>自动参与执行器开的那笔事务</b>——回滚时这一行跟着消失。用 JdbcTemplate 反而更直白：
 * 没有任何业务层的 catch 或补偿逻辑在中间打岔，事务边界看得一清二楚。
 *
 * <p><b>它住在 src/test 下，不会进 fat jar</b>，也不会出现在生产环境。
 */
@Component
public class TestVehicleWriter {

    /** 测试专用标记：清理靠它，断言也靠它。别改成会跟种子数据撞上的值。 */
    public static final String TEST_MODEL = "G1-01-TEST";

    private final JdbcTemplate jdbcTemplate;

    public TestVehicleWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插一行并组装成功结果。 */
    public CommandOutcome<Map<String, Object>> insert(long actorId, TestVehicleRequest request) {
        long vehicleId = insertRow(actorId, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vehicleId", String.valueOf(vehicleId));
        data.put("modelCode", TEST_MODEL);
        data.put("plateMasked", mask(request.plateNumber()));
        data.put("mileage", request.mileage());
        data.put("rowVersion", "1");
        return CommandOutcome.created("/api/v1/vehicles/" + vehicleId, data, vehicleId);
    }

    private long insertRow(long actorId, TestVehicleRequest request) {
        // 时间和项目里其他地方一样，写应用算出来的 UTC 墙上时间，不用 CURRENT_TIMESTAMP：
        // 库列是 DATETIME(3)，会话时区已被连接参数钉成 UTC，两边才对得上。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO t_vehicle "
                            + "(owner_id, model_code, plate_mask_source, plate_masked, mileage, row_version, "
                            + " created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, actorId);
            ps.setString(2, TEST_MODEL);
            ps.setString(3, request.plateNumber());
            ps.setString(4, mask(request.plateNumber()));
            ps.setInt(5, request.mileage());
            ps.setObject(6, now);
            ps.setObject(7, now);
            return ps;
        });
        // 同一笔事务、同一条连接，LAST_INSERT_ID() 就是我们刚插的那一行
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** 脱敏车牌，与种子数据同一套规则：前 2 位 + *** + 后 2 位。 */
    public static String mask(String plateNumber) {
        return plateNumber.substring(0, 2) + "***" + plateNumber.substring(plateNumber.length() - 2);
    }
}
