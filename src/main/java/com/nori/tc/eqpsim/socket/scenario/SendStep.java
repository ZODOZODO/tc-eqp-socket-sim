package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [EqpToTc] <payload...>
 */
public final class SendStep implements ScenarioStep {

    private final String payloadTemplate;

    public SendStep(String payloadTemplate) {
        this.payloadTemplate = Objects.requireNonNull(payloadTemplate, "payloadTemplate must not be null");
    }

    public String getPayloadTemplate() {
        return payloadTemplate;
    }
}