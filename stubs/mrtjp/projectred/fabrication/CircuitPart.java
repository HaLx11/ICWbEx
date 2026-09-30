package mrtjp.projectred.fabrication;

import codechicken.lib.data.MCDataOutput;
import net.minecraft.nbt.NBTTagCompound;

public class CircuitPart {
    // --- added for 0.2.7 (Replay/Sync/Clipboard); descriptors from real PR 4.12.44 jar javap ---
    public int id() {
        return 0;
    }

    public int x() {
        return 0;
    }

    public int y() {
        return 0;
    }

    public void save(NBTTagCompound tag) {
    }

    public void load(NBTTagCompound tag) {
    }

    public void writeDesc(MCDataOutput out) {
    }

    public static CircuitPart createPart(int id) {
        return null;
    }
}
