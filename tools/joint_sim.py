# -*- coding: utf-8 -*-
"""
Offline reproduction of "train gets stuck at rail joints" for track-tool's exact path.

Faithfully mirrors (decompiled / source):
  * RTM RailMap.createRailList + addRailBlock         (block list per rail)
  * RTM RailPosition.init / getNeighborBlockPos + REVISION
  * track-tool RailGrid.snap / make / directionFor    (joint rail positions)
  * track-tool ExactRailLayer.placeSegmented / place  (split, setRail take-over, core cell,
                                                       BASE-LINK, JOINT-FILL)
  * RTM EntityBogie.updateBogiePos (front bogie)      (column lookup -> owner -> nearest point, clamped)
Only straight lines are simulated (the reported case); pieces are straight sub-segments.
"""
import math, sys

REV = [(0.0, -0.5), (-0.5, -0.5), (-0.5, 0.0), (-0.5, 0.499999),
       (0.0, 0.499999), (0.499999, 0.499999), (0.499999, 0.0), (0.499999, -0.5)]

def is_int(v): return abs(v - round(v)) < 1e-6
def is_half(v): f = v - math.floor(v); return abs(f - 0.5) < 1e-6
def reachable(x, z): return not (is_half(x) and is_half(z))

def snap(x, z, nx, nz):
    bx = round(x * 2) / 2.0; bz = round(z * 2) / 2.0
    best = None
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            cx, cz = bx + i * .5, bz + j * .5
            if not reachable(cx, cz): continue
            ex, ez = cx - x, cz - z
            lat = ex * nx + ez * nz
            sc = lat * lat * 4 + ex * ex + ez * ez
            if best is None or sc < best[0]: best = (sc, cx, cz)
    return (best[1], best[2]) if best else (bx, bz)

def direction_for(x, z):
    ix, iz = is_int(x), is_int(z)
    if ix and iz: return 1
    if ix: return 2
    if iz: return 0
    return -1

class RP:
    """RailPosition as built by RailGrid.make (dir comes from the lattice point only)."""
    def __init__(self, x, z, dir_=None):
        d = direction_for(x, z) if dir_ is None else dir_
        sx = x
        if d < 0: sx = x + 0.5; d = direction_for(sx, z)
        self.dir = d
        self.bx = math.floor(sx); self.bz = math.floor(z)
        self.px = self.bx + 0.5 + REV[d][0]; self.pz = self.bz + 0.5 + REV[d][1]
    def neighbor(self):
        return (math.floor(self.px + REV[self.dir][0]), math.floor(self.pz + REV[self.dir][1]))

class Piece:
    def __init__(self, idx, g0, g1, rp0, rp1):
        self.idx = idx; self.g0 = g0; self.g1 = g1   # true geometric end points (x, z)
        self.rp0 = rp0; self.rp1 = rp1
        self.len = math.hypot(g1[0] - g0[0], g1[1] - g0[1])
        self.d = ((g1[0] - g0[0]) / self.len, (g1[1] - g0[1]) / self.len)
    def at(self, s): return (self.g0[0] + self.d[0] * s, self.g0[1] + self.d[1] * s)
    def rail_list(self, half_width):
        """RTM createRailList (flat: y constant -> 2D cells)."""
        split = int(self.len * 4.0)
        cells = []
        excl = {self.rp0.neighbor(), self.rp1.neighbor()}
        px, pz = -self.d[1], self.d[0]          # perpendicular
        for j in range(1, split - 1):
            x, z = self.at(self.len * j / split)
            for i in range(0, half_width + 1):
                d0 = i + 0.25
                for sgn in (1, -1):
                    c = (math.floor(x + sgn * px * d0), math.floor(z + sgn * pz * d0))
                    if c not in excl and c not in cells: cells.append(c)
            c = (math.floor(x), math.floor(z))
            if c not in excl and c not in cells: cells.append(c)
        return cells
    def nearest_s(self, p):
        s = (p[0] - self.g0[0]) * self.d[0] + (p[1] - self.g0[1]) * self.d[1]
        s = min(max(s, 0.0), self.len)
        split = int(self.len * 360.0)             # EntityBogie: split = length*360
        idx = round(s / self.len * split)
        return idx / split * self.len

def split_points(L, n, start, d, mode):
    """t_i for i=1..n-1.  mode 'equal' = current code; 'edge' = the fix."""
    ts = []
    for i in range(1, n):
        t_nom = L * i / n
        if mode == 'equal':
            ts.append(t_nom); continue
        best = None
        step = 0.01
        prev = None
        k = -100
        while k <= 100:                          # search +-1 m
            t = t_nom + k * step
            x, z = start[0] + d[0] * t, start[1] + d[1] * t
            cell = (math.floor(x), math.floor(z))
            if prev is not None and cell != prev[1]:
                lo, hi = prev[0], t              # bisect the crossing
                for _ in range(50):
                    mid = (lo + hi) / 2
                    xm, zm = start[0] + d[0] * mid, start[1] + d[1] * mid
                    if (math.floor(xm), math.floor(zm)) == prev[1]: lo = mid
                    else: hi = mid
                tc = hi
                if best is None or abs(tc - t_nom) < abs(best - t_nom): best = tc
            prev = (t, cell)
            k += 1
        ts.append(best if best is not None else t_nom)
    return ts

def build(L, yaw_deg, start, n_pieces, half_width, mode, anchor_dir):
    d = (math.sin(math.radians(yaw_deg)), math.cos(math.radians(yaw_deg)))
    nx, nz = -d[1], d[0]
    ts = [0.0] + split_points(L, n_pieces, start, d, mode) + [L]
    pieces = []
    rp_prev = RP(start[0], start[1], anchor_dir)             # start = copy of the anchor RP
    for i in range(n_pieces):
        g0 = (start[0] + d[0] * ts[i], start[1] + d[1] * ts[i])
        g1 = (start[0] + d[0] * ts[i + 1], start[1] + d[1] * ts[i + 1])
        sx, sz = snap(g1[0], g1[1], nx, nz)
        rp1 = RP(sx, sz)
        pieces.append(Piece(i, g0, g1, rp_prev, rp1))
        rp_prev = RP(sx, sz)
    owner = {}          # cell -> piece idx ;  cores: cell -> idx
    cores = {}
    for p in pieces:
        lst = p.rail_list(half_width)
        if mode == 'equal':
            core = (p.rp0.bx, p.rp0.bz)                     # current: start RP block
        else:
            x1, z1 = p.at(min(1.0, p.len / 2))           # fix: centreline column 1 m into the piece
            core = (math.floor(x1), math.floor(z1))     #      (Y = start RP blockY, see ExactRailLayer.coreCell)
        for c in lst:                                       # setRail take-over (skips other cores)
            if c in cores and cores[c] != p.idx: continue
            owner[c] = p.idx
        if not (core in cores and cores[core] != p.idx):
            cores[core] = p.idx; owner[core] = p.idx
        for c in (p.rp0.neighbor(), p.rp1.neighbor()):      # JOINT-FILL (only empty cells)
            if c not in owner: owner[c] = p.idx
        if mode == 'edge' and p.idx > 0:                    # fix: ownership pass at the joint
            a = pieces[p.idx - 1]
            want = {}
            for k in range(1, 41):
                for piece, s in ((a, a.len - k * 0.05), (p, k * 0.05)):
                    if 0 < s < piece.len:
                        x, z = piece.at(s)
                        c = (math.floor(x), math.floor(z))
                        want.setdefault(c, set()).add(piece.idx)
            for c, sides in want.items():
                if len(sides) != 1: continue
                side = next(iter(sides))
                if c in cores and cores[c] != side: continue
                owner[c] = side
    return pieces, owner

def drive(pieces, owner, v, forward):
    """Front bogie at constant speed across the whole line. Returns list of stuck joints."""
    order = pieces if forward else list(reversed(pieces))
    cur = order[0]
    s = cur.len * (0.3 if forward else 0.7)
    pos = cur.at(s)
    sign = 1 if forward else -1
    stuck = []
    same = 0
    for tick in range(20000):
        p = (pos[0] + sign * cur.d[0] * v, pos[1] + sign * cur.d[1] * v)
        c = (math.floor(p[0]), math.floor(p[1]))
        if c not in owner:
            return stuck + [('FLY', cur.idx)]
        nxt = pieces[owner[c]]
        cur = nxt
        s = cur.nearest_s(p)
        newpos = cur.at(s)
        if math.hypot(newpos[0] - pos[0], newpos[1] - pos[1]) < 1e-9:
            same += 1
            if same >= 5:
                stuck.append(('STUCK', cur.idx)); return stuck
        else:
            same = 0
        pos = newpos
        # done?
        if forward and cur.idx == len(pieces) - 1 and s > cur.len - 0.5: return stuck
        if not forward and cur.idx == 0 and s < 0.5: return stuck
    return stuck + [('TIMEOUT', cur.idx)]

def min_escape(pieces, owner, forward):
    """Smallest speed (m/tick) at which the bogie gets through every joint."""
    for vv in [x / 100.0 for x in range(1, 150)]:
        if not drive(pieces, owner, vv, forward):
            return vv
    return None

if __name__ == '__main__':
    cases = [
        ('x+  L=97 ', 97.0, 90.0, (100.0, 50.5), 6),
        ('x-  L=97 ', 97.0, 270.0, (100.0, 50.5), 2),
        ('z+  L=50 ', 50.0, 0.0, (20.5, 7.0), 4),
        ('diag45 L=60', 60.0, 45.0, (10.0, 10.0), 5),
        ('yaw 30 L=80', 80.0, 30.0, (0.0, 0.5), 6),
        ('yaw 63 L=120', 120.0, 63.0, (0.0, 0.5), 6),
    ]
    hw = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    print('ballast halfWidth=%d   (min speed in m/tick to get through every joint; 0.01 = never stuck)' % hw)
    for mode in ('equal', 'edge'):
        print('--- mode=%s ---' % mode)
        for name, L, yaw, st, adir in cases:
            n = max(1, math.ceil(L / 20.0))
            pieces, owner = build(L, yaw, st, n, hw, mode, adir)
            f = min_escape(pieces, owner, True); b = min_escape(pieces, owner, False)
            s_f = drive(pieces, owner, 0.10, True); s_b = drive(pieces, owner, 0.10, False)
            fmt = lambda v: ('%.2f m/t (%3.0f km/h)' % (v, v * 72)) if v else 'never'
            print('%-13s pieces=%d  forward: %-22s backward: %-22s  @0.10 m/t: fwd %s bwd %s' % (
                name, n, fmt(f), fmt(b), s_f or 'ok', s_b or 'ok'))
