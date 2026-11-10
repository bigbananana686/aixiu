package com.autocare.server.catalog;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.user.UserEntity;
import com.autocare.server.user.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 服务目录只读路径（G1-02b1）。
 *
 * <p>校验次序就是错误归因的次序：先问你是谁（401），再问你的角色够不够（403），
 * 然后拒掉没点名的 query 参数（G1-02a 的 ?ownerId= 事故就是这一步缺了），
 * 最后才做分页数值校验。目录的写接口与管理员投影在 G2-01a，不在这个类里。
 */
@Service
public class ServiceCatalogService {

    private final ServiceItemMapper serviceItemMapper;
    private final UserMapper userMapper;

    public ServiceCatalogService(ServiceItemMapper serviceItemMapper, UserMapper userMapper) {
        this.serviceItemMapper = serviceItemMapper;
        this.userMapper = userMapper;
    }

    public ServiceItemListResult list(long actorId, String rawPage, String rawPageSize,
                                      Set<String> queryParamNames) {
        // ① 身份：账号不存在或被禁用 → 401（而不是 403——先证明你是谁，才谈得上权限）
        UserEntity user = userMapper.findById(actorId);
        if (user == null || !user.isEnabled()) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        // ② 角色：三角色都可读；403 留给「未知角色」的兜底分支（契约 §4.4）
        Role role = user.getRole();
        if (role != Role.OWNER && role != Role.ADMIN && role != Role.TECH) {
            throw ApiException.of(ErrorCode.FORBIDDEN);
        }
        // ③ 点名 query 参数：Spring 对没声明的参数是静默忽略的，不主动查就等于放行 ?ownerId= 这类筛选
        assertAllowedQueryParams(queryParamNames);
        // ④ 分页：省略取默认，非整数 INVALID_FORMAT，越界 OUT_OF_RANGE（不自动截断）
        int page = parsePaging(rawPage, "page", 1, 1, 10000);
        int pageSize = parsePaging(rawPageSize, "pageSize", 20, 1, 100);
        // ⑤ 组装：范围先限定 enabled=1，再分页；total 是过滤后的数量，不是全表行数
        int offset = (page - 1) * pageSize;
        List<ServiceItem> items = serviceItemMapper.listEnabled(pageSize, offset).stream()
                .map(ServiceItem::from).toList();
        Long total = serviceItemMapper.countEnabled();
        return new ServiceItemListResult(items, page, pageSize, total);
    }

    /** 只放行 page / pageSize，其余 query 参数 400 NOT_ALLOWED（DISPATCH-016 的 D-1 口径）。 */
    private void assertAllowedQueryParams(Set<String> names) {
        for (String name : names) {
            if (!"page".equals(name) && !"pageSize".equals(name)) {
                throw ApiException.invalid(FieldErrors.notAllowed(
                        "/" + name, "本接口不接受该筛选字段"));
            }
        }
    }

    private static int parsePaging(String raw, String name, int defaultValue, int min, int max) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;                       // 省略 = 取默认
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiException.invalid(FieldErrors.invalidFormat("/" + name, "必须为整数"));
        }
        if (value < min || value > max) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/" + name, "必须为 " + min + " 至 " + max + " 的整数"));
        }
        return value;
    }
}
