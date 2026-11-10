package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class BookingObservationTest extends D2OrderSupport {

  List<Long> calls = new ArrayList<>();

  @AfterEach
  void unlinkCalls() {
    // 父类随后删除订单；先移除可选观察外键，才能删除本测试的调用元数据。
    for (long id : calls) {
      db.update(
        "UPDATE t_service_order SET ai_parse_id=NULL WHERE ai_parse_id=?",
        id
      );
      db.update("DELETE FROM t_ai_call_log WHERE id=?", id);
    }
  }

  long call(long actor, String outcome) {
    db.update(
      "INSERT INTO t_ai_call_log(scene,actor_id,input_hash,prompt_version,model_id,document_versions,outcome,created_at) VALUES('BOOKING_DRAFT',?,REPEAT('a',64),'booking-draft-v1','test-fixture','[]',?,UTC_TIMESTAMP(3))",
      actor,
      outcome
    );
    long id = db.queryForObject(
      "SELECT MAX(id) FROM t_ai_call_log",
      Long.class
    );
    calls.add(id);
    return id;
  }

  String body(String parseId) {
    return (
      "{\"vehicleId\":\"1\",\"technicianId\":\"3\",\"serviceStart\":\"" +
      day +
      "T05:00:00.000Z\",\"items\":[{\"serviceItemId\":\"1\",\"quantity\":1,\"catalogVersion\":\"1\"}],\"address\":\"synthetic\",\"aiParseId\":\"" +
      parseId +
      "\"}"
    );
  }

  @Test
  void ownSuccessfulObservation_canBeLinked_afterUserConfirmsBooking()
    throws Exception {
    long parse = call(1, "SUCCEEDED");
    var r = send("POST", "", 1, body("" + parse), key());
    expect(r, 201, null);
    long order = r.data().path("orderId").asLong();
    ids.add(order);
    assertThat(
      db.queryForObject(
        "SELECT ai_parse_id FROM t_service_order WHERE id=?",
        Long.class,
        order
      )
    ).isEqualTo(parse);
  }

  @Test
  void otherOwnersOrFailedObservation_isNotAnAuthorizationCredential()
    throws Exception {
    for (long id : List.of(call(2, "SUCCEEDED"), call(1, "FAILED")))
      expect(
        send("POST", "", 1, body("" + id), key()),
        400,
        "INVALID_ARGUMENT"
      );
  }

  @Test
  void observationId_doesNotBypassVehicleOwnership() throws Exception {
    long parse = call(1, "SUCCEEDED");
    String b = body("" + parse).replace(
      "\"vehicleId\":\"1\"",
      "\"vehicleId\":\"2\""
    );
    expect(send("POST", "", 1, b, key()), 404, "RESOURCE_NOT_FOUND");
  }
}
