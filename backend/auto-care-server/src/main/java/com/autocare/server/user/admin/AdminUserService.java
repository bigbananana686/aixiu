package com.autocare.server.user.admin;

import static com.autocare.server.web.InputValues.*;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.command.CommandExecutor;
import com.autocare.server.command.CommandOutcome;
import com.autocare.server.command.CommandResult;
import com.autocare.server.command.CommandSpec;
import com.autocare.server.security.AdminAccess;
import com.autocare.server.web.ListInputs;
import com.autocare.server.web.UtcTimes;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

@Service
public class AdminUserService {
  private final AdminUserMapper users;
  private final AdminAccess admin;
  private final CommandExecutor commands;

  public AdminUserService(AdminUserMapper users, AdminAccess admin, CommandExecutor commands) {
    this.users = users;
    this.admin = admin;
    this.commands = commands;
  }

  public Map<String, Object> list(long actor, MultiValueMap<String, String> query) {
    admin.require(actor);
    var paging = new LinkedMultiValueMap<>(query);
    String keyword = filter(paging, "keyword");
    String role = filter(paging, "role");
    if (keyword.length() > 64) throw ApiException.invalid(FieldErrors.outOfRange("/keyword", "最多64字符"));
    if (!role.isEmpty() && !Set.of("OWNER", "TECH", "ADMIN").contains(role)) {
      throw ApiException.invalid(FieldErrors.invalidFormat("/role", "未知角色"));
    }
    ListInputs page = ListInputs.parse(paging);
    return Map.of(
      "items", users.list(keyword, role, page.enabled(), page.pageSize(), page.offset()).stream().map(AdminUserService::view).toList(),
      "page", page.page(), "pageSize", page.pageSize(), "total", users.count(keyword, role, page.enabled())
    );
  }

  public CommandResult update(long actor, String pathId, JsonNode body, String key, String requestId) {
    admin.require(actor);
    long userId = id(pathId, "userId");
    object(body, Set.of("expectedVersion", "role", "enabled", "technicianName"));
    int expected = version(body, "expectedVersion");
    String requested = text(body, "role");
    if (!Set.of("OWNER", "TECH").contains(requested)) {
      throw ApiException.invalid(FieldErrors.notAllowed("/role", "仅可调整车主和技师角色"));
    }
    Role role = Role.valueOf(requested);
    boolean enabled = bool(body, "enabled");
    String name = body.has("technicianName") ? boundedText(body, "technicianName", 64).trim() : "";
    requireManaged(users.find(userId));
    return commands.execute(
      CommandSpec.of("updateAdminUser").pathResource("userId", pathId), key, body, requestId,
      () -> {
        admin.require(actor);
        Long technician = users.lockTechnician(userId);
        AdminUserMapper.Row user = requireManaged(users.lock(userId));
        if (user.adminVersion() != expected) throw ApiException.of(ErrorCode.RESOURCE_VERSION_CONFLICT);
        if (user.role() == role && user.enabled() == enabled) return CommandOutcome.ok(view(user), userId);
        if ((user.role() != role || !enabled) && users.hasUnclosedOrder(userId)) {
          throw ApiException.of(ErrorCode.ORDER_STATE_CONFLICT, "账号还有未结案工单，结案后才能调整角色或停用。");
        }
        if (role == Role.TECH && technician == null) {
          if (name.isEmpty()) throw ApiException.invalid(FieldErrors.required("/technicianName", "首次转为技师时请填写对外显示名"));
          users.insertTechnician(userId, name, enabled);
        } else if (technician != null) {
          users.setTechnicianEnabled(userId, role == Role.TECH && enabled);
        }
        users.update(userId, role, enabled);
        users.audit(actor, user, role, enabled, requestId);
        return CommandOutcome.ok(view(users.find(userId)), userId);
      },
      (id, resource) -> {
        admin.require(id);
        return resource != null && users.find(resource) != null;
      }
    );
  }

  private static AdminUserMapper.Row requireManaged(AdminUserMapper.Row user) {
    if (user == null) throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND);
    if (user.role() == Role.ADMIN) throw ApiException.of(ErrorCode.FORBIDDEN, "管理员账号不可在这里调整。");
    return user;
  }

  private static String filter(MultiValueMap<String, String> query, String name) {
    var values = query.remove(name);
    if (values == null) return "";
    if (values.size() != 1) throw ApiException.invalid(FieldErrors.notAllowed("/" + name, "参数不可重复"));
    return values.getFirst().trim();
  }

  private static Map<String, Object> view(AdminUserMapper.Row row) {
    return Map.of("userId", String.valueOf(row.id()), "loginName", row.loginName(), "role", row.role(),
      "enabled", row.enabled(), "version", String.valueOf(row.adminVersion()),
      "createdAt", UtcTimes.format(row.createdAt()), "updatedAt", UtcTimes.format(row.updatedAt()));
  }
}
