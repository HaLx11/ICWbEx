package mrtjp.projectred.fabrication;

/**
 * Compile-time stand-in for ProjectRed's {@code mrtjp.projectred.fabrication.TTimerGateLogic}
 * (a Scala trait, compiled to an interface).
 *
 * <p>0.4.9: the only thing ICWbEx needs from it is the timer pointer, so that a
 * board can be serialised for the server with the timer parked. Real signature
 * from {@code javap mrtjp.projectred.fabrication.TTimerGateLogic} on
 * {@code ProjRed-4.12.44-GTNH.jar}:
 * {@code public abstract long pointer_start();}
 * {@code public abstract void pointer_start_$eq(long);}
 */
public interface TTimerGateLogic {
    long pointer_start();

    void pointer_start_$eq(long start);
}
