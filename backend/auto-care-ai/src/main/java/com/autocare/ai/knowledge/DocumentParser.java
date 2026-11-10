package com.autocare.ai.knowledge;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;

/** Spring AI readers负责提取；项目只补输入上限、资源关闭和失败边界。 */
public final class DocumentParser {

  public static final int MAX_CHARS = 2_000_000,
    MAX_PAGES = 500;

  public record Parsed(
    List<String> pages,
    int pageCount,
    int charCount,
    List<Document> documents
  ) {
    public Parsed(List<String> pages, int pageCount, int charCount) {
      this(pages, pageCount, charCount, toDocuments(pages));
    }

    static List<Document> toDocuments(List<String> pages) {
      var docs = new ArrayList<Document>();
      for (int i = 0; i < pages.size(); i++) docs.add(
        new Document(pages.get(i), Map.of("page_number", i + 1))
      );
      return List.copyOf(docs);
    }
  }

  public Parsed parse(Path path, String type) throws IOException {
    if (
      Files.size(path) > KnowledgeFiles.MAX_BYTES
    ) throw new KnowledgeFiles.FileLimitException();
    var resource = new FileSystemResource(path);
    List<Document> docs;
    int pages = 1;
    if ("application/pdf".equals(type)) {
      try (var reader = new BoundedPdfReader(resource)) {
        pages = reader.pages();
        docs = reader.get();
      } catch (ParseLimitException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new KnowledgeFiles.InvalidFileException();
      }
    } else {
      // 先以有界UTF-8 Reader拒绝非法编码/超限，不交给下游Reader宽松替换字符。
      int actual = 0;
      var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(
        CodingErrorAction.REPORT
      );
      try (
        var in = new InputStreamReader(Files.newInputStream(path), decoder)
      ) {
        char[] b = new char[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          actual += n;
          if (actual > MAX_CHARS) throw new ParseLimitException(1, actual);
        }
      }
      if ("text/markdown".equals(type)) docs = new MarkdownDocumentReader(
        resource,
        MarkdownDocumentReaderConfig.builder().build()
      ).get();
      else {
        var reader = new TextReader(resource);
        reader.setCharset(StandardCharsets.UTF_8);
        docs = reader.get();
      }
    }
    int chars = 0;
    List<Document> normalized = new ArrayList<>();
    for (Document doc : docs) {
      String text = doc.getText() == null ? "" : doc.getText();
      chars += text.length();
      if (chars > MAX_CHARS) throw new ParseLimitException(pages, chars);
      var metadata = new HashMap<String, Object>(doc.getMetadata());
      metadata.putIfAbsent("page_number", 1);
      normalized.add(new Document(text, metadata));
    }
    if (
      normalized.stream().allMatch(d -> d.getText().isBlank())
    ) throw new ParseLimitException(pages, chars);
    return new Parsed(
      normalized.stream().map(Document::getText).toList(),
      pages,
      chars,
      List.copyOf(normalized)
    );
  }

  static final class BoundedPdfReader
    extends PagePdfDocumentReader
    implements AutoCloseable
  {

    int characters;

    BoundedPdfReader(FileSystemResource resource) throws IOException {
      super(
        resource,
        PdfDocumentReaderConfig.builder()
          .withPagesPerDocument(1)
          .withPageTopMargin(0)
          .withPageBottomMargin(0)
          .build()
      );
      if (document.isEncrypted() || pages() < 1 || pages() > MAX_PAGES) {
        int count = pages();
        close();
        throw new ParseLimitException(count, 0);
      }
    }

    int pages() {
      return document.getNumberOfPages();
    }

    @Override
    protected Document toDocument(String text, int first, int last) {
      characters += text.length();
      if (characters > MAX_CHARS) throw new ParseLimitException(
        pages(),
        characters
      );
      return super.toDocument(text, first, last);
    }

    public void close() throws IOException {
      document.close();
    }
  }

  public static class ParseLimitException extends RuntimeException {

    public final int pages, chars;

    public ParseLimitException(int pages, int chars) {
      this.pages = pages;
      this.chars = chars;
    }
  }
}
