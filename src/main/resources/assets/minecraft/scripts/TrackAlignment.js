/* track-tool 对齐脚本 —— 让 RTM 的自定义轨道图 (RailMapCustom) 按解析线形求值。
 *
 * 目的（见 docs/问题档案-放样精度与核心缺口.md 第 41–45 轮）：
 *   RTM 的普通轨道图 (RailMapBasic) 由"锚点链"构成，而 RailPosition 的横向位置只能落在
 *   0.5 m 网格上（blockX + 0.5 + REVISION[dir]），Y 只有 1/16 —— 于是每一段都被量化，
 *   逐段误差正负交替 ⇒ 观感上"类似蚯蚓的左右抖动"(Q1)，并可能导致列车过接头失稳(Q2)。
 *   RailMapCustom 把"锚点之间怎么走"交给脚本，按 (split, index) 逐点求值 ⇒ 段内零量化。
 *
 * 接口（照 RTM 自带 assets/minecraft/scripts/LineArc.js 的结构）：
 *   getDefaultArgs()                 -> "名=默认值,..." 逗号串
 *   getLength()                      -> 轨道总长（含竖向）
 *   getNearlestPoint(split, x, z)    -> 可空实现
 *   getPos(split, index)             -> [z, x]   ★ z 在前
 *   getHeight(split, index)          -> y
 *   getYaw(split, index)             -> 朝向
 *   getPitch(split, index)           -> 坡度
 *   getRoll(split, index)            -> 侧倾/超高
 *   getStepLength(split, index)      -> 沿轨里程 t
 *   getSectionId(t)                  -> 段号：0=入口缓和曲线 1=圆曲线 2=出口缓和曲线
 *
 * 待实测确认（第一版先按此实现，若不对只改这里）：
 *   - yaw/pitch/roll 的单位：按 RailMap.getRailYaw/getRailRoll 的惯例取 **弧度**；
 *   - 曲线方向：dir=+1 左转 / dir=-1 右转；
 *   - 脚本名解析：RailMapCustom(startRP, "TrackAlignment", args) 期望
 *     ModelPackManager.getScript("TrackAlignment") 能解析到本文件。
 */

var R = 300.0;          // 圆曲线半径 m
var angle = 45.0;       // 转角（度，正数）
var ls = 30.0;          // 缓和曲线长度 m（每端）
var cant = 105.0;       // 外轨超高 mm
var rise = 0.0;         // 终点抬高 m
var Rv = 15000.0;       // 竖曲线半径 m
var dir = 1.0;          // +1 左转 / -1 右转

var STEPS = 0.25;       // 采样步长 m
var table = null;       // [[s, x, z, yaw], ...]
var totalLen = 0.0;
var arcLen = 0.0;

function getDefaultArgs() {
    return "R=300.0,angle=45.0,ls=30.0,cant=105.0,rise=0.0,Rv=15000.0,dir=1.0";
}

/* 曲率随里程变化：两端缓和曲线线性过渡，中间圆曲线恒定。 */
function curvatureAt(s) {
    var k = 1.0 / R;
    if (s <= 0.0) return 0.0;
    if (s < ls) return k * (s / ls);
    if (s < ls + arcLen) return k;
    var rest = totalLen - s;
    if (rest < ls) return k * (rest / ls);
    return 0.0;
}

function buildTable() {
    if (table !== null) return;
    arcLen = Math.PI * R * (angle / 180.0) - ls;          // 圆曲线弧长（缓和曲线已吃掉 2*(ls/2R) 的转角）
    if (arcLen < 0.0) arcLen = 0.0;
    totalLen = 2.0 * ls + arcLen;
    table = [];
    var x = 0.0, z = 0.0, yaw = Math.PI;                   // 与 RTM 一致：yaw=PI 表示朝 -Z
    var s = 0.0;
    table.push([0.0, 0.0, 0.0, yaw]);
    while (s < totalLen) {
        var h = Math.min(STEPS, totalLen - s);
        var k = curvatureAt(s + h * 0.5) * dir;
        yaw += k * h;
        x += Math.sin(yaw) * h;
        z += Math.cos(yaw) * h;
        s += h;
        table.push([s, x, z, yaw]);
    }
}

function sample(s) {
    buildTable();
    if (s <= 0.0) return table[0];
    if (s >= totalLen) return table[table.length - 1];
    var i = Math.floor(s / STEPS);
    if (i >= table.length - 1) return table[table.length - 1];
    var a = table[i], b = table[i + 1];
    var f = (s - a[0]) / Math.max(1e-9, (b[0] - a[0]));
    return [s, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f];
}

function getLength() {
    buildTable();
    var lenXZ = totalLen;
    return Math.sqrt(lenXZ * lenXZ + rise * rise);
}

function getNearlestPoint(split, x, z) {
    return 0;                                              // 交给 RTM 兜底
}

function getStepLength(split, index) {
    buildTable();
    var n = Math.max(1, split);
    return totalLen * (index / n);
}

function getSectionId(t) {
    if (t < ls) return 0;
    if (t < ls + arcLen) return 1;
    return 2;
}

function getPos(split, index) {
    var pos = {0.0, 0.0};                                  // 与官方 LineArc.js 完全一致的返回形式
    var p = sample(getStepLength(split, index));
    pos[0] = p[2];                                         // z
    pos[1] = p[1];                                         // x
    return pos;
}

/* 竖曲线 + 直线 + 竖曲线 的竖向剖面（与规划器 VerticalProfile 同思路）。 */
function heightAt(t) {
    if (rise === 0.0) return 0.0;
    var Lv = Math.min(totalLen * 0.25, Rv * Math.abs(rise) / totalLen * 0.5);
    if (Lv < 1e-6) return rise * (t / totalLen);
    if (t <= Lv) {                                         // 入口竖曲线（抛物线）
        var f1 = t / Lv;
        return rise * (Lv / totalLen) * f1 * f1 * 0.5;
    }
    if (t >= totalLen - Lv) {                              // 出口竖曲线
        var f2 = (totalLen - t) / Lv;
        return rise * (1.0 - (Lv / totalLen) * f2 * f2 * 0.5);
    }
    var slope = rise / totalLen;                           // 中间直线段
    return rise * (Lv / totalLen) * 0.5 + slope * (t - Lv);
}

function getHeight(split, index) {
    return heightAt(getStepLength(split, index));
}

function getYaw(split, index) {
    // RTM 约定：RailMapBasic.getRailYaw 内部调用了 Math.toDegrees ⇒ 返回"度"
    return sample(getStepLength(split, index))[3] * 180.0 / Math.PI;
}

function getPitch(split, index) {
    if (rise === 0.0) return 0.0;
    return Math.atan(rise / totalLen) * 180.0 / Math.PI;   // 同 getRailPitch：度
}

/* 超高：缓和曲线内线性顺坡，圆曲线内恒定（弧度）。 */
function getRoll(split, index) {
    var t = getStepLength(split, index);
    var c = cant / 1000.0;                                 // mm -> m 的比值即坡度
    var sign = (dir > 0.0) ? 1.0 : -1.0;
    if (t < ls) return sign * c * (t / ls);
    if (t < ls + arcLen) return sign * c;
    var rest = Math.max(0.0, totalLen - t);
    return sign * c * Math.min(1.0, rest / ls);
}
