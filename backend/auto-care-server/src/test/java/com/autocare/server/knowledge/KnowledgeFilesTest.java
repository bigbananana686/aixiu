package com.autocare.server.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.autocare.ai.knowledge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KnowledgeFilesTest {

  @TempDir
  Path temp;

  @Test
  void contentAddressing_replayKeepsOneFileAndDigest() throws Exception {
    var files = new KnowledgeFiles(temp);
    byte[] bytes = "合成保养资料".getBytes(StandardCharsets.UTF_8);
    var a = files.store(new ByteArrayInputStream(bytes), "text/plain");
    var b = files.store(new ByteArrayInputStream(bytes), "text/plain");
    assertThat(a).isEqualTo(b);
    assertThat(a.hash()).isEqualTo(KnowledgeFiles.hash("合成保养资料"));
    try (var list = Files.list(temp)) {
      assertThat(list.count()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "application/pdf", "application/zip", "image/png" })
  void forgedMedia_isRejectedAndNoFileRetained(String type) {
    assertThatThrownBy(() ->
      new KnowledgeFiles(temp).store(
        new ByteArrayInputStream("plain".getBytes()),
        type
      )
    ).isInstanceOf(KnowledgeFiles.InvalidFileException.class);
    assertThat(temp.toFile().list()).isEmpty();
  }

  @Test
  void malformedUtf8AndBinary_areRejected() {
    for (byte[] b : new byte[][] {
      { (byte) 0xff },
      { 0, 1, 2 },
      "%PDF-1.7".getBytes(),
    })
      assertThatThrownBy(() ->
        new KnowledgeFiles(temp).store(
          new ByteArrayInputStream(b),
          "text/plain"
        )
      ).isInstanceOf(KnowledgeFiles.InvalidFileException.class);
  }

  @Test
  void streamWithoutDeclaredSize_stopsAtTenMiB() {
    var endless = new InputStream() {
      long remaining = KnowledgeFiles.MAX_BYTES + 1;

      public int read() {
        return remaining-- > 0 ? 'a' : -1;
      }
    };
    assertThatThrownBy(() ->
      new KnowledgeFiles(temp).store(endless, "text/plain")
    ).isInstanceOf(KnowledgeFiles.FileLimitException.class);
    assertThat(temp.toFile().list()).isEmpty();
  }

  @Test
  void storageCannotReadOutsideOwnedDirectory() {
    assertThatThrownBy(() ->
      new KnowledgeFiles(temp).resolveOwned(
        temp.resolve("../secret").toString()
      )
    ).isInstanceOf(KnowledgeFiles.InvalidFileException.class);
  }

  @Test
  void emptyAndWhitespaceFiles_failBeforeEmbedding() throws Exception {
    var f = new KnowledgeFiles(temp);
    assertThatThrownBy(() ->
      f.store(new ByteArrayInputStream(new byte[0]), "text/plain")
    ).isInstanceOf(KnowledgeFiles.InvalidFileException.class);
    var raw = f.store(
      new ByteArrayInputStream(" \n\t".getBytes()),
      "text/plain"
    );
    assertThatThrownBy(() ->
      new DocumentParser().parse(Path.of(raw.path()), raw.contentType())
    ).isInstanceOf(DocumentParser.ParseLimitException.class);
  }

  @Test
  void textCharacterLimit_reportsObservedCount() throws Exception {
    var file = temp.resolve("large.txt");
    Files.writeString(file, "a".repeat(2_000_001));
    assertThatThrownBy(() ->
      new DocumentParser().parse(file, "text/plain")
    ).isInstanceOfSatisfying(DocumentParser.ParseLimitException.class, e -> {
      assertThat(e.pages).isEqualTo(1);
      assertThat(e.chars).isGreaterThan(2_000_000);
    });
  }

  @Test
  void pdfPageLimitIsCheckedBeforeExtraction() throws Exception {
    var file = temp.resolve("large.pdf");
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
      for (int i = 0; i < 501; i++) pdf.addPage(
        new org.apache.pdfbox.pdmodel.PDPage()
      );
      pdf.save(file.toFile());
    }
    assertThatThrownBy(() ->
      new DocumentParser().parse(file, "application/pdf")
    ).isInstanceOfSatisfying(DocumentParser.ParseLimitException.class, e ->
      assertThat(e.pages).isEqualTo(501)
    );
  }

  @Test
  void pdfTextHasPageLocator() throws Exception {
    var file = temp.resolve("normal.pdf");
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      pdf.addPage(page);
      try (
        var out = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)
      ) {
        out.beginText();
        out.setFont(
          new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA
          ),
          12
        );
        out.newLineAtOffset(40, 700);
        out.showText("Synthetic maintenance manual");
        out.endText();
      }
      pdf.save(file.toFile());
    }
    var parsed = new DocumentParser().parse(file, "application/pdf");
    assertThat(parsed.pageCount()).isEqualTo(1);
    assertThat(parsed.pages().getFirst()).containsPattern(
      "Synthetic\\s+maintenance"
    );
    assertThat(KnowledgeIndex.chunks(parsed).getFirst().locator()).startsWith(
      "第1页"
    );
  }

  @Test
  void concurrentIdenticalUploadsReuseVerifiedBytesAndDoNotOverwriteCorruption()
    throws Exception {
    var storage = new KnowledgeFiles(temp);
    byte[] content = "机油手册并发上传".getBytes(StandardCharsets.UTF_8);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      var tasks = new java.util.ArrayList<
        java.util.concurrent.Callable<KnowledgeFiles.Stored>
      >();
      for (int i = 0; i < 20; i++) tasks.add(() ->
        storage.store(new ByteArrayInputStream(content), "text/plain")
      );
      var completed = pool.invokeAll(tasks);
      var first = completed.getFirst().get();
      for (var result : completed) assertThat(result.get()).isEqualTo(first);
      assertThat(temp.toFile().list()).hasSize(1);
      var path = Path.of(first.path());
      byte[] invalid = content.clone();
      invalid[0] = 'x';
      Files.write(path, invalid);
      assertThatThrownBy(() ->
        storage.store(new ByteArrayInputStream(content), "text/plain")
      ).isInstanceOf(IOException.class);
      assertThat(Files.readAllBytes(path)).isEqualTo(invalid);
    } finally {
      pool.shutdownNow();
    }
  }
}
