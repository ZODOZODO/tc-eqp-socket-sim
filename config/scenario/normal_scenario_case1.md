# normal_scenario_case1.md
# - Handshake(CMD=INITIALIZE)는 엔진이 자동 처리하며, 시나리오는 handshake 이후부터 실행됩니다.

# window emit 예시: 10초 window 내 랜덤 1회
[ TcToEqp ] CMD=TOOL_CONDITION_REQUEST
[ EqpToTc ] window=10s count=1 CMD=PORT_STATE_CHANGE EQPID={eqpid} PORTID={var.portid} PORTSTATUS={var.portstatus}
[ EqpToTc ] window=10s count=1 CMD=TOOL_CONDITION_REPLY EQPID={eqpid} STATUS=PASS

[ TcToEqp ] CMD=WORK_ORDER_REQUEST
[ EqpToTc ] window=10s count=1 CMD=WORK_ORDER_REPLY EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}

[ EqpToTc ] window=10s count=1 CMD=PORT_STATE_CHANGE EQPID={eqpid} PORTID={var.portid} PORTSTATUS=LOAD

[ EqpToTc ] window=10s count=1 CMD=CARRRIERID_READ EQPID={eqpid} CARID={var.carid}
[ TcToEqp ] CMD=CARRRIERID_READ_REPLY

[ EqpToTc ] window=10s count=1 CMD=CARRIER_VERIFICATION EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}

[ EqpToTc ] window=10s count=1 CMD=READY_TO_WORK EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ TcToEqp ] CMD=WORK_START_REQEUST
[ EqpToTc ] window=10s count=1 CMD=WORK_START_REPLY EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}

[ Sim ] sleep=3000ms
[ EqpToTc ] window=10s count=1 CMD=DCSPECREQ EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ TcToEqp ] CMD=DCSPECREQ_REP

[ EqpToTc ] window=10s count=1 CMD=WORK_STARTED EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ EqpToTc ] window=10s count=1 CMD=PROCESS_STARTED EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}

[ EqpToTc ] window=10s count=1 CMD=WORK_INFORM EQPID={eqpid} LOTID={var.lotid} SLOTID={var.slotid}

[ EqpToTc ] window=10s count=1 CMD=WORK_ENDED EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ EqpToTc ] window=10s count=1 CMD=PROCESS_ENDED EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}

[ EqpToTc ] window=10s count=1 CMD=DATACOLL EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ TcToEqp ] CMD=DATACOLL_REP

[ EqpToTc ] window=10s count=1 CMD=WORK_COMPLETED EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ TcToEqp ] CMD=WORK_COMPLETED_REPLY

[ EqpToTc ] window=10s count=1 CMD=UNLOAD_REQUEST EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ EqpToTc ] window=10s count=1 CMD=PORT_STATE_CHANGE EQPID={eqpid} PORTID={var.portid} PORTSTATUS=UNLOAD

[ EqpToTc ] window=10s count=1 CMD=CARRIER_UNLOAD_COMPLETED EQPID={eqpid} LOTID={var.lotid} CARID={var.carid}
[ EqpToTc ] window=10s count=1 CMD=PORT_STATE_CHANGE EQPID={eqpid} PORTID={var.portid} PORTSTATUS={var.portstatus}

# interval emit 예시: 1초마다 5회
# [ EqpToTc ] every=1s count=5 CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}

# window emit 예시: 10초 window 내 랜덤 2회
# [ EqpToTc ] window=10s count=2 CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}

# sleep 예시
# [ Sim ] sleep=500ms

# loop 예시
# [ Sim ] label=MAIN
# [ TcToEqp ] CMD=PING
# [ EqpToTc ] CMD=PONG EQPID={eqpid}
# [ Sim ] loop=count=2 goto=MAIN