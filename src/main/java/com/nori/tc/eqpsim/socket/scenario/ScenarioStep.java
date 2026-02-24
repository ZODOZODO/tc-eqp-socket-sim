package com.nori.tc.eqpsim.socket.scenario;

/**
 * ScenarioStep marker interface.
 */
public sealed interface ScenarioStep permits
        WaitCmdStep,
        SendStep,
        EmitStep,
        SleepStep,
        LabelStep,
        GotoStep,
        LoopStep,
        FaultStep {
}