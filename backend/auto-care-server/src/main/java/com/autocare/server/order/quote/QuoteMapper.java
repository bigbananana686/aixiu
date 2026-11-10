package com.autocare.server.order.quote;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface QuoteMapper {
  @Insert(
    "INSERT INTO t_order_quote(order_id,version,purpose,base_approved_quote_id,status,total_amount,currency,total_minutes,added_minutes,target_end,expires_at,submitted_by,submitted_at,content_hash) VALUES(#{orderId},#{version},#{purpose},#{baseApprovedQuoteId},#{status},#{totalAmount},#{currency},#{totalMinutes},#{addedMinutes},#{targetEnd},#{expiresAt},#{submittedBy},#{submittedAt},#{contentHash})"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(QuoteRow row);

  @Insert(
    "INSERT INTO t_order_quote_item(quote_id,line_key,source_item_id,name_snapshot,unit_price,currency,quantity,duration_minutes,is_extra) VALUES(#{quote},#{item.lineKey},#{item.serviceItemId},#{item.nameSnapshot},#{item.unitPrice},#{item.currency},#{item.quantity},#{item.durationMinutes},#{item.isExtra})"
  )
  int insertItem(
    @Param("quote") long quote,
    @Param("item") QuoteItemSnapshot item
  );

  @Select("SELECT * FROM t_order_quote WHERE order_id=#{order} AND id=#{quote}")
  QuoteRow find(@Param("order") long order, @Param("quote") long quote);

  @Select(
    "SELECT * FROM t_order_quote WHERE order_id=#{order} AND id=#{quote} FOR UPDATE"
  )
  QuoteRow lock(@Param("order") long order, @Param("quote") long quote);

  @Select(
    "SELECT GREATEST(o.quote_seq,COALESCE((SELECT MAX(q.version) FROM t_order_quote q WHERE q.order_id=o.id),0))+1 FROM t_service_order o WHERE o.id=#{order}"
  )
  int nextVersion(long order);

  @Update(
    "UPDATE t_service_order SET quote_seq=#{version} WHERE id=#{order} AND quote_seq < #{version}"
  )
  int advanceSequence(
    @Param("order") long order,
    @Param("version") int version
  );

  @Select(
    "SELECT line_key,CAST(source_item_id AS CHAR) service_item_id,name_snapshot,CAST(unit_price AS CHAR) unit_price,currency,quantity,duration_minutes,quantity*duration_minutes line_minutes,is_extra FROM t_order_quote_item WHERE quote_id=#{quote} ORDER BY id"
  )
  List<QuoteItemSnapshot> items(long quote);

  @Select(
    "SELECT * FROM t_order_quote WHERE order_id=#{order} ORDER BY version DESC,id DESC LIMIT #{limit} OFFSET #{offset}"
  )
  List<QuoteRow> list(
    @Param("order") long order,
    @Param("limit") int limit,
    @Param("offset") int offset
  );

  @Select("SELECT COUNT(*) FROM t_order_quote WHERE order_id=#{order}")
  long count(long order);

  @Select(
    "SELECT COUNT(*) FROM t_service_order WHERE technician_id=#{tech} AND id<>#{order} AND reservation_active=1 AND occupied_start<#{end} AND effective_end>#{start}"
  )
  int conflicts(
    @Param("tech") long tech,
    @Param("order") long order,
    @Param("start") LocalDateTime start,
    @Param("end") LocalDateTime end
  );

  @Update(
    "UPDATE t_service_order SET state=#{state},row_version=row_version+1,approved_quote_id=#{approved},pending_quote_id=#{pending},committed_end=#{committed},effective_end=#{effective},updated_at=#{now} WHERE id=#{id} AND row_version=#{version}"
  )
  int updateOrder(
    @Param("id") long id,
    @Param("version") int version,
    @Param("state") String state,
    @Param("approved") Long approved,
    @Param("pending") Long pending,
    @Param("committed") LocalDateTime committed,
    @Param("effective") LocalDateTime effective,
    @Param("now") LocalDateTime now
  );

  @Update(
    "UPDATE t_order_quote SET status=#{status},confirmed_by=#{actor},confirmed_at=#{confirmed},terminal_reason=#{reason} WHERE id=#{id} AND status='PENDING'"
  )
  int terminate(
    @Param("id") long id,
    @Param("status") String status,
    @Param("actor") Long actor,
    @Param("confirmed") LocalDateTime confirmed,
    @Param("reason") String reason
  );

  @Insert(
    "INSERT INTO t_order_operation(order_id,action,quote_id,actor_id,payload_summary,request_id,created_at) VALUES(#{order},#{action},#{quote},#{actor},#{summary},#{requestId},#{now})"
  )
  int operation(
    @Param("order") long order,
    @Param("action") String action,
    @Param("quote") long quote,
    @Param("actor") long actor,
    @Param("summary") String summary,
    @Param("requestId") String requestId,
    @Param("now") LocalDateTime now
  );

  @Select(
    "SELECT * FROM t_order_quote WHERE status='PENDING' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at,id LIMIT 100"
  )
  List<QuoteRow> expired();
}
