#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Audit an ICWbEx blueprint (or a plain IC NBT) for inconsistent wiring.

Why: an IC blueprint stores each part's `connMap` -- the bitmask of the four
sides the part believes it is connected to.  Nothing recomputes that mask when
the board is loaded (IntegratedCircuit.load() goes through setPart_do() +
part.load() and never calls CircuitPart.onAdded()), so whatever the file says is
what the game believes.  A board whose masks disagree shows up as "the wire is
there but it does not conduct", which is very hard to diagnose by eye.

This tool re-derives the truth from the coordinates and reports the
disagreements:

    python bp_check.py 蓝图.txt
    python bp_check.py _blueprint.nbt --json

Connection rules used (from ProjectRed 4.12.44's bytecode):

    * wire <-> wire          always, except insulated <-> insulated which needs
                             the same `colour`
    * wire <-> gate          always (a gate decides its own sides via
                             GateLogic.canConnectTo)
    * gate <-> gate          never

A pair counts as broken when the two parts disagree about the same border
(one has the bit, the other does not), because a connection is a property of
the border, not of one side.  Legitimately-disconnected borders (different
insulated colours, gate-to-gate) are reported separately.
"""
import argparse
import collections
import gzip
import json
import struct
import sys

WIRE = {3, 4, 5}
GATE = {6, 7, 8, 9}
PART_NAMES = {0: "Torch", 1: "Lever", 2: "Button", 3: "AlloyWire",
              4: "InsulatedWire", 5: "BundledCable", 6: "IOGate",
              7: "SimpleGate", 8: "ComplexGate", 9: "ArrayGate"}

# r0 = north (0,-1), r1 = east, r2 = south, r3 = west.  Verified empirically:
# these four mappings were tried and this one is the only one that yields zero
# corner-turning contradictions on a real 1392-part board.
DIRS = {0: (0, -1), 1: (1, 0), 2: (0, 1), 3: (-1, 0)}


# --------------------------------------------------------------------------
# NBT
# --------------------------------------------------------------------------
class NBT(object):
    def __init__(self, data):
        self.d = data
        self.p = 0

    def _take(self, n):
        v = self.d[self.p:self.p + n]
        self.p += n
        return v

    def u1(self):
        return self._take(1)[0]

    def u2(self):
        return struct.unpack(">H", self._take(2))[0]

    def i4(self):
        return struct.unpack(">i", self._take(4))[0]

    def i8(self):
        return struct.unpack(">q", self._take(8))[0]

    def string(self):
        n = self.u2()
        return self._take(n).decode("utf-8", "replace")

    def payload(self, t):
        if t == 1:
            return self.u1()
        if t == 2:
            return struct.unpack(">h", self._take(2))[0]
        if t == 3:
            return self.i4()
        if t == 4:
            return self.i8()
        if t == 5:
            return struct.unpack(">f", self._take(4))[0]
        if t == 6:
            return struct.unpack(">d", self._take(8))[0]
        if t == 7:
            return self._take(self.i4())
        if t == 8:
            return self.string()
        if t == 9:
            et = self.u1()
            return [self.payload(et) for _ in range(self.i4())]
        if t == 10:
            out = {}
            while True:
                tt = self.u1()
                if tt == 0:
                    return out
                # Read the name BEFORE the value; `out[read_name()] = value()`
                # would evaluate the value first and misalign the stream.
                key = self.string()
                out[key] = self.payload(tt)
        if t == 11:
            return [self.i4() for _ in range(self.i4())]
        raise ValueError("unknown tag type %d at %d" % (t, self.p))


def load_any(path):
    """Read an ICWbEx blueprint (.txt / share code) or a raw/zipped NBT."""
    raw = open(path, "rb").read()
    text = None
    try:
        text = raw.decode("utf-8").strip()
    except UnicodeDecodeError:
        pass

    if text and text.startswith("ICWBE2:"):
        import base64
        body = text[len("ICWBE2:"):]
        body = body.replace("-", "+").replace("_", "/")
        body += "=" * (-len(body) % 4)
        data = gzip.decompress(base64.b64decode(body))
        return unpack(NBT(data))
    if raw[:2] == b"\x1f\x8b":
        return unpack(NBT(gzip.decompress(raw)))
    return unpack(NBT(raw))


def unpack(reader):
    t = reader.u1()
    reader.string()
    root = reader.payload(t)
    # A blueprint is deduplicated: `tpls` holds unique part tags, `pidx` maps
    # each tile back to its template, `px`/`py` carry the coordinates.
    if "tpls" in root and "pidx" in root:
        tiles = []
        for i, idx in enumerate(root["pidx"]):
            part = dict(root["tpls"][idx])
            part["_x"] = root["px"][i]
            part["_y"] = root["py"][i]
            tiles.append(part)
        return {"name": root.get("name"), "sw": root.get("sw"),
                "sh": root.get("sh"), "parts": tiles, "deduped": True}
    parts = []
    for tag in root.get("parts", []):
        part = dict(tag)
        part["_x"] = tag.get("xpos", 0) & 0xFF
        part["_y"] = tag.get("ypos", 0) & 0xFF
        parts.append(part)
    return {"name": root.get("name"), "sw": root.get("sw"),
            "sh": root.get("sh"), "parts": parts, "deduped": False}


# --------------------------------------------------------------------------
# checks
# --------------------------------------------------------------------------
def connects(part, other):
    """Do these two adjacent parts connect at all (ignoring connMap)?"""
    a, b = part["id"], other["id"]
    if a in WIRE and b in WIRE:
        if a == 4 and b == 4:
            return (part.get("colour", 0) & 0xFF) == (other.get("colour", 0) & 0xFF)
        return True
    if (a in WIRE) != (b in WIRE):
        return True
    return False


def check(board):
    grid = collections.defaultdict(list)
    for p in board["parts"]:
        grid[(p["_x"], p["_y"])].append(p)

    duplicated = {k: v for k, v in grid.items() if len(v) > 1}
    asym, missed, phantom = [], [], []
    done = set()
    for (x, y), parts in grid.items():
        for part in parts:
            if "connMap" not in part:
                continue
            for r, (dx, dy) in DIRS.items():
                nx, ny = x + dx, y + dy
                nb = grid.get((nx, ny))
                bit = (part["connMap"] >> r) & 1
                if not nb:
                    if bit:
                        phantom.append((x, y, PART_NAMES.get(part["id"], part["id"]), r))
                    continue
                pair = (min((x, y), (nx, ny)), max((x, y), (nx, ny)))
                if pair in done:
                    continue
                done.add(pair)
                other = nb[0]
                obit = (other.get("connMap", 0) >> ((r + 2) % 4)) & 1
                if bit != obit:
                    asym.append((x, y, PART_NAMES.get(part["id"], part["id"]), r,
                                 nx, ny, PART_NAMES.get(other["id"], other["id"]),
                                 bit, obit))
                elif not bit and connects(part, other):
                    missed.append((x, y, PART_NAMES.get(part["id"], part["id"]), r,
                                   nx, ny, PART_NAMES.get(other["id"], other["id"])))
    return {"parts": len(board["parts"]),
            "overlapping": {str(k): [PART_NAMES.get(p["id"], p["id"]) for p in v]
                            for k, v in duplicated.items()},
            "asymmetric": asym, "should_connect_but_open": missed,
            "phantom_connections": phantom}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    board = load_any(a.file)
    rep = check(board)
    if a.json:
        print(json.dumps(rep, indent=2, ensure_ascii=False))
        return 0 if not (rep["asymmetric"] or rep["overlapping"]
                         or rep["phantom_connections"]) else 1

    print("file   :", a.file)
    print("board  : %sx%s, %d parts%s" % (
        board["sw"], board["sh"], rep["parts"],
        " (deduplicated)" if board["deduped"] else ""))
    print("name   :", board["name"])
    print()
    print("overlapping parts :", len(rep["overlapping"]))
    for k, v in list(rep["overlapping"].items())[:5]:
        print("   ", k, v)
    print("asymmetric links  :", len(rep["asymmetric"]))
    for row in rep["asymmetric"][:10]:
        print("    (%d,%d) %s r%d  <->  (%d,%d) %s   bits %d/%d" % row)
    # Informational: a wire next to a gate with the border left open.  Most of
    # these are correct -- a gate only connects on the sides its logic uses --
    # but this is the list to eyeball when a specific link "looks connected and
    # is not".
    print("open wire/gate borders (review by eye) :",
          len(rep["should_connect_but_open"]))
    for row in rep["should_connect_but_open"][:10]:
        print("    (%d,%d) %s r%d  <->  (%d,%d) %s" % row)
    print("phantom (bit set, no neighbour) :", len(rep["phantom_connections"]))
    for row in rep["phantom_connections"][:10]:
        print("    (%d,%d) %s r%d" % row)
    bad = rep["asymmetric"] or rep["overlapping"] or rep["phantom_connections"]
    print()
    print("VERDICT:", "INCONSISTENT" if bad else "consistent")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
