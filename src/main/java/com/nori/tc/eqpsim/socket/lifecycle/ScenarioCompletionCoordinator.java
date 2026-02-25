package com.nori.tc.eqpsim.socket.lifecycle;

import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ScenarioCompletionCoordinator
 *
 * 정책(사용자 요구 반영):
 * - ACTIVE: 시나리오 완료 시 시뮬레이터가 close (재연결 금지)
 * - PASSIVE: 시나리오 완료 후에도 연결 유지 (종료는 TC가 수행)
 * - 프로세스 종료 조건:
 *   (1) 모든 EQP 시나리오 완료
 *   (2) PASSIVE 연결 open=0 (TC가 모두 끊음)
 */
@Component
public class ScenarioCompletionCoordinator implements ScenarioCompletionTracker {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCompletionCoordinator.class);

    private static final long EXIT_GRACE_MS = 300;

    private final ConfigurableApplicationContext appContext;
    private final int totalEqpCount;

    private final Set<String> completedEqpIds = ConcurrentHashMap.newKeySet();
    private final Set<String> passiveOpenEqpIds = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean exitTriggered = new AtomicBoolean(false);

    public ScenarioCompletionCoordinator(ConfigurableApplicationContext appContext,
                                         EqpRuntimeRegistry registry) {
        this.appContext = appContext;
        this.totalEqpCount = registry.getTotalEqpCount();

        log.info(StructuredLog.event("process_exit_coordinator_ready",
                "totalEqpCount", totalEqpCount,
                "exitGraceMs", EXIT_GRACE_MS));
    }

    @Override
    public void markPassiveChannelOpened(String eqpId) {
        if (eqpId == null || eqpId.isBlank()) return;

        boolean added = passiveOpenEqpIds.add(eqpId);

        log.info(StructuredLog.event("passive_channel_opened",
                "eqpId", eqpId,
                "added", added,
                "passiveOpenCount", passiveOpenEqpIds.size()));
    }

    @Override
    public void markPassiveChannelClosed(String eqpId) {
        if (eqpId == null || eqpId.isBlank()) return;

        boolean removed = passiveOpenEqpIds.remove(eqpId);

        log.info(StructuredLog.event("passive_channel_closed",
                "eqpId", eqpId,
                "removed", removed,
                "passiveOpenCount", passiveOpenEqpIds.size()));

        checkExitCondition();
    }

    @Override
    public void markScenarioCompleted(String eqpId) {
        if (totalEqpCount <= 0) return;
        if (eqpId == null || eqpId.isBlank()) return;

        boolean added = completedEqpIds.add(eqpId);

        log.info(StructuredLog.event("scenario_global_progress",
                "eqpId", eqpId,
                "added", added,
                "completed", completedEqpIds.size(),
                "total", totalEqpCount,
                "passiveOpenCount", passiveOpenEqpIds.size()));

        checkExitCondition();
    }

    private void checkExitCondition() {
        if (totalEqpCount <= 0) return;
        if (exitTriggered.get()) return;

        boolean allScenarioCompleted = completedEqpIds.size() >= totalEqpCount;
        boolean noPassiveOpen = passiveOpenEqpIds.isEmpty();

        if (!allScenarioCompleted) {
            return;
        }

        if (!noPassiveOpen) {
            log.info(StructuredLog.event("process_exit_waiting_passive_close",
                    "completed", completedEqpIds.size(),
                    "total", totalEqpCount,
                    "passiveOpenCount", passiveOpenEqpIds.size()));
            return;
        }

        if (exitTriggered.compareAndSet(false, true)) {
            scheduleProcessExit();
        }
    }

    private void scheduleProcessExit() {
        log.info(StructuredLog.event("process_exit_scheduled",
                "completed", completedEqpIds.size(),
                "total", totalEqpCount,
                "delayMs", EXIT_GRACE_MS));

        Thread t = new Thread(() -> {
            try {
                Thread.sleep(EXIT_GRACE_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            int code = SpringApplication.exit(appContext, () -> 0);
            System.exit(code);
        }, "eqpsim-exit");

        t.setDaemon(false);
        t.start();
    }
}