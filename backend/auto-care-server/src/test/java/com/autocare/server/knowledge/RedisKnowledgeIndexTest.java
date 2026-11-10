package com.autocare.server.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.knowledge.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisKnowledgeIndexTest {

  @TempDir
  Path temp;

  @Test
  void redisVectorStoreRetrievesOnlyPublishedTokenAndPersistsAcrossAdapterRecreation()
    throws Exception {
    var files = new KnowledgeFiles(temp);
    var embedding = new D5TestConfiguration.FakeEmbeddings();
    int port = Integer.parseInt(
      System.getenv().getOrDefault("AUTOCARE_VECTOR_REDIS_PORT", "16379")
    );
    var first = new KnowledgeIndex(
      files,
      embedding,
      SpringVectorIndex.redis(embedding, temp, "127.0.0.1", port, null)
    );
    var raw = files.store(
      new ByteArrayInputStream(
        "机油机滤保养手册".getBytes(StandardCharsets.UTF_8)
      ),
      "text/plain"
    );
    var old = first.prepare(
      raw.path(),
      raw.contentType(),
      UUID.randomUUID().toString()
    );
    var newer = first.prepare(
      raw.path(),
      raw.contentType(),
      UUID.randomUUID().toString()
    );
    try {
      var fresh = new KnowledgeIndex(
        files,
        embedding,
        SpringVectorIndex.redis(embedding, temp, "127.0.0.1", port, null)
      );
      var allowed = new KnowledgeIndex.Source(
        "manual",
        "2",
        "适用资料",
        newer.path(),
        newer.space()
      );
      var found = fresh.search(List.of(allowed), "机油");
      assertThat(found.evidence()).hasSize(1);
      assertThat(found.evidence().getFirst().documentVersion()).isEqualTo("2");
      assertThat(found.evidence().getFirst().text()).contains("机油机滤");
      assertThat(found.versions()).containsExactly(
        Map.of("documentKey", "manual", "version", "2")
      );
      first.discardUnpublished(Path.of(old.path()));
      assertThat(fresh.search(List.of(allowed), "机油").evidence()).hasSize(1);
    } finally {
      first.discardUnpublished(Path.of(old.path()));
      first.discardUnpublished(Path.of(newer.path()));
    }
  }
}
