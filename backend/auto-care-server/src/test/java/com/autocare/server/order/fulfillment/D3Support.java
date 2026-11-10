package com.autocare.server.order.fulfillment;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;

abstract class D3Support extends D2OrderSupport {

  long ready() throws Exception {
    long id = arrived();
    long q = publish(id, 1);
    expect(decide(id, q, false, 1, "1", key(), null), 200, null);
    return id;
  }

  long servicing() throws Exception {
    long id = ready();
    expect(
      send(
        "POST",
        "/" + id + "/start",
        3,
        "{\"expectedRowVersion\":\"6\"}",
        key()
      ),
      200,
      null
    );
    return id;
  }

  String line(String k, int service, int qty) {
    return (
      "{\"lineKey\":\"" +
      k +
      "\",\"serviceItemId\":\"" +
      service +
      "\",\"quantity\":" +
      qty +
      ",\"catalogVersion\":\"1\"}"
    );
  }

  String submission(long id, String purpose, String lines) {
    var r = db.queryForMap(
      "SELECT row_version,approved_quote_id,pending_quote_id FROM t_service_order WHERE id=?",
      id
    );
    return (
      "{\"purpose\":\"" +
      purpose +
      "\",\"expectedRowVersion\":\"" +
      r.get("row_version") +
      "\",\"expectedApprovedQuoteId\":" +
      qid(r.get("approved_quote_id")) +
      ",\"expectedPendingQuoteId\":" +
      qid(r.get("pending_quote_id")) +
      ",\"items\":[" +
      lines +
      "]}"
    );
  }

  String qid(Object v) {
    return v == null ? "null" : "\"" + v + "\"";
  }

  Reply submit(long id, String purpose, String lines) throws Exception {
    return send(
      "POST",
      "/" + id + "/quotes",
      3,
      submission(id, purpose, lines),
      key()
    );
  }

  long q(Reply r) {
    expect(r, 201, null);
    return r.data().path("quote").path("quoteId").asLong();
  }

  void confirmed(long id, long q) throws Exception {
    expect(
      decide(
        id,
        q,
        false,
        1,
        "" +
          db.queryForObject(
            "SELECT version FROM t_order_quote WHERE id=?",
            Integer.class,
            q
          ),
        key(),
        null
      ),
      200,
      null
    );
  }

  String stamp(long id, int plus) {
    return com.autocare.server.web.UtcTimes.format(
      db
        .queryForObject(
          "SELECT committed_end FROM t_service_order WHERE id=?",
          LocalDateTime.class,
          id
        )
        .plusMinutes(plus)
    );
  }

  String extension(long id, String end) {
    return (
      "{\"expectedRowVersion\":\"" +
      db.queryForObject(
        "SELECT row_version FROM t_service_order WHERE id=?",
        Integer.class,
        id
      ) +
      "\",\"targetEnd\":\"" +
      end +
      "\",\"reason\":\"extra inspection\"}"
    );
  }

  Reply extend(long id, String end, String key) throws Exception {
    return send("POST", "/" + id + "/extension", 3, extension(id, end), key);
  }

  void unchanged(long id, Map<String, Object> before) {
    assertThat(bounds(id)).isEqualTo(before);
  }

  void noOverlap() {
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM t_service_order a JOIN t_service_order b ON a.id<b.id AND a.technician_id=b.technician_id AND a.reservation_active=1 AND b.reservation_active=1 AND a.occupied_start<b.effective_end AND a.effective_end>b.occupied_start",
        Integer.class
      )
    ).isZero();
  }
}
