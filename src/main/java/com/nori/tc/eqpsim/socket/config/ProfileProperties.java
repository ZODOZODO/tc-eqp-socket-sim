package com.nori.tc.eqpsim.socket.config;

/**
 * tc.eqpsim.profiles.<id>.*
 *
 * 현재 단계에서는 SCENARIO를 우선 지원한다.
 * - type=SCENARIO: scenarioFile 지정
 * - type=RATE: (향후 확장) 레이트 정책만으로 송신
 */
public class ProfileProperties {

    private Type type;

    /**
     * type=SCENARIO일 때 사용
     * - 예: "config/scenario/normal_scenario_case1.md"
     */
    private String scenarioFile;

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getScenarioFile() {
        return scenarioFile;
    }

    public void setScenarioFile(String scenarioFile) {
        this.scenarioFile = scenarioFile;
    }

    public enum Type {
        SCENARIO,
        RATE
    }
}