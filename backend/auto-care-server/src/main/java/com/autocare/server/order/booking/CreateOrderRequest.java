package com.autocare.server.order.booking;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code createOrder} 的请求体（契约 §5.1 请求表）。
 *
 * <p><b>为什么整个类都用 setter 记「出现过的字段」，而不是 record</b>：这条请求的 null 语义不统一——
 * {@code description}「省略即不提供」（显式 null 要 400），{@code aiParseId} 却**明文可为 null**。
 * record 分不出这两种，只能逐字段记账。
 *
 * <p>校验分两层，本类只管**记账**：语法层（必填/格式/越界/重复）在 service 的 validate 里做，
 * 语义层（归属、目录、窗口、冲突）在幂等动作的锁内做——次序就是错误归因。
 */
public class CreateOrderRequest {

  private String vehicleId;
  private String technicianId;
  private String serviceStart;
  private List<BookingItemInput> items;
  private String address;
  private String description;
  private String aiParseId;

  private final Set<String> present = new LinkedHashSet<>();

  public boolean has(String field) {
    return present.contains(field);
  }

  public String getVehicleId() {
    return vehicleId;
  }

  public void setVehicleId(String vehicleId) {
    present.add("vehicleId");
    this.vehicleId = vehicleId;
  }

  public String getTechnicianId() {
    return technicianId;
  }

  public void setTechnicianId(String technicianId) {
    present.add("technicianId");
    this.technicianId = technicianId;
  }

  public String getServiceStart() {
    return serviceStart;
  }

  public void setServiceStart(String serviceStart) {
    present.add("serviceStart");
    this.serviceStart = serviceStart;
  }

  public List<BookingItemInput> getItems() {
    return items;
  }

  public void setItems(List<BookingItemInput> items) {
    present.add("items");
    this.items = items;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    present.add("address");
    this.address = address;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    present.add("description");
    this.description = description;
  }

  public String getAiParseId() {
    return aiParseId;
  }

  public void setAiParseId(String aiParseId) {
    present.add("aiParseId");
    this.aiParseId = aiParseId;
  }

  /** 数组元素：三个字段都必填（契约 §5.1 请求表「不可 null」）。 */
  public static class BookingItemInput {

    private String serviceItemId;
    private Integer quantity;
    private String catalogVersion;

    public String getServiceItemId() {
      return serviceItemId;
    }

    public void setServiceItemId(String serviceItemId) {
      this.serviceItemId = serviceItemId;
    }

    public Integer getQuantity() {
      return quantity;
    }

    public void setQuantity(Integer quantity) {
      this.quantity = quantity;
    }

    public String getCatalogVersion() {
      return catalogVersion;
    }

    public void setCatalogVersion(String catalogVersion) {
      this.catalogVersion = catalogVersion;
    }
  }

  /**
   * 校验通过后的形态：ID 已转 long、起点已转 UTC、条目已按 {@code serviceItemId} 升序
   * （契约 §5.1：目录按 serviceItemId 升序稳定读取，条目跟着同序，快照数组顺序就此固定）。
   */
  public record Parsed(
    long vehicleId,
    long technicianId,
    Instant serviceStartUtc,
    List<Item> items,
    String address,
    String description,
    Long aiParseId
  ) {
    public record Item(
      long serviceItemId,
      int quantity,
      String catalogVersion
    ) {}
  }
}
