# track-tool

**A track-laying assistant for RealTrainMod (RTM)** — lay rails with real railway alignment (spiral + circular curve + spiral, outer-rail superelevation, vertical curves) instead of hand-placing marker after marker.

**Languages:** [中文](README.md) · **English** · [日本語](README.ja.md)

| | |
|---|---|
| Minecraft | 1.12.2 |
| Loader | Forge 14.23.5 (compiled against 2768, tested on 2847 / 2860) |
| Requires | **RealTrainMod 2.4.24** + **NGTLib 2.4.21** |
| modid / version | `tracktool` / 0.1.0 |
| Java | 8 |

> Install it on **both client and server**, and use the **same jar**. Parameter packets carry a version; if the two sides disagree the server rejects the request and prints both versions.

---

## What problem it solves

RTM's native way of laying track is to place two markers and connect them with a **Bezier curve**, with anchors quantised to a half-block grid x 8 directions. That is fine for straights and wide curves, but as soon as you want railway-standard alignment three problems appear:

1. **No transition (spiral) curves** - curvature jumps from 0 to 1/R, so vehicles transition harshly;
2. **Kinks at joints** - each piece fits its own Bezier, and anchor quantisation makes neighbouring tangents disagree;
3. **No superelevation ramp** - cant is either absent or changes abruptly at joints.

track-tool decides the alignment **entirely from its own analytic geometry**; RTM is left to do what it is good at: rendering and running trains.

```
user parameters --> plan (pure maths) --> ExactLine (implements NGTLib's ILine)
                                            |
                                            +--> reflectively swap the two ILine fields
                                                 inside RailMapBasic
                                                 (object type stays RailMapBasic, so the
                                                  renderer and vehicles keep working)
```

The result: zero quantisation, continuous tangents across joints, and superelevation ramps that follow the design - with RTM none the wiser.

---

## Usage

### 0. Before you start

1. Install **the same jar** on client and server (`mods/`); versions must match;
2. Make sure **RealTrainMod 2.4.24** + **NGTLib 2.4.21** are present;
3. The server side needs **OP** (permission level 2); single player needs nothing.

> **Back up your world first.** Laying track modifies blocks, and RTM reshapes the ballast.

### 1. Get the staff

Creative inventory -> tab **"RTM Track Laying"** -> take the **Track Staff** (`tracktool:rail_staff`).

### 2. Select an endpoint

**Right-click the very end of an existing rail.** Hitting the middle of a rail answers "please select the rail end" on purpose. Right-click the same end again (or `/tracktool cancel`, or **ESC**) to deselect.

### 3. Open the parameter panel

Press **G** (rebindable) or use `/tracktool gui`. The panel sits in the right third of the screen and contains the three modes (**straight / curve / connect**), the parameters (radius, turn angle, cant, transition length or automatic, rise, vertical-curve radius, parallel lines and spacing), a preview toggle, and the confirm / undo / back / cancel buttons.

### 4. Lay your first curve

| Parameter | Suggested | Note |
|---|---|---|
| Mode | curve | |
| Radius | 300 | metres |
| Turn | 45 | degrees; use 90 to make it obvious |
| Transition | automatic | taken from the GB 50090 reference table by radius |
| Cant | 60 | or leave 0 |
| Rise / vertical curve | 0 / default | leave alone at first |

After confirming, the chat reports `laid: N rail cores, M blocks`. Walk along it: curvature ramps in the spirals, stays constant in the circular arc, and the joints have no kinks; the outer rail is raised and the ramp completes inside the spirals.

### 5. Undo

The panel's **undo** button or `/tracktool undo` (8 steps by default, `maxUndo` in the config).

---

## Advanced usage

- **Connect mode** - select two rail ends and it solves a straight-spiral-arc-spiral-straight that closes **exactly** (position error 0.000 m, heading error 0.000 deg). If the two ends are parallel but laterally offset (an S shape) there is no solver yet, and it reports an error instead of laying something wrong.
- **Parallel tracks** - set the number of offsets and the spacing; offsets are computed exactly (`ds' = (1 - kd) ds`), not approximated.
- **Superelevation** - linear ramp in the spirals, constant on the arc, **capped at 10 degrees (about 260 mm)**. Above that RTM tilts the ballast top so much that the ballast becomes a wall and buries the rails.
- **Vertical curves** - rise plus vertical-curve radius gives a smooth gradient change.
- **Long lines** - laid over several ticks (`segmentsPerTick` / `blocksPerTick`); keep `forceLoadChunks` on to cross unloaded chunks.
- **One core per line** - `/tracktool exact seg 0` for a single core, `/tracktool exact seg 30` for 30 m cores, `/tracktool exact false` to fall back to RTM's native segmented path for A/B comparison.

---

## When something looks wrong

| Symptom | Check first |
|---|---|
| **A train will not move / cannot find the track** | `/tracktool test railcheck` walks the same lookup chain a train uses (base / core / RailMap counters); add `fix` to fill gaps in place |
| **Joints look uneven** | `/tracktool test joints` reports render height differences and cant collapse inside a piece |
| **No preview / rendering looks wrong** | `/tracktool test clientcheck` prints, per core, the geometry source, length, rail-versus-ballast deviation and ballast top height |
| **Confirm does nothing** | `/tracktool status` for selection and parameters; make sure the server has the same jar |
| **Server rejects with a version mismatch** | Install the same jar on both sides |
| **Want to verify the maths** | `/tracktool exact selftest` checks length, end heading and cant without laying anything |

Diagnostics are printed to chat **and** to `latest.log`.

### Offline checks (no game needed)

`rail/plan` is pure maths and does not depend on Minecraft:

```bash
gradlew selfTest       # geometry / codec / RailMap consistency
gradlew connectTest    # connect-mode closure error
```

---

## Commands

```
/tracktool <confirm|back|undo|cancel|status|mode|param|plan|help>
```

Permission level 2. `mode` takes `straight|curve|connect`; `param <name> <value>` mirrors each panel field.

Diagnostic commands: `/tracktool test joints [radius]`, `/tracktool test clientcheck`, `/tracktool test railcheck [n] [fix]`, `/tracktool test cell`, `/tracktool exact [true|false]`, `/tracktool exact seg <metres>`, `/tracktool exact selftest`.

---

## Configuration

`config/tracktool.cfg`:

| Key | Default | Meaning |
|---|---|---|
| `selectDistance` | 16 | staff ray distance (blocks) |
| `segmentLength` | 20.0 | metres of line per RTM rail core |
| `blocksPerTick` | 3000 | blocks written per tick while laying |
| `forceLoadChunks` | true | force-load chunks along long lines |
| `previewEnabled` | true | draw the blue translucent preview |
| `maxUndo` | 8 | undo steps kept per player |
| `guiBackdrop` / `guiBackdropAlpha` | true / 140 | panel backdrop and opacity |

---

## Code layout

```
com.tracktool
+-- rail/plan/     pure maths alignment, no Minecraft or RTM dependency, runs offline
|     Alignment / LineElement / ArcElement / SpiralElement
|     CantProfile / VerticalProfile
|     ConnectSolver           closure solver for connect mode
|     PlanBuilder             parameters -> alignment
+-- rail/          picking, snapping, placing
|     RailRayTrace / SelectionManager
|     RailGrid                RTM's half-block x 8-direction grid
|     RailStandards           GB 50090 reference tables
|     RailPlacer              RTM's native placement path (reference / fallback)
+-- rail2/         analytic geometry path (the main path)
|     ExactLine               analytic geometry as NGTLib's ILine
|     ExactRailInjector       swap the two ILine fields inside RailMapBasic
|     ExactRailLayer          core placement, segmentation, block table, joint fill
|     PlanGeometry / OffsetGeometry / SubGeometry / SampledGeometry
|     ExactRailPersistence    sampled point table in NBT (re-inject after load)
|     ExactRailServerSync     re-inject after load, resend points to clients
|     BrokenCoreSweeper       remove empty cores that would NPE the server
+-- client/       GUI, preview rendering, client-side tick injection
+-- net/          packets
+-- core/         ASM coremod skeleton, NOT enabled (transformer returns unchanged bytes)
```

---

## Building

Requires JDK 8.

```bash
gradlew build          # output in build/libs/tracktool-0.1.0.jar
```

`libs/` needs two **compile-time** dependencies:

```
libs/RTM-2.4.24-43.jar
libs/NGTLib-2.4.21-38.jar
```

They are `compileOnly` and are never packaged into the output.

> **These two jars are third-party works. Do not commit them and do not redistribute them with this project.** Get them from the official RTM / NGTLib release channels and drop them into `libs/`.

---

## Hard rules for contributors

Learned the hard way - read before changing code:

1. **Never replace RailMapBasic with a subclass.** RTM's render path makes hard assumptions about the railmap implementation; a subclass makes the track **invisible**. Only the two internal `ILine` fields (`lineHorizontal` / `lineVertical`) may be swapped.
2. **Never set `RailPosition.scriptName`.** `createRailMap()` will then build a `RailMapCustom`, and the script engine is null, giving `Script exec error`.
3. **Never reference a client-only type from a common class.** Even unreachable code makes the JVM load that type when the class is verified, which kills the class on a dedicated server with `NoClassDefFoundError`. Keep client code in `com.tracktool.client` and call it reflectively.
4. **Snapshot `world.loadedTileEntityList` before iterating.** Calling `world.getTileEntity(...)` inside the loop makes the client instantiate TEs and add them to that list, giving `ConcurrentModificationException`.
5. **Once a rail core block exists, `railPositions` must be written.** RTM's `writeRailData` reads `railPositions[0]` without a null check, so a core with a block but no data NPEs the server on the next packet and can crash the save. Both placement and undo need a `try/finally` guard.
6. **Check the bytecode before trusting units or field order.** For example `RailMap.getRailPos` returns `[Z, X]` (not `[X, Z]`), `ILine.getSlope` must return **radians**, and `NGTMath.sin` takes **degrees**.
7. **Changing a packet field means `TrackSpec.VERSION` + 1.** Otherwise equal version numbers with different layouts silently misread parameters.

### Why cant is capped at 10 degrees

RTM's `TileEntityLargeRailBase.getBlockHeights` tilts the whole ballast top along the cant plane:

```
corner height = rail height - y + sin(cant) * (distance from centreline)   // no clamping
```

A large cant lifts the outer edge a long way (more than one block at 20 degrees), the ballast becomes a slanted wall and the rails end up buried. 10 degrees is about 260 mm, already nearly twice the GB 50090 limit of 150 mm; beyond that RTM cannot draw a sensible bed.

---

## Known limitations

- Connect mode has no **S-shape** solver: two parallel, laterally offset ends cannot be joined (it reports an error rather than laying something wrong)
- Cant is capped at 10 degrees (see above)
- The analytic geometry path accepts a single line up to **4000 m**; beyond that it falls back to RTM's native segmented path
- The sampled point table is capped at 1024 points: the longer the line, the coarser the spacing (about `length / 1023` metres). At R = 300 m the chord error is about 1.6 mm at 2 km and 6.3 mm at 4 km - well below anything the model shows; check the joints before going longer
- The ASM skeleton in `core/` is not enabled

---

## About the Chinese railway standard (GB 50090)

This mod is developed with reference to **GB 50090**, the Chinese national standard *Code for Design of Railway Line*
(GB 50090-2006, 「铁路线路设计规范」). It is **not a dependency** - RealTrainMod never validates any of it - but it is
where the default values, the reference tables shown next to the parameter fields, and the safety limits come from.
Every value can be overridden in the panel.

### What is taken from it

| Concept (Chinese term) | What the mod uses | Source in GB 50090 |
|---|---|---|
| **Easement (transition) length** 「缓和曲线长度」 | Preferred lengths by design speed and radius; a separate "minimum" table for general / difficult conditions | table 3.1.5-1 (preferred, 160/140/120 km/h) and table 3.1.5-2 (minimum, all speeds) |
| **Minimum curve radius** 「最小曲线半径」 | Reference value per design speed | table 3.1.2 |
| **Equilibrium superelevation** 「均衡超高」 | `h = 11.8 x V^2 / R` in mm, with `V` in km/h and `R` in metres; based on a 1500 mm rail-centreline distance, **capped at 150 mm** and quantised to 5 mm | derived from the standard's cant formula |
| **Cant deficiency** 「欠超高」 | 70 mm general / 90 mm difficult, used when suggesting a cant value | standard clause on cant deficiency |
| **Cant gradient** 「超高顺坡率」 | Preferred 1 per mille - this is why the ramp is placed inside the easement | standard clause on cant runoff |
| **Vertical curves** 「竖曲线」 | `Rv = 15000 m` at 160 km/h once the grade break exceeds 1 per mille; `Rv = 10000 m` below 160 km/h above 3 per mille; length `L = Rv x |di| / 1000` | standard clause on vertical curves |
| **Design speeds** 「设计速度」 | 160 / 140 / 120 / 100 / 80 km/h | the standard's speed series |

The default design speed used for automatic easement lengths is **160 km/h**, which is the common figure for Chinese
mainline passenger lines. Standard gauge is 1435 mm.

### Why the mod still allows more cant than the standard

GB 50090 caps superelevation at **150 mm** (about 5.7 degrees on a 1500 mm base). This mod will accept up to
**10 degrees** (about 260 mm) because it is a game, not a railway: beyond roughly 10 degrees RTM tilts the ballast
top so far that the bed turns into a slanted wall and the rails sink into it, so 10 degrees is a *rendering* limit
rather than an engineering one. For anything meant to look like a real line, stay at or below the standard's 150 mm.

### Glossary

- **缓和曲线 / easement** - the spiral (clothoid) between straight and circular curve, where curvature changes linearly
- **圆曲线 / circular curve** - constant curvature, the body of the curve
- **超高 / cant** - raising the outer rail so that part of the centrifugal force is balanced by gravity
- **超高顺坡 / cant gradient** - the rate at which that raising is introduced, spread over the easement
- **竖曲线 / vertical curve** - the smooth transition where the gradient changes
- **坡度 / grade** - the longitudinal slope, usually quoted in per mille
- **轨距 / gauge** - distance between rail centrelines; 1435 mm is standard gauge
- **设计速度 / design speed** - the speed the geometry is dimensioned for

> In short: GB 50090 supplies **sensible railway defaults**. The geometry engine itself is speed-agnostic - it just
> follows the numbers you give it.
---

## License

[MIT License](LICENSE).

Note that MIT covers **track-tool's own code** only. RealTrainMod and NGTLib have their own terms, and this repository neither contains nor redistributes them (see the Building section).

## Credits

- **RealTrainMod** by jp.ngt - this mod is an add-on for it; tracks, trains and rendering all come from RTM
- **NGTLib** by jp.ngt
- Alignment parameters follow **GB 50090**, the Chinese railway line design code