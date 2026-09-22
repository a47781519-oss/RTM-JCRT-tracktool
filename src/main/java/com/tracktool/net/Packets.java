package com.tracktool.net;

import com.tracktool.rail.RailEnd;
import com.tracktool.rail.SelectionManager;
import com.tracktool.rail.TrackSpec;
import io.netty.buffer.ByteBuf;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Every network message of track-tool.
 *
 * <p>Client to server: a look ray (the server re-does the pick itself), the GUI
 * parameters, and discrete actions. Server to client: the authoritative session
 * state, placement progress and the result. The client never generates rails -
 * it only renders a preview from the very same parameters, so preview and
 * result cannot drift apart.</p>
 *
 * <p>Client-side handling is reached through {@link #setClientSink} so this
 * class never references a client-only type and stays loadable on a dedicated
 * server.</p>
 */
public final class Packets {

    private static Consumer<Runnable> scheduler = Runnable::run;
    private static ClientSink sink;

    private Packets() {
    }

    public static void setScheduler(Consumer<Runnable> s) {
        scheduler = s;
    }

    public static void setClientSink(ClientSink s) {
        sink = s;
    }

    static void schedule(Runnable r) {
        scheduler.accept(r);
    }

    /** Implemented by the client proxy; keeps client classes out of this file. */
    public interface ClientSink {
        void onState(State msg);

        void onProgress(Progress msg);

        void onResult(Result msg);
    }

    public static void sendTo(EntityPlayerMP player, IMessage msg) {
        if (player != null && player.connection != null) {
            com.tracktool.TrackToolCore.NETWORK.sendTo(msg, player);
        }
    }

    public static void sendToServer(IMessage msg) {
        com.tracktool.TrackToolCore.NETWORK.sendToServer(msg);
    }

    // ------------------------------------------------------------------
    // client -> server
    // ------------------------------------------------------------------

    /** Look-ray based end selection. The server performs its own ray cast. */
    public static final class Select implements IMessage, IMessageHandler<Select, IMessage> {
        public double ex;
        public double ey;
        public double ez;
        public double dx;
        public double dy = 1.0D;
        public double dz;
        public double maxDist = 16.0D;

        @Override
        public void fromBytes(ByteBuf buf) {
            this.ex = buf.readDouble();
            this.ey = buf.readDouble();
            this.ez = buf.readDouble();
            this.dx = buf.readDouble();
            this.dy = buf.readDouble();
            this.dz = buf.readDouble();
            this.maxDist = buf.readDouble();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeDouble(this.ex);
            buf.writeDouble(this.ey);
            buf.writeDouble(this.ez);
            buf.writeDouble(this.dx);
            buf.writeDouble(this.dy);
            buf.writeDouble(this.dz);
            buf.writeDouble(this.maxDist);
        }

        @Override
        public IMessage onMessage(Select message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.server.addScheduledTask(() -> SelectionManager.INSTANCE.handleSelect(player,
                    message.ex, message.ey, message.ez, message.dx, message.dy, message.dz, message.maxDist));
            return null;
        }
    }

    /** GUI parameters. */
    public static final class Params implements IMessage, IMessageHandler<Params, IMessage> {
        public TrackSpec spec = new TrackSpec();

        public Params() {
        }

        public Params(TrackSpec spec) {
            this.spec = spec;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.spec = TrackSpec.read(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            this.spec.write(buf);
        }

        @Override
        public IMessage onMessage(Params message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            // ★ 版本不一致就当场拒绝并明说：布局不同却照读，会得到"参数填对了、铺出来是另一条线"
            //   这种最难查的故障（服务器上客户端与服务端 jar 不同版是常事）。
            if (message.spec != null && message.spec.wireVersion != com.tracktool.rail.TrackSpec.VERSION) {
                final int cv = message.spec.wireVersion;
                player.server.addScheduledTask(() -> player.sendMessage(
                        new net.minecraft.util.text.TextComponentString(
                                net.minecraft.util.text.TextFormatting.RED
                                        + "[track-tool] 客户端与服务端版本不一致（客户端参数包 v" + cv
                                        + "，服务端 v" + com.tracktool.rail.TrackSpec.VERSION
                                        + "）。请让两边装同一个 tracktool jar，否则铺出来的线形不是你填的。")));
                return null;
            }
            player.server.addScheduledTask(() -> SelectionManager.INSTANCE.handleParams(player, message.spec));
            return null;
        }
    }

    /** Discrete action: clear / back / confirm / undo / sync. */
    public static final class Action implements IMessage, IMessageHandler<Action, IMessage> {
        public byte action;

        public Action() {
        }

        public Action(byte action) {
            this.action = action;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.action = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(this.action);
        }

        @Override
        public IMessage onMessage(Action message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.server.addScheduledTask(() -> SelectionManager.INSTANCE.handleAction(player, message.action));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // server -> client
    // ------------------------------------------------------------------

    /** Authoritative session snapshot. */
    public static final class State implements IMessage {
        public int dim;
        public TrackSpec spec = new TrackSpec();
        public boolean busy;
        public boolean operator = true;
        public int undoDepth;
        public String lastError;
        public String lastMessage;
        public List<RailEnd> ends = new ArrayList<RailEnd>();
        public boolean planOk = true;
        public String planError;
        /** Ask the client to open the staff GUI (used by /tracktool gui). */
        public boolean openGui;
        public double totalLength;
        public int segmentCount;
        public double maxDeviation;
        public double maxCant;
        public List<String> elements = new ArrayList<String>();

        @Override
        public void fromBytes(ByteBuf buf) {
            this.dim = buf.readInt();
            this.spec = TrackSpec.read(buf);
            this.busy = buf.readBoolean();
            this.operator = buf.readBoolean();
            this.undoDepth = buf.readInt();
            this.lastError = ByteBufUtils.readUTF8String(buf);
            this.lastMessage = ByteBufUtils.readUTF8String(buf);
            int n = buf.readByte();
            this.ends = new ArrayList<RailEnd>(Math.max(0, n));
            for (int i = 0; i < n; i++) {
                RailEnd e = new RailEnd();
                e.dim = buf.readInt();
                e.corePos = BlockPos.fromLong(buf.readLong());
                e.endIndex = buf.readByte();
                e.shapeName = ByteBufUtils.readUTF8String(buf);
                NBTTagCompound tag = ByteBufUtils.readTag(buf);
                if (tag != null) {
                    e.rp = RailPosition.readFromNBT(tag);
                }
                e.derive();
                // ★ 端点的位姿一律用服务端下发的值，不能在客户端按 RailPosition 重算：
                //   新路径的轨道几何【不经过 RailPosition】，服务端已改为按几何取端点
                //   （RailRayTrace.resolve）。客户端若自己 derive()，预览用的位姿就与服务端差
                //   最多 ~0.35 m ⇒ 预览与实际不是同一条线（连接模式下会被双圆弧解算放大成完全不同的走向）。
                e.x = buf.readDouble();
                e.y = buf.readDouble();
                e.z = buf.readDouble();
                e.outwardYaw = buf.readDouble();
                e.outwardPitch = buf.readDouble();
                e.jointCant = buf.readDouble();
                e.existingCant = buf.readDouble();
                this.ends.add(e);
            }
            this.planOk = buf.readBoolean();
            this.planError = ByteBufUtils.readUTF8String(buf);
            this.openGui = buf.readBoolean();
            this.totalLength = buf.readDouble();
            this.segmentCount = buf.readInt();
            this.maxDeviation = buf.readDouble();
            this.maxCant = buf.readDouble();
            int m = buf.readShort();
            this.elements = new ArrayList<String>(Math.max(0, m));
            for (int i = 0; i < m; i++) {
                this.elements.add(ByteBufUtils.readUTF8String(buf));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.dim);
            this.spec.write(buf);
            buf.writeBoolean(this.busy);
            buf.writeBoolean(this.operator);
            buf.writeInt(this.undoDepth);
            ByteBufUtils.writeUTF8String(buf, this.lastError == null ? "" : this.lastError);
            ByteBufUtils.writeUTF8String(buf, this.lastMessage == null ? "" : this.lastMessage);
            buf.writeByte(this.ends.size());
            for (RailEnd e : this.ends) {
                buf.writeInt(e.dim);
                buf.writeLong(e.corePos.toLong());
                buf.writeByte(e.endIndex);
                ByteBufUtils.writeUTF8String(buf, e.shapeName == null ? "" : e.shapeName);
                ByteBufUtils.writeTag(buf, e.rp == null ? new NBTTagCompound() : e.rp.writeToNBT());
                // 端点位姿（服务端已按几何修正过，客户端直接用，别自己 derive）
                buf.writeDouble(e.x);
                buf.writeDouble(e.y);
                buf.writeDouble(e.z);
                buf.writeDouble(e.outwardYaw);
                buf.writeDouble(e.outwardPitch);
                buf.writeDouble(e.jointCant);
                buf.writeDouble(e.existingCant);
            }
            buf.writeBoolean(this.planOk);
            ByteBufUtils.writeUTF8String(buf, this.planError == null ? "" : this.planError);
            buf.writeBoolean(this.openGui);
            buf.writeDouble(this.totalLength);
            buf.writeInt(this.segmentCount);
            buf.writeDouble(this.maxDeviation);
            buf.writeDouble(this.maxCant);
            buf.writeShort(this.elements.size());
            for (String s : this.elements) {
                ByteBufUtils.writeUTF8String(buf, s);
            }
        }

        public static final class Handler implements IMessageHandler<State, IMessage> {
            @Override
            public IMessage onMessage(State message, MessageContext ctx) {
                if (ctx.side == Side.CLIENT) {
                    schedule(() -> {
                        if (sink != null) {
                            sink.onState(message);
                        }
                    });
                }
                return null;
            }
        }
    }

    /** Placement progress. */
    /**
     * 服务端 → 客户端：这些核心已被撤掉，把缓存的精确几何丢掉。
     * 坐标用 {@link BlockPos#toLong()} 编码。
     */
    public static final class ExactRailForget implements IMessage {
        public long[] keys = new long[0];

        public ExactRailForget() {
        }

        public ExactRailForget(long[] keys) {
            this.keys = keys;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readInt();
            if (n < 0 || n > 65536) {
                n = 0;
            }
            this.keys = new long[n];
            for (int i = 0; i < n; i++) {
                this.keys[i] = buf.readLong();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.keys.length);
            for (long k : this.keys) {
                buf.writeLong(k);
            }
        }

        public static final class Handler implements IMessageHandler<ExactRailForget, IMessage> {
            @Override
            public IMessage onMessage(ExactRailForget m, MessageContext ctx) {
                // 缓存是 ConcurrentHashMap，与 ExactRail.Handler 一样直接在网络线程里改
                for (long k : m.keys) {
                    com.tracktool.rail2.ExactRailInjector.forgetClient(k);
                }
                return null;
            }
        }
    }

    public static final class Progress implements IMessage {
        public int done;
        public int total;
        public int blocks;

        @Override
        public void fromBytes(ByteBuf buf) {
            this.done = buf.readInt();
            this.total = buf.readInt();
            this.blocks = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.done);
            buf.writeInt(this.total);
            buf.writeInt(this.blocks);
        }

        public static final class Handler implements IMessageHandler<Progress, IMessage> {
            @Override
            public IMessage onMessage(Progress message, MessageContext ctx) {
                if (ctx.side == Side.CLIENT) {
                    schedule(() -> {
                        if (sink != null) {
                            sink.onProgress(message);
                        }
                    });
                }
                return null;
            }
        }
    }

    /** Placement result. */
    public static final class Result implements IMessage {        public boolean ok;
        public String messageKey = "";
        public int blocks;
        public int segments;
        public long millis;

        @Override
        public void fromBytes(ByteBuf buf) {
            this.ok = buf.readBoolean();
            this.messageKey = ByteBufUtils.readUTF8String(buf);
            this.blocks = buf.readInt();
            this.segments = buf.readInt();
            this.millis = buf.readLong();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(this.ok);
            ByteBufUtils.writeUTF8String(buf, this.messageKey == null ? "" : this.messageKey);
            buf.writeInt(this.blocks);
            buf.writeInt(this.segments);
            buf.writeLong(this.millis);
        }

        public static final class Handler implements IMessageHandler<Result, IMessage> {
            @Override
            public IMessage onMessage(Result message, MessageContext ctx) {
                if (ctx.side == Side.CLIENT) {
                    schedule(() -> {
                        if (sink != null) {
                            sink.onResult(message);
                        }
                    });
                }
                return null;
            }
        }
    }

    /** 服务端 -> 客户端：告知某核心为本模组自定几何（scriptArgs 与 anchorLength 均不可靠同步）。
     *
     *  <p>载荷含<b>采样点表</b>：服务端按【实际铺设所用的几何】等里程采样（1 m 一点，上限 1024 点，
     *  坐标为相对起点 RP 的偏移），客户端据此重建几何 ⇒ 两端逐点一致，不再靠参数串反推
     *  （参数串重建不出带竖曲线/多元素/平行偏移的 plan 线形）。参数串仍然带着，作兜底。</p> */
    public static final class ExactRail implements IMessage {

        public int x;
        public int y;
        public int z;
        public String args = "";
        /** 可为 null（兜底路径）。 */
        public com.tracktool.rail2.SampledGeometry samples;
        /** 服务端真实铺下去的路基方块表（相对核心的偏移，客户端直接拿来用，避免自己算出偏差）。 */
        public int[][] blocks;

        /** 表的长度上限（227 m 的弯道约 820 格）。 */
        private static final int MAX_BLOCKS = 8192;

        public ExactRail() {
        }

        public ExactRail(int x, int y, int z, String args) {
            this(x, y, z, args, null, null);
        }

        public ExactRail(int x, int y, int z, String args, com.tracktool.rail2.SampledGeometry samples) {
            this(x, y, z, args, samples, null);
        }

        public ExactRail(int x, int y, int z, String args,
                         com.tracktool.rail2.SampledGeometry samples, int[][] blocks) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.args = args == null ? "" : args;
            this.samples = samples;
            this.blocks = blocks;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.x = buf.readInt();
            this.y = buf.readInt();
            this.z = buf.readInt();
            int len = buf.readInt();
            byte[] b = new byte[Math.max(0, Math.min(len, 4096))];
            buf.readBytes(b);
            this.args = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            int n = buf.readInt();
            if (n < 2 || n > com.tracktool.rail2.SampledGeometry.MAX_POINTS) {
                this.samples = null;
                this.blocks = readBlocks(buf, this.x, this.y, this.z);   // 方块表仍然要读
                return;
            }
            double length = buf.readDouble();
            float[] ax = new float[n];
            float[] az = new float[n];
            float[] ay = new float[n];
            float[] ayaw = new float[n];
            float[] apitch = new float[n];
            float[] aroll = new float[n];
            for (int i = 0; i < n; i++) {
                ax[i] = buf.readFloat();
                az[i] = buf.readFloat();
                ay[i] = buf.readFloat();
                ayaw[i] = buf.readFloat();
                apitch[i] = buf.readFloat();
                aroll[i] = buf.readFloat();
            }
            this.samples = new com.tracktool.rail2.SampledGeometry(length, ax, az, ay, ayaw, apitch, aroll);
            this.blocks = readBlocks(buf, this.x, this.y, this.z);
        }

        private static int[][] readBlocks(ByteBuf buf, int cx, int cy, int cz) {
            if (!buf.isReadable(4)) {
                return null;
            }
            int m = buf.readInt();
            if (m <= 0 || m > MAX_BLOCKS) {
                return null;
            }
            int[][] out = new int[m][];
            for (int i = 0; i < m; i++) {
                out[i] = new int[]{cx + buf.readShort(), cy + buf.readShort(), cz + buf.readShort()};
            }
            return out;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(this.x);
            buf.writeInt(this.y);
            buf.writeInt(this.z);
            byte[] b = this.args.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeInt(b.length);
            buf.writeBytes(b);
            if (this.samples == null || this.samples.pointCount() < 2) {
                buf.writeInt(0);
                writeBlocks(buf);
                return;
            }
            int n = this.samples.pointCount();
            buf.writeInt(n);
            buf.writeDouble(this.samples.length());
            float[] ax = this.samples.arrX();
            float[] az = this.samples.arrZ();
            float[] ay = this.samples.arrY();
            float[] ayaw = this.samples.arrYaw();
            float[] apitch = this.samples.arrPitch();
            float[] aroll = this.samples.arrRoll();
            for (int i = 0; i < n; i++) {
                buf.writeFloat(ax[i]);
                buf.writeFloat(az[i]);
                buf.writeFloat(ay[i]);
                buf.writeFloat(ayaw[i]);
                buf.writeFloat(apitch[i]);
                buf.writeFloat(aroll[i]);
            }
            writeBlocks(buf);
        }

        /** 方块表按"相对核心的 short 偏移"写，227 m 弯道约 820 格 ≈ 5 KB。 */
        private void writeBlocks(ByteBuf buf) {
            if (this.blocks == null || this.blocks.length == 0 || this.blocks.length > MAX_BLOCKS) {
                buf.writeInt(0);
                return;
            }
            int m = 0;
            for (int[] b : this.blocks) {
                if (fits(b, this.x, this.y, this.z)) {
                    m++;
                }
            }
            buf.writeInt(m);
            for (int[] b : this.blocks) {
                if (!fits(b, this.x, this.y, this.z)) {
                    continue;
                }
                buf.writeShort(b[0] - this.x);
                buf.writeShort(b[1] - this.y);
                buf.writeShort(b[2] - this.z);
            }
        }

        private static boolean fits(int[] b, int cx, int cy, int cz) {
            return b != null && b.length >= 3
                    && Math.abs(b[0] - cx) <= Short.MAX_VALUE
                    && Math.abs(b[1] - cy) <= Short.MAX_VALUE
                    && Math.abs(b[2] - cz) <= Short.MAX_VALUE;
        }

        /** 服务端 -> 客户端：请客户端做一次自检并把结果打到聊天栏（诊断用）。 */
        public static final class Diag implements IMessage {
            @Override
            public void fromBytes(ByteBuf buf) {
            }

            @Override
            public void toBytes(ByteBuf buf) {
            }

            public static final class Handler implements IMessageHandler<Diag, IMessage> {
                @Override
                public IMessage onMessage(Diag m, MessageContext ctx) {
                    com.tracktool.rail2.ExactRailInjector.diagRequested = true;
                    return null;
                }
            }
        }

        public static final class Handler implements IMessageHandler<ExactRail, IMessage> {
            @Override
            public IMessage onMessage(ExactRail m, MessageContext ctx) {
                com.tracktool.rail2.ExactRailInjector.rememberArgs(m.x, m.y, m.z, m.args);
                com.tracktool.rail2.ExactRailInjector.rememberSamples(m.x, m.y, m.z, m.samples);
                com.tracktool.rail2.ExactRailInjector.rememberBlocks(m.x, m.y, m.z, m.blocks);
                return null;
            }
        }
    }
}