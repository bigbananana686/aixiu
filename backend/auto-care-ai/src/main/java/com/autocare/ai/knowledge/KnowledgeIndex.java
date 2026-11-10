package com.autocare.ai.knowledge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import tools.jackson.databind.json.JsonMapper;

/** Spring AI ETL/VectorStore/Retriever负责通用处理，项目保留不可变发布与授权引用。 */
public final class KnowledgeIndex {

  public static final String STRATEGY = "springai-cl100k-384-v2";
  private final KnowledgeFiles files;
  private final EmbeddingPort embeddings;
  private final SpringVectorIndex vectors;
  private final DocumentParser parser = new DocumentParser();
  private final JsonMapper json = JsonMapper.builder().build();

  public KnowledgeIndex(
    KnowledgeFiles files,
    EmbeddingPort embeddings,
    SpringVectorIndex vectors
  ) {
    this.files = files;
    this.embeddings = embeddings;
    this.vectors = vectors;
  }

  /** 仅供受控单元测试显式使用；生产由KnowledgeConfiguration注入Redis后端。 */
  public KnowledgeIndex(KnowledgeFiles files, EmbeddingPort embeddings) {
    this(
      files,
      embeddings,
      SpringVectorIndex.testFiles(embeddings, files.root())
    );
  }

  public record Chunk(String key, String locator, String hash, String text) {}

  public record ChunkRef(String key, String locator, String hash) {}

  public record Manifest(
    String space,
    String model,
    int dimensions,
    String strategy,
    String backend,
    String token,
    List<ChunkRef> chunks
  ) {}

  public record Prepared(
    String path,
    String space,
    String model,
    int dimensions,
    String strategy,
    int pages,
    int chars,
    List<Chunk> chunks
  ) {}

  public record Source(
    String documentKey,
    String version,
    String title,
    String path,
    String space
  ) {}

  public record Search(
    List<Evidence> evidence,
    List<Map<String, String>> versions
  ) {}

  public String space() {
    return KnowledgeFiles.hash(
      embeddings.modelId() +
        ":" +
        embeddings.dimensions() +
        ":" +
        STRATEGY +
        ":" +
        vectors.backend()
    );
  }

  public Prepared prepare(String raw, String type, String token)
    throws IOException {
    if (!token.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException(
      "Claim token required"
    );
    Path path = files.root().resolve("index-" + token + ".json");
    if (Files.exists(path)) throw new FileAlreadyExistsException(
      path.toString()
    );
    DocumentParser.Parsed parsed;
    try {
      parsed = parser.parse(files.resolveOwned(raw), type);
    } catch (NoSuchFileException e) {
      throw e;
    } catch (IOException e) {
      throw new KnowledgeFiles.InvalidFileException();
    }
    var chunks = chunks(parsed);
    var documents = new ArrayList<Document>();
    for (var c : chunks)
      documents.add(
        new Document(
          vectorId(token, c.key()),
          c.text(),
          Map.of(
            "indexToken",
            token,
            "chunkKey",
            c.key(),
            "sourceLocator",
            c.locator(),
            "textHash",
            c.hash()
          )
        )
      );
    // 先落孤儿清理清单；只有向量写成功后才交给DB发布。部分Redis失败也有可回收的ID。
    try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
      json.writeValue(
        out,
        new Manifest(
          space(),
          embeddings.modelId(),
          embeddings.dimensions(),
          STRATEGY,
          vectors.backend(),
          token,
          chunks
            .stream()
            .map(c -> new ChunkRef(c.key(), c.locator(), c.hash()))
            .toList()
        )
      );
    }
    vectors.add(token, documents); // Token预算批处理与Embedding由VectorStore负责，均在事务外。
    return new Prepared(
      path.toString(),
      space(),
      embeddings.modelId(),
      embeddings.dimensions(),
      STRATEGY,
      parsed.pageCount(),
      parsed.charCount(),
      chunks
    );
  }

  private static String vectorId(String token, String key) {
    return UUID.nameUUIDFromBytes(
      (token + ":" + key).getBytes(StandardCharsets.UTF_8)
    ).toString();
  }

  /** 调用方已证明未引用且无有效租约；网络清理必须在DB事务外。 */
  public void discardUnpublished(Path path) throws IOException {
    Manifest m;
    try {
      m = json.readValue(
        files.resolveOwned(path.toString()).toFile(),
        Manifest.class
      );
    } catch (tools.jackson.core.JacksonException e) {
      return;
    }
    if (
      m.token() != null &&
      m.token().matches("[a-f0-9-]{36}") &&
      m.chunks() != null &&
      space().equals(m.space())
    ) vectors.discard(
      m.token(),
      m
        .chunks()
        .stream()
        .map(c -> vectorId(m.token(), c.key()))
        .toList()
    );
  }

  public static List<Chunk> chunks(DocumentParser.Parsed parsed) {
    var splitter = TokenTextSplitter.builder()
      .withChunkSize(384)
      .withMinChunkSizeChars(1)
      .withMinChunkLengthToEmbed(1)
      .withMaxNumChunks(10000)
      .withKeepSeparator(true)
      .build();
    var result = new ArrayList<Chunk>();
    int sequence = 0;
    for (Document doc : splitter.apply(parsed.documents())) {
      String text = doc.getText();
      if (text == null || text.isBlank()) continue;
      if (
        text.codePointCount(0, text.length()) > 2000
      ) throw new IllegalArgumentException(
        "Split chunk exceeds evidence budget"
      );
      int page = (
        (Number) doc.getMetadata().getOrDefault("page_number", 1)
      ).intValue();
      result.add(
        new Chunk(
          "p" + page + "-c" + sequence,
          "第" + page + "页 片段" + sequence++,
          KnowledgeFiles.hash(text),
          text
        )
      );
    }
    if (
      result.isEmpty() || result.size() > 10000
    ) throw new IllegalArgumentException("Invalid split result");
    return List.copyOf(result);
  }

  public Search search(List<Source> sources, String query) throws IOException {
    if (
      sources.size() > 100 || query.length() > 16000
    ) throw new IllegalArgumentException("Search bound exceeded");
    if (sources.isEmpty()) return new Search(List.of(), List.of());
    var byToken = new HashMap<String, Source>();
    var byChunk = new HashMap<String, ChunkRef>();
    var versions = new ArrayList<Map<String, String>>();
    long bytes = 0;
    int count = 0;
    for (Source s : sources) {
      if (!space().equals(s.space())) throw new IllegalArgumentException(
        "Embedding space changed; reindex required"
      );
      var path = files.resolveOwned(s.path());
      bytes += Files.size(path);
      if (bytes > 256L * 1024 * 1024) throw new IllegalArgumentException(
        "Search byte budget exceeded"
      );
      var m = json.readValue(path.toFile(), Manifest.class);
      if (
        !space().equals(m.space()) ||
        !vectors.backend().equals(m.backend()) ||
        !STRATEGY.equals(m.strategy()) ||
        !m.token().matches("[a-f0-9-]{36}")
      ) throw new IllegalArgumentException("Invalid index manifest");
      count += m.chunks().size();
      if (count > 10000) throw new IllegalArgumentException(
        "Search chunk budget exceeded"
      );
      byToken.put(m.token(), s);
      for (var c : m.chunks()) byChunk.put(m.token() + ":" + c.key(), c);
      versions.add(
        Map.of("documentKey", s.documentKey(), "version", s.version())
      );
    }
    var evidence = new ArrayList<Evidence>();
    for (Document doc : vectors.search(
      byToken.keySet().stream().sorted().toList(),
      query
    )) {
      String token = (String) doc.getMetadata().get("indexToken"),
        key = (String) doc.getMetadata().get("chunkKey");
      var source = byToken.get(token);
      var chunk = byChunk.get(token + ":" + key);
      String text = doc.getText();
      if (
        source == null ||
        chunk == null ||
        text == null ||
        text.codePointCount(0, text.length()) > 2000 ||
        !chunk.hash().equals(KnowledgeFiles.hash(text))
      ) throw new IllegalArgumentException(
        "Vector result not in authorized immutable candidates"
      );
      evidence.add(
        new Evidence(
          source.documentKey(),
          source.version(),
          chunk.key(),
          chunk.locator(),
          source.title(),
          text
        )
      );
    }
    return new Search(List.copyOf(evidence), List.copyOf(versions));
  }
}
