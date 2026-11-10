package com.autocare.server.command;

import tools.jackson.databind.JsonNode;

/**
 * 执行器的返回值：调用方（controller）照着它拼 HTTP 响应就够了。
 *
 * <p>{@code data} 用的是 {@link JsonNode} 而不是业务 DTO，因为这里有<b>两条来路</b>，
 * 而且它们的原始类型不同：
 * <ul>
 *   <li><b>新执行</b>——data 是业务动作产出的 DTO，被转成 JSON 树（同一棵树也存进了库）。</li>
 *   <li><b>重放</b>——data 是从库里那行 {@code response_json} 里读出来的<b>历史结果</b>，
 *       本来就只有 JSON 形态，没有 DTO 可还原。</li>
 * </ul>
 * 两条路都归到同一棵树，controller 那边就只有一种写法。
 *
 * @param httpStatus      原 HTTP 状态（重放时是<b>当初那个</b>，可能是历史状态）
 * @param location        原 Location，没有则 null
 * @param data            响应数据
 * @param replayed        是不是重放（决定要不要加 {@code Idempotency-Replayed} 头）
 * @param originalRequestId 原请求的 requestId——<b>只在重放时有值</b>，用于 {@code Original-Request-Id} 头。
 *        它是审计线索：本次响应体里的 {@code requestId} 是<b>本次</b>的，两者刻意不同。
 */
public record CommandResult(int httpStatus,
                            String location,
                            JsonNode data,
                            boolean replayed,
                            String originalRequestId) {
}
