package com.nori.tc.eqpsim.socket.scenario;

import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.config.ProfileProperties;
import com.nori.tc.eqpsim.socket.config.TcEqpSimProperties;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * ScenarioRegistry
 *
 * - EQP가 참조하는 SCENARIO profile만 로드한다.
 * - 오류 시 해당 profile은 registry에 등록하지 않는다(결정 10-B).
 */
public final class ScenarioRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRegistry.class);

    private final Map<String, ScenarioPlan> planByProfileId;

    public ScenarioRegistry(TcEqpSimProperties props) {
        Objects.requireNonNull(props, "props must not be null");

        Map<String, ProfileProperties> profiles = props.getProfiles() != null ? props.getProfiles() : Collections.emptyMap();
        Map<String, EqpProperties> eqps = props.getEqps() != null ? props.getEqps() : Collections.emptyMap();

        Set<String> usedProfileIds = new HashSet<>();
        for (Map.Entry<String, EqpProperties> e : eqps.entrySet()) {
            EqpProperties v = e.getValue();
            if (v == null) continue;
            if (v.getProfile() != null && !v.getProfile().trim().isEmpty()) {
                usedProfileIds.add(v.getProfile().trim());
            }
        }

        Map<String, ScenarioPlan> tmp = new LinkedHashMap<>();
        Map<String, ScenarioPlan> cacheByFile = new HashMap<>();

        for (String profileId : usedProfileIds) {
            ProfileProperties profile = profiles.get(profileId);
            if (profile == null) {
                log.warn(StructuredLog.event("scenario_profile_missing", "profileId", profileId));
                continue;
            }
            if (profile.getType() != ProfileProperties.Type.SCENARIO) {
                log.info(StructuredLog.event("scenario_profile_skip_non_scenario",
                        "profileId", profileId, "type", profile.getType()));
                continue;
            }
            String file = profile.getScenarioFile();
            if (file == null || file.trim().isEmpty()) {
                log.warn(StructuredLog.event("scenario_file_blank", "profileId", profileId));
                continue;
            }

            try {
                ScenarioPlan plan = cacheByFile.get(file);
                if (plan == null) {
                    plan = ScenarioMdParser.parseFile(file);
                    cacheByFile.put(file, plan);
                    log.info(StructuredLog.event("scenario_loaded", "file", file, "stepCount", plan.getSteps().size()));
                }
                tmp.put(profileId, plan);
            } catch (IOException | RuntimeException ex) {
                log.error(StructuredLog.event("scenario_load_failed", "profileId", profileId, "file", file), ex);
            }
        }

        this.planByProfileId = Collections.unmodifiableMap(tmp);

        log.info(StructuredLog.event("scenario_registry_ready",
                "usedProfileCount", usedProfileIds.size(),
                "loadedPlanCount", planByProfileId.size()));
    }

    public ScenarioPlan getPlanByProfileId(String profileId) {
        return planByProfileId.get(profileId);
    }
}