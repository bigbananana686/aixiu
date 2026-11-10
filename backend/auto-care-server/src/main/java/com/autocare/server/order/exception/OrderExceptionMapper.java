package com.autocare.server.order.exception;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderExceptionMapper {
  String COLUMNS =
    "id,type,order_id,request_id,requested_target_end,committed_end,actual_finish,over_plan_minutes,recorded_at,resolved";
  String FILTER =
    "<where>created_at &gt;= #{from} AND created_at &lt; #{to} <if test='order != null'>AND order_id=#{order}</if> <if test='resolved != null'>AND resolved=#{resolved}</if> <if test='types != null'>AND type IN <foreach collection='types' item='t' open='(' separator=',' close=')'>#{t}</foreach></if></where>";

  @Insert(
    "INSERT INTO t_order_exception(type,order_id,request_id,requested_target_end,committed_end,actual_finish,over_plan_minutes,recorded_at,created_at) VALUES(#{r.type},#{r.orderId},#{r.requestId},#{r.requestedTargetEnd},#{r.committedEnd},#{r.actualFinish},#{r.overPlanMinutes},#{r.recordedAt},#{r.recordedAt}) ON DUPLICATE KEY UPDATE id=id"
  )
  int insert(@Param("r") OrderExceptionRow r);

  @Select(
    "SELECT " +
      COLUMNS +
      " FROM t_order_exception WHERE order_id=#{order} AND request_id=#{request} AND type=#{type}"
  )
  OrderExceptionRow find(
    @Param("order") long order,
    @Param("request") String request,
    @Param("type") String type
  );

  @Select(
    "SELECT " +
      COLUMNS +
      " FROM t_order_exception WHERE order_id=#{order} ORDER BY created_at DESC,id DESC"
  )
  List<OrderExceptionRow> forOrder(long order);

  @Select(
    "<script>SELECT " +
      COLUMNS +
      " FROM t_order_exception " +
      FILTER +
      " ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>"
  )
  List<OrderExceptionRow> list(
    @Param("order") Long order,
    @Param("resolved") Boolean resolved,
    @Param("types") List<String> types,
    @Param("from") LocalDateTime from,
    @Param("to") LocalDateTime to,
    @Param("size") int size,
    @Param("offset") int offset
  );

  @Select(
    "<script>SELECT COUNT(*) FROM t_order_exception " + FILTER + "</script>"
  )
  long count(
    @Param("order") Long order,
    @Param("resolved") Boolean resolved,
    @Param("types") List<String> types,
    @Param("from") LocalDateTime from,
    @Param("to") LocalDateTime to
  );
}
