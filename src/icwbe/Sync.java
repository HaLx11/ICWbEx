package icwbe;

import java.util.ArrayList;
import java.util.List;
import mrtjp.core.vec.Size;
import mrtjp.projectred.fabrication.BundledCableICPart;
import mrtjp.projectred.fabrication.CircuitPart;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.TileICWorkbench;
import mrtjp.projectred.fabrication.WorldCircuit;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Server sync entry point (0.2.7 rewrite).
 *
 * 0.2.1 sent every edit as ONE whole-circuit desc packet
 * (TileICWorkbench.sendNewICToServer -> IntegratedCircuit.writeDesc). That path
 * transports bundled-cable signals as a packed bitmask: BundledCommons.packDigital
 * maps the legal idle signal (an all-zero byte[16]) to 0, and the receiver's
 * unpackDigital(0) returns NULL - so any idle bundled cable that crosses a desc
 * poisons the receiver (server tick NPE inside osig[i], NPE inside
 * BundledCableICPart.save, and the server echo re-poisons every watcher).
 *
 * 0.2.7 therefore syncs in three tiers:
 *  1. content-only delta  -> per-part replay (see Replay). Unchanged bundled cables
 *     are never re-sent and nothing on this path can produce a null signal.
 *  2. size/name/reset needed, NO bundled cable present -> the whole-circuit desc is
 *     provably harmless (0.2.1 behaviour) and its echo restores the client copy.
 *  3. size/name/reset needed WITH bundled cables present and the workbench holds a
 *     blueprint item (hasBP): the cables are lifted off the local circuit, a
 *     CABLE-FREE whole desc is sent (server read + echo can not be poisoned), the
 *     cables are restored locally and re-created server-side through wire ops whose
 *     server-side onAdded() recomputes their signal; the placements stream back as
 *     ordinary partAdded packets. The GUI freezes its undo bookkeeping until the
 *     echo has settled (Sync.echoHold / consumeRebase).
 *     Without hasBP the server discards whole descs entirely, so tier 3 degrades to
 *     a full ops-only rebuild (vanilla-consistent best effort).
 */
public final class Sync {

    /** Snapshot of the circuit as it was after the previous sync (== player's view). */
    private static volatile TileICWorkbench lastTile;
    private static volatile NBTTagCompound lastSnap;

    /** Undo bookkeeping is frozen until this millis timestamp while an echo settles. */
    private static volatile long echoHoldUntil;
    private static volatile boolean rebaseNeeded;

    private Sync() {
    }

    /**
     * Remembers the circuit state the player is currently looking at. Called from
     * UndoBuffer.snap(), which runs when the GUI opens (reset) and after every
     * committed edit, so the next edit always has an exact diff base.
     */
    public static void recordLast(IntegratedCircuit ic, NBTTagCompound snap) {
        try {
            if (ic == null || snap == null) {
                return;
            }
            WorldCircuit net = ic.network();
            if (net == null || !net.isRemote() || !(net instanceof TileICWorkbench)) {
                return;   // client side only
            }
            lastTile = (TileICWorkbench)net;
            lastSnap = snap;
        }
        catch (Throwable ignored) {
        }
    }

    /** true while an echo-triggered rebuild of the client board may still be pending. */
    public static boolean echoHold() {
        return System.currentTimeMillis() < echoHoldUntil;
    }

    /** Consumes the one-shot "re-baseline undo now that the echo settled" request. */
    public static boolean consumeRebase() {
        if (!rebaseNeeded || echoHold()) {
            return false;
        }
        rebaseNeeded = false;
        return true;
    }

    private static void markEchoRebase() {
        echoHoldUntil = System.currentTimeMillis() + 3000L;
        rebaseNeeded = true;
    }

    public static void toServer(TileICWorkbench tileICWorkbench, IntegratedCircuit integratedCircuit) {
        if (tileICWorkbench == null || integratedCircuit == null) {
            return;
        }
        try {
            push(tileICWorkbench, integratedCircuit);
        }
        catch (Throwable t) {
            // Never let this escape into the GUI event handler. Fall back to the plain
            // desc when it is provably safe (no bundled cable in the circuit).
            //
            // 0.2.8 hardening: 0.2.7 only printed t.toString(), which made the field
            // failure impossible to diagnose from the logs. It also left the tile's
            // lazy stream buffers poisoned on failure: getICStreamOf() writes its key
            // byte the moment it is called - i.e. BEFORE any payload write - and the
            // vanilla tile flushes those buffers every tick (TileICWorkbench.update /
            // updateClient), so a push that died halfway handed the server a stream
            // whose first bytes were a lone command key. The server then parsed the
            // 0xFF terminator as an op id -> CircuitOpDefs.apply(255) -> null ->
            // NPE kick. Resetting both buffers here (the trait setters are public)
            // discards any half-written stream; the next push rebuilds everything
            // because lastSnap is forced to null below.
            System.err.println("[ICWbEx] circuit sync failed:");
            t.printStackTrace();
            try {
                tileICWorkbench.mrtjp$projectred$fabrication$NetWorldCircuit$$icStream_$eq(null);
                tileICWorkbench.mrtjp$projectred$fabrication$NetWorldCircuit$$partStream_$eq(null);
            }
            catch (Throwable ignored) {
            }
            try {
                if (!Replay.hasBundledCable(integratedCircuit)) {
                    tileICWorkbench.sendNewICToServer(integratedCircuit);
                }
            }
            catch (Throwable ignored) {
            }
            lastTile = tileICWorkbench;
            lastSnap = null;   // force a full rebuild next time
            markEchoRebase();
        }
    }

    private static void push(TileICWorkbench tile, IntegratedCircuit ic) {
        Blueprints.ensureCaches(ic);

        NBTTagCompound before = tile == lastTile ? lastSnap : null;
        boolean bundled = Replay.hasBundledCable(ic);
        boolean needReset = before == null
            || Replay.sizeDiffers(before, ic)
            || !Replay.sameName(before, ic);

        if (!needReset) {
            // Tier 1: exact content delta against the last synced state.
            Replay.push(tile, ic, before, false);
            lastSnap = UndoBuffer.snap(ic);
            lastTile = tile;
            return;
        }

        if (!bundled) {
            // Tier 2: the desc is provably harmless and is the only channel for name/size.
            tile.sendNewICToServer(ic);
            lastSnap = UndoBuffer.snap(ic);
            lastTile = tile;
            return;
        }

        if (tile.hasBP()) {
            // Tier 3: cable-free whole desc + wire-op replay for the cables.
            List<Object[]> cables = collectCables(ic);
            int n = cables.size();
            for (int i = 0; i < n; ++i) {
                ic.removePart(((Integer)cables.get(i)[0]).intValue(), ((Integer)cables.get(i)[1]).intValue());
            }
            // Nothing but gates and plain wires remains: the desc - and the server's
            // echo of it - cannot carry a bundled cable, so nothing can be poisoned.
            tile.sendNewICToServer(ic);
            for (int i = 0; i < n; ++i) {
                ic.setPart(((Integer)cables.get(i)[0]).intValue(),
                    ((Integer)cables.get(i)[1]).intValue(), (CircuitPart)cables.get(i)[2]);
            }
            // The server re-creates every cable through its own op: onAdded() recomputes
            // the signal (never null) and streams partAdded packets back to watchers.
            Replay.pushWireParts(tile, ic, cables);
            // The incoming echo wipes the local board (then the partAdded packets
            // rebuild the cables): freeze undo until it has settled.
            lastSnap = null;
            lastTile = tile;
            markEchoRebase();
        }
        else {
            // hasBP == false: the server throws whole descs away; rebuild by ops only.
            Replay.push(tile, ic, null, false);
            lastSnap = UndoBuffer.snap(ic);
            lastTile = tile;
        }
    }

    private static List<Object[]> collectCables(IntegratedCircuit ic) {
        List<Object[]> out = new ArrayList<Object[]>();
        Size size = ic.size();
        int w = size.width();
        int h = size.height();
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                CircuitPart part = ic.getPart(x, y);
                if (part instanceof BundledCableICPart) {
                    out.add(new Object[]{Integer.valueOf(x), Integer.valueOf(y), part});
                }
            }
        }
        return out;
    }
}
