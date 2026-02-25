# 02_LIFECYCLE_PASSIVE.md

## 1. PASSIVE 개요

PASSIVE는 시뮬레이터가 서버(bind)로 동작하며, TC가 접속합니다.

핵심 정책:
- 시나리오 완료 후에도 **연결을 유지**
- 연결 종료는 **TC가 수행**
- 프로세스 종료 판단에서 PASSIVE는 “open 채널이 0이 되었는지”가 중요

---

## 2. PASSIVE 생명주기(정상 흐름)

### 단계 0: 서버 기동
1) `NettyTransportLifecycle.start()`
2) endpoints.passive의 각 포트에 대해 `ServerBootstrap.bind`
3) `event=passive_bind_started` 로그

### 단계 1: TC 접속(accept)
1) TC가 포트에 connect
2) child pipeline 구성 시작
   - `ConnectionLimitHandler` : max-conn 초과 시 close
   - `PassiveBindAndFramerHandler` : EQP pool에서 eqpId 1개 할당

### 단계 2: EQP 할당(pool)
1) `EqpRuntimeRegistry.reservePassiveEqpId(endpointId)`로 eqpId 획득
2) 채널 attribute 세팅
   - `ChannelAttributes.EQP = EqpRuntime(eqpId, mode=PASSIVE, ...)`
   - `ChannelAttributes.ENDPOINT_ID = passive endpointId`
3) pipeline에 framer/handshake 추가

### 단계 3: 프레이밍/핸드셰이크
1) framer가 frame을 구성
2) TC -> `CMD=INITIALIZE`
3) `HandshakeHandler`가 `handshake_rx` 로그
4) EqpSim -> `CMD=INITIALIZE_REP EQPID=<eqpId>`
5) `handshake_tx`, `handshake_completed` 로그
6) PASSIVE의 경우: `tracker.markPassiveChannelOpened(eqpId)`
   - 전역에서 PASSIVE open 채널로 추적

### 단계 4: 시나리오 실행
1) `ScenarioRunnerHandler`가 pipeline에 설치됨(replace)
2) `scenario_started` → step 수행
3) WAIT/SEND/EMIT/Fault 규칙에 따라 송수신 진행
4) `scenario_completed` 도달 시:
   - `tracker.markScenarioCompleted(eqpId)`
   - **PASSIVE는 close하지 않고 keepalive 유지**
   - `event=scenario_passive_keepalive` 로그

---

## 3. PASSIVE 생명주기(종료)

### 단계 5: TC가 연결 종료
1) TC가 close(또는 네트워크 종료)
2) Netty `channelInactive` 발생
3) `EqpLifecycleHandler` 동작
   - `registry.releasePassiveEqpId(endpointId, eqpId)`로 pool 반환
   - `tracker.markPassiveChannelClosed(eqpId)` 호출
     - 전역 PASSIVE open set에서 제거

---

## 4. PASSIVE에서 “프로세스 종료”와의 관계

프로세스 종료 조건:
- 모든 EQP 시나리오 완료
- PASSIVE open 채널 수 = 0

즉 PASSIVE는 “시나리오만 끝났다”로는 종료 조건을 만족하지 않으며,
TC가 PASSIVE 연결을 모두 종료해야 프로세스가 종료됩니다.