package com.autocare.server.conversation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ConversationMapper {
  String COLUMNS =
    "id,public_id,actor_id,facts,revision,claim_token,lease_until,created_at,updated_at";

  class Row {

    public Long id, actorId;
    public String publicId, facts, claimToken;
    public int revision;
    public LocalDateTime leaseUntil, createdAt, updatedAt;
  }

  class Turn {

    public Long id, conversationId;
    public String requestKey, requestHash, messageText, status, responseJson, errorCode;
    public int attempts;
    public LocalDateTime createdAt;
  }

  @Select("SELECT role FROM t_user WHERE id=#{id} AND enabled=1 FOR UPDATE")
  String lockActor(long id);

  @Select("SELECT UTC_TIMESTAMP(3)")
  LocalDateTime now();

  @Insert(
    "INSERT INTO t_ai_conversation(public_id,actor_id,facts,created_at,updated_at) VALUES(#{publicId},#{actorId},#{facts},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int create(Row row);

  @Select(
    "SELECT " +
      COLUMNS +
      " FROM t_ai_conversation WHERE public_id=#{id} AND actor_id=#{actor} AND deleted=0"
  )
  Row find(@Param("id") String id, @Param("actor") long actor);

  @Select(
    "SELECT COUNT(*) FROM t_ai_conversation WHERE id=#{id} AND actor_id=#{actor} AND deleted=0"
  )
  int visible(@Param("id") long id, @Param("actor") long actor);

  @Select(
    "SELECT " +
      COLUMNS +
      " FROM t_ai_conversation WHERE public_id=#{id} AND actor_id=#{actor} AND deleted=0 FOR UPDATE"
  )
  Row lock(@Param("id") String id, @Param("actor") long actor);

  @Select(
    "SELECT " +
      COLUMNS +
      " FROM t_ai_conversation WHERE actor_id=#{actor} AND deleted=0 ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}"
  )
  List<Row> list(
    @Param("actor") long actor,
    @Param("limit") int limit,
    @Param("offset") int offset
  );

  @Select(
    "SELECT COUNT(*) FROM t_ai_conversation WHERE actor_id=#{actor} AND deleted=0"
  )
  long count(long actor);

  @Select(
    "SELECT COALESCE(SUM(t.attempts),0) FROM t_ai_conversation_turn t JOIN t_ai_conversation c ON c.id=t.conversation_id WHERE c.actor_id=#{actor} AND t.updated_at>TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(3))"
  )
  long recent(long actor);

  @Select(
    "SELECT COUNT(*) FROM t_ai_conversation_turn WHERE conversation_id=#{id}"
  )
  long countTurns(long id);

  @Select(
    "SELECT id,conversation_id,request_key,request_hash,message_text,status,attempts,response_json,error_code,created_at FROM t_ai_conversation_turn WHERE conversation_id=#{id} AND request_key=#{key}"
  )
  Turn turn(@Param("id") long id, @Param("key") String key);

  @Insert(
    "INSERT INTO t_ai_conversation_turn(conversation_id,request_key,request_hash,message_text,status,created_at,updated_at) VALUES(#{conversationId},#{requestKey},#{requestHash},#{messageText},'RUNNING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"
  )
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertTurn(Turn turn);

  @Update(
    "UPDATE t_ai_conversation_turn SET status='FAILED',error_code='AI_UNAVAILABLE',updated_at=UTC_TIMESTAMP(3) WHERE conversation_id=#{id} AND status='RUNNING'"
  )
  int expireTurns(long id);

  @Update(
    "UPDATE t_ai_conversation_turn SET status='RUNNING',attempts=attempts+1,error_code=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='FAILED'"
  )
  int retry(long id);

  @Update(
    "UPDATE t_ai_conversation SET claim_token=#{token},lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(3)) WHERE id=#{id} AND deleted=0"
  )
  int claim(@Param("id") long id, @Param("token") String token);

  @Update(
    "UPDATE t_ai_conversation SET facts=#{facts},revision=revision+1,claim_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND claim_token=#{token} AND lease_until>UTC_TIMESTAMP(3) AND deleted=0"
  )
  int complete(
    @Param("id") long id,
    @Param("token") String token,
    @Param("facts") String facts
  );

  @Update(
    "UPDATE t_ai_conversation_turn SET status='SUCCEEDED',response_json=#{result},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='RUNNING'"
  )
  int saveTurn(@Param("id") long id, @Param("result") String result);

  @Update(
    "UPDATE t_ai_conversation SET claim_token=NULL,lease_until=NULL WHERE id=#{id} AND claim_token=#{token}"
  )
  int clearClaim(@Param("id") long id, @Param("token") String token);

  @Update(
    "UPDATE t_ai_conversation_turn SET status='FAILED',error_code=#{code},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='RUNNING'"
  )
  int failTurn(@Param("id") long id, @Param("code") String code);

  @Select(
    "SELECT id,message_text,response_json,created_at FROM t_ai_conversation_turn WHERE conversation_id=#{id} AND status='SUCCEEDED' ORDER BY id LIMIT 100"
  )
  List<Turn> history(long id);

  @Delete("DELETE FROM t_ai_conversation_turn WHERE conversation_id=#{id}")
  int deleteTurns(long id);

  @Update(
    "UPDATE t_ai_conversation SET deleted=1,facts=JSON_OBJECT(),claim_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}"
  )
  int delete(long id);
}
