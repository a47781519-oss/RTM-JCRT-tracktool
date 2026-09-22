package com.tracktool.rail2;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** 把"某个核心用的是我们的几何"持久化到存档（每维度一份）。
 *
 *  <p>为什么必须存：{@code TileEntityLargeRailCore} 读档后会自己 {@code createRailMap()}，
 *  于是 railmap 又变回 RTM 的贝塞尔 —— 方块还在原地，钢轨与车辆路径却回到贝塞尔线上
 *  ⇒ 重进世界后"钢轨与路基分离 / 车跑偏"。存下采样点表后，服务端每秒扫一次把几何补回去，
 *  并把点表重新推给附近玩家（客户端同理会重建 railmap）。</p>
 *
 *  <p>存的是【采样点表】而不是参数串：与铺设当时所用几何逐点同源，不存在"参数反推不回来"的问题。</p> */
public final class ExactRailPersistence extends WorldSavedData {

    public static final String NAME = "tracktool_exact";

    private final Map<Long, SampledGeometry> geos = new HashMap<Long, SampledGeometry>();

    public ExactRailPersistence() {
        super(NAME);
    }

    public ExactRailPersistence(String name) {
        super(name);
    }

    public static ExactRailPersistence get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        ExactRailPersistence data =
                (ExactRailPersistence) storage.getOrLoadData(ExactRailPersistence.class, NAME);
        if (data == null) {
            data = new ExactRailPersistence();
            storage.setData(NAME, data);
        }
        return data;
    }

    /** 铺设成功后登记（服务端）。 */
    public static void remember(World world, BlockPos corePos, SampledGeometry geo) {
        if (world == null || world.isRemote || corePos == null || geo == null) {
            return;
        }
        try {
            ExactRailPersistence data = get(world);
            data.geos.put(corePos.toLong(), geo);
            data.markDirty();
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] PERSIST-FAILED: " + t);
        }
    }

    public SampledGeometry find(BlockPos pos) {
        return this.geos.get(pos.toLong());
    }

    public boolean isEmpty() {
        return this.geos.isEmpty();
    }

    public int size() {
        return this.geos.size();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        this.geos.clear();
        NBTTagList list = nbt.getTagList("rails", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            long pos = tag.getLong("p");
            double len = tag.getDouble("l");
            byte[] data = tag.getByteArray("d");
            SampledGeometry g = unpack(len, data);
            if (g != null) {
                this.geos.put(pos, g);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, SampledGeometry> e : this.geos.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("p", e.getKey());
            tag.setDouble("l", e.getValue().length());
            tag.setByteArray("d", pack(e.getValue()));
            list.appendTag(tag);
        }
        nbt.setTag("rails", list);
        return nbt;
    }

    /** 6 个分量 × n 点的 float，按点交错打包。 */
    static byte[] pack(SampledGeometry g) {
        int n = g.pointCount();
        ByteBuffer bb = ByteBuffer.allocate(n * 6 * 4);
        float[] ax = g.arrX();
        float[] az = g.arrZ();
        float[] ay = g.arrY();
        float[] ayaw = g.arrYaw();
        float[] apitch = g.arrPitch();
        float[] aroll = g.arrRoll();
        for (int i = 0; i < n; i++) {
            bb.putFloat(ax[i]);
            bb.putFloat(az[i]);
            bb.putFloat(ay[i]);
            bb.putFloat(ayaw[i]);
            bb.putFloat(apitch[i]);
            bb.putFloat(aroll[i]);
        }
        return bb.array();
    }

    static SampledGeometry unpack(double len, byte[] data) {
        if (data == null || data.length < 2 * 6 * 4 || data.length % (6 * 4) != 0) {
            return null;
        }
        int n = data.length / (6 * 4);
        ByteBuffer bb = ByteBuffer.wrap(data);
        float[] ax = new float[n];
        float[] az = new float[n];
        float[] ay = new float[n];
        float[] ayaw = new float[n];
        float[] apitch = new float[n];
        float[] aroll = new float[n];
        for (int i = 0; i < n; i++) {
            ax[i] = bb.getFloat();
            az[i] = bb.getFloat();
            ay[i] = bb.getFloat();
            ayaw[i] = bb.getFloat();
            apitch[i] = bb.getFloat();
            aroll[i] = bb.getFloat();
        }
        return new SampledGeometry(len, ax, az, ay, ayaw, apitch, aroll);
    }
}
