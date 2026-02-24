package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.*;
import com.nori.tc.eqpsim.socket.framing.LineEndingFrameDecoder;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.HostPort;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandshakeHandlerTests {

    @Test
    void handshake_replies_and_installs_runner_when_plan_exists() throws Exception {
        // 임시 scenario 파일
        Path scenario = Files.createTempFile("normal_scenario_case1", ".md");
        Files.writeString(scenario, """
                [TcToEqp] CMD=PING
                [EqpToTc] CMD=PONG EQPID={eqpid}
                """, StandardCharsets.UTF_8);

        // properties 구성
        TcEqpSimProperties props = new TcEqpSimProperties();

        SocketTypeProperties st = new SocketTypeProperties();
        st.setKind(SocketTypeProperties.Kind.LINE_END);
        st.setLineEnding(SocketTypeProperties.LineEnding.LF);
        props.getSocketTypes().put("LINE_LF", st);

        ProfileProperties prof = new ProfileProperties();
        prof.setType(ProfileProperties.Type.SCENARIO);
        prof.setScenarioFile(scenario.toString());
        props.getProfiles().put("scenario_case1", prof);

        EqpProperties eqpProps = new EqpProperties();
        eqpProps.setMode(EqpProperties.Mode.PASSIVE);
        eqpProps.setEndpoint("L1");
        eqpProps.setSocketType("LINE_LF");
        eqpProps.setProfile("scenario_case1");
        eqpProps.setWaitTimeoutSec(60);
        eqpProps.setHandshakeTimeoutSec(60);
        props.getEqps().put("TEST001", eqpProps);

        ScenarioRegistry scenarioRegistry = new ScenarioRegistry(props);

        EqpRuntime eqp = new EqpRuntime(
                "TEST001",
                EqpProperties.Mode.PASSIVE,
                "L1",
                HostPort.parse("0.0.0.0:31001"),
                20,
                st,
                "scenario_case1",
                prof,
                60,
                60,
                Map.of("lotid", "X")
        );

        EmbeddedChannel ch = new EmbeddedChannel();
        ch.attr(ChannelAttributes.ENDPOINT_ID).set("L1");
        ch.attr(ChannelAttributes.EQP).set(eqp);

        ch.pipeline().addLast("framer", new LineEndingFrameDecoder(SocketTypeProperties.LineEnding.LF));
        ch.pipeline().addLast("handshake", new HandshakeHandler(scenarioRegistry));

        ch.pipeline().fireChannelActive();

        // INITIALIZE 도착
        ch.writeInbound(Unpooled.copiedBuffer("CMD=INITIALIZE\n", StandardCharsets.UTF_8));

        ByteBuf out = ch.readOutbound();
        assertNotNull(out);

        try {
            String s = out.toString(StandardCharsets.UTF_8);
            assertEquals("CMD=INITIALIZE_REP EQPID=TEST001\n", s);
        } finally {
            ReferenceCountUtil.release(out);
        }

        // handshake가 runner로 교체됐는지 확인
        assertNotNull(ch.pipeline().get("runner"));

        ch.finishAndReleaseAll();
    }
}