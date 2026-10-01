package icwbe;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.4.7: the shape of the controls page (the H key).
 *
 * <p>The page used to be one flat text run: one column, section names buried in the
 * body, no gap between them, and a line height that had to shrink below the font's own
 * height (9px) because 30-odd lines did not fit. It was unreadable, and it wasted more
 * than half the panel width on lines that are only ~20 characters long.
 *
 * <p>The fix is structural, and the structure lives here: the body is split into
 * sections, and the sections are dealt into columns. Like {@link Grow}, this class is
 * deliberately free of Minecraft types so the whole thing can be exercised outside the
 * game (devpack {@code tools/verify_helplayout.py}) - layout bugs are otherwise only
 * visible by opening the GUI and squinting at it.
 *
 * <p><b>Vertical rhythm is expressed in lines, not pixels.</b> A block is
 * {@code titleLines + entryLines}; columns are separated by whole lines. That way the
 * gap between blocks always scales with the line height the page ends up using, and
 * only one number has to be chosen.
 *
 * <p>Section arrays are {@code {title, entry, entry, ...}} with the title's
 * 【】 / [] markers stripped, so the caller can draw the hierarchy itself (colour,
 * indent, rule) instead of relying on brackets typed into the translation.
 */
public final class HelpLayout {

    /**
     * The smallest line height that is still readable. The Minecraft font is 9px tall;
     * below 8 the glyphs start to touch, which is exactly the "cramped" the page was
     * reported for. See {@link #lineHeightFor}.
     */
    public static final int MIN_LINE_H = 8;

    /** No point going taller than this - the page would just look sparse. */
    public static final int MAX_LINE_H = 11;

    /**
     * A page that already reaches this height does not get any more columns. 9 is one
     * full font line: columns are added only when the text would otherwise be tighter
     * than the font itself, never just to fill width.
     */
    public static final int PREFERRED_LINE_H = 9;

    /** a line opening with one of these is a section title: zh 【】, en [] */
    private static final String TITLE_OPEN = "\u3010[";
    private static final String TITLE_CLOSE = "\u3011]";

    private HelpLayout() {
    }

    /**
     * Splits a help body into sections, in order.
     *
     * <p>A blank line is ignored, a line opening with 【 or [ starts a section, and
     * everything else is an entry of the current section. Entries are trimmed: the
     * leading spaces in the translation were the old way of showing indentation, and
     * the renderer applies its own indent now.
     *
     * <p>A body with no markers at all becomes a single section with an empty title,
     * so a translation that dropped them still shows every line.
     */
    public static List<String[]> parse(String body) {
        List<String[]> out = new ArrayList<String[]>();
        if (body == null || body.length() == 0) {
            return out;
        }
        String[] lines = body.split("\n", -1);
        List<String> entries = new ArrayList<String>();
        String title = null;
        boolean open = false;
        for (int i = 0; i < lines.length; ++i) {
            String text = lines[i].trim();
            if (text.length() == 0) {
                continue;
            }
            if (isTitle(text)) {
                // Flush whatever is pending first. `open` guards the previous section;
                // `!entries.isEmpty()` guards leading text that came BEFORE the first
                // heading - without that second test those lines were reset away and
                // silently lost.
                if (open || !entries.isEmpty()) {
                    out.add(pack(open ? title : "", entries));
                }
                title = strip(text);
                entries = new ArrayList<String>();
                open = true;
            }
            else {
                entries.add(text);
            }
        }
        if (open) {
            out.add(pack(title, entries));
        }
        else if (!entries.isEmpty()) {
            out.add(pack("", entries));
        }
        return out;
    }

    /** true when the line is a section heading rather than an entry. */
    public static boolean isTitle(String text) {
        return text != null && text.length() > 0 && TITLE_OPEN.indexOf(text.charAt(0)) >= 0;
    }

    /** drops the 【】 / [] wrapper, leaving the bare heading. */
    public static String strip(String text) {
        if (text == null || text.length() < 2) {
            return text == null ? "" : text;
        }
        if (TITLE_OPEN.indexOf(text.charAt(0)) >= 0
                && TITLE_CLOSE.indexOf(text.charAt(text.length() - 1)) >= 0) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String[] pack(String title, List<String> entries) {
        String[] row = new String[entries.size() + 1];
        row[0] = title;
        for (int i = 0; i < entries.size(); ++i) {
            row[i + 1] = entries.get(i);
        }
        return row;
    }

    /**
     * Deals the blocks into {@code cols} columns, keeping their order.
     *
     * <p>Greedy, with an even share as the target: a column keeps taking blocks while
     * that brings it closer to {@code total / cols}, and always takes at least one.
     * A block is never split - sections stay whole, which is the point of having them.
     *
     * @param blockLines each block's height, in lines (title + entries)
     * @param gapLines   blank lines between two blocks of the same column
     * @return how many blocks go into each column; its length is the usable column count
     */
    public static int[] distribute(int[] blockLines, int gapLines, int cols) {
        int n = blockLines.length;
        if (n == 0) {
            return new int[Math.max(1, cols)];
        }
        if (cols > n) {
            cols = n;
        }
        if (cols < 1) {
            cols = 1;
        }
        int[] out = new int[cols];
        if (cols == 1) {
            out[0] = n;
            return out;
        }
        int total = gapLines * (n - 1);
        for (int i = 0; i < n; ++i) {
            total += blockLines[i];
        }
        int share = total / cols;
        int idx = 0;
        for (int c = 0; c < cols; ++c) {
            if (c == cols - 1) {
                out[c] = n - idx;
                break;
            }
            int take = 0;
            int height = 0;
            while (idx + take < n) {
                int add = blockLines[idx + take] + (take > 0 ? gapLines : 0);
                if (take > 0 && Math.abs(height + add - share) > Math.abs(height - share)) {
                    break;
                }
                height += add;
                ++take;
            }
            if (take == 0) {
                take = 1;
            }
            out[c] = take;
            idx += take;
        }
        return out;
    }

    /** height of one column, in lines. */
    public static int columnLines(int[] blockLines, int gapLines, int[] counts, int col) {
        int start = 0;
        for (int c = 0; c < col && c < counts.length; ++c) {
            start += counts[c];
        }
        int count = col < counts.length ? counts[col] : 0;
        int end = Math.min(blockLines.length, start + count);
        int height = 0;
        for (int i = start; i < end; ++i) {
            if (i > start) {
                height += gapLines;
            }
            height += blockLines[i];
        }
        return height;
    }

    /** height of the tallest column, in lines - what the line height has to fit into. */
    public static int maxColumnLines(int[] blockLines, int gapLines, int[] counts) {
        int max = 0;
        for (int c = 0; c < counts.length; ++c) {
            int height = columnLines(blockLines, gapLines, counts, c);
            if (height > max) {
                max = height;
            }
        }
        return max;
    }

    /**
     * The line height that lets {@code maxLines} fit into {@code availPx}, clamped to
     * the readable range.
     *
     * @return {@code -1} when even {@code min} does not fit, i.e. this arrangement is
     *         not usable and the caller should try a different column count
     */
    public static int lineHeightFor(int availPx, int maxLines, int min, int max) {
        if (maxLines <= 0) {
            return max;
        }
        int height = availPx / maxLines;
        if (height > max) {
            height = max;
        }
        return height < min ? -1 : height;
    }

    // ------------------------------------------------------------------
    // 0.4.7 (second pass): the page can still be taller than the window.
    //
    // The first pass picked the column count and line height to fit, and fell back to
    // "one column, minimum line height" when nothing fit. That fallback still drew every
    // line, so on a small GUI the text simply ran past the bottom of the panel and off
    // the screen. Paging the content is the honest answer: the layout keeps its readable
    // line height and the player scrolls. The arithmetic lives here so the boundary
    // cases (nothing to scroll, scrolled past the end, exactly one page) are testable.
    // ------------------------------------------------------------------

    /** how many lines the content area can show at this line height. */
    public static int visibleLines(int contentPx, int lineHeight) {
        return lineHeight <= 0 ? 0 : Math.max(1, contentPx / lineHeight);
    }

    /** the largest useful scroll offset: 0 when the whole page already fits. */
    public static int scrollMax(int contentLines, int visibleLines) {
        return Math.max(0, contentLines - Math.max(1, visibleLines));
    }

    /** keeps a scroll offset inside [0, scrollMax]. */
    public static int clampScroll(int scroll, int contentLines, int visibleLines) {
        int max = scrollMax(contentLines, visibleLines);
        if (scroll < 0) {
            return 0;
        }
        return scroll > max ? max : scroll;
    }

    /** true when the page needs scrolling at all (drives the footer hint). */
    public static boolean scrollable(int contentLines, int visibleLines) {
        return scrollMax(contentLines, visibleLines) > 0;
    }
}
