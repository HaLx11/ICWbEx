# -*- coding: utf-8 -*-
"""One-shot build script for ICWbEx (client-side add-on for ProjectRed's
IC Workbench, Minecraft 1.7.10 / GTNH).

Requirements
------------
* a JDK whose javac supports ``--release 8`` (JDK 9..25 all do) - the output
  must stay Java-8 bytecode (major 52) for the 1.7.10 classloader
* Python 3.6+

Usage
-----
    python build.py            # -> out/ICWbEx-0.4.6.jar

How it works
------------
ICWbEx has no dev environment: it is built against *signature stubs* only.
1. compile stubs/ -> build/stubsout  (excluding stubs/icwbe/GuiBlueprint.java,
   which references the real GuiICWbEx and must be compiled with src/)
2. compile src/ + that one shadow stub -> build/out
     (classpath = stubs + libs/ICWbEx-0.2.6.jar, which carries the older
      icwbe classes that the replaced classes still reference)
3. assert the compiled call sites against the rules learned in 0.2.8
   (invokeinterface for CCL MCDataOutput, correct NBT SRG names) - these
   assertions exist because 0.2.7 shipped two wrong stubs and crashed in the
   field; read BUILDING.md before touching stubs/
4. assemble the jar: libs/ICWbEx-0.2.6.jar as the base, replace the classes in
   REPLACE (the 7 GuiICWbEx/Lang/Sync/UndoBuffer/Clipboard ones plus Blueprints
   since 0.3.8), add Replay.class, patch the version string 0.2.6 -> NEW_VER
5. javap-verify every .class entry of the finished jar

Bumping the version: change NEW_VER, and update the Lang help title +
mcmod.info text below. The base jar stays 0.2.6. NEW_VER must be 5
characters (the same length as "0.2.6"): the version string inside the
classes is patched byte by byte, and a longer NEW_VER would shift constant
pool offsets and corrupt the class (0.2.10 was lost exactly that way - FML
died at mod discovery). A non-5-char NEW_VER is not patched into classes;
the build prints a warning instead of corrupting anything.
"""
import glob
import json
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC_JAR = os.path.join(HERE, "libs", "ICWbEx-0.2.6.jar")
OUT_DIR = os.path.join(HERE, "out")
STUBSOUT = os.path.join(HERE, "build", "stubsout")
CLASSES = os.path.join(HERE, "build", "out")

NEW_VER = "0.4.6"
OLD_VER = b"0.2.6"
NEW_VER_B = NEW_VER.encode("ascii")

REPLACE = [
    "icwbe/GuiICWbEx.class",
    "icwbe/GuiICWbEx$1.class",
    # 0.3.9: the anonymous ScalaH.Act that re-routes the vanilla "new IC" dialog
    # compiles to this extra inner class. Missing it here compiles fine and then
    # dies with NoClassDefFoundError the moment the dialog is confirmed
    # (assert_inner_classes() below now catches that class of mistake).
    "icwbe/GuiICWbEx$2.class",
    "icwbe/Lang.class",
    "icwbe/Sync.class",
    "icwbe/UndoBuffer.class",
    "icwbe/Clipboard.class",
    "icwbe/Clipboard$Entry.class",
    # 0.3.8: Blueprints is the single funnel every blueprint write goes through
    # (panel save button, name-box Enter, share-import filing). It has to be ours
    # so a save can never serialise the rolled-back board of a staged sync
    # (Sync.boardBeingSynced) and so an overwrite leaves a copy behind.
    # It MUST stay in this list: without it the base jar's version wins and both
    # fixes silently disappear - the assertion in assert_call_sites() checks the
    # finished jar for exactly that.
    "icwbe/Blueprints.class",
]
ADD = ["icwbe/Replay.class"]

MCINFO_DESCRIPTION = (
    "Client-side convenience add-on for the ProjectRed Integrated Circuit Workbench.\n\n"
    "Adds a Blueprint panel (save / load / delete named .icbp layouts, placed next to the vanilla buttons), "
    "marquee selection with copy / cut / paste, undo / redo, and a working middle-click pick-part.\n\n"
    "0.2.7: pasting protects bundled-cable cells, and edits are replayed part by part so an all-zero bundled "
    "signal can no longer become null on the receiving side.\n\n"
    "0.2.8: hotfix for two 0.2.7 build regressions that made the first load drop every bundled cable and the "
    "next load crash the server (NPE inside CircuitOpDefs.apply). Two compile-time stubs did not match the "
    "runtime jars: MCDataOutput was stubbed as a class though the real one is an interface (all wire-op "
    "writes died with IncompatibleClassChangeError, so cables were never re-created server-side and were lost "
    "from the save), and NBT getInteger was stubbed with func_74771_c although that is getByte (call sites "
    "died with NoSuchMethodError). Both stubs now match the runtime exactly, verified against javap of "
    "CodeChickenCore-1.4.22 and Forge's official deobfuscation data. On any sync failure the client now "
    "resets the tile's half-written stream buffer (the vanilla tile flushes it every tick, so a stray stream "
    "key could previously reach the server as a corrupt op command) and logs full stack traces.\n\n"
    "0.2.9: fixes the field-verified 'Invalid gate subID: 0' hard disconnect. While no blueprint is inserted "
    "the server silently discards whole-circuit descs, so 0.2.8's tier-2 whole desc left the server ticking "
    "the old board while the client had already applied the edit; the server's per-part state streams then "
    "found their parts missing on the client, PR's fallback built those gates with subID 0 and the first "
    "key>10 frame of a running sequential gate disconnected the client. Without a blueprint edits are now "
    "replayed part-by-part (removals echo back idempotently, creations are never echoed), and pastes that "
    "would grow the board are refused with a hint while no blueprint is inserted (a board growth can only "
    "travel through a whole desc, which the server would discard).\n\n"
    "0.3.0: fixes the second field-verified 'Invalid gate subID: 0' trigger, present since 0.2.7. Edits that "
    "remove or replace parts (paste over something, delete, undo/redo, board growth) were applied to the "
    "client instantly while the op stream still travelled to the server; during that window the server kept "
    "ticking the old board and streamed state frames for parts the client had already torn down, and PR's "
    "readPartStream fallback built those gates with subID 0 - one key>10 frame from a running sequential gate "
    "disconnected the client (2026-10-01: pasting a 130-part swarm board with live sequential gates killed it "
    "within seconds). Such edits are now staged in two phases: the client briefly rolls back to the pre-edit "
    "board, only the removals are sent and the server's own removal echoes converge both sides on the "
    "intermediate board, and ~0.4s later the edited board is applied and the creations replayed. There is no "
    "longer any moment in which the server streams a frame for a part the client does not have. Purely "
    "additive edits stay instant; the GUI briefly refuses further edits while a staged edit is converging.\n\n"
    "0.3.1: fixes the field case where loading/switching blueprints inside the blueprint panel destroyed "
    "the board - \"cables but no components\", or the load appearing to do nothing at all. The second phase "
    "of a staged edit was pumped from GuiICWbEx.frameUpdate only, but the blueprint panel is a GuiScreen of "
    "its own, so GuiICWbEx (and the pump) stopped running while it was open: phase 1 had already rolled the "
    "client back and sent the removals - and the gates whose live state differs from the snapshot were "
    "removed server-side - while phase 2 never came to put them back. The pump is now also driven from the "
    "GUI text path (Lang.t), which every ICWbEx screen calls every frame, and a board switch that arrives "
    "while the previous edit is still converging is queued and staged as soon as that edit settles, so it "
    "can never be diffed against a base the server no longer holds.\n\n"
    "0.3.2: fixes the silent NBT type error that made every diff meaningless. The sync diffs read the board's "
    "sw/sh and each part's xpos/ypos with NBTTagCompound getInteger, but IntegratedCircuit.save writes them as "
    "NBT bytes - getInteger demands an Int tag, so it returned 0 instead of throwing. Every diff base therefore "
    "collapsed to a single part at (0,0) and every size comparison read '0x0 versus the live board', i.e. "
    "'resized': each edit took the resize branch, wiped the server board with a blank whole-circuit desc and "
    "re-placed everything, and the desc echo that ProjectRed sends back replaced the client copy with the blank "
    "board. The server then streamed frames for parts the client no longer had (thousands of 'client part stream "
    "couldnt find part'), PR's fallback built a running sequential gate with subID 0, and the client was "
    "disconnected with 'Invalid gate subID: 0' - with the workbench left holding a handful of stray parts. All "
    "three call sites now use getByte, and phase 1's blank desc carries the TARGET's name and size (it used to "
    "send the rolled-back size, which forced a second blank desc whose late echo was what wiped the client). "
    "A second silent defect in the same comparison: a snapshot part entry is {id, xpos, ypos} + CircuitPart.save's "
    "fields, but a live part saves only the latter, so part identity never matched either - the diff planned every "
    "cell as a removal and re-placed the whole board on every edit. Those framing keys are now ignored on both "
    "sides, so the diff is exact and purely additive edits (which never need the two-phase rollback) stay instant "
    "again.\n\n"
    "0.3.3: stops the sync from overwriting the board it is syncing. Every whole-circuit desc we send is echoed "
    "straight back by ProjectRed and the client applies that echo with IntegratedCircuit.readDesc - i.e. it "
    "REPLACES the local board with the desc contents. The old tiers 2 and 3 sent a desc purely to transport the "
    "board's NAME (their only other job), so every blueprint switch cost a board-replacing round trip; when that "
    "echo landed after our own second phase the local copy came back empty while the server held the real board, "
    "the server's per-part frames then found nothing on the client (1000+ 'client part stream couldnt find part' "
    "lines in the 07:52 log) and the fallback built a running sequential gate with subID 0 -> 'Invalid gate subID: "
    "0' disconnect. The name is cosmetic, so a desc is now sent ONLY when the server board's size would otherwise "
    "be wrong (ops at coordinates outside it would trip assertCoords and abort the stream); everything else, "
    "bundled cables included, is rebuilt with the same op packets a player's own placements use. A settle watch "
    "also re-applies the committed board locally if a late echo ever empties the client copy again.\n\n"
    "0.3.4: fixes the last piece - the blueprint load path calls Sync.toServer TWICE. GuiBlueprint.doLoad -> "
    "Share.loadNamed -> Blueprints.load applies the blueprint and syncs it (call 1); loadNamed then sets the "
    "board's name from the file name and syncs again (call 2). Because call 1's phase 1 has already rolled the "
    "live board back to the pre-edit board, call 2 carries that OLD board: the sync queued it as 'the next edit', "
    "and 400ms later re-applied it, undoing every load it had just performed (consecutive switches left the "
    "workbench holding a handful of stray parts). A second call whose board matches the one we rolled back to is "
    "now recognised as a name-only echo: the name is folded into the pending target and the call is dropped.\n\n"
    "0.3.5: stops the diff from tearing down parts that are merely RUNNING, and makes the removals that remain harmless.\n\n"
    "(a) A gate's live state (counter value, timer countdown, latch state, measured IO frequency) is not part of its identity. Those fields were compared, so every running gate looked 'changed' against the blueprint it was loaded from, and every load/re-switch diffed a dozen or a hundred parts as edited - each one removed in phase 1 and re-created in phase 2. Only the persistent configuration decides identity now, which turns a re-load of the same board into a zero-op no-op.\n\n"
    "(b) A part that CAN still stream a key>10 state frame (readPartStream checks key \u003e 10; Counter/Sequencer/Timer/StateCell/Synchronizer/SRLatch/ToggleLatch use 11-14, the analog/bundled IO uses 12) is now parked before it is removed or replaced: phase 1a swaps it for a bare alloy wire through the ordinary case-1 create op - which the server applies WITHOUT echoing anything back, so our own copy keeps the original part and keeps absorbing the frames the server is still emitting for it. An inert wire (connMap 0) never streams, so when the real removal goes out one park window later no state frame can be in flight for that cell any more. Without this the very last frame of the part arrived in the same packet batch as the removal echo, PR's fallback re-created the part with subID 0 and the client was kicked with 'Invalid gate subID: 0' - the 2026-10-01 08:11 DIM180 log (a 16x16 test board losing the timer and the latch at (1,1)/(1,2)). Wires, cables, torches, levers, buttons and plain combinational gates stay at keys 1-5 and are removed directly: a throwaway recreate of those cannot throw.\n\n"
    "0.3.8: stops a save from writing the WRONG board into a blueprint. While a staged sync converges, "
    "the live board is deliberately rolled back to the pre-edit one (that is what keeps the server's "
    "in-flight state frames landing on a cell that still holds their part). The blueprint save funnel - "
    "the panel's Save button, Enter in the name box and the share-code import that files what it imported "
    "- serialised that live board, so saving during the window wrote the PREVIOUS board into the file of "
    "the name that was in the box; and clicking a list row fills that box with the row's name, so simply "
    "selecting a blueprint and saving overwrote it. The 2026-10-01 case: blueprints\\BEC蜂群选择器.icbp "
    "came out byte-identical to blueprints\\BEC蜂群信号转换.icbp while carrying its own name inside - the "
    "signature of 'the name came from the box, the content came from the rolled-back board'. The save "
    "funnel now asks Sync which board the pending sync is going to land and serialises that one, and every "
    "overwrite first copies the file it is about to replace into "
    "<game>/icwbe_blueprint_backups/session-<startup>/ (on top of the per-session snapshot of blueprints/).\n\n"
    "0.3.9: stops the vanilla 'new IC' dialog from disconnecting the client. That dialog (NewICNode, "
    "reachable while an IC blueprint/plate is inserted) sends a whole-board desc, and ProjectRed echoes "
    "every desc straight back: the client replaces its board with the echo while the server is still "
    "streaming per-part state frames for the old board, so those frames land on empty cells, PR's "
    "fallback builds the part with subID 0 and a running sequential gate's first key>10 frame kills the "
    "connection with 'Invalid gate subID: 0' (2026-10-01 11:51 field case: a 371-part board was emptied "
    "in the world by that click, then the client was kicked on the timer/IO cluster). ICWbEx now catches "
    "the dialog as it is added to the screen and re-routes its completion delegate through the same "
    "two-phase path a blueprint load uses (park the stateful parts, blank desc, rebuild), keeping the "
    "size and name the player picked. A matching guard refuses a blueprint load that would change the "
    "board size while no blueprint/plate is inserted, because the server silently discards whole descs "
    "in that state - the client and server would diverge and go down the same path.\n\n"
    "0.4.0: stops a rapid row-switch from pairing one blueprint's NAME with another one's CONTENT, and makes "
    "the save guard look at the board size as well. The blueprint panel's load path calls the sync twice: "
    "Blueprints.load applies the file and syncs (call 1), then Share.loadNamed renames the board from the file "
    "name and syncs again (call 2). Call 2 is recognised as a name-only echo of the rollback and its name is "
    "folded into the board that is about to be applied - but when the player had already clicked the next row, "
    "that name belonged to the NEWER request, and folding it onto the board still in flight produced states like "
    "\"测试1\" 32x32/371p (371 parts is a different blueprint's content). Those states self-corrected on the next "
    "switch, but a save inside the window would have filed the wrong content under that name. The fold now targets "
    "the newest parked board (pending wins over in-flight), and the overwrite confirmation also fires when the "
    "board size disagrees with the file - normal edits never change the size.\n\n"
    "0.4.1: completes 0.4.0. That release stopped the name-only echo from being folded onto the wrong "
    "pending board, but it still renamed the LIVE board - and during a staged sync the live board is the "
    "copy we rolled back to, so the rename produced a board carrying one blueprint's name with another "
    "one's content (0.4.0 log: a switch was queued as 32x32/371p under the name 测试1, which is a different "
    "blueprint). The live board is never renamed any more - the name lives on the board that is about to be "
    "applied, which is also the one Blueprints.save serialises. And when no rollback base is available the "
    "call is dropped instead of queued, because an unverifiable board would re-apply the rolled-back one and "
    "undo the load. The queued-switch log now prints both the queued board and the base.\n\n"
    "0.4.2: stops the settle watch from undoing the player's deletions. The watch existed because "
    "ProjectRed echoes a whole-board desc straight back and the client applies it, clearing the local "
    "board - so it remembered the committed board and re-created whatever was missing. Re-creating "
    "'whatever was missing' also matched a plain deletion, though: erasing a few bundled cables dropped "
    "the part count below the committed board, the watch restored those parts from the old board, and the "
    "next diff reported the deletion as an addition - the erased parts came back, unconnected and sitting "
    "on cells that could then not be used for anything new (2026-10-01 12:33 field report). The watch is "
    "now armed only for commits that actually sent a desc (a board resize - the only case where an echo "
    "can replace the board wholesale), and it repairs only a board that came back with NOTHING on it.\n\n"
    "0.4.3: makes the VANILLA eraser tool use the safe delete. The vanilla tools write their op "
    "straight to the local board and to the wire with none of the staging ICWbEx uses, so erasing "
    "re-opens the window the two-phase commit exists to close: our copy has already lost the part "
    "while the server still has it and keeps streaming its state frames. Erase a cell next to a "
    "sequential gate and PR's readPartStream fallback re-creates the gate with subID 0 - the next "
    "key>10 frame disconnects the client (2026-10-01 12:44: a vanilla erase 28s after a blueprint "
    "load kicked the client). While the eraser is the selected tool the prefboard stops handling "
    "the mouse and the gesture becomes a marquee: click or drag a box, release, and the cells are "
    "deleted through the same staged path as the selection keys (parking included). A plain click "
    "still erases exactly one cell, so nothing about the tool feels different.\n\n"
    "0.4.5: gives that eraser back the two things taking the mouse away from the prefboard had "
    "swallowed. The wheel zooms again while the eraser is selected (driven through the node's own "
    "incScale/decScale, so it needs no coordinate conversion and keeps vanilla's 0.2 step and 0.5-3.0 "
    "clamp) - zooming out is exactly what you want when you are picking the few parts to remove. And "
    "the marquee now shows its work: every cell inside the box that holds a part gets a red danger tint, "
    "the box gets the danger border, the number of parts is printed next to it, and the part under the "
    "cursor is outlined before you even press, so nothing is deleted sight unseen. Both come from the "
    "same grid conversion the deletion itself uses, so the preview cannot disagree with the result.\n\n"
    "Purely client-side. The ProjectRed jar itself is never modified, and no custom network channel is "
    "registered: opening the GUI, resizing the board, pasting, undoing and loading blueprints all go "
    "through ProjectRed's own IC synchronisation protocol. The server therefore does NOT need this mod."
)


def die(msg):
    print("BUILD FAILED:", msg)
    sys.exit(1)


def run(cmd, label):
    print("==", label)
    p = subprocess.run(cmd, capture_output=True)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    if p.returncode != 0:
        print(out)
        die("%s exited with %d" % (label, p.returncode))
    if out.strip():
        for line in out.splitlines():
            if "obsolete" not in line and "deprecat" not in line:
                print("   ", line)
    return out


def compile_all():
    if not os.path.isfile(SRC_JAR):
        die("base jar missing: %s" % SRC_JAR)
    for d in (STUBSOUT, CLASSES, OUT_DIR):
        os.makedirs(d, exist_ok=True)

    sep = ";" if os.name == "nt" else ":"
    stubs = [f for f in glob.glob(os.path.join(HERE, "stubs", "**", "*.java"), recursive=True)
             if not f.endswith("GuiBlueprint.java")]
    if not stubs:
        die("no stub sources found")
    run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", STUBSOUT] + stubs,
        "compile stubs (%d files)" % len(stubs))

    srcs = glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True)
    srcs.append(os.path.join(HERE, "stubs", "icwbe", "GuiBlueprint.java"))
    run(["javac", "--release", "8", "-encoding", "UTF-8",
         "-cp", STUBSOUT + sep + SRC_JAR, "-d", CLASSES] + srcs,
        "compile src (%d files)" % len(srcs))


def javap(cls_file):
    p = subprocess.run(["javap", "-c", "-p", cls_file], capture_output=True)
    return p.stdout.decode("utf-8", "replace")


def javap_signatures(cls_file):
    """javap -s: the DECLARED methods with their descriptors. javap -c only shows
    descriptors for CALL sites, so an API check on our own class needs this."""
    p = subprocess.run(["javap", "-p", "-s", cls_file], capture_output=True)
    return p.stdout.decode("utf-8", "replace")


def assert_call_sites():
    """Guards rebuilt from the 0.2.7 field regressions. Do not remove."""
    print("== assert compiled call sites")

    replay = javap(os.path.join(CLASSES, "icwbe", "Replay.class"))
    if re.search(r"invokevirtual\s+#\d+\s+// Method codechicken/lib/data/", replay):
        die("Replay contains invokevirtual on a CCL interface method "
            "(MCDataOutput is an INTERFACE - the stub must be an interface).")
    if "InterfaceMethod codechicken/lib/data/MCDataOutput.writeByte" not in replay:
        die("Replay lost its MCDataOutput.writeByte call site (expected invokeinterface).")
    if re.search(r"func_74762_e:\(Ljava/lang/String;\)I", replay) and "subID" not in replay:
        die("Replay reads NBT with getInteger (func_74762_e) outside the int identity "
            "keys. IntegratedCircuit.save writes name/sw/sh and every part's "
            "id/xpos/ypos as NBT BYTES (javap: i2b + func_74774_a), and getInteger "
            "demands an Int tag - so on real data it does not throw, it silently "
            "returns 0 (0.3.2 field failure). Use func_74771_c (getByte).")
    if "func_74771_c:(Ljava/lang/String;)B" not in replay:
        die("Replay lost its getByte (func_74771_c) call site - the sw/sh/xpos/ypos "
            "reads and the byte identity keys must stay byte-typed.")
    # 0.3.6: the diff is an ALLOW list per part id - it may only ever compare the
    # persistent configuration, never a live/derived field (see Replay.identityKeys).
    for key in ["subID", "orient", "colour", "on"]:
        if key not in replay:
            die("Replay lost the identity key %r - identityKeys() must keep comparing "
                "the part's persistent configuration." % key)
    # 0.3.7: the blueprint safety net (one copy of blueprints/ per session).
    if "backupBlueprintsOnce" not in replay:
        die("Replay lost backupBlueprintsOnce() - a save over a good blueprint "
            "would be unrecoverable (the 32x32 BEC board was lost that way).")
    if "repairLocal" not in replay:
        die("Replay lost repairLocal() - the settle watch needs it to put back a part "
            "a late removal echo took off our copy.")
    # 0.3.6: phase 2 must NOT re-send removals (Replay.push with emitRemoves=false).
    # Parameter names do not survive into the class file, so assert on the branch
    # its else-arm prints and on the 5-arg overload itself.
    if "no removals re-sent" not in replay:
        die("Replay.push lost its emitRemoves=false branch - phase 2 must not "
            "re-send removals, or the client loses every re-created gate.")
    if "NBTTagCompound, boolean, boolean)" not in replay:
        die("Replay.push lost its 5-argument overload (parameter names do not appear "
            "in the bytecode, but the descriptor does).")
    # 0.3.5: the safe-removal protocol. A part that can stream a key>10 state frame
    # has to be turned into an inert wire (case-1 op, never echoed) before it may be
    # removed - without it the removal kills the client with "Invalid gate subID: 0".
    if "createPart:(I)Lmrtjp/projectred/fabrication/CircuitPart;" not in replay:
        die("Replay lost its CircuitPart.createPart call - the parking wire "
            "(Replay.sendDegrade) must be able to build one.")
    if "dangerousCells" not in replay:
        die("Replay lost its stateful-part classification (dangerousCells).")
    if "mrtjp/projectred/fabrication/CircuitPart.writeDesc" not in replay:
        die("Replay lost the writeDesc call of the parking wire.")

    lang = javap(os.path.join(CLASSES, "icwbe", "Lang.class"))
    if "st.bp_overwrite" not in lang:
        die("Lang lost st.bp_overwrite - the panel would show a raw key name.")
    if "backupBlueprintsOnce" not in lang:
        die("Lang.t lost the blueprint-backup hook - it must run before the "
            "player can save over a blueprint.")
    if "Sync.pump" not in lang:
        die("Lang.t lost the Sync.pump driver (0.3.1).")

    sync = javap(os.path.join(CLASSES, "icwbe", "Sync.class"))
    if "icStream_$eq:(Lcodechicken/lib/packet/PacketCustom;)V" not in sync:
        die("Sync lost the icStream buffer reset call (TraitSetter signature must be "
            "(Lcodechicken/lib/packet/PacketCustom;)V exactly).")
    if "partStream_$eq:(Lcodechicken/lib/packet/PacketCustom;)V" not in sync:
        die("Sync lost the partStream buffer reset call.")
    # 0.3.9: the vanilla "new IC" dialog must not send a raw whole-board desc.
    gui = javap(os.path.join(CLASSES, "icwbe", "GuiICWbEx.class"))
    if "NewICNode" not in gui:
        die("GuiICWbEx lost its NewICNode hook - the vanilla 'new IC' dialog would "
            "send a raw whole-board desc again (0.3.9: that wiped the client board "
            "mid-stream and disconnected with Invalid gate subID: 0).")
    if "completionDelegate_$eq" not in gui:
        die("GuiICWbEx no longer re-routes the vanilla new-IC completion delegate.")
    if "addChild" not in gui:
        die("GuiICWbEx lost its addChild override that catches the new-IC dialog.")

    if "boardBeingSynced" not in sync:
        die("Sync lost boardBeingSynced() - Blueprints.save needs it to serialise the "
            "board a staged sync is going to land instead of the rolled-back one "
            "(0.3.8: that is how a blueprint got overwritten with the previous board).")

    # 0.3.8: Blueprints is compiled from src/ and REPLACES the base jar's copy.
    # Both halves matter: the fixes below, and the public API the base jar's
    # GuiBlueprint / Share call. A missing method here is a NoSuchMethodError in
    # the user's game, not a build error - unless this check catches it.
    bp = javap(os.path.join(CLASSES, "icwbe", "Blueprints.class"))
    bp_api = javap_signatures(os.path.join(CLASSES, "icwbe", "Blueprints.class"))
    if "Sync.boardBeingSynced" not in bp:
        die("Blueprints.save lost its Sync.boardBeingSynced() call - a save during a "
            "staged sync would write the rolled-back board into the blueprint.")
    if "icwbe_blueprint_backups" not in bp:
        die("Blueprints lost its pre-overwrite backup (keepPrevious).")
    # 0.4.2: the settle watch may only fire for a desc-sending commit, and only when
    # the local board came back EMPTY. Any wider rule fights the player's deletions.
    if "watchDesc" not in sync:
        die("Sync lost the watchDesc gate - the settle watch would repair the player's "
            "own deletions again (0.4.2: erased parts kept coming back and blocked the "
            "cells they sat on).")
    if "was emptied by a late desc echo" not in sync:
        die("Sync's settle watch no longer reports the have/need counts.")
    if "call while pending with no diff base" not in sync:
        die("Sync lost its 'no diff base while pending' guard - queueing an unverifiable "
            "board would re-apply the rolled-back one and undo the load (0.4.1).")
    if "board switch queued behind the running sync (queued" not in sync or "over base" not in sync:
        die("Sync's queued-switch log no longer names the queued board AND the base.")
    if "CircuitOpErase" not in gui:
        die("GuiICWbEx lost its eraserMode() check - the vanilla eraser would erase "
            "directly again and can disconnect the client (0.4.3).")
    if "eraseRect" not in gui or "deleteKeys" not in gui:
        die("GuiICWbEx lost the eraser marquee (eraseRect/deleteKeys) - click-and-drag "
            "with the vanilla eraser must go through the staged delete.")
    # 0.4.5: the eraser has to keep two vanilla behaviours that taking the mouse away
    # from the prefboard would otherwise swallow - the wheel (zoom) and, when dragging,
    # any clue about what is about to be deleted.
    if "mouseScrolled_Impl" not in gui or "incScale" not in gui or "decScale" not in gui:
        die("GuiICWbEx lost its mouseScrolled_Impl - selecting the eraser would kill "
            "zoom again (0.4.5: zooming is exactly what you want while erasing).")
    if "tintEraseCells" not in gui or "st.erase_preview" not in gui:
        die("GuiICWbEx lost the eraser preview (tintEraseCells + its count) - the "
            "marquee would delete cells with no indication of which ones (0.4.5).")
    # 0.4.6: shift + left drag is the VANILLA board pan (PanNode.dragTestFunction =
    # left shift, run from PanNode.frameUpdate; only the prefboard is ever switched
    # off). The eraser must therefore stand down while shift is held AND drop any
    # marquee whose board has moved under it, or it deletes cells the box no longer
    # covers - which is exactly what happened in the field.
    _intercept_start = gui.find("private boolean intercepting")
    _intercept = gui[_intercept_start:_intercept_start + 900]
    if "shiftHeld" not in _intercept:
        die("GuiICWbEx.intercepting no longer checks shiftHeld - shift+drag (the vanilla "
            "board pan) would be swallowed by the eraser again (0.4.6).")
    if "boardMovedSinceGesture" not in gui or "st.marquee_moved" not in gui:
        die("GuiICWbEx lost its marquee-moved guard - a pan or zoom during a box would "
            "delete the wrong cells (0.4.6).")
    if "beginGesture" not in gui or "gestureAborted" not in gui:
        die("GuiICWbEx lost the gesture bookkeeping that backs the marquee-moved guard.")
    if "eraserMode" not in gui:
        die("GuiICWbEx lost eraserMode() - the vanilla eraser would go back to writing "
            "its op straight through and can disconnect the client (0.4.3).")
    # 0.4.5: the vanilla tools write through the server, so the board can move under
    # lastSnap without any of our commit paths running. The follow guard keeps the diff
    # base level - and it must stay in the GUI's idle path only (never in stageEdit,
    # where re-basing would make the pending edit diff as "nothing changed").
    if "followLocalBoard" not in sync or "followLocalBoard" not in gui:
        die("Sync.followLocalBoard is not wired into GuiICWbEx anymore - the diff base "
            "would lag behind any board change the server made (0.4.5).")
    if "watchArmed" not in sync or "followSentOp" in sync or "noteOpSent" in sync:
        die("Sync's follow guard changed shape - it must skip while the settle watch is "
            "armed (the local board is deliberately wrong then) and the 0.4.4 placement "
            "watches must be gone (they could re-base a staged edit's base).")
    if "foldName" not in sync or "pendNext" not in sync:
        die("Sync lost its foldName/pendNext pairing - the name-only echo of a blueprint "
            "load must land on the NEWEST parked board (0.4.0: folding it onto the older "
            "target produced boards with one blueprint's name and another one's content).")
    # 0.4.1: foldName must NOT rename the live board. The live board during a staged
    # sync is the copy we rolled back to; renaming it produced a board with one
    # blueprint's name and another one's content (see the 0.4.0 field log). The name
    # travels on the board that is about to be applied (pendTarget/pendNext).
    fold = sync[sync.find("private static void foldName"):]
    fold = fold[:fold.find("\n  private ", 1) if fold.find("\n  private ", 1) > 0 else 2000]
    if "name_$eq" in fold:
        die("Sync.foldName renames the live board again - it must only rename the board "
            "that is about to be applied (0.4.1: renaming the rolled-back copy produced "
            "'name of A, content of B' boards).")
    if "func_74778_a" not in fold:
        die("Sync.foldName lost its tag rename - the applied board would keep the old name.")
    if "overwriteGuard" not in bp or "st.bp_overwrite" not in bp:
        die("Blueprints lost overwriteGuard() - 'clicking a list row arms the name box, "
            "and Save then overwrites that blueprint' must keep asking for a second press.")
    if "func_74795_b:(Lnet/minecraft/nbt/NBTTagCompound;Ljava/io/File;)V" not in bp:
        die("Blueprints lost its writeCompressed call (func_74795_b) - saving would "
            "no longer write anything.")
    # 0.3.8: isDeadSignal must stay byte-identical in behaviour to the base jar's
    # (sig == null || sig.length != 16). The old working copy in the sibling project
    # carried an extra "all bytes zero => dead" loop, and copying that in would have
    # refused every blueprint that contains bundled cables (测试1/测试2 are all-zero
    # and load fine) - i.e. it would have blocked exactly the files this fix exists for.
    body = bp[bp.find("private static boolean isDeadSignal"):]
    body = body[:body.find("\n  public ", 1) if body.find("\n  public ", 1) > 0 else 400]
    if "baload" in body:
        die("Blueprints.isDeadSignal reads array elements again - the base jar only "
            "treats null and a wrong length as dead; an all-zero 16-byte cache "
            "(what ensureCaches fills) must stay saveable/loadable.")
    if "arraylength" not in body:
        die("Blueprints.isDeadSignal lost its length check.")
    for name, desc in [
        ("save", "(Ljava/lang/String;Lmrtjp/projectred/fabrication/IntegratedCircuit;)Ljava/lang/String;"),
        ("load", "(Ljava/lang/String;Lmrtjp/projectred/fabrication/IntegratedCircuit;"
                 "Lmrtjp/projectred/fabrication/TileICWorkbench;)Ljava/lang/String;"),
        ("apply", "(Lnet/minecraft/nbt/NBTTagCompound;Lmrtjp/projectred/fabrication/IntegratedCircuit;"
                  "Lmrtjp/projectred/fabrication/TileICWorkbench;)Ljava/lang/String;"),
        ("read", "(Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;"),
        ("list", "()Ljava/util/List;"),
        ("dir", "()Ljava/io/File;"),
        ("sanitize", "(Ljava/lang/String;)Ljava/lang/String;"),
        ("fileFor", "(Ljava/lang/String;)Ljava/io/File;"),
        ("delete", "(Ljava/lang/String;)Z"),
        ("ensureCaches", "(Lmrtjp/projectred/fabrication/IntegratedCircuit;)I"),
        ("countDeadBundled", "(Lmrtjp/projectred/fabrication/IntegratedCircuit;)I"),
        ("countDeadBundledInTag", "(Lnet/minecraft/nbt/NBTTagCompound;)I"),
    ]:
        if (" %s(" % name) not in bp_api or ("descriptor: %s" % desc) not in bp_api:
            die("Blueprints lost %s%s - the base jar's GuiBlueprint/Share call it, so the "
                "panel would die with NoSuchMethodError." % (name, desc))
    print("   all call-site assertions passed")


def verify_classes_parse(jar_path):
    """javap every .class entry of the finished jar, reading the bytes straight
    from the jar via -cp (no temp files, no filesystem cleanup needed). A
    corrupt constant pool must fail HERE, never in the user's FML mod
    discovery. Added after the 0.2.10 incident: its ICWbEx.class was corrupted
    by a 5->6 char version replace and crashed the game at launch with
    ArrayIndexOutOfBoundsException during mod discovery."""
    print("== javap-verify every .class entry")
    with zipfile.ZipFile(jar_path) as z:
        classes = [n[:-len(".class")] for n in z.namelist() if n.endswith(".class")]
    total, bad = len(classes), []
    for cls in classes:
        p = subprocess.run(["javap", "-c", "-p", "-cp", jar_path, cls.replace("/", ".")],
                           capture_output=True)
        txt = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
        # NB: do NOT search a bare "Error" - valid disassembly contains
        # identifiers like statusIsError / refreshErrors (false positive seen
        # on the first 0.3.0 build).
        corrupt = (p.returncode != 0
                   or "unexpected tag" in txt
                   or "出错" in txt          # localized javap: error reading constant pool
                   or "Error reading" in txt
                   or "bad constant pool" in txt
                   or "Bad magic number" in txt)
        if corrupt:
            bad.append(cls)
            print("   CORRUPT:", cls)
            for line in txt.splitlines()[:4]:
                print("   ", line)
    if bad:
        die("javap cannot parse %d class file(s): %s" % (len(bad), bad))
    print("   %d class entries parse cleanly" % total)


def assemble():
    dst = os.path.join(OUT_DIR, "ICWbEx-%s.jar" % NEW_VER)
    with zipfile.ZipFile(SRC_JAR) as src:
        mcinfo = json.loads(src.read("mcmod.info").decode("utf-8"))
    mcinfo[0]["version"] = NEW_VER
    mcinfo[0]["description"] = MCINFO_DESCRIPTION

    new_data = {}
    for name in REPLACE + ADD:
        with open(os.path.join(CLASSES, *name.split("/")), "rb") as f:
            new_data[name] = f.read()
    new_data["mcmod.info"] = (json.dumps(mcinfo, indent=2, ensure_ascii=False) + "\n").encode("utf-8")

    # Byte-level version patching is only safe when OLD and NEW have the same
    # length: the bytes sit inside the constant pool, so a longer NEW_VER
    # shifts every offset after it and corrupts the class (the 0.2.10 jar was
    # lost exactly this way; 0.3.0 keeps the 5-char length).
    ver_patch = len(NEW_VER_B) == len(OLD_VER)
    if not ver_patch:
        print("== WARNING: NEW_VER %r is not %d chars; byte-level version patch"
              " is DISABLED for .class entries (variable-length replace corrupts"
              " the constant pool)." % (NEW_VER, len(OLD_VER)))

    hits = []
    with zipfile.ZipFile(SRC_JAR) as src, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as dstz:
        written = set()
        for info in src.infolist():
            name = info.filename
            if name in REPLACE:
                continue
            data = new_data.get(name, src.read(name))
            if name.endswith(".class") and ver_patch:
                nb = data.replace(OLD_VER, NEW_VER_B)
                if nb != data:
                    hits.append(name)
                    data = nb
            dstz.writestr(name, data)
            written.add(name)
        for name, data in new_data.items():
            if name not in written:
                dstz.writestr(name, data)

    print("== jar assembled:", dst)
    print("   version string patched in:", hits or "(none)")
    with zipfile.ZipFile(dst) as z:
        if z.testzip() is not None:
            die("zip integrity check failed")
        names = z.namelist()
        for want in REPLACE + ADD:
            if want not in names:
                die("missing entry in output jar: " + want)
        stale = [n for n in names if OLD_VER in z.read(n) and n != "mcmod.info"]
        if stale and ver_patch:
            die("stale version bytes in: %s" % stale)
        if stale:
            print("   NOTE: old version bytes left in (patch disabled):", stale)
        if NEW_VER_B not in z.read("mcmod.info"):
            die("mcmod.info version not patched")
        print("   entries:", len(names), "| integrity OK")
    verify_classes_parse(dst)
    # 0.3.8: a REPLACE entry whose class is missing from CLASSES falls back to the
    # BASE jar's copy silently - the fix would be gone with a green build. Read the
    # finished jar back and require our Blueprints to be the one inside.
    p = subprocess.run(["javap", "-c", "-p", "-cp", dst, "icwbe.Blueprints"],
                       capture_output=True)
    jar_bp = p.stdout.decode("utf-8", "replace")
    if "boardBeingSynced" not in jar_bp:
        die("the finished jar does not contain the patched icwbe.Blueprints "
            "(REPLACE entry missing?) - saving would still write the rolled-back board.")
    print("   Blueprints in the jar: patched copy confirmed")
    return dst


def verify_class_is_packaged(jar_path):
    """Every inner class the compiler produced for a REPLACE'd class must also be in
    the jar. Added in 0.3.9 after GuiICWbEx$2 (the anonymous ScalaH.Act that re-routes
    the vanilla "new IC" dialog) was compiled, silently left out, and would have thrown
    NoClassDefFoundError at the first click."""
    print("== verify inner classes of replaced classes are packaged")
    with zipfile.ZipFile(jar_path) as z:
        packed = set(z.namelist())
    missing = []
    for entry in sorted(os.listdir(os.path.join(CLASSES, "icwbe"))):
        if not entry.endswith(".class"):
            continue
        name = "icwbe/" + entry
        if name in REPLACE or name in ADD:
            continue
        base = name.split("$")[0] + ".class"
        if base in REPLACE and name not in packed:
            missing.append(name)
    if missing:
        die("compiled inner class(es) not packaged: %s - add them to REPLACE" % missing)
    print("   ok")


def main():
    if not os.path.isfile(os.path.join(HERE, "libs", "ICWbEx-0.2.6.jar")):
        die("run this script from the project root (libs/ICWbEx-0.2.6.jar not found)")
    compile_all()
    assert_call_sites()
    out = assemble()
    verify_class_is_packaged(out)
    print("DONE ->", out)


if __name__ == "__main__":
    main()
