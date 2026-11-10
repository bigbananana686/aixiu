package com.autocare.server.order.lifecycle;

import com.autocare.server.order.OrderInputs;
import com.autocare.server.order.OrderRow;
import com.autocare.server.order.booking.OrderMapper;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.identity.Role;
import com.autocare.server.command.*;
import com.autocare.server.user.UserEntity;
import com.autocare.server.user.UserMapper;
import com.autocare.server.web.UtcTimes;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OrderLifecycleService {
    private final OrderLifecycleMapper mapper;
    private final OrderMapper orderMapper;
    private final UserMapper users;
    private final CommandExecutor commands;

    public OrderLifecycleService(OrderLifecycleMapper mapper, OrderMapper orderMapper,
                                 UserMapper users, CommandExecutor commands) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.users = users;
        this.commands = commands;
    }

    public CommandResult execute(long actor, String pathId, OrderAction action,
                                 OrderVersionRequest body, String key, String requestId) {
        currentRole(actor, action.role);
        long id = OrderInputs.id(pathId);
        int version = body.validatedVersion();
        String reason = action == OrderAction.DECLINE ? ((DeclineOrderRequest) body).validatedReason() : null;
        OrderRow reference = mapper.find(id);
        requireVisible(reference, actor, action.role);
        // 摘要只使用完整、已校验的规范值；路径和版本都参与，同一原因首尾空白不改变意图。
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("expectedRowVersion", String.valueOf(version));
        if (reason != null) normalized.put("reason", reason);
        return commands.execute(CommandSpec.of(action.operationId).pathResource("orderId", String.valueOf(id))
                        .orderLinked(), key, normalized, requestId,
                () -> transition(actor, reference.getTechnicianId(), id, action, version, reason, requestId),
                (who, resource) -> resource != null && visible(mapper.find(resource), who, action.role));
    }

    private CommandOutcome<?> transition(long actor, long technician, long id, OrderAction action,
                                         int version, String reason, String requestId) {
        mapper.lockTechnician(technician);
        OrderRow row = mapper.lockOrder(id);
        requireVisible(row, actor, action.role);
        // 权限/身份锁内复核；事务前预读只用于定位不可变 technician_id。
        currentRole(actor, action.role);
        LocalDateTime now = orderMapper.dbNowUtc();
        if (action == OrderAction.ACCEPT &&
                (("RESERVED".equals(row.getState()) && !now.isBefore(row.getAcceptDeadline()))
                || ("CANCELLED".equals(row.getState()) && mapper.countAcceptanceExpiry(id) > 0))) {
            throw ApiException.of(ErrorCode.ORDER_ACCEPT_EXPIRED);
        }
        if (!action.fromStates.contains(row.getState())) throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT);
        if (version != row.getRowVersion()) throw ApiException.of(ErrorCode.ORDER_VERSION_CONFLICT);
        boolean active = !"CANCELLED".equals(action.toState);
        writeTransition(row, action.toState, active, action.logAction, actor, reason, requestId, now);
        if (action == OrderAction.ACCEPT) {
            return CommandOutcome.ok(new AcceptOrderResult(String.valueOf(id), action.toState,
                    String.valueOf(row.getRowVersion() + 1), UtcTimes.format(row.getAcceptDeadline()),
                    UtcTimes.format(row.getCommittedEnd()), UtcTimes.format(row.getEffectiveEnd()),
                    UtcTimes.format(now)), id);
        }
        return CommandOutcome.ok(new OrderStateResult(String.valueOf(id), action.toState,
                String.valueOf(row.getRowVersion() + 1), UtcTimes.format(row.getCommittedEnd()),
                UtcTimes.format(row.getEffectiveEnd()), nullableId(row.getApprovedQuoteId()),
                nullableId(row.getPendingQuoteId()), UtcTimes.format(row.getActualStart()),
                UtcTimes.format(row.getActualFinish()), active, UtcTimes.format(now)), id);
    }

    /** 只能在调用方已经持有资源行及订单行锁的同一事务内使用。 */
    void writeTransition(OrderRow row, String to, boolean active, String action, long actor,
                         String summary, String requestId, LocalDateTime now) {
        if (mapper.transition(row.getId(), row.getState(), row.getRowVersion(), to, active, now) != 1) {
            // 已经锁内完成状态/版本判定；0 行是协议被破坏，不能冒充客户端版本冲突。
            throw new IllegalStateException("Locked order transition affected no row");
        }
        mapper.insertStateLog(row.getId(), row.getState(), to, action, actor, requestId, now);
        mapper.insertOperation(row.getId(), action, actor, summary, requestId, now);
    }

    public Role currentRole(long actor, Role required) {
        UserEntity user = users.findById(actor);
        if (user == null || !user.isEnabled()) throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        if (required != null && user.getRole() != required) throw ApiException.of(ErrorCode.FORBIDDEN);
        if (user.getRole() != Role.OWNER && user.getRole() != Role.TECH) throw ApiException.of(ErrorCode.FORBIDDEN);
        return user.getRole();
    }

    public static void requireVisible(OrderRow row, long actor, Role role) {
        if (!visible(row, actor, role)) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public static boolean visible(OrderRow row, long actor, Role role) {
        return row != null && (role == Role.OWNER ? row.getOwnerId() == actor : row.getTechnicianId() == actor);
    }

    public static String nullableId(Long id) { return id == null ? null : String.valueOf(id); }
}
