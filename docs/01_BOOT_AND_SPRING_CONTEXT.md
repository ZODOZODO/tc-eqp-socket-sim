# 01_BOOT_AND_SPRING_CONTEXT.md

## 1. 구동 순서(고수준)

1) Spring Boot 기동
2) `TcEqpSimProperties` 로딩(@ConfigurationProperties)
3) 런타임 레지스트리 구성
   - `EqpRuntimeRegistry` 생성
   - endpoints/passive, endpoints/active 구성 검증
   - eqps 정의 검증(모드/endpoint/socket-type/profile 참조 무결성)
4) 시나리오 로딩
   - `ScenarioRegistry` 생성
   - eqps에서 실제 참조하는 profile의 scenario-file만 로드(낭비 최소화)
5) 종료 정책 준비
   - `ScenarioCompletionCoordinator` 생성(전역 완료/프로세스 종료 판단)
6) Netty transport 기동(SmartLifecycle)
   - `NettyTransportLifecycle.start()`
   - PASSIVE 서버 bind 시작
   - ACTIVE 클라이언트 connect 시작

---

## 2. Spring Context에 올라가는 주요 Bean(메모리 상주 관점)

### 2.1 설정/모델
- `TcEqpSimProperties`
  - `tc.eqpsim.*` 전체 설정을 메모리에 보관
  - socket-types / endpoints / profiles / eqps 포함

### 2.2 런타임 레지스트리
- `EqpRuntimeRegistry`
  - 모든 EQP의 런타임 객체(`EqpRuntime`)를 구성하여 메모리에 상주
  - PASSIVE pool(엔드포인트별 가용 EQP 큐)
  - ACTIVE EQP 목록
  - endpoints(passive bind, active target) 주소 맵
  - `getTotalEqpCount()`로 “총 EQP 수” 제공(종료 정책에서 사용)

### 2.3 시나리오 레지스트리
- `ScenarioRegistry`
  - scenario md 파일을 파싱하여 `ScenarioPlan`으로 보관
  - profileId → ScenarioPlan 매핑

### 2.4 종료 정책/프로세스 종료 트래커
- `ScenarioCompletionCoordinator` (또는 `ScenarioCompletionTracker` 구현체)
  - `markScenarioCompleted(eqpId)` : EQP 시나리오 완료 카운트
  - `markPassiveChannelOpened/Closed(eqpId)` : PASSIVE open 채널 추적
  - 종료 조건 만족 시 `SpringApplication.exit` + `System.exit`

### 2.5 Netty 라이프사이클
- `NettyTransportLifecycle` (SmartLifecycle)
  - 기동 시 NioEventLoopGroup 생성(boss/worker)
  - PASSIVE bind / ACTIVE connect 수행
  - 종료 시 group shutdownGracefully

---

## 3. Netty 구성요소(런타임 생성, Bean 아님)

- bossGroup: PASSIVE accept용 EventLoopGroup (보통 1 thread)
- workerGroup: I/O 처리 EventLoopGroup
- PASSIVE: endpointId별 ServerBootstrap/channel
- ACTIVE: eqpId별 Bootstrap/connect

---

## 4. 채널 파이프라인(핸들러 체인)

### 4.1 공통(핵심)
- `RawInboundBytesLoggingHandler` (optional, 진단용)
  - framer 이전 raw bytes를 로그 출력

- `Framer(ByteToMessageDecoder)`
  - socket-type 규칙에 따라 frame 분리

- `HandshakeHandler`
  - `CMD=INITIALIZE` 수신 → `INITIALIZE_REP` 송신
  - 완료 시 runner로 replace

- `ScenarioRunnerHandler`
  - 시나리오 step 실행(WAIT/SEND/EMIT/FAULT/...)

### 4.2 PASSIVE 추가
- `ConnectionLimitHandler` : 포트당 최대 연결 수 제한
- `PassiveBindAndFramerHandler` : PASSIVE pool에서 EQP 할당
- `EqpLifecycleHandler` : channelInactive 시 EQP 반환 + PASSIVE close 이벤트 트래킹

---

## 5. 설정 로딩(외부 config)

`spring.config.import=optional:file:./config/` 설정 시:

- 실행 위치의 `./config/application.yml`을 로드
- scenario md는 `config/scenario/*.md`를 상대경로로 참조

주의:
- 실행 위치가 프로젝트 루트가 아니면 상대경로가 깨질 수 있으므로,
  `tools/run-dev.ps1`을 사용하는 것이 안전합니다.