package com.nori.tc.eqpsim.socket.scenario;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioMdParserTests {

    @Test
    void parses_basic_wait_send_emit_sleep_label_goto_loop_fault() throws Exception {
        Path tmp = Files.createTempFile("scenario", ".md");
        Files.writeString(tmp, """
                # comment
                [Sim] label=MAIN
                [TcToEqp] CMD=PING
                [EqpToTc] CMD=PONG EQPID={eqpid}
                [EqpToTc] every=1s count=2 CMD=EV
                [Sim] sleep=10ms
                [Sim] loop=count=2 goto=MAIN
                [Sim] fault=drop rate=0.1 count=5
                """, StandardCharsets.UTF_8);

        ScenarioPlan plan = ScenarioMdParser.parseFile(tmp.toString());
        assertNotNull(plan);
        assertTrue(plan.getSteps().size() > 0);
        assertTrue(plan.getLabelIndex().containsKey("MAIN"));
    }
}