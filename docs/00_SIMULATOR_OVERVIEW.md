# 00_SIMULATOR_OVERVIEW.md

## 1. 목적

`tc-eqp-socket-sim`은 nori-tc의 **Socket 통신 경로**를 검증하기 위한 설비(Equipment) 에뮬레이터입니다.

검증 범위는 **바이트 송수신(프레이밍 + 간단 텍스트 프로토콜)** 수준이며, 주요 목표는 다음과 같습니다.

- PASSIVE(서버) / ACTIVE(클라이언트) 연결 모드 모두 검증
- 설비별 시나리오 기반 송수신 동작 검증
- 스트레스/장애 주입(drop/corrupt/fragment/delay/disconnect 등)으로 TC 안정성 검증
- 동시 연결 120대(설계는 1000대 고려) 수준에서의 연결/송수신 안정성 확인

---

## 2. 핵심 개념

### 2.1 설비 모드

- **PASSIVE**
  - 시뮬레이터가 서버(bind)로 동작
  - TC가 시뮬레이터로 접속(accept)
  - **연결 종료는 TC가 수행** (시뮬레이터는 시나리오 완료 후에도 연결 유지)

- **ACTIVE**
  - 시뮬레이터가 클라이언트(connect)로 동작
  - 시뮬레이터가 TC 서버로 접속
  - 시나리오 완료 시 **시뮬레이터가 정상 종료(close)**
  - 종료 정책 A: `SCENARIO_COMPLETED` 사유로 닫힌 ACTIVE는 **재연결하지 않음**

---

### 2.2 핸드셰이크(연결 식별)

연결 후, TC는 먼저 `CMD=INITIALIZE`를 전송합니다.

- TC -> EqpSim: `CMD=INITIALIZE` (라인 종단 문자는 프레이밍 규칙에 의해 처리)
- EqpSim -> TC: `CMD=INITIALIZE_REP EQPID=<eqpId>`

핸드셰이크가 완료되면 해당 채널은 `eqpId`로 식별됩니다.

---

### 2.3 프레이밍(Framer, socket-type)

시뮬레이터는 socket-type에 따라 수신 바이트를 “프레임”으로 분리합니다.

지원 종류:

1) **LINE_END**
- line-ending: `LF` / `CR` / `CRLF`
- 예: `CMD=INITIALIZE\n`

2) **START_END**
- start-hex / end-hex 지정
- 예: STX(0x02) ... ETX(0x03)

3) **REGEX**
- 정규식으로 프레임 경계를 정의
- 주의: regex는 잘못 설계하면 폭주/역추적 비용 증가 가능(필요시에만)

---

### 2.4 메시지 포맷

기본 포맷은 `NAME=VALUE`의 토큰 집합입니다.

- 구분: 공백(현재 사용 계획은 “공백 없이”도 가능하나, 기본 파서는 공백 기준 토큰을 지원)
- 형태: `CMD=... EQPID=... LOTID=...`
- 케이스: 대소문자 구분 없음 → 내부에서 `upper`로 표준화

WAIT 매칭 규칙(핵심):
- `CMD=<EXPECTED>`만 일치하면 통과
- 예상 외 CMD는 무시하고 계속 대기

---

## 3. 시나리오(Scenario) DSL 개요

시나리오는 `config/scenario/*.md` 파일로 작성합니다.

### 3.1 기본 단계

- `[ TcToEqp ] CMD=...`
  - TC가 해당 CMD를 보낼 때까지 대기(WAIT)

- `[ EqpToTc ] CMD=...`
  - 즉시 송신(SEND)

### 3.2 EMIT(주기/윈도우)

- interval 형(고정 간격)
  - `every=1s count=100 ...`  → 1초마다 100회(총 100회)
  - `every=1s count=forever ...` → 1초마다 무한

- window 형(윈도우 내 랜덤 분포)
  - `window=10s count=2 ...` → 10초 창 안에 랜덤하게 2회

### 3.3 변수 치환
- `{eqpid}`: 현재 EQP ID
- `{var.xxx}`: eqp 설정의 vars map
  - 예: `LOTID={var.lotid}`

---

## 4. 장애 주입(Fault) 개요

시나리오 단계로 장애를 주입할 수 있습니다.

예:
- drop: 확률적으로 송신 누락
- corrupt: 일부 바이트 변조
- fragment: 송신을 여러 조각으로 분할
- delay: 송신 지연
- disconnect: 지정 후 채널 종료
- clear: fault 해제

---

## 5. 실행 방식

### 5.1 실행 경로
- `gradlew.bat bootRun`
- `java -jar build/libs/*.jar`

### 5.2 외부 설정 로딩
`src/main/resources/application.yml`에서 `spring.config.import=optional:file:./config/`가 설정되어 있으면,
실행 위치의 `./config/application.yml`을 자동 로드합니다.

---

## 6. 로그(구조화)

로그는 key=value 스타일 구조화 로그를 사용합니다.

핸드셰이크:
- `event=handshake_started`
- `event=handshake_rx` / `event=handshake_tx`
- `event=handshake_completed`

시나리오:
- `event=scenario_started`
- `event=scenario_emit_started`
- `event=scenario_emit_send`
- `event=scenario_completed`

진단(raw bytes):
- `event=netty_rx_raw` (framer 이전 실제 수신 바이트)

---

## 7. 종료 정책(최종)

- ACTIVE: 시나리오 완료 시 시뮬레이터가 close, 재연결 금지
- PASSIVE: 시나리오 완료 후 연결 유지(종료는 TC가 수행)
- 프로세스 종료 조건:
  1) 모든 EQP 시나리오 완료
  2) PASSIVE open 채널 수 = 0 (TC가 모두 끊음)