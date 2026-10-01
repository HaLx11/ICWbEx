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
import net.minecraft.nbt.NBTTagList;

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

    // ---- 0.3.0 two-phase staging (see stageEdit) ----
    private static volatile TileICWorkbench pendTile;
    private static volatile IntegratedCircuit pendIc;
    private static volatile NBTTagCompound pendTarget;  // the edited board (T)
    private static volatile NBTTagCompound pendBase;    // diff base for phase 2 (O, or an empty board after a resize)
    private static volatile long pendAt;

    // ---- 0.3.5: the park window in front of phase 1b ----
    //
    // 1 = phase 1b still owes the removals (or the blank desc), 2 = phase 2 owes the
    // apply. The removals are no longer sent straight from stageEdit: a part that can
    // stream a key>10 state frame is first parked as an inert wire there, and the
    // removal has to wait for the frames already in flight (see Replay.sendDegrade).
    private static volatile int pendStage;
    private static volatile List<int[]> pendRemovals;
    private static volatile boolean pendResized;
    private static volatile String pendName;
    private static volatile int pendW;
    private static volatile int pendH;

    /**
     * 0.3.1: a whole-board switch (blueprint load / share-code load) that arrived
     * while the previous staged edit was still converging. It is staged properly
     * once that edit has settled - see stageEdit.
     */
    private static volatile NBTTagCompound pendNext;

    /** Re-entrancy guard: pump() may legitimately be reached from a render path. */
    private static volatile boolean pumping;

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

    // ---- 0.3.3 settle watch + diagnostics -----------------------------------
    //
    // A whole-circuit desc that WE send is echoed straight back by ProjectRed
    // (TileICWorkbench.read case 5 -> sendICDesc), and the client applies that
    // echo with IntegratedCircuit.readDesc - i.e. it REPLACES the local board
    // with the contents of the desc. That is fine for a desc that carries the
    // board, and harmless for the blank desc phase 1 sends on a resize (phase 2
    // re-applies the target right after) - but only as long as the echo cannot
    // arrive AFTER phase 2. The 07:52 field log shows it can: the client copy was
    // found wiped (hundreds of "client part stream couldnt find part" while the
    // server held the board) and the workbench was left with a handful of parts.
    // So remember the board the client is supposed to hold for a few seconds after
    // we commit, and if the local copy turns up EMPTY while it should not be,
    // re-apply it locally. No packets are involved - the server already has it.
    private static volatile IntegratedCircuit watchIc;
    private static volatile NBTTagCompound watchBoard;
    private static volatile long watchUntil;
    /**
     * 0.4.2: whether the commit that armed the watch had sent a whole-board desc.
     * ONLY those can wipe the local copy (ProjectRed echoes every desc straight back
     * and the client applies the echo with readDesc), so the watch is now armed for
     * them alone - and it only repairs a board that came back EMPTY. Arming it for
     * every commit made it fight the player: deleting a few parts dropped the local
     * count below the committed board's, the watch "restored" them from that board and
     * the next diff reported the deletion as an addition
     * (2026-10-01 12:33 field log: after erasing bundled cables the erased parts kept
     * coming back, occupying their cells so nothing new could be placed there).
     */
    private static volatile boolean watchDesc;

    private static void armWatch(IntegratedCircuit ic, NBTTagCompound board, boolean descSent) {
        watchIc = ic;
        watchBoard = board;
        watchDesc = descSent;
        watchUntil = System.currentTimeMillis() + 5000L;
    }

    private static void disarmWatch() {
        watchIc = null;
        watchBoard = null;
        watchDesc = false;
        watchUntil = 0L;
    }

    /**
     * Post-commit guard (0.3.3; narrowed twice since).
     *
     * <p>ProjectRed echoes every whole-board desc straight back and the client applies
     * that echo with {@code IntegratedCircuit.readDesc}, which CLEARS the local board
     * first. If such an echo lands after our own rebuild, the local copy comes back
     * empty while the server holds the real board - and a client missing parts the
     * server keeps ticking is exactly the state that ends in PR's subID-0 fallback and
     * an "Invalid gate subID: 0" disconnect. So the expected board is remembered for a
     * moment after a commit and re-applied locally (no packets - the server already has
     * it) if our copy turns up EMPTY.
     *
     * <h3>0.4.2 - why it may only look at desc-sending commits, and only at EMPTY boards</h3>
     * Until 0.4.1 the check fired whenever the local part count was below the committed
     * board's, which made it fight the player: deleting a few parts is a perfectly
     * normal edit, but the watch read the smaller board as "parts were stolen by a late
     * echo", re-created them from the OLD committed board and - because the parts came
     * back - the next diff saw the deletion as an addition. Field report 2026-10-01
     * 12:33: after erasing bundled cables the erased parts reappeared, refused to
     * connect to the rest and their cells could not be used for new parts.
     *
     * <p>Two narrowings fix that for good:
     * <ol>
     *   <li>the watch is armed only when the commit actually sent a whole-board desc
     *       ({@code watchDesc}) - nothing else can replace the local board wholesale,
     *       and ICWbEx sends a desc only for a size change;</li>
     *   <li>it repairs only a board that came back with <b>nothing</b> on it
     *       ({@code have == 0}) - the signature of a desc echo. Any partial difference
     *       is the player's own editing and must never be touched.</li>
     * </ol>
     */
    private static void checkSettle() {
        IntegratedCircuit ic = watchIc;
        NBTTagCompound want = watchBoard;
        if (ic == null || want == null) {
            return;
        }
        if (System.currentTimeMillis() > watchUntil) {
            disarmWatch();
            return;
        }
        if (!watchDesc) {
            disarmWatch();   // nothing in flight can empty the board wholesale
            return;
        }
        if (busy() || echoHold()) {
            return;   // an edit is still in flight - never fight it
        }
        int need = countPartsInTag(want);
        int have = countParts(ic);
        if (need <= 0 || have != 0) {
            return;   // a non-empty board is the player's own editing, never ours to fix
        }
        int fixed = Replay.repairLocal(ic, want);
        if (fixed > 0) {
            System.out.println("[ICWbEx] local board was emptied by a late desc echo ("
                + have + " -> " + need + " parts); restored it from the committed board");
            try {
                ic.refreshErrors();
            }
            catch (Throwable ignored) {
            }
            armWatch(ic, want, true);   // keep watching, a second echo could follow
        }
    }

    /** Part count of a live circuit, or -1 when it could not be read. */
    static int countParts(IntegratedCircuit ic) {
        if (ic == null) {
            return -1;
        }
        try {
            int n = 0;
            // NB: the signature stub of scala.collection.Iterator is non-generic
            // (see stubs/) - use the raw type, exactly like the checks in Blueprints.
            scala.collection.Iterator it = ic.parts().values().iterator();
            while (it.hasNext()) {
                it.next();
                ++n;
            }
            return n;
        }
        catch (Throwable t) {
            return -1;
        }
    }

    /** Part count of a saved snapshot. */
    private static int countPartsInTag(NBTTagCompound tag) {
        try {
            NBTTagList list = tag.func_150295_c("parts", 10);
            return list == null ? 0 : list.func_74745_c();
        }
        catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 0.3.4: true when {@code incoming} is just the board phase 1 rolled the client
     * back to (same size and same part count), i.e. a caller that re-sent the
     * pre-edit board instead of the board the player asked for. Share.loadNamed does
     * exactly that: right after Blueprints.load (which applies the blueprint and
     * calls Sync.toServer) it only sets the board's name from the file name and
     * calls Sync.toServer a second time - by then the live board IS the rolled-back
     * one. See the note in stageEdit.
     */
    private static boolean isRollbackEcho(NBTTagCompound incoming, NBTTagCompound rolledBackTo) {
        if (incoming == null || rolledBackTo == null) {
            return false;
        }
        try {
            return (incoming.func_74771_c("sw") & 0xFF) == (rolledBackTo.func_74771_c("sw") & 0xFF)
                && (incoming.func_74771_c("sh") & 0xFF) == (rolledBackTo.func_74771_c("sh") & 0xFF)
                && countPartsInTag(incoming) == countPartsInTag(rolledBackTo);
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * Copies the incoming board's name onto the board we are about to apply.
     *
     * <p><b>0.4.0:</b> the name belongs to the <b>newest</b> request, so when a newer
     * switch has already been parked in {@code pendNext} that is the board to rename -
     * not the one still in flight. Folding onto {@code pendTarget} regardless produced
     * boards that carried one blueprint's NAME and another one's CONTENT, which is
     * both confusing and dangerous (the save funnel would then file that content under
     * the wrong name). Field case 2026-10-01 12:08 (rapid row switching):
     * <pre>
     *   stageEdit: before "测试2" 167p -> target "BEC蜂群选择器" 371p   (staged, client rolled back)
     *   board switch queued ... (target "测试1" 32x32/371p)           (newer request parked)
     *   call 2 while pending = name-only echo; target stays "测试2" 16x16/174p
     *   phase2 done: local "测试2" 16x16/174p                        <- 174 parts, named 测试2
     * </pre>
     * The echo's name came from the newer load ({@code loadNamed} renames the live board
     * after {@code Blueprints.load} has already been parked), so it must land on that
     * newer board - where it is already correct - and never on the older target.
     */
    private static void foldName(IntegratedCircuit ic, NBTTagCompound incoming) {
        try {
            String name = incoming.func_74779_i("name");
            if (name == null || name.length() == 0) {
                return;
            }
            NBTTagCompound newest = pendNext != null ? pendNext : pendTarget;
            if (newest != null) {
                newest.func_74778_a("name", name);
            }
            // 0.4.1: NEVER rename the live board here. While a sync is staged the live copy
            // IS the board we rolled back to, and it has to keep its own identity: renaming
            // it produced a board with one blueprint's name and another one's content, and
            // the very next call snapshotted that hybrid (0.4.0 field log:
            //   stageEdit: before "测试1" -> target "测试2" ...
            //   board switch queued behind the running sync (target "测试1" 32x32/371p)
            // where 32x32/371p is a different blueprint's content under 测试1's name).
            // The name that matters is the one on the board we are going to APPLY: `newest`
            // above. It reaches the live board anyway when the pump applies it, and
            // Blueprints.save already redirects to that board via Sync.boardBeingSynced.
        }
        catch (Throwable t) {
            // a cosmetic field - never let it break the sync
        }
    }

    /** "name WxH/Np" for a live board - for the log only. */
    static String sigOf(IntegratedCircuit ic) {
        if (ic == null) {
            return "null";
        }
        try {
            return "\"" + ic.name() + "\" " + ic.size().width() + "x" + ic.size().height()
                + "/" + countParts(ic) + "p";
        }
        catch (Throwable t) {
            return "?";
        }
    }

    /** "name WxH/Np" for a snapshot board - for the log only. */
    static String sigOf(NBTTagCompound tag) {
        if (tag == null) {
            return "null";
        }
        try {
            return "\"" + tag.func_74779_i("name") + "\" " + (tag.func_74771_c("sw") & 0xFF)
                + "x" + (tag.func_74771_c("sh") & 0xFF) + "/" + countPartsInTag(tag) + "p";
        }
        catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 0.3.0: true while a staged edit is still walking through its two phases.
     * The GUI refuses further edits (and freezes undo bookkeeping) in this window.
     * 0.3.1: also true while a queued whole-board switch is waiting for its turn.
     */
    public static boolean busy() {
        return pendTarget != null || pendNext != null;
    }

    // ---- 0.4.5 keeping the diff base honest ---------------------------------
    //
    // Every vanilla tool the mod does not take over writes through the SERVER: the op
    // is only put on the wire (IntegratedCircuit.sendOpUse), the local board is updated
    // when the echo comes back. So the board can change underneath lastSnap/lastTile -
    // our own commit paths never ran - and a base that still describes the board from
    // BEFORE such an op turns "place a part, then erase it" into "nothing changed": the
    // removal would never leave the client while the server kept the part, and the
    // server's next state frame for it would go through PR's subID-0 fallback.
    //
    // The guard below costs one part count per frame and re-snapshots only when the
    // count really moved. It deliberately does nothing while anything of ours is in
    // flight (busy / echo hold / the settle watch), because in those windows the local
    // board is INTENTIONALLY not what the server holds (see stageEdit's rollback and
    // checkSettle's repair) - following it there would poison the base instead.
    private static volatile IntegratedCircuit followIc;
    private static volatile TileICWorkbench followTile;
    private static volatile int followCount;

    private static boolean watchArmed() {
        long until = watchUntil;
        return until != 0L && System.currentTimeMillis() <= until;
    }

    public static void followLocalBoard(TileICWorkbench tile, IntegratedCircuit ic) {
        if (ic == null || busy() || echoHold() || watchArmed()) {
            return;
        }
        if (ic != followIc) {
            followIc = ic;
            followTile = tile;
            followCount = countParts(ic);
            lastTile = tile;
            lastSnap = Replay.snapPure(ic);
            return;
        }
        int n = countParts(ic);
        if (n >= 0 && n != followCount) {
            followCount = n;
            lastTile = tile;
            lastSnap = Replay.snapPure(ic);
        }
    }

    /**
     * 0.3.8: the board this pending sync will eventually put on the client, or null
     * when nothing is in flight.
     *
     * <p>While a sync is staged the live circuit is deliberately rolled back to the
     * board that came before the edit (see stageEdit): that is what keeps every state
     * frame the server is still emitting for a part we are about to remove landing on
     * a cell that still holds that part. The cost is that anything reading the live
     * board during the window reads the PREVIOUS board.
     *
     * <p>That cost was paid in user data on 2026-10-01: the blueprint panel's save
     * funnel (GuiBlueprint.doSave -> Share.saveNamed -> Blueprints.save, and the same
     * funnel from a share-code import) serialised the live board. Saving while a
     * switch or an import was converging therefore wrote the OLD board into the file
     * of the name the player had selected - blueprints\BEC蜂群选择器.icbp came out
     * byte-identical to blueprints\BEC蜂群信号转换.icbp while carrying its own name
     * inside, which is precisely the signature of that write (a plain file copy would
     * have kept the other board's name tag).
     *
     * <p>So Blueprints.save asks here first and serialises THIS board instead of the
     * rolled-back one. A queued whole-board switch (pendNext) beats the board being
     * staged, because it is the newer intent of the player.
     */
    public static NBTTagCompound boardBeingSynced(IntegratedCircuit ic) {
        if (ic == null || ic != pendIc) {
            return null;
        }
        if (pendNext != null) {
            return pendNext;
        }
        return pendTarget;
    }

    /**
     * Two-phase commit pump. Finishes a staged edit once the removal echoes have
     * had time to converge both sides on the intermediate board: applies the
     * edited board locally and replays only the creations.
     *
     * <h3>0.3.1 - why this must not be driven by GuiICWbEx alone</h3>
     * It used to be called from GuiICWbEx.frameUpdate_Impl only. But the blueprint
     * panel (icwbe.GuiBlueprint) is a GuiScreen of its own: while it is in front,
     * GuiICWbEx stops updating and the pump stops with it. A blueprint load goes
     * through Sync.toServer like any other edit, so it staged phase 1 - the client
     * was rolled back, the server was left with the removals applied (the server's
     * echo of an emptied or trimmed board) - and phase 2 never came. Field result
     * (2026-10-01, DIM180): switching blueprints inside the panel removed the gate
     * parts the plan diffed as "changed" (live gates carry state that the snapshot
     * does not, while wires compare equal because connMap is ignored) and never
     * put them back, so the workbench ended up holding only cable - and the board
     * the player was looking at never visibly changed.
     *
     * The pump is now also driven from Lang.t(), which every ICWbEx screen calls
     * from its draw path (GuiBlueprint calls it five times per frame), so the
     * second phase runs no matter which of our screens is in front. See Lang.t.
     */
    public static void pump() {
        checkSettle();
        if (pendTarget == null || pumping || System.currentTimeMillis() < pendAt) {
            return;
        }
        pumping = true;
        try {
            if (pendStage == 1) {
                // 0.3.5: phase 1b - the removals (or the blank desc). stageEdit already
                // parked every part that could stream a frame past the removal, so this
                // runs one park window later on purpose.
                runPhase1();
                return;
            }
            runPhase2();
        }
        finally {
            pumping = false;
        }
    }

    /** Phase 1b of a staged edit - see Replay.sendDegrade and runPhase2. */
    private static void runPhase1() {
        final TileICWorkbench tile = pendTile;
        final boolean resized = pendResized;
        final List<int[]> removals = pendRemovals;
        pendRemovals = null;
        pendStage = 2;
        try {
            if (resized) {
                // A blank desc is the only channel for the board size; it also empties
                // the server. hasBP was checked by the GUI before the edit
                // (st.no_bp_grow), so the server will honour it.
                IntegratedCircuit blank = new IntegratedCircuit();
                blank.name_$eq(pendName);
                blank.size_$eq(new Size(pendW, pendH));
                System.out.println("[ICWbEx] phase1b: blank desc \"" + pendName + "\" " + pendW + "x" + pendH);
                tile.sendNewICToServer(blank);
                markEchoRebase();
                NBTTagCompound emptyBase = new NBTTagCompound();
                blank.save(emptyBase);
                pendBase = emptyBase;
            }
            else {
                System.out.println("[ICWbEx] phase1b: " + (removals == null ? 0 : removals.size()) + " removals");
                Replay.sendRemovals(tile, removals);
            }
        }
        catch (Throwable t) {
            System.err.println("[ICWbEx] staged removals failed:");
            t.printStackTrace();
            abortStaged(tile);
            return;
        }
        pendAt = System.currentTimeMillis() + 400L;
    }

    /** Phase 2: apply the edited board locally and replay only the creations. */
    private static void runPhase2() {
        final TileICWorkbench tile = pendTile;
        final IntegratedCircuit ic = pendIc;
        final NBTTagCompound target = pendTarget;
        final NBTTagCompound base = pendBase;
        // 0.4.2: a size change is the only case where phase 1 sent a whole-board desc,
        // and therefore the only case where an echo can wipe the local board. The watch
        // is armed for exactly that.
        final boolean descSent = pendResized;
        pendTile = null;
        pendIc = null;
        pendTarget = null;
        pendBase = null;
        pendStage = 0;
        pendResized = false;
        try {
            System.out.println("[ICWbEx] phase2: apply " + sigOf(target) + " (base " + sigOf(base) + ")");
            ic.load(target);
            ic.refreshErrors();
            // Phase 2: everything the intermediate board still lacks. Phase 1 has
            // already emptied every cell whose part must go or change, so NO removals
            // are sent here (0.3.6): a removal the server applies is answered with a
            // partRemoved echo that trims OUR copy, while the matching creation is a
            // case-1 op the server never echoes - the client would lose that part for
            // good and the server's next state frame for it would go through PR's
            // subID-0 fallback. See Replay.push's emitRemoves parameter.
            Replay.push(tile, ic, base, false, false);
            System.out.println("[ICWbEx] phase2 done: local " + sigOf(ic));
            lastTile = tile;
            lastSnap = target;
            // Short freeze: the undo commit that records the finished edit must
            // NOT run while late echoes could still touch the board.
            echoHoldUntil = System.currentTimeMillis() + 500L;
            armWatch(ic, target, descSent);
        }
        catch (Throwable t) {
            System.err.println("[ICWbEx] staged apply failed:");
            t.printStackTrace();
            abortStaged(tile);
        }
        // 0.3.1: a whole-board switch that arrived mid-flight is staged NOW, while
        // client and server are back in step (both are `target`), so the diff base
        // is trustworthy again. Without this the queued switch would have been
        // diffed against a base the server no longer held and would have silently
        // skipped every shared part.
        if (pendNext != null && pendTarget == null) {
            final NBTTagCompound next = pendNext;
            pendNext = null;
            try {
                ic.load(next);
                ic.refreshErrors();
                stageEdit(tile, ic);
            }
            catch (Throwable t) {
                System.err.println("[ICWbEx] queued board switch failed:");
                t.printStackTrace();
            }
        }
    }

    /** Drops a staged edit and neutralises the tile's stream buffers (0.2.8 hardening). */
    private static void abortStaged(TileICWorkbench tile) {
        pendTile = null;
        pendIc = null;
        pendTarget = null;
        pendBase = null;
        pendRemovals = null;
        pendStage = 0;
        if (tile == null) {
            return;
        }
        try {
            tile.mrtjp$projectred$fabrication$NetWorldCircuit$$icStream_$eq(null);
            tile.mrtjp$projectred$fabrication$NetWorldCircuit$$partStream_$eq(null);
        }
        catch (Throwable ignored) {
        }
        lastTile = tile;
        lastSnap = null;
        markEchoRebase();
    }

    /**
     * 0.3.0 two-phase edit staging.
     *
     * Why: the client applies edits optimistically (the board the GUI shows is the
     * board the player wants), but the op stream reaches the integrated server a
     * tick or more later. In that window the server keeps ticking the OLD board and
     * streams per-part state frames for parts the client has already removed or
     * replaced. PR's readPartStream fallback then builds those parts with subID 0,
     * and the first key>10 frame of a sequential gate throws
     * "Invalid gate subID: 0" - a hard disconnect (field case 2026-10-01: one paste
     * of a 130-part swarm board with running sequential gates killed the client).
     *
     * Pure additions cannot hit that window (the server never streams frames for
     * parts it does not have), so they stay optimistic. Any edit that REMOVES or
     * REPLACES a part (or resizes the board) is staged:
     *   phase 1: roll the client copy back to the pre-edit board (O), then send
     *            only the removals (or a blank desc when the board must grow - the
     *            size change can only travel through a whole desc). The server's
     *            own partRemoved echoes then walk the client down to the same
     *            intermediate board; during all of this every state frame that
     *            arrives finds its part still present on the client.
     *   phase 2 (pump, ~400ms later): apply the edited board (T) and replay the
     *            creations against O. The server never streams frames for parts
     *            it does not have yet, so this direction is safe too.
     */
    private static void stageEdit(TileICWorkbench tile, IntegratedCircuit ic) {
        disarmWatch();   // a new edit owns the board now; never fight it
        NBTTagCompound before = tile == lastTile ? lastSnap : null;

        if (pendTarget != null) {
            // 0.3.1: a staged edit is still converging and the server has ALREADY
            // applied its phase 1 - it holds the trimmed intermediate board (or an
            // empty one, when phase 1 sent a blank desc). Diffing the new board
            // against `before` now would skip every part the two boards share, and
            // those parts are precisely the ones the server just lost: that is how
            // switching blueprints inside the blueprint panel ate whole rows of
            // gates. Park the new board, roll the client back so it stays a
            // superset of the server (no in-flight state frame can hit a missing
            // part), and stage it properly in pump() once both sides are in step.
            NBTTagCompound incoming = Replay.snapPure(ic);
            if (before == null) {
                // 0.4.1: with no rollback base we cannot tell the name-only echo of a
                // blueprint load (call 2, which carries the board we just rolled back to)
                // from a genuine switch. Queueing it would re-apply that rolled-back board
                // and silently undo the load (the 0.3.3 failure mode), so drop it: the
                // player's next click is staged normally. This is only reachable when a
                // stage was aborted or the tile was re-opened, i.e. on failure paths.
                foldName(ic, incoming);
                System.out.println("[ICWbEx] call while pending with no diff base = dropped as echo; target stays "
                    + sigOf(pendTarget));
                return;
            }
            if (isRollbackEcho(incoming, before)) {
                // 0.3.4: the load path calls us TWICE. GuiBlueprint.doLoad ->
                // Share.loadNamed -> Blueprints.load (which applies the blueprint
                // and calls Sync.toServer - call 1) and then loadNamed renames the
                // board from the file name and calls Sync.toServer again (call 2).
                // By call 2 phase 1 has already rolled the live board back to the
                // pre-edit board, so call 2 carries the OLD board, not the one the
                // player asked for. Queueing it (0.3.1-0.3.3 did) re-applied the
                // old board right after the load had landed: every blueprint load
                // silently reverted, and consecutive switches left the workbench
                // holding a handful of stray parts. It is a name-only echo - fold
                // the name into the pending target and drop the call.
                foldName(ic, incoming);
                System.out.println("[ICWbEx] call 2 while pending = name-only echo; target stays "
                    + sigOf(pendTarget));
                return;
            }
            pendNext = incoming;
            try {
                ic.load(before);
                ic.refreshErrors();
            }
            catch (Throwable ignored) {
            }
            // 0.4.1: name BOTH boards. The queued board is the newest request and the base
            // is what we rolled the client back to; printing only the live copy (as 0.3.1
            // did) hid the case where the queued board was not the board the player asked
            // for (see foldName).
            System.out.println("[ICWbEx] board switch queued behind the running sync (queued "
                + sigOf(incoming) + " over base " + sigOf(before) + ")");
            return;
        }
        if (before == null) {
            // No reliable diff base (tile switch / first edit): fall back to the
            // 0.2.9 behaviour and let push() pick a whole-desc or ops-only route.
            System.out.println("[ICWbEx] stageEdit: no diff base -> push(); target " + sigOf(ic));
            push(tile, ic);
            return;
        }
        NBTTagCompound target = Replay.snapPure(ic);
        List<int[]> removals = Replay.planRemovals(before, ic);
        boolean resized = Replay.sizeDiffers(before, ic);
        System.out.println("[ICWbEx] stageEdit: before " + sigOf(before) + " -> target "
            + sigOf(ic) + " removals=" + removals.size() + " resized=" + resized);
        if (removals.isEmpty() && !resized) {
            // Pure addition / no change to existing cells: optimistic is safe.
            try {
                ic.load(target);
                ic.refreshErrors();
            }
            catch (Throwable ignored) {
            }
            push(tile, ic);
            return;
        }
        // Dangerous edit: roll the client back so every in-flight state frame
        // still finds its part, then converge through the intermediate board.
        // Capture the TARGET's name/size BEFORE the rollback: once ic.load(before)
        // has run, `ic` *is* the pre-edit board again. Phase 1's blank desc exists
        // precisely to hand the server the board's new size - 0.3.1 sent the
        // rolled-back size instead, so the server got emptied at the OLD size and
        // phase 2 had to send a second blank desc; that second desc is echoed back
        // to us one round trip later, i.e. after phase 2 had already applied the
        // target, and the echo replaced the client copy with the blank board while
        // the server held the real one (the "couldnt find part" flood + the
        // Invalid gate subID disconnect in the 07:32 field log).
        String targetName = ic.name();
        int tW = ic.size().width();
        int tH = ic.size().height();
        try {
            ic.load(before);
            ic.refreshErrors();
        }
        catch (Throwable t) {
            t.printStackTrace();
            return;   // better an unsynced edit than a poisoned stream
        }
        // 0.3.5: park every part that could still stream a key>10 state frame out of a
        // cell we are about to drop or replace. Without this the removal makes the
        // client copy disappear exactly while the server's last frames for that very
        // part are in flight, and PR's readPartStream fallback turns them into a gate
        // with subID 0 -> "Invalid gate subID: 0" -> disconnect. See Replay.sendDegrade.
        List<int[]> danger = Replay.dangerousCells(before, resized ? Replay.allCells(before) : removals);
        if (!danger.isEmpty()) {
            try {
                Replay.sendDegrade(tile, danger);
                System.out.println("[ICWbEx] phase1a: parked " + danger.size()
                    + " stateful part(s) as inert wires");
            }
            catch (Throwable t) {
                System.err.println("[ICWbEx] parking failed; removals go out immediately:");
                t.printStackTrace();
                danger = new ArrayList<int[]>();
            }
        }
        pendTile = tile;
        pendIc = ic;
        pendTarget = target;
        pendBase = resized ? null : before;
        pendRemovals = removals;
        pendResized = resized;
        pendName = targetName;
        pendW = tW;
        pendH = tH;
        pendStage = 1;
        // One park window before phase 1b: the frames the parked parts queued for that
        // tick have to land on our still-intact copy first.
        pendAt = System.currentTimeMillis() + (danger.isEmpty() ? 0L : 150L);
        lastTile = tile;
        lastSnap = before;
    }
    public static void toServer(TileICWorkbench tileICWorkbench, IntegratedCircuit integratedCircuit) {
        if (tileICWorkbench == null || integratedCircuit == null) {
            return;
        }
        try {
            stageEdit(tileICWorkbench, integratedCircuit);
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
            abortStaged(tileICWorkbench);
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
        boolean needSize = before == null || Replay.sizeDiffers(before, ic);
        boolean descFirst = needSize && tile.hasBP();

        System.out.println("[ICWbEx] push: before " + sigOf(before) + " board " + sigOf(ic)
            + " sizeFix=" + needSize + " hasBP=" + tile.hasBP());

        // 0.3.3 - ops only, and only a size change may send a desc.
        //
        // A whole-circuit desc is the ONE and ONLY channel for the board size, and
        // ProjectRed echoes every desc it accepts straight back (TileICWorkbench.read
        // case 5 -> sendICDesc -> the client's read case 2 -> IntegratedCircuit.
        // readDesc), which REPLACES the local board with the desc contents. Tiers 2
        // and 3 used to send a desc for the NAME as well (that was their only other
        // job), so every blueprint switch - every name change - cost a board-
        // replacing round trip; whenever that echo landed after our own second phase
        // the client copy came back empty while the server held the real board, the
        // server's per-part frames then found nothing ("client part stream couldnt
        // find part", 1000+ lines in the 07:52 log) and the fallback built a running
        // sequential gate with subID 0 -> "Invalid gate subID: 0" disconnect.
        //
        // The name is cosmetic. A desc is now sent only when the server board's size
        // would otherwise be wrong, because ops at coordinates outside it trip
        // assertCoords and abort the whole IC stream. Everything else - including
        // bundled cables, which travel as ordinary OpWire placements - is rebuilt by
        // ops, and nothing comes back to overwrite the local board.
        if (descFirst) {
            Replay.push(tile, ic, before, true);
            // 0.4.2: this path DOES send a whole-board desc, so an echo really can wipe
            // the local copy - arm the watch (it only repairs an EMPTY board).
            armWatch(ic, Replay.snapPure(ic), true);
        }
        else {
            Replay.push(tile, ic, before, false);
        }
        lastSnap = UndoBuffer.snap(ic);
        lastTile = tile;
    }
}
