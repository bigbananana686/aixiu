package com.autocare.server.order.lifecycle;

import com.autocare.common.identity.Role;
import java.util.Set;

/** 迁移表集中表达角色、前态、后态和契约 operationId。 */
public enum OrderAction {
    ACCEPT("acceptOrder", "ACCEPT_ORDER", Role.TECH, Set.of("RESERVED"), "ACCEPTED"),
    DECLINE("declineOrder", "DECLINE_ORDER", Role.TECH, Set.of("RESERVED"), "CANCELLED"),
    CANCEL("cancelOrder", "CANCEL_ORDER", Role.OWNER, Set.of("RESERVED", "ACCEPTED"), "CANCELLED"),
    DEPART("departOrder", "DEPART_ORDER", Role.TECH, Set.of("ACCEPTED"), "EN_ROUTE"),
    ARRIVE("arriveOrder", "ARRIVE_ORDER", Role.TECH, Set.of("EN_ROUTE"), "ARRIVED");

    final String operationId;
    final String logAction;
    final Role role;
    final Set<String> fromStates;
    final String toState;

    OrderAction(String operationId, String logAction, Role role, Set<String> fromStates, String toState) {
        this.operationId = operationId;
        this.logAction = logAction;
        this.role = role;
        this.fromStates = fromStates;
        this.toState = toState;
    }
}
