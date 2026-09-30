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
    python build.py            # -> out/ICWbEx-0.2.8.jar

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
4. assemble the jar: libs/ICWbEx-0.2.6.jar as the base, replace the 7 updated
   classes, add Replay.class, patch the version string 0.2.6 -> 0.2.8

Bumping the version: change NEW_VER, and update the Lang help title +
mcmod.info text below. The base jar stays 0.2.6.
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

NEW_VER = "0.2.8"
OLD_VER = b"0.2.6"
NEW_VER_B = NEW_VER.encode("ascii")

REPLACE = [
    "icwbe/GuiICWbEx.class",
    "icwbe/GuiICWbEx$1.class",
    "icwbe/Lang.class",
    "icwbe/Sync.class",
    "icwbe/UndoBuffer.class",
    "icwbe/Clipboard.class",
    "icwbe/Clipboard$Entry.class",
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


def assert_call_sites():
    """Guards rebuilt from the 0.2.7 field regressions. Do not remove."""
    print("== assert compiled call sites")

    replay = javap(os.path.join(CLASSES, "icwbe", "Replay.class"))
    if re.search(r"invokevirtual\s+#\d+\s+// Method codechicken/lib/data/", replay):
        die("Replay contains invokevirtual on a CCL interface method "
            "(MCDataOutput is an INTERFACE - the stub must be an interface).")
    if "InterfaceMethod codechicken/lib/data/MCDataOutput.writeByte" not in replay:
        die("Replay lost its MCDataOutput.writeByte call site (expected invokeinterface).")
    if re.search(r"func_74771_c:\(Ljava/lang/String;\)I", replay):
        die("Replay calls func_74771_c as int - that SRG name is getByte(String)B; "
            "use func_74762_e (getInteger).")
    if "func_74762_e:(Ljava/lang/String;)I" not in replay:
        die("Replay lost its getInteger (func_74762_e) call site.")

    sync = javap(os.path.join(CLASSES, "icwbe", "Sync.class"))
    if "icStream_$eq:(Lcodechicken/lib/packet/PacketCustom;)V" not in sync:
        die("Sync lost the icStream buffer reset call (TraitSetter signature must be "
            "(Lcodechicken/lib/packet/PacketCustom;)V exactly).")
    if "partStream_$eq:(Lcodechicken/lib/packet/PacketCustom;)V" not in sync:
        die("Sync lost the partStream buffer reset call.")
    print("   all call-site assertions passed")


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

    hits = []
    with zipfile.ZipFile(SRC_JAR) as src, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as dstz:
        written = set()
        for info in src.infolist():
            name = info.filename
            if name in REPLACE:
                continue
            data = new_data.get(name, src.read(name))
            if name.endswith(".class"):
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
        if stale:
            die("stale version bytes in: %s" % stale)
        if NEW_VER_B not in z.read("mcmod.info"):
            die("mcmod.info version not patched")
        print("   entries:", len(names), "| integrity OK")
    return dst


def main():
    if not os.path.isfile(os.path.join(HERE, "libs", "ICWbEx-0.2.6.jar")):
        die("run this script from the project root (libs/ICWbEx-0.2.6.jar not found)")
    compile_all()
    assert_call_sites()
    out = assemble()
    print("DONE ->", out)


if __name__ == "__main__":
    main()
