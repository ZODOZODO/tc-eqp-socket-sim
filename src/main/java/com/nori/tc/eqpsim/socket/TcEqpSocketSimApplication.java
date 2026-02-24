package com.nori.tc.eqpsim.socket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * tc-eqp-socket-sim
 *
 * 스캐폴딩 단계:
 * - Spring Boot 앱이 정상적으로 기동되는 최소 구성만 포함
 * - Netty/Scenario/Properties 로딩 구현은 단계별로 추가한다.
 *
 * 구성 프로퍼티:
 * - @ConfigurationPropertiesScan을 통해 tc.eqpsim.* 설정 모델을 스캔한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.nori.tc.eqpsim.socket")
public class TcEqpSocketSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcEqpSocketSimApplication.class, args);
    }
}