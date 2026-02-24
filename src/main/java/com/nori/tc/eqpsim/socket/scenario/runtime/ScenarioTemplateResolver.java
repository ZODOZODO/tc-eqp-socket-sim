package com.nori.tc.eqpsim.socket.scenario.runtime;

import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {eqpid}, {var.xxx} 치환 처리.
 *
 * 정책:
 * - {eqpid} 대소문자 무시
 * - {var.xxx}에서 xxx는 lower normalize하여 eqpRuntime.varsLowerKey에서 조회
 */
public final class ScenarioTemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)\\}");

    private ScenarioTemplateResolver() {}

    public static String resolve(String template, EqpRuntime eqp) {
        if (template == null || template.isEmpty()) return template;

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String keyRaw = m.group(1);
            String key = keyRaw.trim().toLowerCase(Locale.ROOT);

            String replacement = null;

            if (key.equals("eqpid")) {
                replacement = eqp.getEqpId();
            } else if (key.startsWith("var.")) {
                String varKey = key.substring("var.".length());
                replacement = eqp.getVarsLowerKey().get(varKey);
            }

            if (replacement == null) {
                // 모르는 placeholder는 원문 유지
                replacement = "{" + keyRaw + "}";
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}