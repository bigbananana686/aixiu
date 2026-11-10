package com.autocare.server.conversation;

import com.autocare.ai.conversation.ConversationAssistant;
import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.catalog.*;
import com.autocare.server.order.lifecycle.*;
import com.autocare.server.user.UserMapper;
import com.autocare.server.vehicle.VehicleMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ConversationTools {

  private final UserMapper users;
  private final VehicleMapper vehicles;
  private final ServiceItemMapper catalog;
  private final OrderLifecycleMapper orders;
  private final ObjectMapper json;

  public ConversationTools(
    UserMapper users,
    VehicleMapper vehicles,
    ServiceItemMapper catalog,
    OrderLifecycleMapper orders,
    ObjectMapper json
  ) {
    this.users = users;
    this.vehicles = vehicles;
    this.catalog = catalog;
    this.orders = orders;
    this.json = json;
  }

  public Role role(long actor) {
    var u = users.findById(actor);
    if (u == null || !u.isEnabled()) throw ApiException.of(
      ErrorCode.UNAUTHENTICATED
    );
    if (
      u.getRole() != Role.OWNER && u.getRole() != Role.TECH
    ) throw ApiException.of(ErrorCode.FORBIDDEN);
    return u.getRole();
  }

  public boolean ownVehicle(long actor, String id) {
    Long n = id(id);
    return (
      role(actor) == Role.OWNER &&
      n != null &&
      vehicles.findOwned(n, actor) != null
    );
  }

  public ConversationAssistant.ReadTools forActor(long actor) {
    return new ConversationAssistant.ReadTools() {
      public String myVehicles() {
        if (role(actor) != Role.OWNER) return "[]";
        return json.writeValueAsString(
          vehicles
            .listByOwner(actor, 20, 0)
            .stream()
            .map(v ->
              Map.of(
                "vehicleId",
                "" + v.getId(),
                "modelCode",
                v.getModelCode(),
                "mileage",
                v.getMileage(),
                "plateMasked",
                v.getPlateMasked()
              )
            )
            .toList()
        );
      }

      public String serviceCatalog() {
        role(actor);
        return json.writeValueAsString(
          catalog
            .listEnabled(30, 0)
            .stream()
            .map(ConversationTools.this::item)
            .toList()
        );
      }

      public String orderSummary(String orderId) {
        Role role = role(actor);
        Long n = id(orderId);
        var o = n == null ? null : orders.find(n);
        if (
          !OrderLifecycleService.visible(o, actor, role)
        ) return "{\"error\":\"RESOURCE_NOT_FOUND\"}";
        return json.writeValueAsString(
          Map.of(
            "orderId",
            orderId,
            "state",
            o.getState(),
            "modelCode",
            o.getModelSnapshot(),
            "mileage",
            o.getMileageSnapshot()
          )
        );
      }

      public String quoteDraft(List<String> ids) {
        role(actor);
        return json.writeValueAsString(draft(ids));
      }
    };
  }

  public boolean serviceExists(String id) {
    Long n = id(id);
    return n != null && catalog.findEnabled(n) != null;
  }

  public Map<String, Object> draft(List<String> ids) {
    if (
      ids == null ||
      ids.isEmpty() ||
      ids.size() > 8 ||
      new HashSet<>(ids).size() != ids.size()
    ) return Map.of("error", "INVALID_ARGUMENT");
    var items = new ArrayList<Map<String, Object>>();
    BigDecimal sum = BigDecimal.ZERO;
    for (String id : ids) {
      Long n = id(id);
      var row = n == null ? null : catalog.findEnabled(n);
      if (row == null) return Map.of("error", "RESOURCE_NOT_FOUND");
      items.add(item(row));
      sum = sum.add(row.getPrice());
    }
    return Map.of(
      "items",
      items,
      "estimatedTotal",
      sum.setScale(2).toPlainString(),
      "currency",
      "CNY",
      "requiresTechnicianReview",
      true
    );
  }

  private Map<String, Object> item(ServiceItemRow r) {
    return Map.of(
      "serviceItemId",
      "" + r.getId(),
      "name",
      r.getName(),
      "unitPrice",
      r.getPrice().setScale(2).toPlainString(),
      "currency",
      r.getCurrency(),
      "durationMinutes",
      r.getDurationMinutes(),
      "catalogVersion",
      "" + r.getVersion(),
      "quantity",
      1
    );
  }

  private static Long id(String id) {
    try {
      return id != null && id.matches("[1-9][0-9]{0,18}")
        ? Long.valueOf(id)
        : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
