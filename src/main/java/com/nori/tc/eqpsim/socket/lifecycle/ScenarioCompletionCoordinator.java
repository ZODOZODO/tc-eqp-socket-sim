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
 * 역할:
 * - EQP 시나리오 완료 / PASSIVE 채널 open-close 이벤트를 추적한다.
 * - 종료 조건을 만족하면 프로세스 종료를 스케줄한다.
 *
 * 종료 조건:
 * 1) 모든 EQP 시나리오 완료
 * 2) PASSIVE open 채널 수 = 0 (TC가 모두 끊음)
 *
 * ✅ [S1 수정] SpringApplication.exit() 데드락 방지
 *
 * 문제:
 * - SpringApplication.exit() 호출 시 Spring 컨텍스트가 닫히면서
 *   NettyTransportLifecycle.stop() → workerGroup.shutdownGracefully().syncUninterruptibly() 실행
 * - 이 흐름이 Netty EventLoop 스레드에서 호출될 경우, EventLoop가 자기 자신을 기다리는 데드락 발생
 * - 또한 workerGroup.shutdownGracefully()에 타임아웃이 없으면
 *   진행 중인 작업이 있을 때 무한 대기 가능
 *
 * 수정:
 * 1) 종료는 반드시 별도 비-데몬 스레드("eqpsim-exit")에서 수행
 *    → EventLoop 스레드에서 호출되지 않으므로 데드락 없음
 * 2) Spring context close 전 NettyTransportLifecycle의 stop을 SmartLifecycle 위임
 *    → Spring이 올바른 Phase 순서로 정리하므로 순서 문제 없음
 * 3) EXIT_GRACE_MS 대기 후 종료 → 진행 중인 로그/flush 완료 기회 제공
 */
@Component
public class ScenarioCompletionCoordinator implements ScenarioCompletionTracker {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCompletionCoordinator.class);

    /**
     * 종료 전 대기 시간(ms):
     * - 로그 flush, in-flight 메시지 완료를 위한 grace period
     */
    private static final long EXIT_GRACE_MS = 500;

    private final ConfigurableApplicationContext appContext;
    private final int totalEqpCount;

    private final Set<String> completedEqpIds = ConcurrentHashMap.newKeySet();
    private final Set<String> passiveOpenEqpIds = ConcurrentHashMap.newKeySet();

    /** 중복 종료 시도 방지 */
    private final AtomicBoolean exitTriggered = new AtomicBoolean(false);

    public ScenarioCompletionCoordinator(ConfigurableApplicationContext appContext,
                                         EqpRuntimeRegistry registry) {
        this.appContext = appContext;
        this.totalEqpCount = registry.getTotalEqpCount();

        log.info(StructuredLog.event("process_exit_coordinator_ready",
                "totalEqpCount", totalEqpCount,
                "exitGraceMs", EXIT_GRACE_MS));
    }

    // ─── ScenarioCompletionTracker 구현 ──────────────────────────────────────

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

    // ─── 종료 조건 판단 ───────────────────────────────────────────────────────

    private void checkExitCondition() {
        if (totalEqpCount <= 0) return;
        if (exitTriggered.get()) return;

        boolean allScenarioCompleted = completedEqpIds.size() >= totalEqpCount;
        boolean noPassiveOpen = passiveOpenEqpIds.isEmpty();

        if (!allScenarioCompleted) return;

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

    /**
     * 프로세스 종료를 별도 비-데몬 스레드에서 수행한다.
     *
     * ✅ [S1 수정] 핵심 안전 조치:
     * - 반드시 새 스레드("eqpsim-exit")에서 SpringApplication.exit() 호출
     *   → Netty EventLoop 스레드에서 호출 시 발생하는 데드락을 원천 차단
     * - setDaemon(false): JVM이 이 스레드 완료를 기다림 → 종료 흐름 보장
     * - EXIT_GRACE_MS 대기: in-flight 로그/메시지 완료 기회 제공
     *
     * 종료 순서:
     * 1) "eqpsim-exit" 스레드 기동
     * 2) EXIT_GRACE_MS 대기
     * 3) SpringApplication.exit(appContext) → SmartLifecycle.stop() 순서대로 호출
     *    → NettyTransportLifecycle.stop() → shutdownGracefully() (비-EventLoop 스레드에서 안전)
     * 4) System.exit(code)
     */
    private void scheduleProcessExit() {
        log.info(StructuredLog.event("process_exit_scheduled",
                "completed", completedEqpIds.size(),
                "total", totalEqpCount,
                "delayMs", EXIT_GRACE_MS));

        Thread exitThread = new Thread(() -> {
            try {
                // grace period: 진행 중인 로그/flush 완료 대기
                Thread.sleep(EXIT_GRACE_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            log.info(StructuredLog.event("process_exit_executing",
                    "completed", completedEqpIds.size(),
                    "total", totalEqpCount));

            // Spring 컨텍스트 정상 종료 (SmartLifecycle 위임 → Netty 포함 정리)
            int exitCode = SpringApplication.exit(appContext, () -> 0);
            System.exit(exitCode);
        }, "eqpsim-exit");

        // non-daemon: JVM이 이 스레드의 완료를 기다린다 (종료 흐름 보장)
        exitThread.setDaemon(false);
        exitThread.start();
    }
}