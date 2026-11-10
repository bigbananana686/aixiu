package com.autocare.server.catalog.admin;

import static com.autocare.server.web.InputValues.*;

import com.autocare.common.error.*;
import com.autocare.server.command.*;
import com.autocare.server.order.booking.OrderMapper;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

@Service
public class AdminCatalogService {

  private final AdminCatalogMapper mapper;
  private final AdminAccess access;
  private final CommandExecutor commands;
  private final OrderMapper clock;

  public AdminCatalogService(
    AdminCatalogMapper mapper,
    AdminAccess access,
    CommandExecutor commands,
    OrderMapper clock
  ) {
    this.mapper = mapper;
    this.access = access;
    this.commands = commands;
    this.clock = clock;
  }

  public CommandResult write(
    long actor,
    String path,
    JsonNode body,
    String key,
    String requestId
  ) {
    access.require(actor);
    boolean create = path == null;
    long id = create ? 0 : id(path, "serviceItemId");
    object(
      body,
      create
        ? Set.of("code", "name", "durationMinutes", "unitPrice", "enabled")
        : Set.of(
            "expectedCatalogVersion",
            "name",
            "durationMinutes",
            "unitPrice",
            "enabled"
          )
    );
    Map<String, Object> n = new LinkedHashMap<>();
    if (create) {
      String code = text(body, "code");
      if (!code.matches("[A-Z][A-Z0-9_]{1,31}")) throw ApiException.invalid(
        FieldErrors.invalidFormat("/code", "目录编码格式不合法")
      );
      n.put("code", code);
    } else {
      n.put(
        "expectedCatalogVersion",
        String.valueOf(version(body, "expectedCatalogVersion"))
      );
      if (body.size() == 1) throw ApiException.invalid(
        FieldErrors.required("/", "至少提供一个可变字段")
      );
      if (mapper.find(id) == null) throw ApiException.of(
        ErrorCode.RESOURCE_NOT_FOUND
      );
    }
    if (create || body.has("name")) n.put(
      "name",
      boundedText(body, "name", 64)
    );
    if (create || body.has("durationMinutes")) n.put(
      "durationMinutes",
      integer(body, "durationMinutes", 1, 1440)
    );
    if (create || body.has("unitPrice")) n.put(
      "unitPrice",
      money(body, "unitPrice")
    );
    if (create || body.has("enabled")) n.put(
      "enabled",
      body.has("enabled") ? bool(body, "enabled") : true
    );
    CommandSpec spec = CommandSpec.of(
      create ? "createServiceItem" : "updateServiceItem"
    );
    if (!create) spec = spec.pathResource("serviceItemId", String.valueOf(id));
    return commands.execute(
      spec,
      key,
      n,
      requestId,
      () -> {
        access.require(actor);
        AdminCatalogRow row;
        if (create) {
          row = new AdminCatalogRow();
          row.setCode((String) n.get("code"));
          row.setCurrency("CNY");
          row.setVersion(1);
          row.setCreatedAt(clock.dbNowUtc());
        } else {
          row = mapper.lock(id);
          if (row == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
          if (
            row.getVersion() !=
            Integer.parseInt((String) n.get("expectedCatalogVersion"))
          ) throw ApiException.of(ErrorCode.CATALOG_VERSION_CONFLICT).field(
            FieldErrors.versionMismatch(
              "/expectedCatalogVersion",
              "目录版本不一致"
            )
          );
          if (
            row.getVersion() == Integer.MAX_VALUE
          ) throw new IllegalStateException(
            "Catalog version exhausted; operator action required"
          );
        }
        if (n.containsKey("name")) row.setName((String) n.get("name"));
        if (n.containsKey("durationMinutes")) row.setDurationMinutes(
          (Integer) n.get("durationMinutes")
        );
        if (n.containsKey("unitPrice")) row.setPrice(
          new BigDecimal((String) n.get("unitPrice"))
        );
        if (n.containsKey("enabled")) row.setEnabled(
          (Boolean) n.get("enabled")
        );
        row.setUpdatedAt(clock.dbNowUtc());
        if (create) {
          try {
            mapper.insert(row);
          } catch (DuplicateKeyException e) {
            throw ApiException.invalid(
              FieldErrors.alreadyExists("/code", "目录编码已存在")
            );
          }
        } else {
          if (mapper.update(row) != 1) throw new IllegalStateException(
            "Locked catalog write affected no row"
          );
          row.setVersion(row.getVersion() + 1);
        }
        return create
          ? CommandOutcome.created(
              "/api/v1/admin/service-items/" + row.getId(),
              view(row),
              row.getId()
            )
          : CommandOutcome.ok(view(row), row.getId());
      },
      (who, resource) -> {
        access.require(who);
        return resource != null && mapper.find(resource) != null;
      }
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
    long actor,
    MultiValueMap<String, String> query
  ) {
    access.require(actor);
    ListInputs q = ListInputs.parse(query);
    return Map.of(
      "items",
      mapper
        .list(q.enabled(), q.pageSize(), q.offset())
        .stream()
        .map(AdminCatalogService::view)
        .toList(),
      "page",
      q.page(),
      "pageSize",
      q.pageSize(),
      "total",
      mapper.count(q.enabled())
    );
  }

  static Map<String, Object> view(AdminCatalogRow r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("serviceItemId", String.valueOf(r.getId()));
    m.put("code", r.getCode());
    m.put("name", r.getName());
    m.put("durationMinutes", r.getDurationMinutes());
    m.put("unitPrice", r.getPrice().toPlainString());
    m.put("currency", r.getCurrency());
    m.put("enabled", r.getEnabled());
    m.put("catalogVersion", String.valueOf(r.getVersion()));
    m.put("createdAt", UtcTimes.format(r.getCreatedAt()));
    m.put("updatedAt", UtcTimes.format(r.getUpdatedAt()));
    return m;
  }
}
