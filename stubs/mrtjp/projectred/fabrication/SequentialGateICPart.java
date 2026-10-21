package mrtjp.projectred.fabrication;

/**
 * Compile-time stand-in for ProjectRed's
 * {@code mrtjp.projectred.fabrication.SequentialGateICPart} (Scala class).
 *
 * <p>0.4.9: the real hierarchy is
 * {@code SequentialGateICPart extends RedstoneGateICPart implements TComplexGateICPart},
 * and {@code RedstoneGateICPart extends GateICPart extends CircuitPart}. The
 * devpack has no stub for the intermediate classes and does not need one: only
 * {@code instanceof} and {@code logic()} are used, and {@code instanceof} is
 * answered by the runtime type, so extending {@link CircuitPart} straight away
 * keeps the compile happy without changing behaviour.
 *
 * <p>Real members (javap on {@code ProjRed-4.12.44-GTNH.jar}):
 * {@code public SequentialICGateLogic logic();}
 * {@code public void logic_$eq(SequentialICGateLogic);}
 * {@code public TComplexICGateLogic getLogicComplex();}
 * {@code public void assertLogic();}
 */
public class SequentialGateICPart extends CircuitPart {
    public SequentialICGateLogic logic() {
        return null;
    }
}
