/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  cpw.mods.fml.relauncher.Side
 *  cpw.mods.fml.relauncher.SideOnly
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.StatCollector
 */
package icwbe;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

@SideOnly(value=Side.CLIENT)
public final class Lang {
    private static final Map<String, String> ZH_T = new HashMap<String, String>();
    private static final Map<String, String> EN_T = new HashMap<String, String>();
    private static Boolean cachedChinese;

    private Lang() {
    }

    private static void put(String string, String string2, String string3) {
        ZH_T.put(string, string2);
        EN_T.put(string, string3);
    }

    public static void refresh() {
        cachedChinese = null;
    }

    public static boolean uiChinese() {
        if (cachedChinese == null) {
            cachedChinese = Lang.detectChinese();
        }
        return cachedChinese;
    }

    private static boolean detectChinese() {
        String string = null;
        try {
            String string2 = StatCollector.func_74838_a((String)"language.code");
            if (string2 != null && !string2.equals("language.code") && string2.indexOf(95) > 0) {
                string = string2;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (string == null) {
            string = Lang.optionsLang();
        }
        if (string == null) {
            return true;
        }
        return string.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static String optionsLang() {
        try {
            Minecraft minecraft = Minecraft.func_71410_x();
            if (minecraft == null) {
                return null;
            }
            File file = new File(minecraft.field_71412_D, "options.txt");
            if (!file.isFile()) {
                return null;
            }
            BufferedReader bufferedReader = null;
            try {
                bufferedReader = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(file), "UTF-8"));
                String string;
                while ((string = bufferedReader.readLine()) != null) {
                    if (!string.startsWith("lang:")) continue;
                    String string2 = string.substring(5).trim();
                    return string2.length() == 0 ? null : string2;
                }
                return null;
            }
            finally {
                if (bufferedReader != null) {
                    try {
                        bufferedReader.close();
                    }
                    catch (Throwable throwable) {}
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return null;
    }

    private static String gameText(String string) {
        String string2 = "icwbe." + string;
        try {
            String string3;
            if (StatCollector.func_94522_b((String)string2) && (string3 = StatCollector.func_74838_a((String)string2)) != null && string3.length() > 0 && !string3.equals(string2)) {
                return string3;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return null;
    }

    public static String t(String string) {
        String string2 = Lang.gameText(string);
        if (string2 != null) {
            return string2;
        }
        Map<String, String> map = Lang.uiChinese() ? ZH_T : EN_T;
        String string3 = map.get(string);
        if (string3 != null) {
            return string3;
        }
        Map<String, String> map2 = map == ZH_T ? EN_T : ZH_T;
        String string4 = map2.get(string);
        return string4 != null ? string4 : string;
    }

    public static String t(String string, Object ... objectArray) {
        String string2 = Lang.t(string);
        for (int i = 0; i < objectArray.length; ++i) {
            string2 = string2.replace("%" + (i + 1) + "$s", String.valueOf(objectArray[i]));
        }
        return string2;
    }

    static {
        Lang.put("st.paste_ready", "\u7c98\u8d34\u5c31\u7eea\uff1a\u79fb\u5230\u76ee\u6807\u4f4d\u7f6e\u540e\u5de6\u952e\u653e\u4e0b\uff0c\u53f3\u952e\u6216 Esc \u53d6\u6d88", "paste ready: move and left-click to place, right-click or Esc to cancel");
        Lang.put("st.paste_cancel", "\u5df2\u53d6\u6d88\u7c98\u8d34", "paste cancelled");
        Lang.put("st.no_sel", "\u8fd8\u6ca1\u6709\u9009\u4e2d\u4efb\u4f55\u5143\u4ef6", "nothing selected yet");
        Lang.put("st.copied", "\u5df2\u590d\u5236 %1$s \u4e2a\u5143\u4ef6\uff08%2$sx%3$s\uff09", "copied %1$s parts (%2$sx%3$s)");
        Lang.put("st.copy_empty", "\u9009\u4e2d\u7684\u4f4d\u7f6e\u6ca1\u6709\u5143\u4ef6\uff0c\u6ca1\u4ec0\u4e48\u53ef\u590d\u5236", "nothing to copy there");
        Lang.put("st.clip_empty", "\u526a\u8d34\u677f\u662f\u7a7a\u7684\uff0c\u5148\u6846\u9009\u518d\u6309 Ctrl+C", "clipboard is empty - select something and press Ctrl+C");
        Lang.put("st.undo", "\u5df2\u64a4\u9500\u4e0a\u4e00\u6b65", "undone");
        Lang.put("st.redo", "\u5df2\u91cd\u505a", "redone");
        Lang.put("st.no_undo", "\u6ca1\u6709\u53ef\u4ee5\u64a4\u9500\u7684\u6b65\u9aa4\u4e86", "nothing to undo");
        Lang.put("st.no_redo", "\u6ca1\u6709\u53ef\u4ee5\u91cd\u505a\u7684\u6b65\u9aa4\u4e86", "nothing to redo");
        Lang.put("st.deleted", "\u5df2\u5220\u9664 %1$s \u4e2a\u5143\u4ef6", "deleted %1$s parts");
        Lang.put("st.cleared", "\u5df2\u6e05\u7a7a\u9009\u62e9\uff08\u5143\u4ef6\u6ca1\u5220\uff09", "selection cleared (parts untouched)");
        Lang.put("st.bp_saved", "\u84dd\u56fe\u5df2\u4fdd\u5b58\uff1a%1$s", "blueprint saved: %1$s");
        Lang.put("st.bp_loaded", "\u84dd\u56fe\u5df2\u8bfb\u53d6\u5e76\u5e94\u7528\uff1a%1$s", "blueprint loaded: %1$s");
        Lang.put("st.bp_need_name", "\u5148\u7ed9\u84dd\u56fe\u8d77\u4e2a\u540d\u5b57", "type a name for the blueprint first");
        Lang.put("st.bp_no_file", "\u6ca1\u6709\u8fd9\u4e2a\u84dd\u56fe\uff1a%1$s", "no such blueprint: %1$s");
        Lang.put("st.bp_deleted", "\u5df2\u5220\u9664\u84dd\u56fe\uff1a%1$s", "blueprint deleted: %1$s");
        Lang.put("st.share_copied", "\u5206\u4eab\u4e32\u5df2\u590d\u5236\u5230\u526a\u8d34\u677f\uff08%1$s \u5b57\u7b26\uff09\uff0c\u53d1\u7ed9\u522b\u4eba\u5373\u53ef", "share code copied (%1$s chars) - send it to anyone");
        Lang.put("st.share_copy_fail", "\u590d\u5236\u5931\u8d25\uff1a\u526a\u8d34\u677f\u4e0d\u53ef\u7528", "copy failed: the clipboard is not available");
        Lang.put("st.share_empty", "\u526a\u8d34\u677f\u91cc\u6ca1\u6709\u5185\u5bb9", "the clipboard is empty");
        Lang.put("st.share_bad", "\u8fd9\u4e32\u4e0d\u662f\u6709\u6548\u7684\u5206\u4eab\u4e32\uff08%1$s\uff09", "not a valid share code (%1$s)");
        Lang.put("st.share_loaded", "\u5df2\u4ece\u5206\u4eab\u4e32\u8f7d\u5165 %1$s \u4e2a\u5143\u4ef6", "loaded %1$s parts from a share code");
        Lang.put("share.reason.empty", "\u7a7a\u7684\u6216\u592a\u77ed", "empty or too short");
        Lang.put("share.reason.bad", "\u5185\u5bb9\u635f\u574f", "corrupted");
        Lang.put("share.reason.toolong", "\u592a\u957f\u4e86", "too long");
        Lang.put("st.bp_dead", "\u6709 %1$s \u6839\u6ca1\u901a\u7535\u7684\u96c6\u675f\u7f06\uff0c\u5220\u6389\u6216\u901a\u7535\u540e\u518d\u8bd5", "%1$s unpowered bundled cable(s) - remove or power them first");
        Lang.put("bp.title", "\u84dd\u56fe", "Blueprints");
        Lang.put("bp.dir", "\u5b58\u6863\u76ee\u5f55\uff1a.minecraft/blueprints/", "folder: .minecraft/blueprints/");
        Lang.put("bp.save", "\u4fdd\u5b58", "Save");
        Lang.put("bp.load", "\u8f7d\u5165", "Load");
        Lang.put("bp.confirm", "\u786e\u5b9a?", "Sure?");
        Lang.put("bp.del", "\u5220\u9664", "Del");
        Lang.put("bp.back", "\u8fd4\u56de", "Back");
        Lang.put("bp.namehint", "<\u540d\u5b57\uff0c\u53ef\u7528\u4e2d\u6587>", "<name; CJK ok>");
        Lang.put("bp.share_copy", "\u590d\u5236\u5206\u4eab\u4e32", "Copy code");
        Lang.put("bp.share_load", "\u4ece\u526a\u8d34\u677f\u8f7d\u5165", "Paste code");
        Lang.put("bp.empty", "\u8fd8\u6ca1\u6709\u84dd\u56fe \u2014\u2014 \u4e0a\u9762\u8f93\u5165\u540d\u5b57\u540e\u70b9\u300c\u4fdd\u5b58\u300d", "no blueprints yet - type a name and hit Save");
        Lang.put("bp.pick", "\u70b9\u540d\u5b57=\u9009\u4e2d\u3000\u70b9\u4e24\u6b21=\u8f7d\u5165\u3000\u540d\u5b57\u53ef Ctrl+V \u7c98\u8d34", "click=select, twice=load, Ctrl+V pastes a name");
        Lang.put("bp.range", "\u7b2c %1$s-%2$s \u6761 / \u5171 %3$s \u6761", "items %1$s-%2$s of %3$s");
        Lang.put("bp.wheel", "\u6eda\u8f6e\u7ffb\u5217\u8868", "use the wheel to scroll the list");
        Lang.put("bp.del_twice", "\u518d\u70b9\u4e00\u6b21\u300c\u5220\u9664\u300d\u786e\u8ba4\u8981\u5220\u6389 %1$s", "click Del again to really delete %1$s");
        Lang.put("bp.pick_row", "\u5148\u5728\u5217\u8868\u91cc\u70b9\u4e00\u4e2a\u84dd\u56fe", "click a blueprint in the list first");
        Lang.put("help.title", "IC \u5de5\u4f5c\u53f0\u589e\u5f3a\u7248 v0.2.8 \u00b7 \u64cd\u4f5c\u8bf4\u660e", "IC Workbench Ex v0.2.8 - controls");
        Lang.put("help.body", "\u3010\u57fa\u672c\u64cd\u4f5c\u3011\n  \u5de6\u952e\u70b9\u753b\u677f = \u653e\u4e0b\u624b\u4e0a\u7684\u5143\u4ef6\n  \u53f3\u952e\u70b9\u5143\u4ef6 = \u6253\u5f00\u5b83\u7684\u8bbe\u7f6e\n  \u4e2d\u952e\u70b9\u5143\u4ef6 = \u5438\u53d6\u5b83\uff08\u624b\u4e0a\u6362\u6210\u5b83\uff09\n  \u6eda\u8f6e = \u7f29\u653e\u753b\u677f\n\u3010\u6846\u9009 / \u590d\u5236\u3011\n  \u6309\u4f4f Ctrl \u62d6\u52a8 = \u6846\u4f4f\u4e00\u7247\uff08Shift \u8ffd\u52a0\uff09\n  Ctrl+C \u590d\u5236\u3000Ctrl+X \u526a\u5207\u3000Ctrl+V \u7c98\u8d34\n  \u7c98\u8d34\u65f6\u5de6\u952e\u843d\u4f4d\uff0c\u53f3\u952e\u6216 Esc \u53d6\u6d88\n  \u7c98\u8d34\u4e0d\u4f1a\u8986\u76d6\u96c6\u675f\u7ebf\u7f06\u6240\u5728\u683c\n  Del \u5220\u9664\u9009\u4e2d\u3000Ctrl+A \u5168\u9009\n  Ctrl+Z \u64a4\u9500\u3000Ctrl+Y \u91cd\u505a\n\u3010\u84dd\u56fe\u3011\n  \u53f3\u4e0a\u89d2\u300c\u84dd\u56fe\u300d\u6309\u94ae\n  \u8f93\u5165\u540d\u5b57 = \u4fdd\u5b58\uff1b\u5217\u8868\u5355\u51fb\u9009\u4e2d\uff0c\u518d\u70b9\u4e00\u6b21\u8f7d\u5165\n  \u300c\u5220\u9664\u300d\u8981\u70b9\u4e24\u6b21\uff1b\u540d\u5b57\u53ef\u7528\u4e2d\u6587\n  \u300c\u590d\u5236\u5206\u4eab\u4e32\u300d\u628a\u7535\u8def\u538b\u6210\u4e00\u4e32\u5b57\u7b26\uff08\u526a\u8d34\u677f\uff09\uff0c\n  \u53d1\u7ed9\u522b\u4eba\u540e\u5bf9\u65b9\u70b9\u300c\u4ece\u526a\u8d34\u677f\u8f7d\u5165\u300d\u5c31\u80fd\u590d\u539f\n\u3010Esc \u9010\u5c42\u9000\u51fa\u3011\n  \u5f00\u7740\u672c\u8bf4\u660e = \u5173\u8bf4\u660e\n  \u6b63\u5728\u7c98\u8d34 = \u53d6\u6d88\u7c98\u8d34\n  \u6846\u4f4f\u4e86\u4e00\u7247 = \u6e05\u7a7a\u9009\u62e9\n  \u90fd\u6ca1\u6709 = \u5173\u95ed\u754c\u9762", "[basics]\n  LMB on the board = place the held part\n  RMB on a part = open its settings\n  MMB on a part = pick it up\n  wheel = zoom the board\n[select / copy]\n  hold Ctrl and drag = marquee (Shift adds)\n  Ctrl+C copy, Ctrl+X cut, Ctrl+V paste\n  while pasting: LMB places, RMB or Esc cancels\n  paste never overwrites cells holding bundled cables\n  Del delete, Ctrl+A select all\n  Ctrl+Z undo, Ctrl+Y redo\n[blueprints]\n  the button at the top right\n  type a name = Save\n  click a row to select, click again to load\n  Delete asks twice; names may be CJK\n  Copy code -> a short text string in the clipboard;\n  the other side pastes and hits Paste code\n[Esc steps back one layer]\n  reading this page = close it\n  pasting = cancel the paste\n  a marquee result = clear the selection\n  none of the above = close the GUI");
        Lang.put("help.foot", "H \u6216 Esc \u5173\u95ed\u672c\u9875", "H or Esc closes this page");
        Lang.put("bp.err", "\u5931\u8d25\uff1a%1$s", "failed: %1$s");
        Lang.put("btn.bp", "\u84dd\u56fe", "Blueprints");
        Lang.put("hint.idle", "Ctrl+\u62d6\u52a8:\u6846\u9009\nEsc:\u9000\u51fa\nH:\u64cd\u4f5c\u8bf4\u660e", "Ctrl+drag: marquee\nEsc: exit\nH: controls");
        Lang.put("hint.sel", "\u5df2\u9009 %1$s \u4e2a\nCtrl+C:\u590d\u5236\nCtrl+V:\u7c98\u8d34\nDel:\u5220\u9664\nEsc:\u6e05\u7a7a", "%1$s selected\nCtrl+C: copy\nCtrl+V: paste\nDel: delete\nEsc: clear");
        Lang.put("hint.paste", "\u5de6\u952e:\u843d\u4f4d\n\u53f3\u952e:\u53d6\u6d88\nEsc:\u53d6\u6d88\n\u526a\u8d34\u677f %1$s \u4e2a", "LMB: place\nRMB: cancel\nEsc: cancel\nclipboard: %1$s");
        Lang.put("st.sel", "\u5df2\u9009 %1$s \u4e2a\u5143\u4ef6", "%1$s parts selected");
        Lang.put("st.sel_skip", "\u6709 %2$s \u6839\u96c6\u675f\u7ebf\u7f06\u65e0\u6cd5\u590d\u5236\uff0c\u5df2\u5ffd\u7565\uff08\u5f53\u524d\u9009\u4e2d %1$s \u4e2a\u5143\u4ef6\uff09", "%2$s bundled cable(s) cannot be copied and were skipped (%1$s parts selected)");
        Lang.put("st.picked", "\u5df2\u5438\u53d6\uff1a%1$s", "picked up: %1$s");
        Lang.put("st.pick_none", "\u8fd9\u4e2a\u683c\u5b50\u662f\u7a7a\u7684\uff0c\u6ca1\u4e1c\u897f\u53ef\u5438\u53d6", "that cell is empty - nothing to pick");
        Lang.put("st.pasted", "\u5df2\u7c98\u8d34 %1$s \u4e2a\u5143\u4ef6", "pasted %1$s parts");
        Lang.put("st.paste_none", "\u8fd9\u91cc\u653e\u4e0d\u4e0b\u4efb\u4f55\u5143\u4ef6", "nothing fits here");
        Lang.put("st.paste_out", "\u843d\u4e86\u677f\u5916\uff0c\u5148\u6269\u677f\u518d\u8bf4", "outside the board");
        Lang.put("st.grew", "\u5df2\u8d34\u4e0a %1$s \u4e2a\u5143\u4ef6\uff0c\u5e76\u628a\u677f\u5b50\u6269\u5230 %2$s x %3$s", "pasted %1$s parts, board grown to %2$s x %3$s");
        Lang.put("st.paste_skip", "\u5df2\u4fdd\u62a4 %2$s \u4e2a\u96c6\u675f\u7ebf\u7f06\u6240\u5728\u683c\u4e0d\u88ab\u8986\u76d6\uff08\u8d34\u4e0a %1$s \u4e2a\u5143\u4ef6\uff09", "%2$s bundled-cable cell(s) protected from overwrite (%1$s parts pasted)");
    }
}
