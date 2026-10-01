package icwbe;

/**
 * 0.4.7: where the status line goes and how much of it fits.
 *
 * <p>The status used to be drawn in a 62px column in the right-hand strip, wrapped to
 * at most 7 lines, and every notice a paste raised was appended to it unconditionally.
 * One paste can raise three notices, which measures about 566px in Chinese and 924px
 * in English; at 62px that is 10 to 28 lines against a 7-line cap, so the renderer cut
 * the message off mid-sentence and said nothing about it. That is the truncation that
 * was reported.
 *
 * <p>Two changes fix it, and both live here because both are decisions about numbers:
 * the status moved to the header strip (about 3.7x the width), and the notices are
 * admitted only while the message still fits the strip. Like {@link Grow} and
 * {@link HelpLayout} this class holds no Minecraft types, so the whole rule can be
 * exercised offline (devpack {@code tools/verify_helplayout.py}) - a guarantee like
 * "the status is never silently cut" is worth being able to test.
 *
 * <p>The caller measures with the real font and passes the numbers in, which is why
 * every method here takes widths rather than text.
 */
public final class StatusLine {

    /** the vanilla draws its machine title at x=8. */
    public static final int TITLE_X = 8;
    /** gap between the vanilla title and the status. */
    public static final int TITLE_GAP = 8;
    /** right-hand margin of the header strip. */
    public static final int SIDE_MARGIN = 6;
    /** never let the strip collapse to nothing, whatever the title measures. */
    public static final int MIN_WIDTH = 24;
    /**
     * An untranslated title key comes back as the key itself, which is far longer than
     * any real title. Clamping the start to a third of the width means that can never
     * squeeze the status out entirely.
     */
    public static final int MAX_LEFT_FRACTION = 3;
    /**
     * The header strip is the area above the board viewport, which the vanilla puts at
     * y=18, so two 9px lines fit.
     */
    public static final int LINES = 2;

    private StatusLine() {
    }

    /** x where the status may start: after the vanilla title, clamped. */
    public static int left(int titleWidth, int screenWidth) {
        int x = TITLE_X + titleWidth + TITLE_GAP;
        int clamp = Math.max(TITLE_X, screenWidth / MAX_LEFT_FRACTION);
        return Math.min(x, clamp);
    }

    /** horizontal room the status has. */
    public static int width(int titleWidth, int screenWidth) {
        return Math.max(MIN_WIDTH, screenWidth - SIDE_MARGIN - left(titleWidth, screenWidth));
    }

    /** total text width the strip can show: its width, times the lines it may use. */
    public static int budget(int titleWidth, int screenWidth) {
        return width(titleWidth, screenWidth) * LINES;
    }

    /**
     * Decides which of {@code extra} can be appended to the message.
     *
     * <p>Walked in the order given (the caller lists them most important first) and each
     * one is admitted only if the message still fits {@code budget} afterwards. A notice
     * is taken whole or not at all - never half of one - which is the point: the failure
     * being fixed is a message that was cut mid-sentence.
     *
     * <p>A notice that does not fit is skipped and the walk continues, so a long
     * high-priority notice does not automatically discard the shorter ones behind it.
     *
     * @param baseWidth  width of the primary message, which is never dropped
     * @param extraWidths width of each candidate notice
     * @param spaceWidth width of one separating space
     * @param budget     what {@link #budget} returned
     * @param maxExtras  hard cap on how many notices may be added
     * @return a flag per entry of {@code extraWidths}: true means append it
     */
    public static boolean[] fitFlags(int baseWidth, int[] extraWidths, int spaceWidth,
                                     int budget, int maxExtras) {
        boolean[] out = new boolean[extraWidths.length];
        int running = baseWidth;
        int added = 0;
        for (int i = 0; i < extraWidths.length && added < maxExtras; ++i) {
            int candidate = running + spaceWidth + extraWidths[i];
            if (candidate > budget) {
                continue;
            }
            out[i] = true;
            running = candidate;
            ++added;
        }
        return out;
    }
}
