package mrtjp.projectred.fabrication;

import codechicken.lib.data.MCDataOutput;
import mrtjp.core.vec.Point;

public interface CircuitOp {
    int id();

    String getOpName();

    // --- added for 0.2.7 (Replay); real interface method, descriptor verified via javap ---
    void writeOp(IntegratedCircuit ic, Point start, Point end, MCDataOutput out);
}
