package com.autocare.server.command;

/**
 * 业务动作跑完之后交回来的东西：**这次要返回给客户端的 HTTP 结果**。
 *
 * <p>为什么不让业务动作直接返回 {@code ResponseEntity}：那会让业务层认识 HTTP。
 * 这里只认「状态码 + Location + data + 业务主键」这四个概念——一个创建动作本来就该知道自己
 * 建出了什么、以及去哪儿能读到它。
 *
 * @param <T> data 的类型（会先被转成 JSON 树再存库，所以不必可序列化到某种特定形式）
 */
public record CommandOutcome<T>(int httpStatus, String location, T data, Long resourceId) {

    /**
     * 创建成功：201 + 相对 Location。
     *
     * <p>Location 必须是<b>相对</b>路径（契约：创建资源 201 并返回相对 Location），
     * 例如 {@code /api/v1/vehicles/101}。写绝对地址会把部署域名焊进响应里。
     */
    public static <T> CommandOutcome<T> created(String location, T data, Long resourceId) {
        return new CommandOutcome<>(201, location, data, resourceId);
    }

    /** 普通命令成功：200，无 Location。 */
    public static <T> CommandOutcome<T> ok(T data, Long resourceId) {
        return new CommandOutcome<>(200, null, data, resourceId);
    }

    /** 普通命令成功，且没有单独的业务主键（resourceId 取不到时用）。 */
    public static <T> CommandOutcome<T> ok(T data) {
        return new CommandOutcome<>(200, null, data, null);
    }
}
