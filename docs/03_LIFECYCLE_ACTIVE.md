# 03_LIFECYCLE_ACTIVE.md

## 1. ACTIVE 개요

ACTIVE는 시뮬레이터가 클라이언트(connect)로 동작하며, TC 서버로 접속합니다.

핵심 정책:
- 시나리오 완료 후 **시뮬레이터가 close**
- 종료 사유를 `SCENARIO_COMPLETED`로 마킹
- 종료 정책 A: `SCENARIO_COMPLETED`로 닫힌 ACTIVE는 **재연결 금지**

---

## 2. ACTIVE 생명주기(정상 흐름)

### 단계 0: ACTIVE connect 시작
1) `NettyTransportLifecycle.start()`
2) registry의 activeEqps 목록 순회
3) eqpId별 `ActiveClientConnector.connectNow()` 호출
4) connect 성공 시 `event=active_connected`

### 단계 1: 채널 초기화
1) `ActiveChannelInitializer`가 pipeline 구성
2) channel attribute 세팅
   - `ChannelAttributes.EQP = EqpRuntime(eqpId, mode=ACTIVE, ...)`
   - `ChannelAttributes.ENDPOINT_ID = active endpointId`
3) (선택) `RawInboundBytesLoggingHandler` 추가
4) framer 추가
5) `HandshakeHandler` 추가

### 단계 2: 핸드셰이크
1) TC -> `CMD=INITIALIZE`
2) `HandshakeHandler.handshake_rx`
3) EqpSim -> `CMD=INITIALIZE_REP EQPID=<eqpId>`
4) `handshake_tx`, `handshake_completed`
5) 완료 후 ScenarioRunner로 replace

### 단계 3: 시나리오 실행
1) `scenario_started`
2) step 수행(WAIT/SEND/EMIT/FAULT)
3) `scenario_completed` 시:
   - `tracker.markScenarioCompleted(eqpId)`
   - ACTIVE는 close 예약
   - `ChannelAttributes.CLOSE_REASON = SCENARIO_COMPLETED`

### 단계 4: 정상 close 및 재연결 차단
1) 채널이 close됨
2) `ActiveClientConnector` closeFuture 리스너에서 closeReason 검사
3) closeReason == `SCENARIO_COMPLETED` 이면:
   - `event=active_closed_no_reconnect`
   - stopped=true 처리
   - 재연결 예약하지 않음

---

## 3. ACTIVE 생명주기(비정상/재연결)

- connect 실패 / channel_closed(비정상)인 경우:
  - `active_reconnect_scheduled`
  - backoff 정책(initial/max/multiplier) 적용
- 단, 시나리오 완료 close(`SCENARIO_COMPLETED`)는 재연결하지 않음