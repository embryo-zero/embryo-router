#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0 (Embryo Router, 2026-11-16)
"""Embryo Chip Router test designs (2026-11-16): places SKY130 standard cells in rows and connects
each cell's output to inputs of nearby cells, like a small logic block, then writes a placed DEF.

usage: make_test_chip.py cells.lef out.def [rows] [sites per row] [seed]
"""
import random
import re
import sys

CELLS = ["sky130_fd_sc_hd__inv_1", "sky130_fd_sc_hd__nand2_1", "sky130_fd_sc_hd__nor2_1", "sky130_fd_sc_hd__a21oi_1",
         "sky130_fd_sc_hd__xor2_1", "sky130_fd_sc_hd__dfxtp_1", "sky130_fd_sc_hd__buf_1", "sky130_fd_sc_hd__o21ai_0",
         "sky130_fd_sc_hd__nand3_1", "sky130_fd_sc_hd__mux2_1"]
SITE_W, ROW_H = 460, 2720          # dbu (1000 per micron)


def read_lef(path):
    text = open(path).read()
    out = {}
    for m in re.finditer(r"^MACRO (\S+)\n(.*?)^END \1", text, re.S | re.M):
        name, body = m.group(1), m.group(2)
        if name not in CELLS:
            continue
        w = float(re.search(r"SIZE ([\d.]+) BY", body).group(1))
        ins, outs = [], []
        for p in re.finditer(r"PIN (\S+)\n(.*?)END \1", body, re.S):
            if "USE SIGNAL" not in p.group(2) and "USE CLOCK" not in p.group(2):
                continue
            (outs if "DIRECTION OUTPUT" in p.group(2) else ins).append(p.group(1))
        out[name] = (round(w * 1000), ins, outs)
    return out


def main():
    lef, out = sys.argv[1], sys.argv[2]
    rows = int(sys.argv[3]) if len(sys.argv) > 3 else 6
    sites = int(sys.argv[4]) if len(sys.argv) > 4 else 60
    rnd = random.Random(int(sys.argv[5]) if len(sys.argv) > 5 else 1)
    cells = read_lef(lef)
    x0, y0 = 12 * SITE_W, 4 * ROW_H // 2       # room around the core for the I/O pins
    die_w, die_h = x0 * 2 + sites * SITE_W, y0 * 2 + rows * ROW_H
    comps = []
    for r in range(rows):
        x = 0
        while True:
            name = rnd.choice(list(cells))
            w = cells[name][0]
            gap = rnd.choice([0, 0, 1, 2]) * SITE_W
            if x + gap + w > sites * SITE_W:
                break
            for g in range(gap // SITE_W):       # filler cells keep the wells continuous, as on a real chip
                comps.append((f"fill{len(comps)}", "sky130_fd_sc_hd__fill_1", x0 + x, y0 + r * ROW_H, "N" if r % 2 == 0 else "FS", r))
                x += SITE_W
            comps.append((f"u{len(comps)}", name, x0 + x, y0 + r * ROW_H, "N" if r % 2 == 0 else "FS", r))
            x += w
    # nets: each output drives 1-3 free inputs among the next/previous few cells (rows wrap into neighbours)
    free_inputs = [(c[0], p) for c in comps if c[1] in cells for p in cells[c[1]][1]]
    rnd.shuffle(free_inputs)
    pos = {c[0]: (c[2], c[3]) for c in comps}
    nets = []
    taken = set()
    for c in comps:
        if c[1] not in cells:
            continue
        for o in cells[c[1]][2]:
            cx, cy = pos[c[0]]
            near = sorted((abs(pos[i[0]][0] - cx) + abs(pos[i[0]][1] - cy), i) for i in free_inputs
                          if i not in taken and i[0] != c[0])
            k = rnd.choice([1, 1, 2, 2, 3])
            pick = [i for _, i in near[:12]]
            rnd.shuffle(pick)
            sinks = pick[:k]
            if not sinks:
                continue
            taken.update(sinks)
            nets.append((f"n{len(nets)}", [(c[0], o)] + sinks))
    # a few I/O pins on met2 at the bottom and top edges, each driving a free input
    pins = []
    left = [i for i in free_inputs if i not in taken]
    rnd.shuffle(left)
    for k in range(min(8, len(left))):
        px = x0 + (k + 1) * (sites * SITE_W) // 9
        px = px - px % 460 + 230                     # on a met2 track
        py = 0 if k % 2 == 0 else die_h
        name = f"io{k}"
        pins.append((name, px, py, "N" if k % 2 == 0 else "S"))
        nets.append((f"io_net{k}", [("PIN", name), left[k]]))
    with open(out, "w") as f:
        f.write("VERSION 5.8 ;\nDIVIDERCHAR \"/\" ;\nBUSBITCHARS \"[]\" ;\nDESIGN embryo_test ;\nUNITS DISTANCE MICRONS 1000 ;\n")
        f.write(f"DIEAREA ( 0 0 ) ( {die_w} {die_h} ) ;\n")
        for r in range(rows):
            f.write(f"ROW ROW_{r} unithd {x0} {y0 + r * ROW_H} {'N' if r % 2 == 0 else 'FS'} DO {sites} BY 1 STEP {SITE_W} 0 ;\n")
        # fill the end of each row too
        for r in range(rows):
            used = max([c[2] + cells.get(c[1], (SITE_W,))[0] if c[1] in cells else c[2] + SITE_W for c in comps if c[5] == r] or [x0])
            while used + SITE_W <= x0 + sites * SITE_W:
                comps.append((f"fill{len(comps)}", "sky130_fd_sc_hd__fill_1", used, y0 + r * ROW_H, "N" if r % 2 == 0 else "FS", r))
                used += SITE_W
        f.write(f"COMPONENTS {len(comps)} ;\n")
        for name, macro, x, y, o, _ in comps:
            f.write(f"- {name} {macro} + PLACED ( {x} {y} ) {o} ;\n")
        f.write("END COMPONENTS\n")
        f.write(f"PINS {len(pins)} ;\n")
        for name, px, py, o in pins:
            # a 0.28 x 1.0 um met2 pin on the die edge, pointing inwards
            f.write(f"- {name} + NET io_net{name[2:]} + DIRECTION INPUT + USE SIGNAL\n"
                    f"  + LAYER met2 ( -140 0 ) ( 140 1000 )\n  + PLACED ( {px} {py} ) {o} ;\n")
        f.write("END PINS\n")
        f.write(f"NETS {len(nets)} ;\n")
        for name, terms in nets:
            f.write(f"- {name} " + " ".join(f"( {a} {b} )" for a, b in terms) + " ;\n")
        f.write("END NETS\nEND DESIGN\n")
    print(f"{out}: {len(comps)} cells in {rows} rows, {len(nets)} nets, die {die_w / 1000} x {die_h / 1000} um")


if __name__ == "__main__":
    main()
