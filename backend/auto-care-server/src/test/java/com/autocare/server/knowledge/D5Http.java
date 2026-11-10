package com.autocare.server.knowledge;

import com.autocare.common.identity.Role;
import com.autocare.server.security.JwtTokenIssuer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;

public final class D5Http {

  public record Reply(HttpResponse<String> http, JsonNode body) {
    public JsonNode data() {
      return body.path("data");
    }
  }

  private final int port;
  private final JwtTokenIssuer issuer;
  private final ObjectMapper json;

  public D5Http(int port, JwtTokenIssuer issuer, ObjectMapper json) {
    this.port = port;
    this.issuer = issuer;
    this.json = json;
  }

  public Reply request(
    String method,
    String path,
    long actor,
    byte[] body,
    String type,
    String key
  ) throws Exception {
    var builder = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api/v1" + path)
    ).timeout(Duration.ofSeconds(30));
    if (actor > 0) builder.header(
      "Authorization",
      "Bearer " +
        issuer.issue(
          actor,
          actor == 5
            ? Role.ADMIN
            : actor == 3 || actor == 4
              ? Role.TECH
              : Role.OWNER,
          Instant.now()
        )
    );
    if (type != null) builder.header("Content-Type", type);
    if (key != null) builder.header("Idempotency-Key", key);
    var reply = HttpClient.newHttpClient().send(
      builder
        .method(
          method,
          body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body)
        )
        .build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    );
    JsonNode tree = reply.body().isBlank()
      ? json.createObjectNode()
      : json.readTree(reply.body());
    synchronized (D5Http.class) {
      Files.writeString(
        Path.of("target/d5-http-evidence.jsonl"),
        json.writeValueAsString(
          Map.of(
            "path",
            path,
            "actor",
            actor,
            "status",
            reply.statusCode(),
            "response",
            tree
          )
        ) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      );
    }
    return new Reply(reply, tree);
  }

  public Reply json(
    String method,
    String path,
    long actor,
    String body,
    String key
  ) throws Exception {
    return request(
      method,
      path,
      actor,
      body == null ? null : body.getBytes(StandardCharsets.UTF_8),
      body == null ? null : "application/json",
      key
    );
  }

  public Reply upload(
    long actor,
    byte[] content,
    String name,
    String media,
    Map<String, String> form,
    String key
  ) throws Exception {
    String boundary = "D5Boundary" + UUID.randomUUID();
    var out = new ByteArrayOutputStream();
    for (var e : form.entrySet())
      out.write(
        (
          "--" +
          boundary +
          "\r\nContent-Disposition: form-data; name=\"" +
          e.getKey() +
          "\"\r\n\r\n" +
          e.getValue() +
          "\r\n"
        ).getBytes(StandardCharsets.UTF_8)
      );
    if (content != null) {
      out.write(
        (
          "--" +
          boundary +
          "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" +
          name +
          "\"\r\nContent-Type: " +
          media +
          "\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8)
      );
      out.write(content);
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return request(
      "POST",
      "/admin/knowledge-documents",
      actor,
      out.toByteArray(),
      "multipart/form-data; boundary=" + boundary,
      key
    );
  }
}
