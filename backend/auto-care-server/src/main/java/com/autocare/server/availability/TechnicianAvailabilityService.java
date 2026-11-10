package com.autocare.server.availability;

import com.autocare.common.error.ApiException;
import com.autocare.common.error.ErrorCode;
import com.autocare.common.error.FieldErrors;
import com.autocare.common.identity.Role;
import com.autocare.server.user.UserEntity;
import com.autocare.server.user.UserMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技师可用时段（提示查询）。**只算提示，不做承诺**——真正能不能约，G1-03 的 createOrder
 * 会在技师资源行锁内按同一套公式重新完整校验（契约 §4.5 与设计 §3.4）。
 *
 * <p><b>时间账（设计 §3.2，C=通勤、B1=前缓冲、B2=后缓冲、D=工时）</b>：
 *
 * <pre>
 * occupied_start = S − C − B1
 * booking_end    = S + D + B2
 * </pre>
 *
 * 占用必须整体落在工作窗口内，所以每段窗口的可选起点区间是：
 *
 * <pre>
 * 下界 = window_start + C + B1        上界 = window_end − D − B2
 * </pre>
 *
 * 一条占用 {@code [O, E)} 挡掉的起点是<b>开区间</b> {@code (O − tail, E + lead)}：
 * S 取在开区间端点上时占用区间恰好与已有占用<b>端点相接</b>，按 §3.2「半开区间、端点相接不算冲突」
 * 是允许的——所以扣除时端点必须保留，这也是用「开区间减法」而不是闭区间减法的原因。
 *
 * <p><b>时区账</b>：{@code date} 是 Asia/Shanghai 的日界；窗口与占用在库里都是 UTC 的 DATETIME(3)。
 * 统一做法是「先把日期换算成 UTC 的 [当日 00:00, 次日 00:00) 区间，全程用 Instant 计算，
 * 只在出参格式化那一步转回字符串」——两层各转一次，中间不混。
 */
@Service
public class TechnicianAvailabilityService {

    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TIME_ZONE = "Asia/Shanghai";
    /** 设计 §3.1 的默认缓冲：可配置演示参数。本片取默认值，配置化归 G2/G4，来源写进回报。 */
    private static final int BUFFER_BEFORE_MINUTES = 15;
    private static final int BUFFER_AFTER_MINUTES = 15;
    private static final int MAX_SERVICE_ITEMS = 50;
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ENTITY_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    /** 契约样例的形态：UTC + 固定 3 位毫秒（{@code 2026-11-01T01:45:00.000Z}）。 */
    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final AvailabilityMapper availabilityMapper;
    private final UserMapper userMapper;

    public TechnicianAvailabilityService(AvailabilityMapper availabilityMapper, UserMapper userMapper) {
        this.availabilityMapper = availabilityMapper;
        this.userMapper = userMapper;
    }

    public TechnicianAvailabilityResult availability(long actorId, String rawDate, List<String> rawServiceItemIds,
                                                     String rawTechnicianId, Set<String> queryParamNames) {
        assertOwner(actorId);
        assertAllowedQueryParams(queryParamNames);
        LocalDate date = parseDate(rawDate);
        List<Long> itemIds = parseItemIds(rawServiceItemIds);
        int totalMinutes = resolveTotalMinutes(itemIds);
        Long technicianFilter = parseOptionalTechnicianId(rawTechnicianId);

        Instant dayStartUtc = date.atStartOfDay(SERVICE_ZONE).toInstant();
        Instant dayEndUtc = dayStartUtc.plus(Duration.ofDays(1));
        boolean isToday = date.equals(LocalDate.now(SERVICE_ZONE));

        List<TechnicianAvailability> technicians = new ArrayList<>();
        for (TechnicianRow tech : availabilityMapper.listEnabledTechnicians()) {
            if (technicianFilter != null && tech.getTechnicianId() != technicianFilter) {
                continue;
            }
            technicians.add(project(tech, dayStartUtc, dayEndUtc, totalMinutes, isToday));
        }
        // 契约 §4.5：technicians 按 technicianId 升序——排序是服务端固定的，不接受客户端指定
        technicians.sort(Comparator.comparingLong(t -> Long.parseLong(t.technicianId())));
        return new TechnicianAvailabilityResult(rawDate, TIME_ZONE, totalMinutes, List.copyOf(technicians));
    }

    // ------------------------------------------------------------------ 单个技师的投影

    private TechnicianAvailability project(TechnicianRow tech, Instant dayStartUtc, Instant dayEndUtc,
                                           int totalMinutes, boolean isToday) {
        long lead = (long) tech.getCommuteMinutes() + BUFFER_BEFORE_MINUTES;
        long tail = (long) totalMinutes + BUFFER_AFTER_MINUTES;

        List<long[]> pieces = new ArrayList<>();
        for (WindowRow window : availabilityMapper.findWindows(tech.getTechnicianId(),
                toUtc(dayStartUtc), toUtc(dayEndUtc))) {
            Instant segStart = max(utc(window.getWindowStart()), dayStartUtc);
            Instant segEnd = min(utc(window.getWindowEnd()), dayEndUtc);
            Instant lo = segStart.plus(Duration.ofMinutes(lead));
            Instant hi = segEnd.minus(Duration.ofMinutes(tail));
            // 设计 §3.2「起点不能早于创建时允许的时间」：查「今天」时，过去的起点剪掉（本片拍板项 2）
            if (isToday) {
                Instant now = Instant.now();
                if (now.isAfter(lo)) {
                    lo = now;
                }
            }
            if (lo.isAfter(hi)) {
                continue;
            }
            pieces.add(new long[]{lo.toEpochMilli(), hi.toEpochMilli()});
        }

        for (OccupiedRow reservation : availabilityMapper.findActiveReservations(tech.getTechnicianId(),
                toUtc(dayStartUtc), toUtc(dayEndUtc))) {
            long blockedLo = utc(reservation.getOccupiedStart()).minus(Duration.ofMinutes(tail)).toEpochMilli();
            long blockedHi = utc(reservation.getEffectiveEnd()).plus(Duration.ofMinutes(lead)).toEpochMilli();
            subtractOpen(pieces, blockedLo, blockedHi);
        }

        List<ServiceStartRange> ranges = new ArrayList<>();
        for (long[] piece : pieces) {
            ranges.add(new ServiceStartRange(
                    UTC_MILLIS.format(Instant.ofEpochMilli(piece[0])),
                    UTC_MILLIS.format(Instant.ofEpochMilli(piece[1]))));
        }
        return new TechnicianAvailability(Long.toString(tech.getTechnicianId()), tech.getDisplayName(),
                List.copyOf(ranges));
    }

    /**
     * 从闭区间集合里挖掉一个<b>开</b>区间 {@code (a, b)}。端点保留：{@code a} 与 {@code b}
     * 本身不在开区间里，所以挖完之后的闭区间端点允许落在 {@code a} / {@code b} 上。
     */
    private static void subtractOpen(List<long[]> pieces, long a, long b) {
        List<long[]> next = new ArrayList<>();
        for (long[] piece : pieces) {
            if (a >= piece[1] || b <= piece[0]) {
                next.add(piece);
                continue;
            }
            if (a > piece[0]) {
                next.add(new long[]{piece[0], Math.min(a, piece[1])});
            }
            if (b < piece[1]) {
                next.add(new long[]{Math.max(b, piece[0]), piece[1]});
            }
        }
        pieces.clear();
        pieces.addAll(next);
    }

    // ------------------------------------------------------------------ 入参校验（次序：身份 → 点名 → 格式/边界）

    private void assertOwner(long actorId) {
        UserEntity user = userMapper.findById(actorId);
        if (user == null || !user.isEnabled()) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (user.getRole() != Role.OWNER) {
            throw ApiException.of(ErrorCode.FORBIDDEN);
        }
    }

    /** 与两个列表接口同一口径（DISPATCH-016 的 D-1）：没点名的 query 参数一律拒，不让 Spring 静默忽略。 */
    private void assertAllowedQueryParams(Set<String> names) {
        for (String name : names) {
            if (!"date".equals(name) && !"serviceItemIds".equals(name) && !"technicianId".equals(name)) {
                throw ApiException.invalid(FieldErrors.notAllowed("/" + name, "本接口不接受该筛选字段"));
            }
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.invalid(FieldErrors.required("/date"));
        }
        if (!DATE.matcher(raw).matches()) {
            throw ApiException.invalid(FieldErrors.invalidFormat("/date", "必须为 YYYY-MM-DD 的当地日期"));
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // 形状对了但不是真实存在的日期（2026-02-30 这类）：同样是格式问题，不能让它变成 500
            throw ApiException.invalid(FieldErrors.invalidFormat("/date", "必须为真实存在的 YYYY-MM-DD 日期"));
        }
    }

    private List<Long> parseItemIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.invalid(FieldErrors.required("/serviceItemIds"));
        }
        if (raw.size() > MAX_SERVICE_ITEMS) {
            throw ApiException.invalid(FieldErrors.outOfRange(
                    "/serviceItemIds", "最多 " + MAX_SERVICE_ITEMS + " 项"));
        }
        List<Long> ids = new ArrayList<>(raw.size());
        Set<Long> seen = new HashSet<>();
        for (String one : raw) {
            String value = one == null ? "" : one.trim();
            if (!ENTITY_ID.matcher(value).matches()) {
                throw ApiException.invalid(FieldErrors.invalidFormat(
                        "/serviceItemIds", "目录项 ID 必须为无前导零的十进制正整数"));
            }
            long id = Long.parseLong(value);
            if (!seen.add(id)) {
                throw ApiException.invalid(FieldErrors.duplicate("/serviceItemIds", "目录项不得重复"));
            }
            ids.add(id);
        }
        return ids;
    }

    /** 存在性与启用位一起核：对预约意向来说「不存在」和「停用」都是目标不可用（本片拍板项，回报留证）。 */
    private int resolveTotalMinutes(List<Long> itemIds) {
        List<CatalogItemRow> rows = availabilityMapper.findItems(itemIds);
        Map<Long, CatalogItemRow> byId = new HashMap<>();
        for (CatalogItemRow row : rows) {
            byId.put(row.getId(), row);
        }
        int total = 0;
        for (Long id : itemIds) {
            CatalogItemRow row = byId.get(id);
            if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
                throw ApiException.invalid(FieldErrors.notAvailable("/serviceItemIds", "目录项不存在或已停用"));
            }
            total += row.getDurationMinutes();
        }
        return total;
    }

    private Long parseOptionalTechnicianId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!ENTITY_ID.matcher(raw).matches()) {
            throw ApiException.invalid(FieldErrors.invalidFormat(
                    "/technicianId", "必须为无前导零的十进制正整数"));
        }
        return Long.parseLong(raw);
    }

    // ------------------------------------------------------------------ 时间小工具

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toUtc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
