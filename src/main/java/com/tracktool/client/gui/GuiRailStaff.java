package com.tracktool.client.gui;

import com.tracktool.TrackToolConfig;
import com.tracktool.client.ClientState;
import com.tracktool.net.Packets;
import com.tracktool.rail.RailStandards;
import com.tracktool.rail.TrackSpec;
import com.tracktool.rail.plan.Alignment;
import com.tracktool.rail.plan.RailPlan;
import com.tracktool.util.Geo;
import jp.ngt.rtm.RTMResource;
import jp.ngt.rtm.modelpack.ModelPackManager;
import jp.ngt.rtm.modelpack.cfg.RailConfig;
import jp.ngt.rtm.modelpack.modelset.ModelSetRail;
import jp.ngt.rtm.modelpack.modelset.ResourceSet;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The laying staff GUI.
 *
 * <p>It occupies the right third of the screen with a translucent backdrop, so
 * the world and the blue preview stay visible while parameters are edited. All
 * widgets are flat rectangles drawn in code: no texture atlas, no background
 * gradient, and hit boxes that match what is drawn pixel for pixel.</p>
 */
public class GuiRailStaff extends GuiScreen {

    private static final int PAD = 5;
    private static final int ROW_H = 18;
    private static final int LINE_H = 11;
    /** Bottom area reserved for the status line and the two action button rows. */
    private static final int ACTION_AREA = 66;

    private final List<Field> fields = new ArrayList<Field>();
    private final Map<GuiButton, Integer> baseY = new IdentityHashMap<GuiButton, Integer>();
    private final List<Check> checks = new ArrayList<Check>();
    private final List<Radio> radios = new ArrayList<Radio>();
    private final List<Dropdown> dropdowns = new ArrayList<Dropdown>();
    /** 纯文字的说明行（跟着内容区一起滚）。 */
    private final List<Note> notes = new ArrayList<Note>();

    private int panelX;
    private int panelW;
    private int scroll;
    /** 可滚动内容的上边界（状态信息区下方），防止滚动时与状态文字重叠。 */
    private int contentTop = 48;
    /** 需要重建界面（例如连接模式里自动解算被玩家改半径关掉了）。 */
    private boolean pendingRebuild;
    /** 上一帧解算结果是不是 S 形；一翻转就要重建面板（半径/缓和曲线行要收起来）。 */
    private boolean lastSCurve;
    private int contentHeight;
    private int contentBottom;
    private int tableSpeedIndex = 4;
    private long lastSync;
    private boolean dirty;
    private String statusLine = "";

    /** 一行只读说明文字。 */
    private static final class Note {
        String text;
        int baseY;
    }

    /** One labelled text input. */
    private static final class Field {
        String labelKey;
        GuiTextField tf;
        /** 这一行若是下拉框，指向它（不能再按"第 i 个无输入框的行"去配对）。 */
        Dropdown dropdown;
        boolean integral;
        double min;
        double max;
        int baseY;
        String tooltip;
    }

    private static final class Check {
        WidgetCheck widget;
        int baseY;
    }

    private static final class Radio {
        List<Widget> buttons = new ArrayList<Widget>();
        List<Integer> values = new ArrayList<Integer>();
        int selected;
        int baseY;
        String labelKey;
    }

    private TrackSpec spec() {
        return ClientState.localSpec;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.fields.clear();
        this.checks.clear();
        this.radios.clear();
        this.dropdowns.clear();
        this.notes.clear();
        this.baseY.clear();
        this.panelW = Math.max(150, this.width / 3);
        this.panelX = this.width - this.panelW;
        this.scroll = 0;

        TrackSpec s = this.spec();
        int x = this.panelX + PAD;
        int w = this.panelW - PAD * 2;
        int y = 26;

        // ---- mode buttons（固定在顶部，不随滚动移动）----
        int bw = (w - 4) / 3;
        this.reg(new Widget(100, x, y, bw, 16, I18n.format("tracktool.mode.straight")), Integer.MIN_VALUE);
        this.reg(new Widget(101, x + bw + 2, y, bw, 16, I18n.format("tracktool.mode.curve")), Integer.MIN_VALUE);
        this.reg(new Widget(102, x + bw * 2 + 4, y, w - bw * 2 - 4, 16,
                I18n.format("tracktool.mode.connect")), Integer.MIN_VALUE);
        y += 22;

        // ---- 状态信息区（文字在 drawScreen 里画，固定不滚动）----
        //   只预留实际会用到的行数：已选端点 / 模式 / 总长 / 分段(或错误)，连接模式多一行解算结果。
        //   contentTop 以下才允许画会滚动的控件，否则滚动时两者会叠在一起。
        y += LINE_H * (s.mode == TrackSpec.MODE_CONNECT ? 5 : 4) + 3;
        this.contentTop = y - 1;

        // ---- common parameters ----
        y = this.addInt(x, y, w, "tracktool.field.rise", s.riseM, -2000, 2000);
        y = this.addInt(x, y, w, "tracktool.field.verticalRadius", (int) s.verticalRadius, 100, 100000);
        y = this.addInt(x, y, w, "tracktool.field.linesLeft", s.linesLeft, 0, 64);
        y = this.addInt(x, y, w, "tracktool.field.linesRight", s.linesRight, 0, 64);
        y = this.addInt(x, y, w, "tracktool.field.spacing", s.spacingM, 1, 100);

        // ---- 轨道模型：按钮直接打开 RTM 原版的选择界面（不再用下拉框）----
        y = this.addButtonRow(x, y, w, I18n.format("tracktool.field.railPack"),
                this.railModelLabel(), 210);
        y = this.addDropdown(x, y, w, I18n.format("tracktool.field.ballast"),
                this.ballastNames(), this.ballastIndex(), idx -> {
                    TrackSpec sp = this.spec();
                    List<String> names = this.ballastNames();
                    sp.ballastBlock = idx <= 0 ? "" : this.ballastId(idx);
                    this.onChanged();
                });

        // ---- mode specific ----
        if (s.mode == TrackSpec.MODE_STRAIGHT) {
            y = this.addInt(x, y, w, "tracktool.field.straightLength", s.straightLengthM, 1, 20000);
        } else if (s.mode == TrackSpec.MODE_CURVE) {
            y = this.addInt(x, y, w, "tracktool.field.radius", s.radiusM, 1, 100000);
            y = this.addDouble(x, y, w, "tracktool.field.angle", s.angleDeg, 0.05D, 3600.0D);
            y = this.addDouble(x, y, w, "tracktool.field.cant", s.cantDeg, -TrackSpec.MAX_CANT_DEG, TrackSpec.MAX_CANT_DEG);
            y = this.addDouble(x, y, w, "tracktool.field.cantGradient", s.cantGradientPermille, 0.1D, 20.0D);
            y = this.addCheck(x, y, w, "tracktool.field.autoTransition", s.autoTransition);
            y = this.addDouble(x, y, w, "tracktool.field.transition", s.transitionLength, 1.0D, 5000.0D);
            y = this.addRadio(x, y, w, "tracktool.field.turn", new String[]{
                    I18n.format("tracktool.turn.left"), I18n.format("tracktool.turn.right")},
                    s.turnLeft ? 0 : 1);
            y = this.addCheck(x, y, w, "tracktool.field.cantInvert", s.cantInvert);
        } else {
            // 连接模式：直线-缓和-圆-缓和-直线。勾上自动解算 = 用能放下的最大半径；
            // 去掉勾选后，下面的半径/超高/缓和曲线长就按玩家填的走（规则与弯道生成一致）。
            //
            // ★ S 形（反向曲线）是个例外：那边的半径与缓和曲线长是解出来的，不是填出来的
            //   （两个弯加三段直线只有两个自由度，再把半径固死就超定了）。
            //   所以这时把那四行收起来，只留超高相关的行，免得玩家改了半径却没任何反应。
            boolean sLocked = this.solvedSCurve();
            if (!sLocked) {
                y = this.addCheck(x, y, w, "tracktool.field.connectAuto", s.connectAutoSolve);
                y = this.addInt(x, y, w, "tracktool.field.radius", s.radiusM, 1, 100000);
            } else {
                y = this.addNote(x, y, w, I18n.format("tracktool.info.s_locked"));
            }
            // 外轨超高自适应：勾上后超高在两端之间线性过渡，下面那个输入框就不起作用了
            y = this.addCheck(x, y, w, "tracktool.field.connectCantAdaptive", s.connectCantAdaptive);
            y = this.addDouble(x, y, w, "tracktool.field.cant", s.cantDeg, -TrackSpec.MAX_CANT_DEG, TrackSpec.MAX_CANT_DEG);
            if (!sLocked) {
                y = this.addCheck(x, y, w, "tracktool.field.autoTransition", s.autoTransition);
                y = this.addDouble(x, y, w, "tracktool.field.transition", s.transitionLength, 1.0D, 5000.0D);
            }
            y = this.addCheck(x, y, w, "tracktool.field.cantInvert", s.cantInvert);
        }

        // ---- reference table (drawn as text) ----
        y += LINE_H * 4 + 6;

        this.contentHeight = y;
        // Reserve the whole bottom area (status line + two button rows) so the
        // scrolling content can never collide with the action bar.
        this.contentBottom = this.height - ACTION_AREA;

        // ---- bottom action bar (never scrolls) ----
        int by = this.height - 46;
        int hw = (w - 4) / 2;
        this.reg(new Widget(200, x, by, hw, 18, I18n.format("tracktool.action.back")), Integer.MIN_VALUE);
        this.reg(new Widget(201, x + hw + 4, by, w - hw - 4, 18,
                I18n.format("tracktool.action.confirm")).accent(), Integer.MIN_VALUE);
        this.reg(new Widget(202, x, by + 20, hw, 18, I18n.format("tracktool.action.undo")), Integer.MIN_VALUE);
        this.reg(new Widget(203, x + hw + 4, by + 20, w - hw - 4, 18,
                I18n.format("tracktool.action.cancel")), Integer.MIN_VALUE);

        this.updateWidgets();
        this.refreshButtons();
    }

    // ------------------------------------------------------------------
    // widget construction helpers
    // ------------------------------------------------------------------

    private void reg(GuiButton b, int base) {
        this.buttonList.add(b);
        this.baseY.put(b, base);
    }

    private int addInt(int x, int y, int w, String key, int value, int min, int max) {
        return this.addField(x, y, w, key, String.valueOf(value), true, min, max);
    }

    private int addDouble(int x, int y, int w, String key, double value, double min, double max) {
        return this.addField(x, y, w, key, String.format("%.2f", value), false, min, max);
    }

    private int addField(int x, int y, int w, String key, String value, boolean integral,
                         double min, double max) {
        Field f = new Field();
        f.labelKey = key;
        f.integral = integral;
        f.min = min;
        f.max = max;
        f.baseY = y;
        f.tooltip = I18n.format(key + ".tip");
        int fx = x + w * 45 / 100;
        f.tf = new GuiTextField(0, this.fontRenderer, fx, y, x + w - fx, 14);
        f.tf.setMaxStringLength(12);
        f.tf.setText(value);
        f.tf.setEnableBackgroundDrawing(false);
        f.tf.setTextColor(0xFFFFFF);
        f.tf.setDisabledTextColour(0xFF808080);
        this.fields.add(f);
        return y + ROW_H;
    }

    /** 用自动换行摆一段说明文字，返回下一行的 y。 */
    private int addNote(int x, int y, int w, String text) {
        for (String line : this.fontRenderer.listFormattedStringToWidth(text, w)) {
            Note n = new Note();
            n.text = line;
            n.baseY = y;
            this.notes.add(n);
            y += LINE_H;
        }
        return y + 4;
    }

    /** 当前解算结果是否走的 S 形分支。 */
    private boolean solvedSCurve() {
        return this.spec().mode == TrackSpec.MODE_CONNECT
                && ClientState.plan != null && ClientState.plan.sCurve;
    }

    private int addCheck(int x, int y, int w, String key, boolean value) {
        WidgetCheck c = WidgetCheck.create(300 + this.checks.size(), x, y, I18n.format(key), value, this);
        Check holder = new Check();
        holder.widget = c;
        holder.baseY = y;
        this.reg(c, y);
        this.checks.add(holder);
        return y + ROW_H;
    }

    /**
     * 一行"标签 + 若干互斥按钮"。
     *
     * <p>★ 按钮必须和其它行一样从 45% 处起排，标签才放得下：早先按钮是占满整行的，
     * 标签只好画到 {@code y - 10}（自己这一行的上方），结果正好压在上一行的标签上
     * —— 第 61 轮用户截图里"转向"与"缓和曲线长(米)"糊成一团就是这么来的。</p>
     */
    private int addRadio(int x, int y, int w, String key, String[] labels, int selected) {
        Radio r = new Radio();
        r.labelKey = key;
        r.selected = selected;
        r.baseY = y;
        int n = Math.max(1, labels.length);
        int bx = x + w * 45 / 100;
        int avail = x + w - bx;
        int gap = 3;
        int bw = Math.max(18, (avail - gap * (n - 1)) / n);
        for (int i = 0; i < labels.length; i++) {
            Widget b = new Widget(400 + this.radios.size() * 8 + i, bx + i * (bw + gap), y, bw, 14, labels[i]);
            this.reg(b, y);
            r.buttons.add(b);
            r.values.add(i);
        }
        this.radios.add(r);
        return y + ROW_H;
    }

    private int addDropdown(int x, int y, int w, String label, List<String> options,
                            int selected, java.util.function.Consumer<Integer> onSelect) {
        Dropdown d = new Dropdown(x + w * 45 / 100, y, x + w - (x + w * 45 / 100),
                () -> options, onSelect);
        d.selected = selected;
        this.dropdowns.add(d);
        Field f = new Field();
        f.labelKey = label;
        f.baseY = y;
        f.tf = null;
        f.dropdown = d;
        this.fields.add(f);
        return y + ROW_H;
    }

    /** 一行"标签 + 按钮"（按钮用来打开 RTM 原版的选择界面）。 */
    private int addButtonRow(int x, int y, int w, String label, String text, int id) {
        Field f = new Field();
        f.labelKey = label;
        f.baseY = y;
        f.tf = null;
        f.dropdown = null;
        this.fields.add(f);
        int bx = x + w * 45 / 100;
        this.reg(new Widget(id, bx, y, x + w - bx, 14, text), y);
        return y + ROW_H;
    }

    // ------------------------------------------------------------------
    // data sources for the dropdowns
    // ------------------------------------------------------------------

    /** 轨道模型按钮上显示的名字（空 = 默认）。 */
    private String railModelLabel() {
        String r = this.spec().railResource;
        if (r == null || r.isEmpty()) {
            return I18n.format("tracktool.tip.default");
        }
        return this.clip(r, this.panelW * 50 / 100);
    }

    private List<String> railPackNames() {
        List<String> list = new ArrayList<String>();
        list.add(I18n.format("tracktool.tip.default"));
        try {
            for (ResourceSet set : ModelPackManager.INSTANCE.getModelList(RTMResource.RAIL)) {
                if (set == null || set.isDummy()) {
                    continue;
                }
                RailConfig cfg = (RailConfig) ((ModelSetRail) set).getConfig();
                if (cfg == null) {
                    continue;
                }
                list.add(cfg.getName());
            }
        } catch (Throwable ignored) {
            // model packs may not be loaded yet; the default entry still works
        }
        return list;
    }

    private int railPackIndex() {
        String want = this.spec().railResource;
        if (want == null || want.isEmpty()) {
            return 0;
        }
        List<String> names = this.railPackNames();
        int idx = names.indexOf(want);
        return idx < 0 ? 0 : idx;
    }

    private List<String> ballastNames() {
        List<String> list = new ArrayList<String>();
        list.add(I18n.format("tracktool.tip.default"));
        list.add(I18n.format("tracktool.ballast.air"));
        try {
            RailConfig cfg = this.currentRailConfig();
            if (cfg != null && cfg.defaultBallast != null) {
                for (RailConfig.BallastSet bs : cfg.defaultBallast) {
                    list.add(bs.blockName + (bs.blockMetadata == 0 ? "" : ":" + bs.blockMetadata));
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return list;
    }

    private String ballastId(int idx) {
        List<String> names = this.ballastNames();
        if (idx <= 0 || idx == 1) {
            return "minecraft:air";
        }
        String s = names.get(idx);
        int c = s.indexOf(':');
        return c > 0 && Character.isDigit(s.charAt(c + 1)) ? s.substring(0, c) : s;
    }

    private int ballastIndex() {
        String want = this.spec().ballastBlock;
        if (want == null || want.isEmpty()) {
            return 0;
        }
        if ("minecraft:air".equals(want) || "air".equals(want)) {
            return 1;
        }
        List<String> names = this.ballastNames();
        for (int i = 2; i < names.size(); i++) {
            if (names.get(i).startsWith(want)) {
                return i;
            }
        }
        return 0;
    }

    private RailConfig currentRailConfig() {
        String name = this.spec().railResource;
        if (name == null || name.isEmpty()) {
            return null;
        }
        ResourceSet set = ModelPackManager.INSTANCE.getResourceSet(RTMResource.RAIL, name);
        if (set == null || set.isDummy()) {
            return null;
        }
        return (RailConfig) ((ModelSetRail) set).getConfig();
    }

    private void refreshDropdowns() {
        if (!this.dropdowns.isEmpty()) {
            this.dropdowns.get(0).selected = this.railPackIndex();
        }
        if (this.dropdowns.size() > 1) {
            this.dropdowns.get(1).selected = this.ballastIndex();
        }
    }

    // ------------------------------------------------------------------
    // interaction
    // ------------------------------------------------------------------

    private void onChanged() {
        this.dirty = true;
        ClientState.setLocalSpec(this.spec());
    }

    /** Pushes the values in the text fields into the spec. */
    private void collect() {
        TrackSpec s = this.spec();
        for (Field f : this.fields) {
            if (f.tf == null) {
                continue;
            }
            String key = f.labelKey;
            String text = f.tf.getText().trim();
            if (text.isEmpty()) {
                // Empty means "keep the previous value" and is reported once.
                this.reportInvalid(key, "tracktool.err.empty");
                continue;
            }
            double v;
            try {
                v = Double.parseDouble(text.replace(',', '.'));
            } catch (NumberFormatException e) {
                this.reportInvalid(key, "tracktool.err.not_number");
                continue;
            }
            boolean clamped = false;
            if (v < f.min) {
                v = f.min;
                clamped = true;
            }
            if (v > f.max) {
                v = f.max;
                clamped = true;
            }
            int iv = Geo.roundHalfUp(v);
            if ("tracktool.field.rise".equals(key)) {
                s.riseM = iv;
            } else if ("tracktool.field.verticalRadius".equals(key)) {
                s.verticalRadius = iv;
            } else if ("tracktool.field.linesLeft".equals(key)) {
                s.linesLeft = iv;
            } else if ("tracktool.field.linesRight".equals(key)) {
                s.linesRight = iv;
            } else if ("tracktool.field.spacing".equals(key)) {
                s.spacingM = Math.max(1, iv);
            } else if ("tracktool.field.straightLength".equals(key)) {
                s.straightLengthM = iv;
            } else if ("tracktool.field.radius".equals(key)) {
                // 连接模式下玩家手改半径 ⇒ 自动解算必须让位，否则"改了没反应"
                if (s.mode == TrackSpec.MODE_CONNECT && s.connectAutoSolve && iv != s.radiusM) {
                    s.connectAutoSolve = false;
                    this.pendingRebuild = true;
                }
                s.radiusM = iv;
            } else if ("tracktool.field.angle".equals(key)) {
                s.angleDeg = v;
            } else if ("tracktool.field.cant".equals(key)) {
                s.cantDeg = v;
            } else if ("tracktool.field.cantGradient".equals(key)) {
                s.cantGradientPermille = v;
            } else if ("tracktool.field.transition".equals(key)) {
                s.transitionLength = v;
            } else if ("tracktool.field.connectTransition".equals(key)) {
                s.connectTransition = v;
            }
            if (clamped) {
                this.reportInvalid(key, "tracktool.err.clamped");
                f.tf.setText(f.integral ? String.valueOf(iv) : String.format("%.2f", v));
            }
        }
        for (Check c : this.checks) {
            String k = c.widget.displayString;
            if (k.equals(I18n.format("tracktool.field.autoTransition"))) {
                s.autoTransition = c.widget.checked;
            } else if (k.equals(I18n.format("tracktool.field.cantInvert"))) {
                s.cantInvert = c.widget.checked;
            } else if (k.equals(I18n.format("tracktool.field.connectAuto"))) {
                s.connectAutoSolve = c.widget.checked;
            } else if (k.equals(I18n.format("tracktool.field.connectCantAdaptive"))) {
                s.connectCantAdaptive = c.widget.checked;
            }
        }
        for (Radio r : this.radios) {
            if ("tracktool.field.turn".equals(r.labelKey)) {
                s.turnLeft = r.selected == 0;
            }
        }
    }

    private void reportInvalid(String key, String reason) {
        if (this.mc == null || this.mc.player == null) {
            return;
        }
        String msg = I18n.format("tracktool.err.field", I18n.format(key), I18n.format(reason));
        this.statusLine = msg;
    }

    private void refreshButtons() {
        TrackSpec s = this.spec();
        boolean one = ClientState.state.ends.size() == 1;
        boolean two = ClientState.state.ends.size() >= 2;
        boolean busy = ClientState.state.busy;
        setEnabled(100, !busy);
        setEnabled(101, !busy && !two);
        setEnabled(102, !busy && two);
        // 「回退选点」只在有选点时可用；撤掉已铺的轨道是「撤销铺设」的事（两者以前会重叠）
        setEnabled(200, !busy && !ClientState.state.ends.isEmpty());
        setEnabled(201, !busy && (one || (s.mode == TrackSpec.MODE_CONNECT && two)));
        setEnabled(202, !busy && ClientState.state.undoDepth > 0);
        setEnabled(203, !busy && !ClientState.state.ends.isEmpty());
        // Highlight the active mode button.
        for (GuiButton b : this.buttonList) {
            if (b instanceof Widget) {
                Widget w = (Widget) b;
                if (b.id == 100) {
                    w.accent = s.mode == TrackSpec.MODE_STRAIGHT;
                } else if (b.id == 101) {
                    w.accent = s.mode == TrackSpec.MODE_CURVE;
                } else if (b.id == 102) {
                    w.accent = s.mode == TrackSpec.MODE_CONNECT;
                }
            }
        }
    }

    private void setEnabled(int id, boolean enabled) {
        for (GuiButton b : this.buttonList) {
            if (b.id == id) {
                b.enabled = enabled;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        TrackSpec s = this.spec();
        switch (button.id) {
            case 100:
                s.mode = TrackSpec.MODE_STRAIGHT;
                this.onChanged();
                this.rebuild();
                return;
            case 101:
                s.mode = TrackSpec.MODE_CURVE;
                this.onChanged();
                this.rebuild();
                return;
            case 102:
                s.mode = TrackSpec.MODE_CONNECT;
                this.onChanged();
                this.rebuild();
                return;
            case 210: {
                // 用 RTM 原版的模型选择界面挑轨道
                this.collect();
                RailModelSelector sel = new RailModelSelector(this, s.railResource, name -> {
                    TrackSpec sp = this.spec();
                    sp.railResource = name == null ? "" : name;
                    this.onChanged();
                    this.rebuild();
                });
                this.mc.displayGuiScreen(new jp.ngt.rtm.gui.GuiSelectModel(this.mc.world, sel));
                return;
            }
            case 200:
                this.collect();
                Packets.sendToServer(new Packets.Action(com.tracktool.rail.SelectionManager.ACTION_BACK));
                return;
            case 201:
                this.collect();
                this.onChanged();
                this.flushNow();
                Packets.sendToServer(new Packets.Action(com.tracktool.rail.SelectionManager.ACTION_CONFIRM));
                return;
            case 202:
                Packets.sendToServer(new Packets.Action(com.tracktool.rail.SelectionManager.ACTION_UNDO));
                return;
            case 203:
                Packets.sendToServer(new Packets.Action(com.tracktool.rail.SelectionManager.ACTION_CLEAR));
                return;
            default:
                break;
        }
        for (Check c : this.checks) {
            if (c.widget.id == button.id) {
                c.widget.checked = !c.widget.checked;
                this.collect();
                this.onChanged();
                return;
            }
        }
        for (Radio r : this.radios) {
            for (int i = 0; i < r.buttons.size(); i++) {
                if (r.buttons.get(i).id == button.id) {
                    r.selected = r.values.get(i);
                    this.collect();
                    this.onChanged();
                    return;
                }
            }
        }
        super.actionPerformed(button);
    }

    private void rebuild() {
        this.collect();
        this.initGui();
    }

    private void flushNow() {
        this.lastSync = System.currentTimeMillis();
        this.dirty = false;
        Packets.sendToServer(new Packets.Params(this.spec()));
    }

    @Override
    public void updateScreen() {
        for (Field f : this.fields) {
            if (f.tf != null) {
                f.tf.updateCursorCounter();
            }
        }
        if (this.dirty && System.currentTimeMillis() - this.lastSync > 250L) {
            this.collect();
            this.flushNow();
            ClientState.setLocalSpec(this.spec());
        }
        boolean sNow = this.solvedSCurve();
        if (sNow != this.lastSCurve) {
            this.lastSCurve = sNow;
            this.pendingRebuild = true;     // S 形一启用/停用，半径与缓和曲线行要跟着收放
        }
        if (this.pendingRebuild) {
            this.pendingRebuild = false;
            this.initGui();                 // 例如自动解算被关掉后，复选框要跟着变
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // ESC cancels every selected end, as required by the brief.
            Packets.sendToServer(new Packets.Action(com.tracktool.rail.SelectionManager.ACTION_CLEAR));
            this.mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            // Enter confirms, exactly like the button and /tracktool confirm.
            this.collect();
            this.onChanged();
            this.flushNow();
            Packets.sendToServer(new Packets.Action(com.tracktool.rail.SelectionManager.ACTION_CONFIRM));
            return;
        }        if (keyCode == Keyboard.KEY_TAB) {
            this.cycleFocus(Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ? -1 : 1);
            return;
        }
        boolean handled = false;
        for (Field f : this.fields) {
            if (f.tf != null && f.tf.isFocused() && f.tf.textboxKeyTyped(typedChar, keyCode)) {
                handled = true;
                this.dirty = true;
            }
        }
        if (!handled) {
            super.keyTyped(typedChar, keyCode);
        }
    }

    /** Tab order follows the visual order of the widgets, GUI first. */
    private void cycleFocus(int dir) {
        List<GuiTextField> list = new ArrayList<GuiTextField>();
        for (Field f : this.fields) {
            if (f.tf != null && f.tf.getVisible()) {
                list.add(f.tf);
            }
        }
        if (list.isEmpty()) {
            return;
        }
        int cur = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isFocused()) {
                cur = i;
            }
            list.get(i).setFocused(false);
        }
        int next = (cur + dir + list.size()) % list.size();
        list.get(next).setFocused(true);
        this.dirty = true;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        for (Dropdown d : this.dropdowns) {
            if (d.mouseClicked(mouseX, mouseY)) {
                return;
            }
        }
        for (Field f : this.fields) {
            if (f.tf != null) {
                boolean was = f.tf.isFocused();
                f.tf.mouseClicked(mouseX, mouseY, mouseButton);
                if (was && !f.tf.isFocused()) {
                    this.collect();
                    this.onChanged();
                }
            }
        }
        // Buttons are only clickable inside the visible content area.
        for (GuiButton b : this.buttonList) {
            if (isScrolled(b) && (mouseY < this.contentTop || mouseY > this.contentBottom)) {
                continue;
            }
            if (b.mousePressed(this.mc, mouseX, mouseY)) {
                // Hit test is not enough: vanilla GuiScreen also fires the
                // action here, and without it every button silently does nothing.
                this.selectedButton = b;
                b.playPressSound(this.mc.getSoundHandler());
                this.actionPerformed(b);
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean isScrolled(GuiButton b) {
        Integer base = this.baseY.get(b);
        return base != null && base != Integer.MIN_VALUE;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) {
            return;
        }
        for (Dropdown dr : this.dropdowns) {
            if (dr.mouseScrolled(d > 0 ? 1 : -1)) {
                return;
            }
        }
        int max = Math.max(0, this.contentHeight - this.contentBottom + 24);
        this.scroll = Geo.clampInt(this.scroll - d / 120 * 8, 0, max);
        this.updateWidgets();
    }

    private void updateWidgets() {
        for (Map.Entry<GuiButton, Integer> e : this.baseY.entrySet()) {
            int base = e.getValue();
            if (base == Integer.MIN_VALUE) {
                continue;
            }
            GuiButton b = e.getKey();
            b.y = base - this.scroll;
            // ★ 会滚动的按钮必须自己裁剪：香草的 drawScreen 只看 visible，
            //   否则滚上去之后会浮在顶部的状态文字上（轨道模型按钮就是这么露出来的）。
            //   判据必须与参数行（drawFields）完全一致，否则按钮会比它的标签早一步消失。
            b.visible = b.y >= this.contentTop && b.y <= this.contentBottom;
        }
        for (Field f : this.fields) {
            if (f.tf != null) {
                f.tf.y = f.baseY - this.scroll;
            }
        }
        for (Dropdown d : this.dropdowns) {
            d.move(this.panelX + PAD + (this.panelW - PAD * 2) * 45 / 100, 0);
        }
        this.repositionDropdowns();
    }

    private void repositionDropdowns() {
        for (Field f : this.fields) {
            if (f.dropdown != null) {
                f.dropdown.y = f.baseY - this.scroll;
                f.dropdown.enabled = f.dropdown.y >= this.contentTop && f.dropdown.y <= this.contentBottom;
            }
        }
    }

    // ------------------------------------------------------------------
    // rendering
    // ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (TrackToolConfig.guiBackdrop) {
            int a = TrackToolConfig.guiBackdropAlpha;
            drawRect(this.panelX, 0, this.width, this.height, (a << 24) | 0x101418);
        }
        drawRect(this.panelX, 0, this.panelX + 1, this.height, 0x804A9EFF);

        this.fontRenderer.drawString(I18n.format("tracktool.gui.title"), this.panelX + PAD, 6, 0x9CC6FF, false);
        this.drawInfo(mouseX, mouseY);
        this.drawFields();
        this.drawReferenceTable();
        this.drawStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);

        for (Dropdown d : this.dropdowns) {
            d.draw(this.mc, mouseX, mouseY);
        }
        for (Dropdown d : this.dropdowns) {
            d.drawTooltip(this, mouseX, mouseY);
        }
        this.drawFieldTooltips(mouseX, mouseY);
    }

    /** 状态区还放得下一行吗（放不下就不画，绝不越过 contentTop）。 */
    private boolean infoFits(int y) {
        return y + LINE_H <= this.contentTop;
    }

    private void drawInfo(int mouseX, int mouseY) {
        int x = this.panelX + PAD;
        int y = 26 + 22;
        int n = ClientState.state.ends.size();
        String sel = I18n.format("tracktool.info.selected", n) + " / 2";
        int color = n == 0 ? 0xFFAAAAAA : (n >= 2 ? 0xFF6FE06F : 0xFFFFD24A);
        this.fontRenderer.drawString(sel, x, y, color, false);
        y += LINE_H;
        String modeKey = this.spec().mode == TrackSpec.MODE_STRAIGHT ? "tracktool.mode.straight"
                : (this.spec().mode == TrackSpec.MODE_CURVE ? "tracktool.mode.curve" : "tracktool.mode.connect");
        this.fontRenderer.drawString(I18n.format("tracktool.info.mode", I18n.format(modeKey)), x, y,
                0xFFFFFF, false);
        y += LINE_H;
        RailPlan plan = ClientState.plan;
        if (ClientState.hasPlan) {
            this.fontRenderer.drawString(I18n.format("tracktool.info.length",
                    String.format("%.1f", plan.totalLength)), x, y, 0x9CC6FF, false);
            y += LINE_H;
            this.fontRenderer.drawString(I18n.format("tracktool.info.segments",
                    plan.segmentCount(), String.format("%.3f", plan.maxDeviation)), x, y,
                    plan.maxDeviation > 1.0D ? 0xFFFF8080 : 0x9CC6FF, false);
            y += LINE_H;
            // 连接模式：把解算出来的弯道参数显示出来（自动解算时这是唯一能看到半径的地方）
            if (this.spec().mode == TrackSpec.MODE_CONNECT && plan.solvedRadius > 0.0D && this.infoFits(y)) {
                this.fontRenderer.drawString(I18n.format(
                        plan.sCurve ? "tracktool.info.connect_s" : "tracktool.info.connect_solved",
                        String.format("%.0f", plan.solvedRadius), String.format("%.0f", plan.solvedTransition)),
                        x, y, plan.sCurve ? 0xFFD2A44A : 0x9CC6FF, false);
            }
        } else {
            this.fontRenderer.drawString(I18n.format("tracktool.info.no_plan"), x, y, 0xFFAAAAAA, false);
            y += LINE_H;
            String msg = null;
            if (plan != null && plan.errorKey != null && !plan.errorKey.isEmpty()) {
                // 本地规划的错误（连接模式的"半径放不下/两端背离/平行错开"都在这里）
                msg = plan.errorArg == null ? I18n.format(plan.errorKey)
                        : I18n.format(plan.errorKey, plan.errorArg);
            } else if (ClientState.state.planError != null && !ClientState.state.planError.isEmpty()) {
                msg = I18n.format(ClientState.state.planError);
            }
            if (msg != null) {
                // 自动换行：错误里常带着"最大只能用 N 米"这种关键数字，截断了就看不到了
                for (String line : this.fontRenderer.listFormattedStringToWidth(msg, this.panelW - PAD * 2)) {
                    if (!this.infoFits(y)) {
                        break;
                    }
                    this.fontRenderer.drawString(line, x, y, 0xFFFF8080, false);
                    y += LINE_H;
                }
            }
        }
        if (ClientState.placing && this.infoFits(y)) {
            this.fontRenderer.drawString(I18n.format("tracktool.info.placing",
                    ClientState.progressDone, ClientState.progressTotal), x, y, 0x9CC6FF, false);
        }
    }

    private void drawFields() {
        int x = this.panelX + PAD;
        int w = this.panelW - PAD * 2;
        for (Note n : this.notes) {
            int y = n.baseY - this.scroll;
            if (y >= this.contentTop && y <= this.contentBottom) {
                this.fontRenderer.drawString(n.text, x, y, 0xFFFFD24A, false);
            }
        }
        for (Field f : this.fields) {
            int y = f.baseY - this.scroll;
            if (y < this.contentTop || y > this.contentBottom) {
                continue;
            }
            if (!ClientState.state.busy) {
                this.fontRenderer.drawString(this.clip(I18n.format(f.labelKey), w * 45 / 100 - 4),
                        x, y + 3, 0xDDDDDD, false);
            }
            if (f.tf != null) {
                int bx = f.tf.x;
                int bw = f.tf.width;
                drawRect(bx - 2, y - 1, bx + bw + 2, y + 15, 0x66101010);
                drawRect(bx - 2, y + 14, bx + bw + 2, y + 15, 0x80FFFFFF);
                f.tf.drawTextBox();
            }
        }
        for (Check c : this.checks) {
            int y = c.baseY - this.scroll;
            c.widget.visible = y >= this.contentTop && y <= this.contentBottom;
            if (c.widget.visible) {
                c.widget.width = c.widget.neededWidth(this.mc);
                c.widget.x = x;
            }
        }
        for (Radio r : this.radios) {
            int y = r.baseY - this.scroll;
            boolean vis = y >= this.contentTop && y <= this.contentBottom;
            for (Widget b : r.buttons) {
                b.visible = vis;
            }
            if (vis) {
                // 与普通参数行完全同一条基线（y + 3）和同一个截断宽度，才不会压到上一行
                this.fontRenderer.drawString(this.clip(I18n.format(r.labelKey), w * 45 / 100 - 4),
                        x, y + 3, 0xDDDDDD, false);
            }
        }
    }

    private String clip(String s, int maxWidth) {
        return this.fontRenderer.trimStringToWidth(s, Math.max(10, maxWidth));
    }

    private void drawReferenceTable() {
        int x = this.panelX + PAD;
        int y = this.contentHeight - LINE_H * 4 - 2 - this.scroll;
        if (y < this.contentTop || y > this.contentBottom) {
            return;
        }
        this.fontRenderer.drawString(I18n.format("tracktool.ref.title"), x, y, 0x9CC6FF, false);
        double[][] table = RailStandards.tableForSpeed(RailStandards.SPEEDS[this.tableSpeedIndex]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < table[0].length; i++) {
            sb.append(String.format("%.0f/%.0f ", table[0][i], table[1][i]));
        }
        this.fontRenderer.drawString(this.clip(I18n.format("tracktool.ref.speed",
                RailStandards.SPEEDS[this.tableSpeedIndex]), this.panelW), x, y + LINE_H, 0xFFFFFF, false);
        this.fontRenderer.drawString(this.clip(sb.toString(), this.panelW - PAD * 2), x, y + LINE_H * 2,
                0xCCCCCC, false);
        this.fontRenderer.drawString(this.clip(I18n.format("tracktool.ref.legend"), this.panelW - PAD * 2),
                x, y + LINE_H * 3, 0x999999, false);
    }

    private void drawStatus() {
        int x = this.panelX + PAD;
        int y = this.height - 60;
        // Diagnostic: if the language file did not load, say so instead of
        // silently showing raw translation keys all over the panel.
        if ("tracktool.gui.title".equals(I18n.format("tracktool.gui.title"))) {
            this.fontRenderer.drawString("i18n: tracktool lang not loaded ("
                    + this.mc.gameSettings.language + ")", x, this.height - 72, 0xFFFF5555, false);
        }
        if (this.statusLine != null && this.statusLine.length() > 0) {
            this.fontRenderer.drawString(this.clip(this.statusLine, this.panelW - PAD * 2), x, y, 0xFF9CC6FF, false);
        } else if (ClientState.state.busy) {
            this.fontRenderer.drawString(I18n.format("tracktool.info.working"), x, y, 0xFF9CC6FF, false);
        } else {
            String last = ClientState.state.lastMessage;
            this.fontRenderer.drawString(last == null ? "" : I18n.format(last), x, y, 0xFF9CC6FF, false);
        }
    }

    private void drawFieldTooltips(int mouseX, int mouseY) {
        for (Field f : this.fields) {
            if (f.tf == null || f.tooltip == null) {
                continue;
            }
            int y = f.baseY - this.scroll;
            if (mouseX >= f.tf.x && mouseX < f.tf.x + f.tf.width && mouseY >= y && mouseY < y + 14) {
                this.drawHoveringText(java.util.Collections.singletonList(f.tooltip), mouseX, mouseY);
                return;
            }
        }
    }

    /** Called when the authoritative state arrives. */
    public void onStateChanged() {
        this.refreshDropdowns();
        this.refreshButtons();
        this.collect();
        ClientState.setLocalSpec(this.spec());
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
