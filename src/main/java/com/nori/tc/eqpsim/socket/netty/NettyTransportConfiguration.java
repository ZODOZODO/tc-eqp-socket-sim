package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.TcEqpSimProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NettyTransportConfiguration {

    @Bean
    public EqpRuntimeRegistry eqpRuntimeRegistry(TcEqpSimProperties props) {
        return new EqpRuntimeRegistry(props);
    }

    @Bean
    public ScenarioRegistry scenarioRegistry(TcEqpSimProperties props) {
        return new ScenarioRegistry(props);
    }

    @Bean
    public NettyTransportLifecycle nettyTransportLifecycle(EqpRuntimeRegistry registry,
                                                           TcEqpSimProperties props,
                                                           ScenarioRegistry scenarioRegistry,
                                                           ScenarioCompletionTracker tracker) {
        return new NettyTransportLifecycle(registry, props.getEndpoints().getActiveBackoff(), scenarioRegistry, tracker);
    }
}