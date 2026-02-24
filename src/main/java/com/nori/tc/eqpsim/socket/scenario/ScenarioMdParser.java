package com.nori.tc.eqpsim.socket.scenario;

import com.nori.tc.eqpsim.socket.protocol.FrameTokenParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * ScenarioMdParser
 *
 * 지원 문법(확정):
 * - [TcToEqp] CMD=XXXX [timeout=15s optional]
 * - [EqpToTc] <payload tokens...>
 * - [EqpToTc] every=1s count=60 [jitter=200ms optional] <payload tokens...>
 * - [EqpToTc] window=10s count=2 <payload tokens...>
 * - [Sim] sleep=500ms
 * - [Sim] label=MAIN
 * - [Sim] goto=MAIN
 * - [Sim] loop=count=5 goto=MAIN
 * - [Sim] fault=delay|fragment|drop|corrupt|disconnect|clear ... (scope 지원)
 *
 * 주석/빈줄:
 * - 빈줄 무시
 * - '#' 시작 줄 무시
 * - "1) " 같은 번호 프리픽스는 무시(선택적)
 *
 * 오류 정책:
 * - 파싱 오류는 IllegalArgumentException으로 throw (상위에서 EQP disable 처리)
 */
public final class ScenarioMdParser {

    private ScenarioMdParser() {}

    public static ScenarioPlan parseFile(String scenarioFilePath) throws IOException {
        Path p = Paths.get(scenarioFilePath);
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("scenario file not found: " + scenarioFilePath);
        }

        List<ScenarioStep> steps = new ArrayList<>();
        Map<String, Integer> labelIndex = new HashMap<>();

        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;

            while ((line = br.readLine()) != null) {
                lineNo++;
                String raw = line;
                String trimmed = preprocessLine(raw);
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("#")) continue;

                int lb = trimmed.indexOf('[');
                int rb = trimmed.indexOf(']');
                if (lb != 0 || rb <= 1) {
                    throw new IllegalArgumentException("invalid step tag at " + scenarioFilePath + ":" + lineNo + " -> " + raw);
                }

                String tag = trimmed.substring(1, rb).trim();
                String body = trimmed.substring(rb + 1).trim();

                if (tag.equalsIgnoreCase("TcToEqp")) {
                    steps.add(parseWaitStep(scenarioFilePath, lineNo, body));
                } else if (tag.equalsIgnoreCase("EqpToTc")) {
                    steps.add(parseEqpToTcStep(scenarioFilePath, lineNo, body));
                } else if (tag.equalsIgnoreCase("Sim")) {
                    ScenarioStep simStep = parseSimStep(scenarioFilePath, lineNo, body);
                    if (simStep instanceof LabelStep ls) {
                        String key = ls.getLabel();
                        if (labelIndex.containsKey(key)) {
                            throw new IllegalArgumentException("duplicate label '" + key + "' at " + scenarioFilePath + ":" + lineNo);
                        }
                        labelIndex.put(key, steps.size());
                    }
                    steps.add(simStep);
                } else {
                    throw new IllegalArgumentException("unknown tag [" + tag + "] at " + scenarioFilePath + ":" + lineNo);
                }
            }
        }

        // goto/loop의 label 존재 검증
        for (int i = 0; i < steps.size(); i++) {
            ScenarioStep st = steps.get(i);
            if (st instanceof GotoStep g) {
                if (!labelIndex.containsKey(g.getLabel())) {
                    throw new IllegalArgumentException("goto label not found: " + g.getLabel() + " in " + scenarioFilePath);
                }
            } else if (st instanceof LoopStep lp) {
                if (!labelIndex.containsKey(lp.getGotoLabel())) {
                    throw new IllegalArgumentException("loop goto label not found: " + lp.getGotoLabel() + " in " + scenarioFilePath);
                }
            }
        }

        return new ScenarioPlan(p.toString(), steps, labelIndex);
    }

    private static ScenarioStep parseWaitStep(String file, int lineNo, String body) {
        Map<String, String> map = FrameTokenParser.parseToUpperKeyMap(body);
        String cmd = map.get("CMD");
        if (cmd == null || cmd.isBlank()) {
            throw new IllegalArgumentException("WAIT step requires CMD=... at " + file + ":" + lineNo);
        }
        String expectedCmdUpper = cmd.trim().toUpperCase(Locale.ROOT);

        Long timeoutOverrideSec = null;
        String timeoutStr = map.get("TIMEOUT");
        if (timeoutStr != null && !timeoutStr.isBlank()) {
            timeoutOverrideSec = parseDurationToMs(timeoutStr) / 1000;
            if (timeoutOverrideSec <= 0) {
                throw new IllegalArgumentException("timeout must be >0 at " + file + ":" + lineNo);
            }
        }
        return new WaitCmdStep(expectedCmdUpper, timeoutOverrideSec);
    }

    private static ScenarioStep parseEqpToTcStep(String file, int lineNo, String body) {
        // EMIT 구문인지 판별: every= 또는 window=
        Map<String, String> map = FrameTokenParser.parseToUpperKeyMap(body);

        String every = map.get("EVERY");
        String window = map.get("WINDOW");

        if (every != null || window != null) {
            return parseEmitStep(file, lineNo, body, map);
        }

        // 그냥 SEND
        if (body.isBlank()) {
            throw new IllegalArgumentException("SEND step payload is blank at " + file + ":" + lineNo);
        }
        return new SendStep(body);
    }

    private static ScenarioStep parseEmitStep(String file, int lineNo, String body, Map<String, String> mapUpper) {
        String countStr = mapUpper.get("COUNT");
        if (countStr == null || countStr.isBlank()) {
            throw new IllegalArgumentException("EMIT requires count=... at " + file + ":" + lineNo);
        }

        EmitStep.Count count;
        if (countStr.equalsIgnoreCase("forever")) {
            count = EmitStep.CountForever.INSTANCE;
        } else {
            int c;
            try {
                c = Integer.parseInt(countStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid count at " + file + ":" + lineNo + " -> " + countStr);
            }
            count = new EmitStep.CountFixed(c);
        }

        String everyStr = mapUpper.get("EVERY");
        String windowStr = mapUpper.get("WINDOW");

        EmitStep.Mode mode;
        long intervalOrWindowMs;

        if (everyStr != null) {
            mode = EmitStep.Mode.INTERVAL;
            intervalOrWindowMs = parseDurationToMs(everyStr);
        } else if (windowStr != null) {
            mode = EmitStep.Mode.WINDOW;
            intervalOrWindowMs = parseDurationToMs(windowStr);
        } else {
            throw new IllegalArgumentException("EMIT requires every=... or window=... at " + file + ":" + lineNo);
        }

        Long jitterMs = null;
        String jitterStr = mapUpper.get("JITTER");
        if (jitterStr != null && !jitterStr.isBlank()) {
            jitterMs = parseDurationToMs(jitterStr);
            if (jitterMs < 0) jitterMs = 0L;
        }

        // payloadTemplate = control 토큰(every/window/count/jitter 등) 제외하고 다시 join
        String payload = buildPayloadExcludingControlTokens(body, Set.of("every", "window", "count", "jitter"));
        if (payload.isBlank()) {
            throw new IllegalArgumentException("EMIT payload is blank at " + file + ":" + lineNo);
        }

        return new EmitStep(mode, intervalOrWindowMs, count, payload, jitterMs);
    }

    private static ScenarioStep parseSimStep(String file, int lineNo, String body) {
        if (body.isBlank()) {
            throw new IllegalArgumentException("[Sim] step body blank at " + file + ":" + lineNo);
        }

        Map<String, String> map = FrameTokenParser.parseToUpperKeyMap(body);

        String sleep = map.get("SLEEP");
        if (sleep != null) {
            long ms = parseDurationToMs(sleep);
            return new SleepStep(ms);
        }

        String label = map.get("LABEL");
        if (label != null && !label.isBlank()) {
            return new LabelStep(label.trim());
        }

        String go = map.get("GOTO");
        if (go != null && !go.isBlank()) {
            return new GotoStep(go.trim());
        }

        String loop = map.get("LOOP"); // value like "count=5"
        if (loop != null) {
            int count = parseLoopCount(loop, file, lineNo);
            String gotoLabel = map.get("GOTO");
            if (gotoLabel == null || gotoLabel.isBlank()) {
                throw new IllegalArgumentException("loop requires goto=LABEL at " + file + ":" + lineNo);
            }
            return new LoopStep(count, gotoLabel.trim());
        }

        String fault = map.get("FAULT");
        if (fault != null) {
            return parseFaultStep(file, lineNo, body, map);
        }

        throw new IllegalArgumentException("unknown [Sim] command at " + file + ":" + lineNo + " -> " + body);
    }

    private static int parseLoopCount(String loopValue, String file, int lineNo) {
        String v = loopValue.trim();
        // loop=count=5 형태
        if (v.toLowerCase(Locale.ROOT).startsWith("count=")) {
            v = v.substring("count=".length()).trim();
        }
        try {
            int c = Integer.parseInt(v);
            if (c <= 0) throw new IllegalArgumentException();
            return c;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid loop count at " + file + ":" + lineNo + " -> " + loopValue);
        }
    }

    private static FaultStep parseFaultStep(String file, int lineNo, String rawBody, Map<String, String> mapUpper) {
        String typeStr = mapUpper.get("FAULT");
        FaultStep.Type type;
        try {
            type = FaultStep.Type.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid fault type at " + file + ":" + lineNo + " -> " + typeStr);
        }

        // scope 결정: duration=... 있으면 DURATION, 아니면 next/count 있으면 NEXT. 둘 다 없으면 duration=0으로 금지.
        FaultStep.ScopeMode scopeMode;
        Long durationMs = null;
        Integer nextCount = null;

        String durationStr = mapUpper.get("DURATION");
        if (durationStr != null && !durationStr.isBlank()) {
            scopeMode = FaultStep.ScopeMode.DURATION;
            durationMs = parseDurationToMs(durationStr);
            if (durationMs <= 0) throw new IllegalArgumentException("duration must be > 0 at " + file + ":" + lineNo);
        } else {
            // next count
            String countStr = mapUpper.get("COUNT");
            if (countStr == null || countStr.isBlank()) {
                throw new IllegalArgumentException("fault requires duration=... OR count=... (next scope) at " + file + ":" + lineNo);
            }
            scopeMode = FaultStep.ScopeMode.NEXT;
            try {
                nextCount = Integer.parseInt(countStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid fault count at " + file + ":" + lineNo + " -> " + countStr);
            }
            if (nextCount <= 0) throw new IllegalArgumentException("fault count must be > 0 at " + file + ":" + lineNo);
        }

        Long delayMs = null, jitterMs = null, afterMs = null, downMs = null;
        Integer minParts = null, maxParts = null;
        Double rate = null;
        Boolean protectFraming = null;

        switch (type) {
            case DELAY -> {
                String msStr = mapUpper.get("MS");
                if (msStr == null) throw new IllegalArgumentException("fault=delay requires ms=... at " + file + ":" + lineNo);
                delayMs = parseDurationToMs(msStr);
                String jStr = mapUpper.get("JITTER");
                if (jStr != null) jitterMs = parseDurationToMs(jStr);
            }
            case FRAGMENT -> {
                String minStr = mapUpper.get("MINPARTS");
                String maxStr = mapUpper.get("MAXPARTS");
                if (minStr == null || maxStr == null) {
                    throw new IllegalArgumentException("fault=fragment requires minParts=.. maxParts=.. at " + file + ":" + lineNo);
                }
                minParts = Integer.parseInt(minStr.trim());
                maxParts = Integer.parseInt(maxStr.trim());
                if (minParts <= 0 || maxParts < minParts) {
                    throw new IllegalArgumentException("invalid fragment parts at " + file + ":" + lineNo);
                }
            }
            case DROP -> {
                String rateStr = mapUpper.get("RATE");
                if (rateStr == null) throw new IllegalArgumentException("fault=drop requires rate=.. at " + file + ":" + lineNo);
                rate = Double.parseDouble(rateStr.trim());
            }
            case CORRUPT -> {
                String rateStr = mapUpper.get("RATE");
                if (rateStr == null) throw new IllegalArgumentException("fault=corrupt requires rate=.. at " + file + ":" + lineNo);
                rate = Double.parseDouble(rateStr.trim());
                String protect = mapUpper.get("PROTECTFRAMING");
                protectFraming = (protect == null) ? Boolean.TRUE : Boolean.parseBoolean(protect.trim());
            }
            case DISCONNECT -> {
                String afterStr = mapUpper.get("AFTER");
                if (afterStr == null) throw new IllegalArgumentException("fault=disconnect requires after=.. at " + file + ":" + lineNo);
                afterMs = parseDurationToMs(afterStr);
                String downStr = mapUpper.get("DOWN");
                if (downStr != null) downMs = parseDurationToMs(downStr);
            }
            case CLEAR -> {
                // scope 무시해도 되지만, 문법상 scope를 요구하므로 그대로 둠
            }
        }

        return new FaultStep(type, scopeMode, durationMs, nextCount, delayMs, jitterMs, minParts, maxParts, rate, protectFraming, afterMs, downMs);
    }

    private static String buildPayloadExcludingControlTokens(String body, Set<String> controlKeysLower) {
        String[] tokens = body.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();

        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                if (controlKeysLower.contains(k)) {
                    continue;
                }
            }
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static long parseDurationToMs(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("ms")) {
            return Long.parseLong(v.substring(0, v.length() - 2).trim());
        }
        if (v.endsWith("s")) {
            return Long.parseLong(v.substring(0, v.length() - 1).trim()) * 1000L;
        }
        // default: seconds
        return Long.parseLong(v.trim()) * 1000L;
    }

    private static String preprocessLine(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // "1) ..." / "(1) ..." prefix 제거 (선택)
        s = s.replaceFirst("^\\(?\\d+\\)?\\s*\\)\\s*", "");
        return s.trim();
    }
}