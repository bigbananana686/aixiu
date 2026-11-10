package com.autocare.server.order.lifecycle;

import com.autocare.server.order.OrderRow;
import com.autocare.server.order.booking.OrderMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.UUID;

/** 可由调度及恢复测试共同调用；候选读取与每单事务分离。 */
@Service
public class AcceptExpiryService {
    private static final Logger log = LoggerFactory.getLogger(AcceptExpiryService.class);
    private final OrderLifecycleMapper mapper;
    private final OrderMapper orders;
    private final OrderLifecycleService lifecycle;
    private final TransactionTemplate transaction;

    public AcceptExpiryService(OrderLifecycleMapper mapper, OrderMapper orders,
                               OrderLifecycleService lifecycle, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.orders = orders;
        this.lifecycle = lifecycle;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public int scan() {
        int changed = 0;
        for (long id : mapper.expiredCandidates()) {
            try {
                if (expire(id)) changed++;
            } catch (DataAccessException e) {
                // 单个锁等待或数据库故障不使其他候选饥饿；未提交候选下一轮仍可恢复。
                log.warn("接单到期处理失败 orderId={} exception={}; 下轮重试", id, e.getClass().getSimpleName());
            }
        }
        return changed;
    }

    public boolean expire(long id) {
        OrderRow reference = mapper.find(id);
        if (reference == null) return false;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            mapper.lockTechnician(reference.getTechnicianId());
            OrderRow row = mapper.lockOrder(id);
            if (row == null || !"RESERVED".equals(row.getState())) return false;
            LocalDateTime now = orders.dbNowUtc();
            if (now.isBefore(row.getAcceptDeadline())) return false;
            lifecycle.writeTransition(row, "CANCELLED", false, "EXPIRE_ACCEPT", mapper.expiryActor(),
                    "acceptDeadline reached", UUID.randomUUID().toString(), now);
            return true;
        }));
    }
}
