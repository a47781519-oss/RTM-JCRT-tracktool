# track-tool

**RealTrainMod (RTM) 的铺轨辅助模组** —— 用真正的铁路线形（缓和曲线 + 圆曲线 + 缓和曲线、外轨超高、竖曲线）在 Minecraft 里铺设轨道，而不是靠一段段手摆标记点。

| | |
|---|---|
| Minecraft | 1.12.2 |
| 加载器 | Forge 14.23.5（编译目标 2768，实测运行于 2847 / 2860） |
| 前置 | **RealTrainMod 2.4.24** + **NGTLib 2.4.21** |
| modid / 版本 | `tracktool` / 0.1.0 |
| Java | 8 |

> **客户端与服务端都要装**，并且必须是**同一份 jar**。参数包带版本号，两边不一致时服务端会直接红字拒绝并报出双方版本。

---

## 它解决什么问题

RTM 原生的铺轨方式是放两个 marker，中间由一条**贝塞尔曲线**连接，锚点还被量化到「半格网格 × 8 个方向」上。做直线和大半径缓弯够用，一旦要做符合铁路规范的线形就会暴露三个问题：

1. **没有缓和曲线**——曲率从 0 突变到 1/R，车辆过渡生硬；
2. **接头处折角**——每段各自拟合贝塞尔，锚点量化误差让相邻段切线对不上；
3. **没有超高顺坡**——外轨超高要么没有，要么在接头处突变。

track-tool 的做法是：**线形完全由自己的解析几何决定，RTM 只负责渲染和行车**。

```
用户参数 ──► plan（纯数学线形）──► ExactLine（实现 NGTLib 的 ILine）
                                      │
                                      └─► 反射换掉 RailMapBasic 内部的两个 ILine
                                          （对象类型仍是 RailMapBasic ⇒ 渲染器与车辆照常工作）
```

于是：几何零量化、接头逐点连续、超高按设计顺坡，而 RTM 那一侧完全无感。

---

---

## 怎么用（详细教程）

### 0. 装之前先确认三件事

1. **客户端与服务端都要装同一份 jar**（放 `mods/`），版本必须一致 —— 不一致时服务端会红字拒绝并报出双方版本号；
2. 前置到位：**RealTrainMod 2.4.24** + **NGTLib 2.4.21**（本模组是附属，缺了不加载）；
3. **服务端需要 OP**（权限等级 2）；单人游戏无需权限。

> 第一次用之前**先备份存档** —— 铺轨会改动方块，RTM 的路基也会被重新整形。

### 1. 拿到铺轨权杖

创造模式物品栏 → 标签页 **「RTM铺轨」** → 取 **「铺轨权杖」**（`tracktool:rail_staff`）。
权杖无限耐久、不可堆叠，纯工具物品。

### 2. 选中一个端点

**对准一段已有铁轨的最端部右键**：

- 命中端部 ⇒ 聊天栏提示已选中，该端出现**黄色线框**；
- 点在轨道**中段** ⇒ 提示 **「请选择轨道端侧」**（避免中途接出岔线）；
- 取消：再右键同一端部 / `/tracktool cancel` / 按 **ESC**。

> 选点距离默认 16 格（`selectDistance` 可调）。看不到线框时，先确认选区里确实有 RTM 轨道、且自己没飞得太远。

### 3. 打开参数面板

按 **G**（可在「控制 → 按键绑定」里改键），或 `/tracktool gui`。面板贴在屏幕右侧 1/3、半透明背景，含：

- **模式**：直线铺设 / 弯道生成 / 连接模式
- **参数**：半径、弯角、外轨超高、缓和曲线长（可勾「自动」）、抬高高度、竖曲线半径、左右平行线条数、线间距
- **预览**：勾选后世界里出现**蓝色半透明线形**（`previewEnabled`）
- **按钮**：确认 / 撤回 / 返回 / 取消

> 面板里每个输入框都对应 `/tracktool param <名> <值>`，脚本化操作可直接发指令。

### 4. 铺第一条弯道（推荐参数）

| 参数 | 建议值 | 说明 |
|---|---|---|
| 模式 | 弯道生成 | |
| 半径 | 300 | 米 |
| 弯角 | 45 | 度；想看得更明显可用 90 |
| 缓和曲线 | 勾「自动」 | 按 GB 50090 参考表随半径取值 |
| 外轨超高 | 60 | 或留 0 |
| 抬高 / 竖曲线 | 0 / 默认 | 先不动 |

点**确认**后：

- 聊天栏报 **`铺设完成：N 段轨道核心，M 个方块`**（长线路按约 20 m 分多个核心）；
- 沿弯道走一圈：**缓和曲线处曲率渐变、圆曲线处恒定、接头无折角**；外轨有超高且顺坡在缓和段内完成。

### 5. 撤销与重来

- 面板「撤回」或 `/tracktool undo`（默认保留 8 步，`maxUndo` 可调）；
- **ESC** 取消当前选区；`/tracktool status` 随时看参数与选区状态。

---

## 进阶用法

### 连接两个端点（连接模式）

1. 右键第一段轨道端部 → **再右键第二段轨道端部**；
2. 模式选**连接模式** → 确认。

自动解算「直线–缓和–圆–缓和–直线」把两端**精确闭合**（终点误差 0.000 m、朝向误差 0.000°）。
两端**平行且横向错开**（需 S 形）时当前无解算器，会**明确报错而不会乱铺**。

### 平行双线 / 多线

面板里设「左右平行线条数」与「线间距」，一次铺出**精确等距**的复线。
注意：缓和曲线的等距线**不是**缓和曲线，本模组按 `ds' = (1−κd)ds` 反算里程，不做直线近似。

### 超高与竖曲线

- **超高**：沿缓和曲线线性顺坡、圆曲线上恒定；**上限 10° ≈ 260 mm**（再大 RTM 会把路基外缘抬得过高，道砟成斜墙、钢轨被埋，原因见「为什么限制超高」）；
- **竖曲线**：填「抬高高度」+「竖曲线半径」，坡度变化处平滑过渡（竖曲线–直线–竖曲线）。

### 长线路

- 分帧铺设（`segmentsPerTick` + `blocksPerTick`，默认每 tick 最多 3000 格），避免卡服；
- 需要跨未加载区块时保持 `forceLoadChunks = true`；
- **单条解析线形上限 4000 m**，超过会自动回退 RTM 原生分段路径（能铺，但会退化回贝塞尔那些毛病）。
- 采样点表上限固定为 1024 点：线越长采样间距越大（约 `长度/1023` 米）。R=300 m 时弦高误差约 2 km = 1.6 mm、4 km = 6.3 mm，均远低于模型可见尺度；继续放长前请先检查接头。

### 一条线用一个核心（高级）

```
/tracktool exact seg 0        # 整条线只放一个核心（视距够大时最平滑）
/tracktool exact seg 30       # 每个核心覆盖 30 m
/tracktool exact              # 查询解析几何路径是否开启
/tracktool exact false        # 关掉，退回 RTM 原生分段路径（A/B 对比）
```

---

## 出问题时的自查顺序

| 现象 | 先查什么 |
|---|---|
| **放了车但车不动 / 找不到轨道** | `/tracktool test railcheck`（沿轨道走一遍放车那条查找链：底座 / 核心 / RailMap 三个计数），加 `fix` 就地补铺 |
| **轨道接头看着不齐** | `/tracktool test joints`（报渲染高差与段内超高塌陷，不必靠肉眼） |
| **客户端看不到预览 / 渲染异常** | `/tracktool test clientcheck`（逐核心打印线形来源、长度、钢轨相对自己路基的偏差、路基顶面高度） |
| **点了确认没反应** | `/tracktool status` 看选区与参数；确认服务端也装了同一个 jar |
| **服务端红字报版本不一致** | 两边换成同一份 jar |
| **想确认几何算得对不对** | `/tracktool exact selftest`（不铺轨，直接验算长度 / 端点朝向 / 超高） |

> 诊断输出**同时进聊天栏与 `latest.log`**，反馈问题时带上那几行最省事。

### 离线验证（不用开游戏）

`rail/plan` 是纯数学代码，不依赖 Minecraft：

```bash
gradlew selfTest       # 几何 / 编解码 / RailMap 一致性
gradlew connectTest    # 连接模式的端点闭合误差
```

## 功能

### 三种模式

| 模式 | 说明 |
|---|---|
| **直线铺设** | 从选中端点沿切线方向铺指定长度的直线 |
| **弯道生成** | 缓和曲线 → 圆曲线 → 缓和曲线，可设半径、弯角、超高、缓和曲线长 |
| **连接模式** | 选中两个端点，自动解算「直线–缓和–圆–缓和–直线」把两端**精确闭合**（终点误差 0.000 m、朝向误差 0.000°） |

### 线形要素

- **缓和曲线（回旋线）**：可手填长度，也可勾选「自动」按 GB 50090 参考表按半径取值
- **外轨超高**：沿缓和曲线线性顺坡，圆曲线上保持常数；上限 10°（见下文「为什么限制超高」）
- **竖曲线**：抬高高度 + 竖曲线半径，坡度变化处平滑过渡
- **平行线**：左右各若干条，按**精确等距线**计算（缓和曲线的等距线不是缓和曲线，这里按 `ds' = (1−κd)ds` 反算里程，不做近似）
- **自动分段**：长线路按约 20 m 切成多个 RTM 轨道核心（RTM 是按核心渲染的，一个核心太长会需要极大视距），**每段都是同一条解析几何的一段里程**，所以接头处逐点一致

### 界面与操作

1. 创造模式物品栏 → 标签页「**RTM铺轨**」→ 拿到「**铺轨权杖**」（`tracktool:rail_staff`）
2. **对准已有铁轨的一端右键**选中端点（点中间会提示「请选择轨道端侧」）
3. 按 **G** 打开参数面板（可在控制设置里改键），选模式、填参数，面板里有半透明预览
4. 点「确认」铺设；「撤销」可回退（默认保留 8 步）

服务器上**需要 OP 权限**（单人无限制）。

---

## 指令

```
/tracktool <confirm|back|undo|cancel|status|mode|param|plan|help>
```

权限等级 2。`mode` 接 `straight|curve|connect`，`param <参数名> <值>` 对应面板里的每个输入框。

### 诊断指令

调试线形问题时很有用，输出同时进聊天栏和 `latest.log`：

| 指令 | 作用 |
|---|---|
| `/tracktool test joints [半径]` | 把附近所有核心的端点两两配对，报告**渲染高差**与**段内超高塌陷**——接头齐不齐不用肉眼看 |
| `/tracktool test clientcheck` | 客户端自检：逐个核心打印线形来源、长度、钢轨相对自己路基的偏差、GL 列表状态、路基顶面高度 |
| `/tracktool test railcheck [n] [fix]` | 沿轨道逐点走一遍「放车」那条查找链（底座 / 核心 / RailMap 三个计数），`fix` 就地补铺 |
| `/tracktool test cell` | 查单格：是否在方块表里、离中心线多远 |
| `/tracktool exact [true\|false]` | 查询/切换解析几何路径（默认开启；关掉退回 RTM 原生分段路径，用于 A/B 对比） |
| `/tracktool exact seg <米>` | 改每个核心覆盖的长度，`0` = 整条线一个核心 |
| `/tracktool exact selftest` | 不铺轨、直接验算几何（长度 / 端点朝向 / 超高） |

---

## 配置

`config/tracktool.cfg`，常用项：

| 键 | 默认 | 说明 |
|---|---|---|
| `selectDistance` | 16 | 权杖选点的射线距离（格） |
| `segmentLength` | 20.0 | 每个 RTM 轨道核心覆盖的长度（米） |
| `blocksPerTick` | 3000 | 铺设时每 tick 最多写多少方块 |
| `forceLoadChunks` | true | 铺长线路时强制加载沿途区块 |
| `previewEnabled` | true | 是否画蓝色半透明预览 |
| `maxUndo` | 8 | 每位玩家保留的撤销步数 |
| `guiBackdrop` / `guiBackdropAlpha` | true / 140 | 面板背景板与透明度 |

---

## 代码结构

```
com.tracktool
├── rail/plan/      纯数学线形，不依赖 Minecraft 与 RTM，可离线跑
│   ├── Alignment / LineElement / ArcElement / SpiralElement
│   ├── CantProfile / VerticalProfile        超高与竖曲线
│   ├── ConnectSolver                        连接模式的闭合解算
│   └── PlanBuilder                          参数 → 线形
├── rail/           选点、落格、放置
│   ├── RailRayTrace / SelectionManager      端点拾取与会话
│   ├── RailGrid                             RTM 的「半格 × 8 方向」栅格
│   ├── RailStandards                        GB 50090 参考表
│   └── RailPlacer                           RTM 原生铺设路径（对照/回退）
├── rail2/          解析几何路径（主路径）
│   ├── ExactLine                            把解析几何实现成 NGTLib 的 ILine
│   ├── ExactRailInjector                    反射换掉 RailMapBasic 的两条 ILine
│   ├── ExactRailLayer                       落核心、分段、方块表、接头补格
│   ├── PlanGeometry / OffsetGeometry / SubGeometry / SampledGeometry
│   ├── ExactRailPersistence                 采样点表存档（读档后补注入）
│   ├── ExactRailServerSync                  读档补注入 + 向客户端补发点表
│   └── BrokenCoreSweeper                    清理会让服务端 NPE 的空核心
├── client/         GUI、预览渲染、客户端 tick 注入
├── net/            数据包
└── core/           ASM 核心插件骨架，**当前未启用**：transformer 原样返回不做改写，
                    且 build.gradle 生成的 jar manifest 里没有 FMLCorePlugin
                    （注意 src/main/resources/META-INF/MANIFEST.MF 里写着这一项，
                      但会被 jar 任务自己的 manifest 覆盖掉 —— 想启用得改 build.gradle）
```

### 离线自检

`rail/plan` 不依赖 Minecraft，可以不开游戏直接验算：

```bash
gradlew selfTest       # 几何 / 编解码 / RailMap 一致性
gradlew connectTest    # 连接模式的端点闭合误差
```

---

## 构建

需要 JDK 8。

```bash
gradlew build          # 产物在 build/libs/tracktool-0.1.0.jar
```

`libs/` 下需要放两个**编译期依赖**：

```
libs/RTM-2.4.24-43.jar
libs/NGTLib-2.4.21-38.jar
```

它们是 `compileOnly`，只用于编译，不会打进产物。

> ⚠️ **这两个 jar 是第三方作品，请勿提交进仓库、也不要随本项目分发。** 自行从 RTM / NGTLib 的官方发布渠道获取并放到 `libs/` 下即可。

---

## 给贡献者的硬约束

这些是踩坑换来的，改代码前务必读一遍：

1. **绝不把 `RailMapBasic` 换成子类。** RTM 的渲染通道对 railmap 的实现有硬性假设，换成子类会导致轨道**完全不渲染**。只允许替换它内部的 `lineHorizontal` / `lineVertical` 两个 `ILine`。
2. **绝不设置 `RailPosition.scriptName`。** 设了之后 `createRailMap()` 会去建 `RailMapCustom`，而脚本引擎是 null ⇒ `Script exec error`。
3. **公共类里绝不能出现客户端类型。** 哪怕代码永远不会在服务端执行、哪怕外面包了 `try/catch` —— JVM **校验该类时**就会去加载那个类型，专用服务端上直接 `NoClassDefFoundError`，整个类不可用。客户端代码一律单独放 `com.tracktool.client`，由公共侧**反射**调用。
4. **遍历 `world.loadedTileEntityList` 前先拷一份快照。** 循环体里调 `world.getTileEntity(...)` 会让客户端当场实例化 TE 并塞进这个列表 ⇒ `ConcurrentModificationException`。
5. **轨道核心方块一落地，就必须保证 `railPositions` 被写进去。** RTM 的 `writeRailData` 直接取 `railPositions[0]` **不判空**，世界里留一颗「有方块、没数据」的核心，服务端发包时就会 NPE，存档/服务器直接崩。放置与撤回两条路径都要有 `try/finally` 兜底。
6. **单位与字段顺序先看字节码再写代码。** 例如 `RailMap.getRailPos` 返回的是 `[Z, X]`（不是 `[X, Z]`），`ILine.getSlope` 要返回**弧度角**，`NGTMath.sin` 的入参是**度**。
7. **参数包改字段就要 `TrackSpec.VERSION` +1。** 否则两边版本号相同、布局不同，会静默读错参数。

### 为什么限制超高 ≤ 10°

RTM 的 `TileEntityLargeRailBase.getBlockHeights` 把路基顶面按超高平面整体倾斜：

```
每个角点高度 = 轨面高 − y + sin(超高) × (该角点到中心线的距离)     // 不做任何夹紧
```

超高一大，路基外缘就爬得很高（20° 时超过一格），道砟变成一堵斜墙，钢轨反而被埋进去。10° ≈ 260 mm 超高，已是 GB 50090 上限（150 mm）的近两倍，再大 RTM 就画不出合理的道床了。

---

## 已知限制

- 连接模式没有 **S 形**解算器：两端平行且横向错开时接不上（会明确报错，不会乱铺）
- 超高上限 10°（原因见上）
- 解析几何路径对单条线有 **4000 m** 长度上限，超过会回退到 RTM 原生分段路径
- `core/` 里的 ASM 骨架尚未启用

---

## 许可

本项目采用 [MIT License](LICENSE)。

注意：MIT 只覆盖 **track-tool 自身的代码**。前置的 RealTrainMod 与 NGTLib 有各自的许可条款，
本仓库既不包含也不再分发它们（见上文「构建」一节）。

## 鸣谢

- **RealTrainMod** by jp.ngt —— 本模组是它的附属工具，轨道、车辆、渲染全部由 RTM 提供
- **NGTLib** by jp.ngt
- 线形参数参考 **GB 50090《铁路线路设计规范》**
