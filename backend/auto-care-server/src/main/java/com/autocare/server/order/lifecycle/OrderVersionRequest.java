package com.autocare.server.order.lifecycle;

import com.autocare.server.order.OrderInputs;

import tools.jackson.databind.JsonNode;

/** 区分省略、null 和错误 JSON 类型；所有状态命令共用版本语法。 */
public class OrderVersionRequest {
    private JsonNode expectedRowVersion;
    private boolean versionPresent;

    public void setExpectedRowVersion(JsonNode value) {
        versionPresent = true;
        expectedRowVersion = value;
    }

    public int validatedVersion() {
        return OrderInputs.version(expectedRowVersion, versionPresent);
    }
}
