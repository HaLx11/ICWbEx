/*
 * ICWbEx 0.4.9: keep a placed IC from crashing the client.
 *
 * <h3>The crash</h3>
 * Placing an IC gate runs (client-side, in the right-click prediction)
 * <pre>
 *   PlayerControllerMP.onPlayerRightClick
 *     -> ItemPartGate.onItemUse -> TItemMultiPart.place$1
 *     -> CircuitGatePart.preparePlacement
 *     -> CircuitGateLogic.constructICLogic -> ItemICBlueprint.loadIC
 *     -> IntegratedCircuit.load -> ... -> Timer.load
 *     -> TTimerGateLogic.getTotalTime -> MultipartSaveLoad.loadingWorld().getWorldTime()
 * </pre>
 * {@code loadingWorld()} is only set while a world is being read from disk, so it
 * is null here and the placement dies with an NPE.
 *
 * <h3>Why the item, and not the board</h3>
 * {@code Timer.load} takes the {@code pelapsed} key and, when it is >= 0, converts
 * the stored*elapsed* back into an absolute pointer via {@code getTotalTime()}.
 * {@code getLong} returns 0 for a missing key, so an untouched timer is just as
 * dangerous as a running one. The number lives in the chip item's {@code icdata},
 * written by the server when the board is popped out of the workbench - a path
 * ProjectRed owns and ICWbEx never touches.
 *
 * <h3>Why a client-side tick guard is enough</h3>
 * The same chip placed on the server does NOT crash (the server's board has no
 * pending network world at that instant), so the chip on the wire is fine; only
 * the client's copy of the item has to be made harmless before the click is
 * processed. Rewriting {@code pelapsed} to -1 on the client's held stack makes
 * {@code Timer.load} skip the {@code getTotalTime()} branch entirely, and it also
 * does what the user asked for: a chip that is placed starts its timers from 0.
 *
 * <p>The rewrite is in-place on the stack's own tag object, so it costs one
 * comparison per tick once the timer is already parked.
 */
package icwbe;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class HeldChipGuard {

    /** NBT tag type of TAG_Compound, the type of the {@code icdata} payload. */
    private static final int TAG_COMPOUND = 10;

    private static boolean installed;

    /** Log rate limit: at most one "held chip" line per window. */
    private static final long LOG_INTERVAL_MS = 5000L;
    private static long lastLogAt;
    private static int lastStopped;

    private HeldChipGuard() {
    }

    /**
     * Hooks the guard onto the FML client tick bus. Called once from
     * {@link ICWbEx#onLoadComplete}. Never throws: a guard that breaks startup
     * would be worse than the crash it prevents.
     */
    public static void install() {
        if (installed) {
            return;
        }
        try {
            cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new HeldChipGuard());
            installed = true;
            System.out.println("[ICWbEx] held-chip guard installed");
        }
        catch (Throwable t) {
            System.out.println("[ICWbEx] held-chip guard NOT installed: " + t);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        try {
            rewrite();
        }
        catch (Throwable ignored) {
            // This runs on the client thread every tick; one bad stack must never
            // take the game down. The next tick will try again.
        }
    }

    private static void rewrite() {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null || mc.field_71439_g == null) {
            return;
        }
        ItemStack held = mc.field_71439_g.func_70694_bm();
        if (held == null) {
            return;
        }
        NBTTagCompound itemTag = held.func_77978_p();
        if (itemTag == null || !itemTag.func_150297_b("icdata", TAG_COMPOUND)) {
            return;
        }
        NBTTagCompound icTag = itemTag.func_74775_l("icdata");
        if (icTag == null) {
            return;
        }
        int n = Blueprints.stopTimers(icTag);
        if (n > 0) {
            // The server keeps its own copy of the chip and re-syncs it into our
            // inventory every so often, which puts `pelapsed` back. We therefore
            // rewrite on every tick and simply win the race -- but logging it
            // every time turned into 41 lines in one session, so the message is
            // rate limited. The rewrite itself is never throttled.
            lastStopped += n;
            long now = System.currentTimeMillis();
            if (now - lastLogAt < LOG_INTERVAL_MS) {
                return;
            }
            lastLogAt = now;
            System.out.println("[ICWbEx] held chip: stopped " + lastStopped
                + " running timer(s) so placing it cannot crash the client");
            lastStopped = 0;
        }
    }
}
