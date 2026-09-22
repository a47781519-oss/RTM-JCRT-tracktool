package com.tracktool.rail2;

import jp.ngt.rtm.rail.TileEntityLargeRailCore;
import jp.ngt.rtm.rail.util.RailPosition;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端侧的"几何补注入 + 向客户端补发点表"。
 *
 *  <p>解决两件读档后才暴露的事：</p>
 *  <ol>
 *    <li>核心 TE 读档会 {@code createRailMap()} ⇒ railmap 退回贝塞尔，
 *        车辆与渲染就不再走我们的线形（方块却还在原处）⇒ 必须把几何再换回去；</li>
 *    <li>客户端只在铺设那一刻收到过点表 ⇒ 重进游戏后没有，必须按距离补发。</li>
 *  </ol>
 *
 *  <p>每 40 tick（2 秒）扫一次<b>已加载的</b> TE 列表，只处理存档里登记过的核心，
 *  已经是本几何的直接跳过（{@code swapWith} 内部判重）。 */
public final class ExactRailServerSync {

    private static final int PERIOD_TICKS = 40;
    private static final double SEND_RADIUS = 192.0D;

    /** 每个玩家已经收到过点表的核心（退出游戏时清空 ⇒ 重进会重发）。 */
    private static final Map<UUID, Set<Long>> SENT = new ConcurrentHashMap<UUID, Set<Long>>();

    private int counter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.counter < PERIOD_TICKS) {
            return;
        }
        this.counter = 0;
        try {
            WorldServer[] worlds = FMLCommonHandler.instance().getMinecraftServerInstance().worlds;
            if (worlds == null) {
                return;
            }
            for (WorldServer world : worlds) {
                if (world != null) {
                    tickWorld(world);
                }
            }
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] SERVER-SYNC 异常: " + t);
        }
    }

    private static void tickWorld(WorldServer world) {
        ExactRailPersistence store = ExactRailPersistence.get(world);
        if (store.isEmpty()) {
            return;
        }
        // 用下标遍历而不是迭代器：世界 tick 期间列表可能被改动。
        for (int i = 0; i < world.loadedTileEntityList.size(); i++) {
            TileEntity te;
            try {
                te = world.loadedTileEntityList.get(i);
            } catch (Throwable t) {
                break;
            }
            if (!(te instanceof TileEntityLargeRailCore)) {
                continue;
            }
            TileEntityLargeRailCore core = (TileEntityLargeRailCore) te;
            BlockPos pos = core.getPos();
            SampledGeometry geo = store.find(pos);
            if (geo == null) {
                continue;
            }
            RailPosition[] rps = core.getRailPositions();
            if (rps == null || rps.length < 1 || rps[0] == null) {
                continue;
            }
            // 已是本几何时 swapWith 内部会直接返回（不刷屏）。
            ExactRailInjector.swapWith(core, rps[0], geo, "reload");
            pushToNearbyPlayers(world, core, pos, geo, rps);
        }
    }

    private static void pushToNearbyPlayers(World world, TileEntityLargeRailCore core, BlockPos pos,
                                            SampledGeometry geo, RailPosition[] rps) {
        long key = pos.toLong();
        int[][] table = null;
        boolean tableBuilt = false;
        for (EntityPlayer p : world.playerEntities) {
            if (!(p instanceof EntityPlayerMP)) {
                continue;
            }
            double dx = pos.getX() + 0.5D - p.posX;
            double dy = pos.getY() + 0.5D - p.posY;
            double dz = pos.getZ() + 0.5D - p.posZ;
            if (dx * dx + dy * dy + dz * dz > SEND_RADIUS * SEND_RADIUS) {
                continue;
            }
            Set<Long> sent = SENT.get(p.getUniqueID());
            if (sent == null) {
                sent = java.util.Collections.synchronizedSet(new HashSet<Long>());
                SENT.put(p.getUniqueID(), sent);
            }
            if (!sent.add(key)) {
                continue;
            }
            if (!tableBuilt) {
                tableBuilt = true;
                table = buildTable(world, core, geo, rps);       // 真要发的时候才算
            }
            com.tracktool.net.Packets.sendTo((EntityPlayerMP) p,
                    new com.tracktool.net.Packets.ExactRail(pos.getX(), pos.getY(), pos.getZ(), "", geo, table));
            System.out.println("[tracktool-exact] RESYNC 点表 -> " + p.getName() + " core=" + pos
                    + " pts=" + geo.pointCount() + " 方块表=" + (table == null ? 0 : table.length));
        }
    }

    /**
     * 重算客户端用的路基方块表。
     *
     * <p>★ 必须用 {@link ExactRailMap}（纯解析几何），<b>不能用核心当前的 RailMapBasic</b>：
     * RTM 的 {@code RailMapBasic.getRailHeight} 会额外加 {@code |sin(超高)|·1.5}，
     * 而 {@code createRailList} 里 {@code int y = (int) 高度} 一取整就把整列抬高一格。
     * 铺设当时用的正是 ExactRailMap（不含这份抬升），两者一旦不一致，表里的格子在世界里
     * 就是空气 ⇒ RTM 画成 {@code renderMissingBlock} 的<b>基岩贴图</b>。
     * 第 60 轮用户实测"抬升高度时有几段基岩路基"就是这个：平道上 4.0625+0.26 取整仍是 4，
     * 所以只有<b>又有超高又有坡度</b>时才会暴露（存档实证：表里 (868,6,999) 是空气，
     * 真正的方块在 (868,5,999)）。</p>
     *
     * <p>最后再用 {@link ExactRailLayer#snapTableToWorld} 按世界实况兜底一次，
     * 这样旧存档里已经错位的轨道也能自愈。</p>
     */
    private static int[][] buildTable(World world, TileEntityLargeRailCore core,
                                      SampledGeometry geo, RailPosition[] rps) {
        try {
            jp.ngt.rtm.rail.util.RailMap rm = rps.length >= 2 && rps[1] != null
                    ? new ExactRailMap(rps[0], rps[1], geo)
                    : core.getRailMap(null);
            if (rm == null) {
                return null;
            }
            int[][] table = ExactRailLayer.withJointCells(world,
                    rm, ExactRailLayer.blockTable(rm, core.getResourceState()));
            return ExactRailLayer.snapTableToWorld(world, table);
        } catch (Throwable t) {
            System.out.println("[tracktool-exact] RESYNC 方块表重算失败: " + t);
            return null;
        }
    }

    /** 玩家下线即忘，重进时会重新补发。 */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player != null) {
            SENT.remove(event.player.getUniqueID());
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player != null) {
            SENT.remove(event.player.getUniqueID());
        }
    }
}
