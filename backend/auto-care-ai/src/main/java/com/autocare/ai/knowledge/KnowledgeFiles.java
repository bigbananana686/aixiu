package com.autocare.ai.knowledge;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 服务器自己产生存储名；客户端文件名仅展示，不参与路径。 */
public final class KnowledgeFiles {

  public static final long MAX_BYTES = 10L * 1024 * 1024;
  private final Path root;

  public KnowledgeFiles(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public record Stored(
    String path,
    String hash,
    long bytes,
    String contentType
  ) {}

  public Stored store(InputStream input, String claimedType)
    throws IOException {
    Files.createDirectories(root);
    Path temp = Files.createTempFile(root, "upload-", ".tmp");
    try {
      MessageDigest digest = sha();
      long size = 0;
      try (OutputStream out = Files.newOutputStream(temp)) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
          size += n;
          if (size > MAX_BYTES) throw new FileLimitException();
          out.write(buffer, 0, n);
          digest.update(buffer, 0, n);
        }
      }
      if (size == 0) throw new InvalidFileException();
      String actual = detect(temp, claimedType);
      String hash = HexFormat.of().formatHex(digest.digest());
      Path target = root.resolve("raw-" + hash + ".bin");
      // 命中已有内容时不再尝试移动。并发首写的失败只能在赢家字节确实匹配时复用。
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (!sameContent(target, hash, size)) throw new IOException(
          "Stored content mismatch"
        );
      } else {
        try {
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
          if (!sameContent(target, hash, size)) throw e;
        }
      }
      return new Stored(target.toString(), hash, size, actual);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private boolean sameContent(Path path, String hash, long size)
    throws IOException {
    if (
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(path) != size
    ) return false;
    MessageDigest digest = sha();
    long read = 0;
    try (var in = Files.newInputStream(path)) {
      byte[] bytes = new byte[8192];
      int n;
      while ((n = in.read(bytes)) != -1) {
        read += n;
        if (read > size) return false;
        digest.update(bytes, 0, n);
      }
    }
    return (
      read == size && HexFormat.of().formatHex(digest.digest()).equals(hash)
    );
  }

  public Path resolveOwned(String path) {
    Path p = Path.of(path).toAbsolutePath().normalize();
    if (
      !p.getParent().equals(root) || Files.isSymbolicLink(p)
    ) throw new InvalidFileException();
    return p;
  }

  private String detect(Path file, String type) throws IOException {
    if (type != null) type = type
      .split(";", 2)[0]
      .strip()
      .toLowerCase(Locale.ROOT);
    if (
      !Set.of("application/pdf", "text/plain", "text/markdown").contains(
        type == null ? "" : type
      )
    ) throw new InvalidFileException();
    byte[] head;
    try (InputStream in = Files.newInputStream(file)) {
      head = in.readNBytes(5);
    }
    boolean pdf = Arrays.equals(
      head,
      "%PDF-".getBytes(StandardCharsets.US_ASCII)
    );
    if ("application/pdf".equals(type)) {
      if (!pdf) throw new InvalidFileException();
      return type;
    }
    if (pdf) throw new InvalidFileException();
    var decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT);
    try (
      Reader r = new InputStreamReader(Files.newInputStream(file), decoder)
    ) {
      char[] buffer = new char[8192];
      int n;
      while ((n = r.read(buffer)) != -1)
        for (int i = 0; i < n; i++) if (
          Character.isISOControl(buffer[i]) && "\t\n\r\f".indexOf(buffer[i]) < 0
        ) throw new InvalidFileException();
    } catch (CharacterCodingException e) {
      throw new InvalidFileException();
    }
    return type;
  }

  public static String hash(String value) {
    return HexFormat.of().formatHex(
      sha().digest(value.getBytes(StandardCharsets.UTF_8))
    );
  }

  private static MessageDigest sha() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static class InvalidFileException extends RuntimeException {}

  public static class FileLimitException extends RuntimeException {}
}
