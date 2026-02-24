package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [Sim] loop=count=5 goto=BURST
 */
public final class LoopStep implements ScenarioStep {

    private final int count;
    private final String gotoLabel;

    public LoopStep(int count, String gotoLabel) {
        if (count <= 0) throw new IllegalArgumentException("loop count must be > 0");
        this.count = count;
        this.gotoLabel = Objects.requireNonNull(gotoLabel, "gotoLabel must not be null").trim();
        if (this.gotoLabel.isEmpty()) throw new IllegalArgumentException("gotoLabel is blank");
    }

    public int getCount() {
        return count;
    }

    public String getGotoLabel() {
        return gotoLabel;
    }
}