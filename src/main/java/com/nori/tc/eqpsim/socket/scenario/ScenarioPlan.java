package com.nori.tc.eqpsim.socket.scenario;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ScenarioPlan
 *
 * - steps: 순차 실행 스텝 목록
 * - labelIndex: label -> stepIndex
 */
public final class ScenarioPlan {

    private final String sourceFile;
    private final List<ScenarioStep> steps;
    private final Map<String, Integer> labelIndex;

    public ScenarioPlan(String sourceFile, List<ScenarioStep> steps, Map<String, Integer> labelIndex) {
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        this.steps = Collections.unmodifiableList(Objects.requireNonNull(steps, "steps must not be null"));
        this.labelIndex = Collections.unmodifiableMap(Objects.requireNonNull(labelIndex, "labelIndex must not be null"));
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public List<ScenarioStep> getSteps() {
        return steps;
    }

    public Map<String, Integer> getLabelIndex() {
        return labelIndex;
    }
}