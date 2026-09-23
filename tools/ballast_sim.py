# -*- coding: utf-8 -*-
"""Lateral ballast gaps at piece joints: current fills vs a full-width fill.
Ideal footprint = every block touched by the continuous centre line and the lateral lines at
RTM's offsets (i + 0.25, i = 0..halfWidth, both sides) over the whole line."""
import math, importlib.util, os
spec = importlib.util.spec_from_file_location('js', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'joint_sim.py'))
js = importlib.util.module_from_spec(spec); spec.loader.exec_module(js)

def offsets(hw):
    return [0.0] + [s * (i + 0.25) for i in range(hw + 1) for s in (1, -1)]

def cells_along(p, s0, s1, hw, step):
    px, pz = -p.d[1], p.d[0]
    out = set()
    s = s0
    while s <= s1 + 1e-9:
        x, z = p.at(s)
        for o in offsets(hw):
            out.add((math.floor(x + px * o), math.floor(z + pz * o)))
        s += step
    return out


def seg_cells(x0, z0, x1, z1):
    """Every grid cell the segment passes through (supercover DDA)."""
    out = set()
    cx, cz = math.floor(x0), math.floor(z0); ex, ez = math.floor(x1), math.floor(z1)
    out.add((cx, cz))
    dx, dz = x1 - x0, z1 - z0
    sx = 1 if dx > 0 else -1; sz = 1 if dz > 0 else -1
    tdx = abs(1.0 / dx) if dx != 0 else float('inf'); tdz = abs(1.0 / dz) if dz != 0 else float('inf')
    tmx = ((cx + (1 if dx > 0 else 0)) - x0) / dx if dx != 0 else float('inf')
    tmz = ((cz + (1 if dz > 0 else 0)) - z0) / dz if dz != 0 else float('inf')
    for _ in range(1000):
        if (cx, cz) == (ex, ez): break
        if tmx < tmz: tmx += tdx; cx += sx
        elif tmz < tmx: tmz += tdz; cz += sz
        else:                                   # exact corner: include both side cells
            out.add((cx + sx, cz)); out.add((cx, cz + sz)); tmx += tdx; tmz += tdz; cx += sx; cz += sz
        if tmx > 1.0 + 1e-12 and tmz > 1.0 + 1e-12 and (cx, cz) != (ex, ez):
            out.add((cx, cz)); break
        out.add((cx, cz))
    out.add((ex, ez))
    return out

def traverse(p, hw):
    px, pz = -p.d[1], p.d[0]
    out = set()
    n = max(1, int(math.ceil(p.len / 0.25)))
    for o in offsets(hw):
        prev = None
        for k in range(n + 1):
            x, z = p.at(p.len * k / n); x += px * o; z += pz * o
            if prev: out |= seg_cells(prev[0], prev[1], x, z)
            prev = (x, z)
    return out

def build(L, yaw, st, hw, full_fill):
    d = (math.sin(math.radians(yaw)), math.cos(math.radians(yaw))); nx, nz = -d[1], d[0]
    n = max(1, math.ceil(L / 20.0))
    ts = [0.0] + js.split_points(L, n, st, d, 'edge') + [L]
    pieces = []; rp_prev = js.RP(*js.snap(st[0], st[1], nx, nz))
    for i in range(n):
        g0 = (st[0] + d[0] * ts[i], st[1] + d[1] * ts[i]); g1 = (st[0] + d[0] * ts[i + 1], st[1] + d[1] * ts[i + 1])
        sx, sz = js.snap(g1[0], g1[1], nx, nz)
        pieces.append(js.Piece(i, g0, g1, rp_prev, js.RP(sx, sz))); rp_prev = js.RP(sx, sz)
    have = set()
    for p in pieces:
        have |= set(p.rail_list(hw))                                  # RTM list (after exclusions)
        for c in (p.rp0.neighbor(), p.rp1.neighbor()):                  # JOINT-FILL (new rule: fills terrain)
            have.add(c)
        have |= cells_along(p, 0.05, p.len - 0.05, 0, 0.1)            # CENTER-FILL (centre line only)
    if full_fill:                                                   # proposed: ONE pass over the whole line
        for p in pieces:                                                # after every piece is placed; every block
            have |= traverse(p, hw)                                      # the centre/lateral lines touch gets filled
    ideal = set()
    for p in pieces:
        ideal |= cells_along(p, 0.0, p.len, hw, 0.01)
    # ignore the two free ends of the whole line (nothing is expected beyond them)
    return ideal, have, pieces

for hw in (1, 2):
    print('=== ballast halfWidth=%d ===' % hw)
    for yaw in (0, 15, 30, 37, 45, 60, 75, 90, 120, 200, 333):
        for full in (False, True):
            ideal, have, pieces = build(120.0, yaw, (0.3, 0.7), hw, full)
            miss = sorted(ideal - have)
            # exclude cells whose centre lies beyond the line's two free ends
            p0, pl = pieces[0], pieces[-1]
            def inside(c):
                cx, cz = c[0] + .5, c[1] + .5
                a0 = (cx - p0.g0[0]) * p0.d[0] + (cz - p0.g0[1]) * p0.d[1]
                a1 = (cx - pl.g1[0]) * pl.d[0] + (cz - pl.g1[1]) * pl.d[1]
                return a0 >= 0 and a1 <= 0
            miss = [c for c in miss if inside(c)]
            if not full:
                cur = len(miss)
            else:
                print('  yaw %3d: current code missing %2d ballast cells -> with full-width fill %d' % (yaw, cur, len(miss)))
