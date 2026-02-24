# fault_disconnect_case1.md
# - fault는 시나리오 단계로 주입됩니다.
# - disconnect는 after=... 뒤에 채널을 close 합니다.
# - clear는 count 또는 duration이 필요하므로 count=1 형태로 둡니다.

[ TcToEqp ] CMD=TOOL_CONDITION_REQUEST
[ EqpToTc ] CMD=TOOL_CONDITION_REPLY EQPID={eqpid} LOTID={var.lotid}

# drop 주입 (10초 동안)
[ Sim ] fault=drop rate=0.10 duration=10s
[ EqpToTc ] every=200ms count=20 CMD=TOOLEVENTS EQPID={eqpid}

# corrupt 주입 (다음 30회 중 rate=0.05)
[ Sim ] fault=corrupt rate=0.05 protectFraming=true count=30
[ EqpToTc ] every=100ms count=30 CMD=TOOLEVENTS EQPID={eqpid}

# fragment 주입 (다음 10회)
[ Sim ] fault=fragment minParts=2 maxParts=6 count=10
[ EqpToTc ] every=100ms count=10 CMD=TOOLEVENTS EQPID={eqpid}

# clear
[ Sim ] fault=clear count=1

# 3초 후 disconnect
[ Sim ] fault=disconnect after=3s down=2s count=1