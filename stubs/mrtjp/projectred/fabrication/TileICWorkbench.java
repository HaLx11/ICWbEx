package mrtjp.projectred.fabrication;

import codechicken.lib.data.MCDataOutput;

public class TileICWorkbench {
    public IntegratedCircuit circuit() {
        return null;
    }

    // --- added for 0.2.7 (Replay/Sync); descriptors from real PR 4.12.44 jar javap ---
    public MCDataOutput getICStreamOf(int key) {
        return null;
    }

    public void flushICStream() {
    }

    public void sendNewICToServer(IntegratedCircuit ic) {
    }

    public boolean hasBP() {
        return false;
    }

    // --- added for 0.2.8 (Sync resets these after a failed push); signatures from
    // CFR-decompiled TileICWorkbench (PR 4.12.44): public TraitSetters of the
    // NetWorldCircuit trait's icStream/partStream fields. Setting them to null
    // discards a half-written client stream, so a corrupt buffer can never be
    // flushed to the server (TileICWorkbench.update()/updateClient() flush these
    // buffers every tick; getICStreamOf() writes its key byte the moment it is
    // called, i.e. before any payload write can fail).
    public void mrtjp$projectred$fabrication$NetWorldCircuit$$icStream_$eq(codechicken.lib.packet.PacketCustom x) {
    }

    public void mrtjp$projectred$fabrication$NetWorldCircuit$$partStream_$eq(codechicken.lib.packet.PacketCustom x) {
    }
}
