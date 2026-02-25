# BIND 가 완료되길 확실히 기다림
[ Sim ] sleep=10s

# interval emit 예시: 1초마다 1회
[ EqpToTc ] every=1s count=100 CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}

# 무한으로 설정하고 싶을 경우 count=forever 로 설정
# [ EqpToTc ] every=1s count=forever CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}

# 1초에 10개 event를 3회 반복
# [ EqpToTc ] every=100ms count=30 CMD=TOOLEVENTS EQPID={eqpid} LOTID={var.lotid}
