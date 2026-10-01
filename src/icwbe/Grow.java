package icwbe;

/**
 * 0.4.7: pure geometry for "this paste does not fit - grow the board in whole
 * 16x16 plates".
 *
 * <p>Deliberately free of Minecraft / ProjectRed types. Every rule below is taken
 * from the vanilla constraints, and keeping the planner pure means the whole table
 * of cases can be executed outside the game instead of being checked by launching
 * Minecraft and pasting by hand.
 *
 * <p><b>The vanilla rules this mirrors</b> (ProjRed-4.12.44, disassembled
 * {@code CircuitOp$} / {@code OpGate} / {@code OpIOGate} / {@code NewICNode}):
 *
 * <ul>
 *   <li>{@code CircuitOp$.isOnBorder(size,p)} = {@code x==0 || y==0 || x==w-1 || y==h-1}
 *       - the outermost ring.
 *   <li>{@code CircuitOp$.isOnEdge(size,p)} = the four corners.
 *   <li>{@code OpGate.canPlace} = {@code !isOnBorder} - a gate may NEVER touch the
 *       ring. This is why a paste that lands a gate on the ring has to grow.
 *   <li>{@code OpIOGate.canPlace} = {@code isOnBorder && !isOnEdge} - an IO lives ON
 *       the ring (and never in a corner), which is why growth must walk it outward.
 *   <li>{@code OpWire} / {@code SimplePlacementOp} (wires, torches, levers, buttons)
 *       declare no {@code canPlace} at all - they are legal anywhere, ring included.
 *       They therefore never trigger growth.
 *   <li>{@code NewICNode.maxBoardSize} = {@code Size(4,4)} plates - 64x64, a hard
 *       ceiling that is never exceeded.
 * </ul>
 *
 * <p><b>Direction.</b> Growth only ever adds width to the right and height to the
 * bottom - that is what the existing paste path did and what the vanilla size dialog
 * produces. A part sitting on the LEFT or TOP ring therefore cannot be rescued by
 * growing (the board would have to move the paste instead), so the planner leaves it
 * alone rather than growing for nothing and stranding the part somewhere else.
 *
 * <p><b>The clipboard holds an IO - the caller must not grow at all.</b> An IO is
 * legal only ON the ring, and growing moves the ring, so a paste that was aimed so
 * that its IO lands on the border stops landing on it the moment the board gets
 * bigger. {@code stampAt} therefore suppresses growth entirely when the clip holds
 * an IO (it drops the plan {@link #planGrowth} returns) and pastes as-is - see
 * {@link #containsIO}, which is how it asks. That decision deliberately lives with
 * the caller and not in here: this class only answers "what would be legal", while
 * "the player is placing an interface, so do not move the board under them" is a
 * policy about intent, and keeping it out means the whole geometry table stays a
 * pure function.
 *
 * <p>All coordinate arrays are flat triples: {@code pasted} is {@code (x, y, partId)}
 * per cell the paste will write, {@code edgeIOs} is {@code (x, y, kind)} per IO that
 * growth would strand, where kind 1 is the right column and 2 the bottom row.
 */
public final class Grow {

    /** the grid a board is built from; the vanilla size dialog also steps in plates of 16. */
    public static final int PLATE = 16;

    /** {@code NewICNode.maxBoardSize} = 4 plates. Never exceeded. */
    public static final int MAX = 64;

    /**
     * Part id of the IO gate. {@code CircuitPartDefs$} registers, in order:
     * Torch 0, Lever 1, Button 2, AlloyWire 3, InsulatedWire 4, BundledCable 5,
     * IOGate 6, SimpleGate 7, ComplexGate 8, ArrayGate 9.
     */
    public static final int ID_IO = 6;
    public static final int ID_SIMPLE_GATE = 7;
    public static final int ID_COMPLEX_GATE = 8;
    public static final int ID_ARRAY_GATE = 9;

    /** returned by {@link #planGrowth} when the board cannot be made big enough. */
    public static final int[] IMPOSSIBLE = new int[]{-1, -1};

    private Grow() {
    }

    /**
     * The parts placed through {@code OpGate}, i.e. the ones {@code OpGate.canPlace}
     * keeps off the ring. Wires (3/4/5) and torches/levers/buttons (0/1/2) are placed
     * by ops without a {@code canPlace}, so they may sit on the ring and must not
     * trigger a growth.
     */
    public static boolean isGate(int id) {
        return id == ID_SIMPLE_GATE || id == ID_COMPLEX_GATE || id == ID_ARRAY_GATE;
    }

    /** the part {@code OpIOGate} places: legal only on the ring, never in a corner. */
    public static boolean isIO(int id) {
        return id == ID_IO;
    }

    /**
     * The vanilla ring: {@code CircuitOp$.isOnBorder && !CircuitOp$.isOnEdge} - the
     * outermost row/column, minus the four corners.
     *
     * <p>That is exactly the set {@code OpIOGate.canPlace} accepts and {@code
     * OpGate.canPlace} rejects, i.e. "where an interface is allowed to stand". Cells
     * outside the board are NOT on the ring (they are simply off the board, and
     * {@code stamp} drops them on its own); {@code w}/{@code h} of 1 degenerates to
     * "the single cell is a corner", which is what the vanilla corner test does too.
     */
    public static boolean isOnRing(int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return false;
        }
        if (x != 0 && y != 0 && x != w - 1 && y != h - 1) {
            return false;                                  // interior
        }
        if ((x == 0 || x == w - 1) && (y == 0 || y == h - 1)) {
            return false;                                  // corner
        }
        return true;
    }

    /**
     * The vanilla OUTER BORDER: {@code CircuitOp$.isOnBorder}, i.e. the outermost row
     * or column, CORNERS INCLUDED.
     *
     * <p>Keep this apart from {@link #isOnRing}. They are two different vanilla
     * predicates and mixing them up is exactly what left a hole in 0.4.7:
     * <ul>
     *   <li>{@code OpGate.canPlace = !isOnBorder} - a gate may not touch the border,
     *       corners included. That is this method.</li>
     *   <li>{@code OpIOGate.canPlace = isOnBorder && !isOnEdge} - an IO lives on the
     *       border but never in a corner. That is {@link #isOnRing}.</li>
     * </ul>
     * Using the IO predicate for the gate rule let a gate land in a corner unnoticed,
     * and the growth rule only ever covers the right/bottom sides, so a gate aimed at
     * the left or top border was placed illegally too. See the class note.
     *
     * <p>Out-of-board coordinates are not "on the border" - they are simply off the
     * board and {@code stamp} drops them on its own account.
     */
    public static boolean isOnBorder(int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return false;
        }
        return x == 0 || y == 0 || x == w - 1 || y == h - 1;
    }

    // ------------------------------------------------------------------
    // 0.4.7: "an IO paste must hug the border" - which origins are usable.
    //
    // The clip is given as flat (dx,dy) pairs of its IO cells. An origin (n,m) is
    // usable when EVERY IO lands on the ring AND the whole clip still fits on the
    // board. That is the marquee-hugging-the-edge behaviour, stated exactly.
    //
    // Note what falls out of "the whole clip must fit". For an IO at clip (dx,dy) to
    // reach the right ring, n + dx == w-1, i.e. n == w-1-dx, and fitting requires
    // n <= w-clipW, so dx >= clipW-1 - the IO has to BE on the clip's right edge. The
    // same holds for the other three sides. So an IO that is not on any edge of the
    // clip can never be placed at all, and a clip with IO on two OPPOSITE sides is
    // equally hopeless (it would need the clip to be both w-clipW and 0 wide). Those
    // are the cases the caller refuses; everything else gets a real (often 1-D) set of
    // origins to snap to. See hasInteriorIO for the first of them.
    // ------------------------------------------------------------------

    public static final int SIDE_TOP = 1;
    public static final int SIDE_RIGHT = 2;
    public static final int SIDE_BOTTOM = 4;
    public static final int SIDE_LEFT = 8;

    /**
     * Which edges of the CLIP carry an IO, as a {@link #SIDE_TOP}.. bitmask. Used for
     * the message shown when a paste is refused - the placement rule itself only cares
     * whether {@link #ioPasteOrigins} came back empty.
     */
    public static int ioSides(int clipW, int clipH, int[] ioOffsets) {
        int mask = 0;
        for (int i = 0; i + 1 < ioOffsets.length; i += 2) {
            int dx = ioOffsets[i];
            int dy = ioOffsets[i + 1];
            if (dy == 0) {
                mask |= SIDE_TOP;
            }
            if (dy == clipH - 1) {
                mask |= SIDE_BOTTOM;
            }
            if (dx == 0) {
                mask |= SIDE_LEFT;
            }
            if (dx == clipW - 1) {
                mask |= SIDE_RIGHT;
            }
        }
        return mask;
    }

    /**
     * True when some IO sits on no edge of the clip at all.
     *
     * <p>Such a clip can never be pasted anywhere: getting that IO onto the ring needs
     * the origin to be negative by more than the clip is wide (see the note above), so
     * the paste would have to hang off the board. Kept separate from "no origins" only
     * so the refusal can say which of the two situations it is.
     */
    public static boolean hasInteriorIO(int clipW, int clipH, int[] ioOffsets) {
        for (int i = 0; i + 1 < ioOffsets.length; i += 2) {
            int dx = ioOffsets[i];
            int dy = ioOffsets[i + 1];
            if (dx != 0 && dx != clipW - 1 && dy != 0 && dy != clipH - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every origin that puts all of the clip's IOs on the ring while keeping the whole
     * clip on the board - flat (x,y) pairs, empty when there is no such origin.
     */
    public static int[] ioPasteOrigins(int boardW, int boardH, int clipW, int clipH, int[] ioOffsets) {
        int nMax = boardW - clipW + 1;
        int mMax = boardH - clipH + 1;
        if (ioOffsets.length == 0 || nMax <= 0 || mMax <= 0) {
            return new int[0];
        }
        int[] buf = new int[2 * nMax * mMax];
        int k = 0;
        for (int n = 0; n < nMax; ++n) {
            for (int m = 0; m < mMax; ++m) {
                if (allIOsOnRing(boardW, boardH, n, m, ioOffsets)) {
                    buf[k++] = n;
                    buf[k++] = m;
                }
            }
        }
        int[] out = new int[k];
        System.arraycopy(buf, 0, out, 0, k);
        return out;
    }

    private static boolean allIOsOnRing(int w, int h, int n, int m, int[] ioOffsets) {
        for (int i = 0; i + 1 < ioOffsets.length; i += 2) {
            if (!isOnRing(w, h, n + ioOffsets[i], m + ioOffsets[i + 1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The allowed origin closest to where the pointer is, or {@code null} when there is
     * none. This is what makes the marquee visibly snap onto the border instead of
     * silently refusing the drop.
     */
    public static int[] nearestOrigin(int[] origins, int x, int y) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i + 1 < origins.length; i += 2) {
            long dx = origins[i] - x;
            long dy = origins[i + 1] - y;
            long d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best < 0 ? null : new int[]{origins[best], origins[best + 1]};
    }

    /**
     * True when the paste writes an IO anywhere. The caller uses this to suppress
     * growth: the clip's IO is almost always aimed at the border, and growth would
     * move the border out from under it.
     */
    public static boolean containsIO(int[] pasted) {
        for (int i = 0; i + 2 < pasted.length; i += 3) {
            if (isIO(pasted[i + 2])) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many of the paste's cells fall outside a board of this size.
     *
     * <p>{@code Clipboard.stamp} writes through {@code setPart}, whose {@code
     * assertCoords} throws for a coordinate outside the board - and stamp swallows
     * that per cell, so those parts are dropped without a word and the "pasted N"
     * status silently comes out short of the clip's size. This counts them so the
     * caller can say so, which matters most in the one case where the player did not
     * ask for growth and got no growth: a clip holding an IO.
     */
    public static int countOffBoard(int[] pasted, int w, int h) {
        int out = 0;
        for (int i = 0; i + 2 < pasted.length; i += 3) {
            int x = pasted[i];
            int y = pasted[i + 1];
            if (x < 0 || y < 0 || x >= w || y >= h) {
                ++out;
            }
        }
        return out;
    }

    /** {@code required} rounded up to the next plate, or -1 when that exceeds {@link #MAX}. */
    public static int plateUp(int required) {
        int r = (required + PLATE - 1) / PLATE * PLATE;
        return r > MAX ? -1 : r;
    }

    /**
     * The size to grow to, or {@code current} when nothing is needed, or -1 when
     * {@code required} leaves the board bigger than {@link #MAX}.
     */
    public static int growTo(int current, int required) {
        if (required <= current) {
            return current;
        }
        return plateUp(required);
    }

    /**
     * Plans the board size a paste needs in order to be legal.
     *
     * @return {@code null} when no growth is needed (the paste already fits, and no
     *         gate lands on the ring); {@link #IMPOSSIBLE} when growth is needed but
     *         the result would pass 64x64; otherwise {@code {newWidth, newHeight}},
     *         always a multiple of 16 (or the unchanged current size on an axis that
     *         does not grow).
     */
    public static int[] planGrowth(int w, int h, int[] pasted, int[] edgeIOs) {
        int reqW = w;
        int reqH = h;
        for (int i = 0; i + 2 < pasted.length; i += 3) {
            int x = pasted[i];
            int y = pasted[i + 1];
            if (x < 0 || y < 0) {
                // stamp() drops cells with a negative coordinate, so they cannot
                // make the board illegal and must not influence the size.
                continue;
            }
            // A gate may not sit on the ring, so its column may not end up being the
            // last one: the board needs one cell of slack beyond it. Growing cannot
            // help a gate pasted at x/y == 0 (that is the left/top ring no matter how
            // big the board gets) - x+2 there is <= the current size, so it simply
            // never asks for a growth.
            int needX = isGate(pasted[i + 2]) ? x + 2 : x + 1;
            int needY = isGate(pasted[i + 2]) ? y + 2 : y + 1;
            if (needX > reqW) {
                reqW = needX;
            }
            if (needY > reqH) {
                reqH = needY;
            }
        }
        int newW = growTo(w, reqW);
        int newH = growTo(h, reqH);
        if (newW < 0 || newH < 0) {
            return IMPOSSIBLE;
        }
        if (newW == w && newH == h) {
            return null;
        }
        // A stranded IO is walked out to the new ring. It must not land on a cell the
        // paste is about to write, or the paste would silently delete the IC's
        // external interface. That can only happen when the pasted block ends exactly
        // on the new ring, and one more plate on that side always clears it - so this
        // loop runs at most a couple of times.
        for (int guard = 0; guard < 4; ++guard) {
            boolean bumpW = newW > w && hitsIO(pasted, edgeIOs, 1, newW - 1, newH - 1);
            boolean bumpH = newH > h && hitsIO(pasted, edgeIOs, 2, newW - 1, newH - 1);
            if (!bumpW && !bumpH) {
                break;
            }
            if (bumpW) {
                newW = growTo(newW, newW + PLATE);
                if (newW < 0) {
                    return IMPOSSIBLE;
                }
            }
            if (bumpH) {
                newH = growTo(newH, newH + PLATE);
                if (newH < 0) {
                    return IMPOSSIBLE;
                }
            }
        }
        return new int[]{newW, newH};
    }

    /**
     * True when the paste writes the cell some stranded IO is moving to.
     *
     * @param kind    1 = IO coming off the right column (destination {@code (edgeX, y)}),
     *                2 = IO coming off the bottom row (destination {@code (x, edgeY)})
     * @param edgeX   the new board's right column (kind 1 only)
     * @param edgeY   the new board's bottom row (kind 2 only)
     */
    private static boolean hitsIO(int[] pasted, int[] edgeIOs, int kind, int edgeX, int edgeY) {
        for (int i = 0; i + 2 < edgeIOs.length; i += 3) {
            if (edgeIOs[i + 2] != kind) {
                continue;
            }
            int tx = kind == 1 ? edgeX : edgeIOs[i];
            int ty = kind == 1 ? edgeIOs[i + 1] : edgeY;
            for (int j = 0; j + 2 < pasted.length; j += 3) {
                if (pasted[j] == tx && pasted[j + 1] == ty) {
                    return true;
                }
            }
        }
        return false;
    }
}
