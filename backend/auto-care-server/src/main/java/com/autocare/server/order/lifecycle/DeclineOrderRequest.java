package com.autocare.server.order.lifecycle;

import com.autocare.server.order.OrderInputs;

import tools.jackson.databind.JsonNode;

public class DeclineOrderRequest extends OrderVersionRequest {
    private JsonNode reason;
    private boolean reasonPresent;

    public void setReason(JsonNode value) {
        reasonPresent = true;
        reason = value;
    }

    public String validatedReason() {
        return OrderInputs.reason(reason, reasonPresent);
    }
}
