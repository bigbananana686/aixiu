package com.autocare.ai.knowledge;

import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;

/** 生产RedisVectorStore；SimpleVectorStore只用于显式测试/演示构造，不作自动回退。 */
public final class SpringVectorIndex implements AutoCloseable {

  private record RedisBackend(
    VectorStore store,
    redis.clients.jedis.RedisClient client
  ) {}

  private final EmbeddingModel model;
  private final Path root;
  private final Supplier<RedisBackend> factory;
  private volatile RedisBackend shared;
  private final String backend;

  private SpringVectorIndex(
    EmbeddingPort embeddings,
    Path root,
    Supplier<RedisBackend> factory,
    String backend
  ) {
    model = new PortModel(embeddings);
    this.root = root;
    this.factory = factory;
    this.backend = backend;
  }

  public static SpringVectorIndex testFiles(EmbeddingPort model, Path root) {
    return new SpringVectorIndex(model, root, null, "simple-test");
  }

  public static SpringVectorIndex redis(
    EmbeddingPort embeddings,
    Path root,
    String host,
    int port,
    String password
  ) {
    var model = new PortModel(embeddings);
    String space = KnowledgeFiles.hash(
      embeddings.modelId() +
        ":" +
        embeddings.dimensions() +
        ":" +
        KnowledgeIndex.STRATEGY +
        ":redis-schema-2"
    );
    return new SpringVectorIndex(
      embeddings,
      root,
      () -> {
        var config = redis.clients.jedis.DefaultJedisClientConfig.builder()
          .password(password == null || password.isBlank() ? null : password)
          .connectionTimeoutMillis(3000)
          .socketTimeoutMillis(5000)
          .build();
        var client = redis.clients.jedis.RedisClient.builder()
          .hostAndPort(new redis.clients.jedis.HostAndPort(host, port))
          .clientConfig(config)
          .build();
        var store = RedisVectorStore.builder(client, model)
          .indexName("autocare_knowledge_" + space.substring(0, 20))
          .prefix("autocare:knowledge:" + space + ":")
          .initializeSchema(true)
          .metadataFields(
            RedisVectorStore.MetadataField.tag("indexToken"),
            RedisVectorStore.MetadataField.tag("chunkKey")
          )
          .batchingStrategy(
            new org.springframework.ai.embedding.TokenCountBatchingStrategy()
          )
          .build();
        try {
          store.afterPropertiesSet();
          return new RedisBackend(store, client);
        } catch (RuntimeException e) {
          client.close();
          throw e;
        }
      },
      "redis"
    );
  }

  public String backend() {
    return backend;
  }

  public void discard(String token, List<String> ids)
    throws java.io.IOException {
    if (factory != null) {
      for (int i = 0; i < ids.size(); i += 100) shared().delete(
        ids.subList(i, Math.min(i + 100, ids.size()))
      );
    } else Files.deleteIfExists(root.resolve("vector-" + token + ".json"));
  }

  private synchronized VectorStore shared() {
    if (shared == null) shared = factory.get();
    return shared.store();
  }

  public synchronized void close() {
    if (shared != null) {
      shared.client().close();
      shared = null;
    }
  }

  private SimpleVectorStore simple() {
    return SimpleVectorStore.builder(model)
      .batchingStrategy(new TokenCountBatchingStrategy())
      .build();
  }

  public void add(String token, List<Document> documents) {
    if (factory != null) {
      shared().add(documents);
      return;
    }
    var store = simple();
    store.add(documents);
    Path path = root.resolve("vector-" + token + ".json");
    if (Files.exists(path)) throw new IllegalArgumentException(
      "Immutable vector snapshot already exists"
    );
    store.save(path.toFile());
  }

  public List<Document> search(List<String> tokens, String query) {
    if (tokens.isEmpty()) return List.of();
    var filter = new FilterExpressionBuilder()
      .in("indexToken", tokens.toArray())
      .build();
    if (factory != null) return VectorStoreDocumentRetriever.builder()
      .vectorStore(shared())
      .filterExpression(filter)
      .topK(8)
      .similarityThreshold(0.25)
      .build()
      .retrieve(new Query(query));
    var found = new ArrayList<Document>();
    for (String token : tokens) {
      var store = simple();
      store.load(root.resolve("vector-" + token + ".json").toFile());
      found.addAll(
        VectorStoreDocumentRetriever.builder()
          .vectorStore(store)
          .filterExpression(filter)
          .topK(8)
          .similarityThreshold(0.25)
          .build()
          .retrieve(new Query(query))
      );
    }
    return found
      .stream()
      .sorted(
        Comparator.comparing(
          (Document d) -> d.getScore(),
          Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(Document::getId)
      )
      .limit(8)
      .toList();
  }

  static final class PortModel implements EmbeddingModel {

    final EmbeddingPort port;

    PortModel(EmbeddingPort port) {
      this.port = port;
    }

    public int dimensions() {
      return port.dimensions();
    }

    public float[] embed(Document document) {
      return embed(document.getText());
    }

    public EmbeddingResponse call(EmbeddingRequest request) {
      var vectors = port.embed(request.getInstructions());
      if (
        vectors.size() != request.getInstructions().size()
      ) throw new IllegalArgumentException("Embedding count mismatch");
      var result = new ArrayList<Embedding>();
      for (int i = 0; i < vectors.size(); i++) {
        var vector = vectors.get(i);
        if (
          vector == null || vector.length != port.dimensions()
        ) throw new IllegalArgumentException("Embedding dimensions mismatch");
        double norm = 0;
        for (float n : vector) {
          if (!Float.isFinite(n)) throw new IllegalArgumentException(
            "Invalid embedding"
          );
          norm += (double) n * n;
        }
        if (norm == 0) throw new IllegalArgumentException("Zero embedding");
        result.add(new Embedding(vector, i));
      }
      return new EmbeddingResponse(result);
    }
  }
}
