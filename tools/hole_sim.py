# -*- coding: utf-8 -*-
"""Holes at joints on real terrain: old JOINT-FILL (air only) vs new CENTER-FILL.
Reuses tools/joint_sim.py; adds the existing anchor rail behind the start."""
import math, importlib.util, sys
import os
spec = importlib.util.spec_from_file_location('js', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'joint_sim.py'))
js = importlib.util.module_from_spec(spec); spec.loader.exec_module(js)

class AnchorRP(js.RP):
    """RP at an exact lattice position with a given direction (native RTM anchor)."""
    def __init__(self, x, z, d):
        self.dir = d
        self.bx = round(x - 0.5 - js.REV[d][0]); self.bz = round(z - 0.5 - js.REV[d][1])
        self.px = self.bx + 0.5 + js.REV[d][0]; self.pz = self.bz + 0.5 + js.REV[d][1]
        assert abs(self.px - x) < 1e-5 and abs(self.pz - z) < 1e-5, (x, z, d, self.px, self.pz)

def build(L, yaw, st, adir, hw, terrain, center_fill):
    d = (math.sin(math.radians(yaw)), math.cos(math.radians(yaw))); nx, nz = -d[1], d[0]
    n = max(1, math.ceil(L / 20.0))
    ts = [0.0] + js.split_points(L, n, st, d, 'edge') + [L]
    anchor_rp = AnchorRP(st[0], st[1], adir)            # faces our way (outward from the old rail)
    # the existing rail: 20 m behind the start; its end RP is the anchor RP (native)
    back = (st[0] - d[0] * 20, st[1] - d[1] * 20)
    sb = js.snap(back[0], back[1], nx, nz)
    old = js.Piece(-1, back, st, js.RP(sb[0], sb[1]), anchor_rp); old.idx = 0
    pieces = [old]
    rp_prev = AnchorRP(st[0], st[1], adir)              # our start RP = copy of the anchor RP
    for i in range(n):
        g0 = (st[0] + d[0] * ts[i], st[1] + d[1] * ts[i]); g1 = (st[0] + d[0] * ts[i + 1], st[1] + d[1] * ts[i + 1])
        sx, sz = js.snap(g1[0], g1[1], nx, nz)
        p = js.Piece(i + 1, g0, g1, rp_prev, js.RP(sx, sz)); pieces.append(p); rp_prev = js.RP(sx, sz)
    owner, cores = {}, {}
    for p in pieces:
        lst = p.rail_list(hw)
        x1, z1 = p.at(min(1.0, p.len / 2)); core = (math.floor(x1), math.floor(z1))
        for c in lst:
            if c in cores and cores[c] != p.idx: continue
            owner[c] = p.idx
        if not (core in cores and cores[core] != p.idx): cores[core] = p.idx; owner[core] = p.idx
        if p.idx == 0: continue                          # the old rail: already there, untouched
        for c in (p.rp0.neighbor(), p.rp1.neighbor()):   # JOINT-FILL
            if c not in owner and not terrain: owner[c] = p.idx   # old rule: only fills air
        if center_fill:                                   # new rule: every centreline column gets a rail block
            s = 0.05
            while s <= p.len - 0.05 + 1e-9:
                x, z = p.at(s); c = (math.floor(x), math.floor(z))
                if c not in owner: owner[c] = p.idx
                s += 0.1
        if p.idx > 1:                                     # JOINT-OWNER pass (ownership only)
            a = pieces[p.idx - 1]; want = {}
            for k in range(1, 41):
                for piece, s in ((a, a.len - k * 0.05), (p, k * 0.05)):
                    if 0 < s < piece.len:
                        x, z = piece.at(s); want.setdefault((math.floor(x), math.floor(z)), set()).add(piece.idx)
            for c, sides in want.items():
                if len(sides) == 1 and c in owner and not (c in cores and cores[c] != next(iter(sides))):
                    owner[c] = next(iter(sides))
    return pieces, owner

def holes(pieces, owner):
    out = set()
    for p in pieces:
        s = 0.05
        while s <= p.len - 0.05:
            x, z = p.at(s); c = (math.floor(x), math.floor(z))
            if c not in owner: out.add((c, p.idx))
            s += 0.1
    return sorted(out)

cases = [('+x', 97.0, 90.0, (100.0, 50.5), 6), ('-x', 97.0, 270.0, (100.0, 50.5), 2),
         ('+z', 60.0, 0.0, (20.5, 7.0), 4), ('-z', 60.0, 180.0, (20.5, 7.0), 0),
         ('diag45', 60.0, 45.0, (10.0, 10.0), 5), ('yaw 30', 80.0, 30.0, (0.0, 0.5), 6)]
for label, terrain, cf in (('superflat (rail level = air), old code', False, False),
                           ('real terrain (flowers/grass at rail level), old code', True, False),
                           ('real terrain, NEW centre-line fill', True, True)):
    print('=== %s ===' % label)
    for name, L, yaw, st, adir in cases:
        pieces, owner = build(L, yaw, st, adir, 1, terrain, cf)
        h = holes(pieces, owner)
        f = js.drive(pieces, owner, 0.3, True); b = js.drive(pieces, owner, 0.3, False)
        where = ', '.join('piece%d@%s' % (i, c) for c, i in h[:4])
        print('  %-7s holes=%d %-40s drive fwd:%-14s bwd:%s' % (name, len(h), where, (f or 'ok'), (b or 'ok')))
