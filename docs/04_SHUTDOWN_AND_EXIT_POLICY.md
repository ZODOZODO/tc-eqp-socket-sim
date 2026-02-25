# 04_SHUTDOWN_AND_EXIT_POLICY.md

## 1. 정상 종료(채널 레벨)

### 1.1 ACTIVE 정상 종료
- 조건: 시나리오가 `scenario_completed`까지 정상 도달
- 동작:
  1) `markScenarioCompleted(eqpId)` 호출
  2) `CLOSE_REASON=SCENARIO_COMPLETED` 설정
  3) channel close 수행(짧은 grace 후)
  4) ActiveClientConnector는 closeReason 검사 후 재연결 금지

### 1.2 PASSIVE 정상 종료(시뮬레이터 기준)
- 조건: 시나리오 완료
- 동작:
  - 시뮬레이터는 channel close를 하지 않음
  - 연결은 유지되고, 종료는 TC가 수행해야 함
  - TC가 close하면 channelInactive에서 passiveClosed로 처리

---

## 2. 프로세스 종료 조건(최종 정책)

프로세스 종료는 아래 2가지가 모두 만족될 때만 발생합니다.

1) **모든 EQP 시나리오 완료**
   - `completedEqpIds.size == totalEqpCount`

2) **PASSIVE open 채널 수 = 0**
   - PASSIVE는 TC가 끊어야 하므로, open 채널이 남아있으면 프로세스 종료를 지연함

즉:
- “시나리오만 완료”로는 종료되지 않습니다(PASSIVE 때문에).
- PASSIVE 연결을 TC가 모두 닫아줘야 종료됩니다.

---

## 3. 프로세스 종료 동작

1) 종료 조건 만족
2) `event=process_exit_scheduled` 로그
3) 짧은 지연(EXIT_GRACE_MS) 후:
   - `SpringApplication.exit(context)`
   - `System.exit(code)`

---

## 4. 운영/테스트에서의 권장 사용 패턴

### 4.1 “완전 종료” 테스트(권장)
- ACTIVE 시나리오 완료 → 자동 close + 재연결 없음
- PASSIVE 시나리오 완료 → 유지
- TC가 PASSIVE 연결을 모두 종료
- 프로세스 자동 종료

### 4.2 PASSIVE를 계속 유지해야 하는 테스트(주의)
- 이 경우 프로세스 자동 종료와 충돌
- 해결책:
  - (A) 프로세스 종료 조건에서 PASSIVE open 조건을 제거(정책 변경)
  - (B) TC가 종료 시점에 PASSIVE도 close하도록 테스트 시나리오 설계

현재 구현은 (B) 전제를 둡니다.

---

## 5. 로그로 보는 종료 판단 흐름(예시)

- 각 EQP 완료:
  - `event=scenario_global_progress eqpId=... completed=x total=y passiveOpenCount=z`

- 모든 시나리오 완료했지만 PASSIVE open이 남음:
  - `event=process_exit_waiting_passive_close ... passiveOpenCount=z`

- PASSIVE가 모두 닫힘:
  - `event=passive_channel_closed ... passiveOpenCount=0`

- 종료 스케줄:
  - `event=process_exit_scheduled ...`