package com.autocare.server.conversation;

import com.autocare.ai.conversation.ConversationAssistant;
import java.util.*;

/** 经本轮证据与对象权限复核的业务快照，独立于滑动记忆窗口。 */
public record ConversationFacts(
  String vehicleId,
  String modelCode,
  Integer mileage,
  List<String> symptoms,
  String address,
  String preferredTimeText,
  List<String> serviceItemIds
) {
  public ConversationFacts {
    symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
    serviceItemIds =
      serviceItemIds == null ? List.of() : List.copyOf(serviceItemIds);
  }

  public static ConversationFacts empty() {
    return new ConversationFacts(
      null,
      null,
      null,
      List.of(),
      null,
      null,
      List.of()
    );
  }

  ConversationFacts apply(
    long actor,
    String message,
    ConversationAssistant.Prepared p,
    ConversationTools tools
  ) {
    var reply = p.reply();
    if (
      reply == null ||
      reply.reply() == null ||
      reply.reply().isBlank() ||
      reply.reply().length() > 1200 ||
      reply.updates() == null ||
      reply.updates().size() > 20
    ) throw new IllegalArgumentException("Invalid assistant reply");
    String vehicle = vehicleId,
      model = modelCode,
      location = address,
      time = preferredTimeText;
    Integer km = mileage;
    var symptoms = new LinkedHashSet<>(this.symptoms);
    var services = new LinkedHashSet<>(serviceItemIds);
    for (var u : reply.updates()) {
      if (
        u == null ||
        u.field() == null ||
        u.value() == null ||
        u.value().isBlank() ||
        u.value().length() > 200 ||
        u.evidence() == null ||
        u.evidence().isBlank() ||
        u.evidence().length() > 250
      ) throw new IllegalArgumentException("Invalid fact update");
      boolean user = message.contains(u.evidence());
      boolean trusted = p
        .toolEvidence()
        .stream()
        .anyMatch(e -> e.contains(u.evidence()));
      if (!user && !trusted) throw new IllegalArgumentException(
        "Fact evidence absent"
      );
      String v = u.value().strip();
      switch (u.field()) {
        case "vehicleId" -> {
          if (
            !tools.ownVehicle(actor, v) || !u.evidence().contains(v)
          ) throw new IllegalArgumentException("Vehicle not visible");
          vehicle = v;
        }
        case "modelCode" -> {
          if (
            v.length() > 64 ||
            !u
              .evidence()
              .toLowerCase(Locale.ROOT)
              .contains(v.toLowerCase(Locale.ROOT))
          ) throw new IllegalArgumentException("Model not evidenced");
          model = v;
        }
        case "mileage" -> {
          int n = Integer.parseInt(v);
          if (
            n < 0 || n > 2000000 || !mileageEvidence(u.evidence(), n)
          ) throw new IllegalArgumentException("Mileage not evidenced");
          km = n;
        }
        case "symptom" -> {
          literalUser(user, u.evidence(), v);
          symptoms.add(v);
        }
        case "address" -> {
          literalUser(user, u.evidence(), v);
          location = v;
        }
        case "preferredTimeText" -> {
          literalUser(user, u.evidence(), v);
          time = v;
        }
        case "serviceItemId" -> {
          if (
            !tools.serviceExists(v) || !u.evidence().contains(v)
          ) throw new IllegalArgumentException("Service not evidenced");
          services.add(v);
        }
        default -> throw new IllegalArgumentException("Unknown fact field");
      }
    }
    if (
      symptoms.size() > 20 || services.size() > 8
    ) throw new IllegalArgumentException("Fact list budget exceeded");
    return new ConversationFacts(
      vehicle,
      model,
      km,
      List.copyOf(symptoms),
      location,
      time,
      List.copyOf(services)
    );
  }

  private static void literalUser(boolean user, String evidence, String value) {
    if (!user || !evidence.contains(value)) throw new IllegalArgumentException(
      "User fact not evidenced"
    );
  }

  static boolean mileageEvidence(String evidence, int value) {
    var m = java.util.regex.Pattern.compile(
      "(?<![0-9.])([0-9]{1,7}(?:\\.[0-9]{1,3})?)\\s*(万|千)?(?![0-9.])"
    ).matcher(evidence);
    while (m.find()) {
      var n = new java.math.BigDecimal(m.group(1));
      String unit = m.group(2);
      if (unit != null) n = n.multiply(
        java.math.BigDecimal.valueOf("万".equals(unit) ? 10000 : 1000)
      );
      if (n.compareTo(java.math.BigDecimal.valueOf(value)) == 0) return true;
    }
    return false;
  }

  public List<String> missing() {
    var x = new ArrayList<String>();
    if (vehicleId == null) x.add("vehicleId");
    if (symptoms.isEmpty()) x.add("symptoms");
    if (preferredTimeText == null) x.add("preferredTimeText");
    if (address == null) x.add("address");
    if (serviceItemIds.isEmpty()) x.add("serviceItemIds");
    return List.copyOf(x);
  }
}
