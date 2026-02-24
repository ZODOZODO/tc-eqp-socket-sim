package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [Sim] goto=MAIN
 */
public final class GotoStep implements ScenarioStep {

    private final String label;

    public GotoStep(String label) {
        this.label = Objects.requireNonNull(label, "label must not be null").trim();
        if (this.label.isEmpty()) throw new IllegalArgumentException("goto label is blank");
    }

    public String getLabel() {
        return label;
    }
}