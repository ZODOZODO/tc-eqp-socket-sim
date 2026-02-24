package com.nori.tc.eqpsim.socket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tc-eqp-socket-sim
 *
 * 목적:
 * - 차세대 TC v5.0 기준 "설비(Equipment) 소켓 에뮬레이터" 실행 엔트리포인트
 *
 * 현재 단계(스캐폴딩):
 * - 애플리케이션이 정상 기동되는 최소 구성만 둔다.
 * - Netty/Scenario/Properties 로딩 구현은 다음 단계에서 추가한다.
 */
@SpringBootApplication
public class TcEqpSocketSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcEqpSocketSimApplication.class, args);
    }
}