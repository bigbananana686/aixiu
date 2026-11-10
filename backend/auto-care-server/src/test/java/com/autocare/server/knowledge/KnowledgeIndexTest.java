package com.autocare.server.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.analysis.*;
import com.autocare.ai.knowledge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeIndexTest {

  @TempDir
  Path temp;

  @Test
  void unicodeChunking_isStableBoundedAndDoesNotSplitSurrogate() {
    var parsed = new DocumentParser.Parsed(List.of("🚗".repeat(4000)), 1, 8000);
    var a = KnowledgeIndex.chunks(parsed);
    assertThat(a).isEqualTo(KnowledgeIndex.chunks(parsed));
    assertThat(a.size()).isGreaterThan(3);
    for (var c : a) {
      assertThat(
        c.text().codePointCount(0, c.text().length())
      ).isLessThanOrEqualTo(1800);
      assertThat(c.text()).doesNotContain("�");
    }
  }

  @Test
  void immutableIndex_reloadsAndReturnsAtMostEightCandidates()
    throws Exception {
    var files = new KnowledgeFiles(temp);
    var index = new KnowledgeIndex(
      files,
      new D5TestConfiguration.FakeEmbeddings()
    );
    var raw = files.store(
      new ByteArrayInputStream(
        "机油机滤保养资料\n"
          .repeat(3000)
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      ),
      "text/markdown"
    );
    var p = index.prepare(
      raw.path(),
      raw.contentType(),
      UUID.randomUUID().toString()
    );
    var reloaded = new KnowledgeIndex(
      files,
      new D5TestConfiguration.FakeEmbeddings()
    );
    var r = reloaded.search(
      List.of(
        new KnowledgeIndex.Source(
          "manual",
          "1",
          "合成资料",
          p.path(),
          p.space()
        )
      ),
      "机油"
    );
    assertThat(r.evidence()).hasSize(8);
    assertThat(r.versions()).containsExactly(
      Map.of("documentKey", "manual", "version", "1")
    );
    assertThat(r.evidence()).allSatisfy(e ->
      assertThat(
        e.text().codePointCount(0, e.text().length())
      ).isLessThanOrEqualTo(2000)
    );
    assertThatThrownBy(() ->
      index.prepare(
        raw.path(),
        raw.contentType(),
        Path.of(p.path()).getFileName().toString().substring(6, 42)
      )
    ).isInstanceOf(FileAlreadyExistsException.class);
  }

  @Test
  void incompatibleSpace_isRejectedInsteadOfMixingVectors() throws Exception {
    var index = new KnowledgeIndex(
      new KnowledgeFiles(temp),
      new D5TestConfiguration.FakeEmbeddings()
    );
    assertThatThrownBy(() ->
      index.search(
        List.of(
          new KnowledgeIndex.Source("manual", "1", "x", "ignored", "old-space")
        ),
        "机油"
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("space changed");
  }

  @Test
  void absentCandidates_returnsMissingEvidenceWithoutCallingEmbedding()
    throws Exception {
    var port = new D5TestConfiguration.FakeEmbeddings();
    port.unavailable = true;
    assertThat(
      new KnowledgeIndex(new KnowledgeFiles(temp), port)
        .search(List.of(), "x")
        .evidence()
    ).isEmpty();
  }

  @Test
  void dimensionMismatch_neverWritesPublishableIndex() throws Exception {
    var files = new KnowledgeFiles(temp);
    var raw = files.store(
      new ByteArrayInputStream("manual".getBytes()),
      "text/plain"
    );
    EmbeddingPort invalid = new EmbeddingPort() {
      public String modelId() {
        return "bad";
      }

      public int dimensions() {
        return 2;
      }

      public List<float[]> embed(List<String> s) {
        return List.of(new float[] { 1 });
      }
    };
    assertThatThrownBy(() ->
      new KnowledgeIndex(files, invalid).prepare(
        raw.path(),
        "text/plain",
        UUID.randomUUID().toString()
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("dimensions");
  }

  @Test
  void forgedCitationAndMissingCitation_areNotAccepted() {
    var evidence = List.of(
      new Evidence("manual", "1", "c1", "第1页", "手册", "说明")
    );
    var c = new QuoteAnalyzer.Citation(
      "ref",
      "other",
      "1",
      "c1",
      "第1页",
      "手册"
    );
    var r = new QuoteAnalyzer.Result(
      List.of(
        new QuoteAnalyzer.Finding("f1", "结论", "base-1", List.of("ref"))
      ),
      List.of(),
      List.of(c),
      List.of()
    );
    assertThatThrownBy(() ->
      AnalysisValidator.validate(r, evidence, Set.of("base-1"))
    ).isInstanceOf(AnalysisValidator.InvalidAnalysisException.class);
    var noRef = new QuoteAnalyzer.Result(
      List.of(new QuoteAnalyzer.Finding("f1", "结论", "base-1", List.of())),
      List.of(),
      List.of(),
      List.of()
    );
    assertThatThrownBy(() ->
      AnalysisValidator.validate(noRef, evidence, Set.of("base-1"))
    ).isInstanceOf(AnalysisValidator.InvalidAnalysisException.class);
  }

  @Test
  void wrongLineKeyAndDuplicateCitationIds_areRejected() {
    var e = new Evidence("manual", "1", "c1", "page", "title", "text");
    var c = new QuoteAnalyzer.Citation(
      "ref",
      "manual",
      "1",
      "c1",
      "page",
      "title"
    );
    var r = new QuoteAnalyzer.Result(
      List.of(
        new QuoteAnalyzer.Finding("f", "statement", "other", List.of("ref"))
      ),
      List.of(),
      List.of(c),
      List.of()
    );
    assertThatThrownBy(() ->
      AnalysisValidator.validate(r, List.of(e), Set.of("line"))
    ).isInstanceOf(AnalysisValidator.InvalidAnalysisException.class);
    var repeated = new QuoteAnalyzer.Result(
      List.of(),
      List.of("缺证据"),
      List.of(c, c),
      List.of()
    );
    assertThatThrownBy(() ->
      AnalysisValidator.validate(repeated, List.of(e), Set.of())
    ).isInstanceOf(AnalysisValidator.InvalidAnalysisException.class);
  }
}
