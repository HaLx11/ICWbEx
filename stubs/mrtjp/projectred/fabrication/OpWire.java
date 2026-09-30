package mrtjp.projectred.fabrication;

/**
 * Abstract base of the wire-placement ops. 0.2.7 stub; createPart() descriptor
 * ()Lmrtjp/projectred/fabrication/CircuitPart; verified via javap on PR 4.12.44.
 */
public abstract class OpWire implements CircuitOp {
    public abstract CircuitPart createPart();
}
