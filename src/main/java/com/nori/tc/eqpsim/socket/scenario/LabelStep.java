package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [Sim] label=MAIN
 */
public final class LabelStep implements ScenarioStep {

    private final String label;

    public LabelStep(String label) {
        this.label = Objects.requireNonNull(label, "label must not be null").trim();
        if (this.label.isEmpty()) throw new IllegalArgumentException("label is blank");
    }

    public String getLabel() {
        return label;
    }
}