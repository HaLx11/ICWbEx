/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  mrtjp.projectred.fabrication.IntegratedCircuit
 *  mrtjp.projectred.fabrication.TileICWorkbench
 *  net.minecraft.nbt.CompressedStreamTools
 *  net.minecraft.nbt.NBTTagCompound
 */
package icwbe;

import icwbe.Blueprints;
import icwbe.Sync;
import java.util.ArrayDeque;
import java.util.Deque;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.TileICWorkbench;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

public final class UndoBuffer {
    private static final int LIMIT = 64;
    private final Deque<NBTTagCompound> past = new ArrayDeque<NBTTagCompound>();
    private final Deque<NBTTagCompound> future = new ArrayDeque<NBTTagCompound>();
    private NBTTagCompound current;
    private String currentSig;

    public static NBTTagCompound snap(IntegratedCircuit integratedCircuit) {
        Blueprints.ensureCaches(integratedCircuit);
        NBTTagCompound nBTTagCompound = new NBTTagCompound();
        integratedCircuit.save(nBTTagCompound);
        // Keep Sync's diff base in step with what the player is looking at: this runs
        // on GUI open (reset) and after every committed edit.
        Sync.recordLast(integratedCircuit, nBTTagCompound);
        return nBTTagCompound;
    }

    private static String sigOf(NBTTagCompound nBTTagCompound) {
        byte[] byArray = CompressedStreamTools.func_74798_a((NBTTagCompound)nBTTagCompound);
        if (byArray == null) {
            return "none";
        }
        long l = -3750763034362895579L;
        for (int i = 0; i < byArray.length; ++i) {
            l ^= (long)(byArray[i] & 0xFF);
            l *= 1099511628211L;
        }
        return Long.toHexString(l) + ":" + byArray.length;
    }

    public void reset(IntegratedCircuit integratedCircuit) {
        this.past.clear();
        this.future.clear();
        this.current = UndoBuffer.snap(integratedCircuit);
        this.currentSig = UndoBuffer.sigOf(this.current);
    }

    public boolean commitIfChanged(IntegratedCircuit integratedCircuit) {
        NBTTagCompound nBTTagCompound = UndoBuffer.snap(integratedCircuit);
        String string = UndoBuffer.sigOf(nBTTagCompound);
        if (string.equals(this.currentSig)) {
            return false;
        }
        if (this.current != null) {
            this.past.push(this.current);
            while (this.past.size() > 64) {
                this.past.removeLast();
            }
            this.future.clear();
        }
        this.current = nBTTagCompound;
        this.currentSig = string;
        return true;
    }

    public boolean canUndo() {
        return !this.past.isEmpty();
    }

    public boolean canRedo() {
        return !this.future.isEmpty();
    }

    public boolean undo(IntegratedCircuit integratedCircuit, TileICWorkbench tileICWorkbench) {
        if (this.past.isEmpty()) {
            return false;
        }
        NBTTagCompound nBTTagCompound = this.past.pop();
        if (this.current != null) {
            this.future.push(this.current);
        }
        UndoBuffer.apply(integratedCircuit, tileICWorkbench, nBTTagCompound);
        this.current = nBTTagCompound;
        this.currentSig = UndoBuffer.sigOf(nBTTagCompound);
        return true;
    }

    public boolean redo(IntegratedCircuit integratedCircuit, TileICWorkbench tileICWorkbench) {
        if (this.future.isEmpty()) {
            return false;
        }
        NBTTagCompound nBTTagCompound = this.future.pop();
        if (this.current != null) {
            this.past.push(this.current);
        }
        UndoBuffer.apply(integratedCircuit, tileICWorkbench, nBTTagCompound);
        this.current = nBTTagCompound;
        this.currentSig = UndoBuffer.sigOf(nBTTagCompound);
        return true;
    }

    public static void apply(IntegratedCircuit integratedCircuit, TileICWorkbench tileICWorkbench, NBTTagCompound nBTTagCompound) {
        integratedCircuit.load(nBTTagCompound);
        integratedCircuit.refreshErrors();
        if (tileICWorkbench != null) {
            Sync.toServer(tileICWorkbench, integratedCircuit);
        }
    }
}

