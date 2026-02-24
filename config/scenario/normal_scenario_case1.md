# normal_scenario_case1.md
# - Handshake(CMD=INITIALIZE)는 엔진이 자동 처리하며, 시나리오는 handshake 이후부터 실행됩니다.

[ TcToEqp ] CMD=TOOL_CONDITION_REQUEST
[ EqpToTc ] CMD=TOOL_CONDITION_REPLY EQPID={eqpid} LOTID={var.lotid} STEPID={var.stepid}

[ TcToEqp ] CMD=WORK_ORDER_REQUEST
[ EqpToTc ] CMD=WORK_ORDER_REPLY EQPID={eqpid} LOTID={var.lotid}

# interval emit 예시: 1초마다 5회
[ EqpToTc ] every=1s count=5 CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}

# window emit 예시: 10초 window 내 랜덤 2회
[ EqpToTc ] window=10s count=2 CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}

# sleep 예시
[ Sim ] sleep=500ms

# loop 예시
[ Sim ] label=MAIN
[ TcToEqp ] CMD=PING
[ EqpToTc ] CMD=PONG EQPID={eqpid}
[ Sim ] loop=count=2 goto=MAIN