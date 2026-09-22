package com.tracktool.rail;

import com.tracktool.TrackToolConfig;
import com.tracktool.TrackToolCore;
import com.tracktool.net.Packets;
import com.tracktool.rail.plan.PlanSegment;
import com.tracktool.rail.plan.RailPlan;
import jp.ngt.rtm.modelpack.state.ResourceStateRail;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Deferred placement engine.
 *
 * <p>A long curve is planned as one alignment but written to the world a few
 * rail cores per tick, so a thousand-metre curve neither freezes the server nor
 * depends on the whole curve being inside the player's loaded chunks. Chunks a
 * queued segment needs are loaded on demand, which is how "the whole curve must
 * be laid" is guaranteed even when part of it is outside the loaded area.</p>
 *
 * <p>Only one job per dimension runs at a time: that is the concurrency lock
 * for edits. A failing segment rolls the whole job back, so placement is
 * transactional.</p>
 */
public final class PlacementQueue {

    public static final PlacementQueue INSTANCE = new PlacementQueue();

    private final List<Task> tasks = new ArrayList<Task>();
    private final List<Task> pending = new ArrayList<Task>();

    private PlacementQueue() {
    }

    /** One generation job. */
    public static final class Task {
        public final UUID owner;
        public final EntityPlayerMP player;
        public final SelectionManager.Session session;
        public final RailPlan plan;
        public final ResourceStateRail prop;
        public final boolean creative;
        public int index;
        public int placed;
        public int failedAt = -1;
        public String failKey;
        public long startTime = System.currentTimeMillis();
        public long lastProgress;
        public RailPlacer.UndoRecord undo = new RailPlacer.UndoRecord();
        public boolean done;
        public boolean rolledBack;

        Task(UUID owner, EntityPlayerMP player, SelectionManager.Session session, RailPlan plan,
             ResourceStateRail prop, boolean creative) {
            this.owner = owner;
            this.player = player;
            this.session = session;
            this.plan = plan;
            this.prop = prop;
            this.creative = creative;
        }

        public boolean isAlive() {
            return this.player != null && !this.player.isDead && this.player.connection != null;
        }
    }

    public void submit(Task task) {
        task.session.busy = true;
        boolean free = !this.dimensionBusy(task.player.world.provider.getDimension())
                && this.pending.isEmpty();
        this.pending.add(task);
        TrackToolCore.info("queued job for %s (dim=%d, segments=%d, running=%d, pending=%d, lockFree=%s)",
                task.player.getName(), task.player.world.provider.getDimension(),
                task.plan.segmentCount(), this.tasks.size(), this.pending.size(), free);
    }

    public void cancelFor(UUID owner) {
        for (Iterator<Task> it = this.pending.iterator(); it.hasNext(); ) {
            if (it.next().owner.equals(owner)) {
                it.remove();
            }
        }
        for (Iterator<Task> it = this.tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.owner.equals(owner)) {
                RailPlacer.restore(t.player.world, t.undo);
                t.session.busy = false;
                it.remove();
            }
        }
    }

    public void clearAll() {
        this.pending.clear();
        this.tasks.clear();
    }

    public boolean isBusy(EntityPlayerMP player) {
        for (Task t : this.tasks) {
            if (t.player == player) {
                return true;
            }
        }
        return false;
    }

    public int queueSize() {
        return this.pending.size() + this.tasks.size();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Promote pending jobs whose dimension is free (the concurrency lock).
        for (Iterator<Task> it = this.pending.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (!t.isAlive()) {
                t.session.busy = false;
                it.remove();
                continue;
            }
            if (!this.dimensionBusy(t.player.world.provider.getDimension())) {
                it.remove();
                this.tasks.add(t);
                TrackToolCore.info("job started for %s (dim=%d, segments=%d)",
                        t.player.getName(), t.player.world.provider.getDimension(), t.plan.segmentCount());
            }
        }
        for (Iterator<Task> it = this.tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (!t.isAlive()) {
                RailPlacer.restore(t.player.world, t.undo);
                t.session.busy = false;
                it.remove();
                continue;
            }
            this.step(t);
            if (t.done) {
                t.session.busy = false;
                it.remove();
            }
        }
    }

    private boolean dimensionBusy(int dim) {
        for (Task t : this.tasks) {
            if (t.player.world.provider.getDimension() == dim) {
                return true;
            }
        }
        return false;
    }

    private void step(Task t) {
        int segments = 0;
        int blocks = 0;
        while (t.index < t.plan.segments.size()
                && segments < TrackToolConfig.segmentsPerTick
                && blocks < TrackToolConfig.blocksPerTick) {
            PlanSegment seg = t.plan.segments.get(t.index);
            if (!this.ensureLoaded(t, seg)) {
                // Chunk not available yet: retry next tick instead of failing.
                break;
            }
            boolean ok;
            ok = RailPlacer.place(t.player.world, seg.start, seg.end, t.prop, t.creative, t.undo);
            if (!ok) {
                t.failedAt = t.index;
                t.failKey = "tracktool.err.place_failed";
                this.rollback(t);
                return;
            }
            blocks += (int) (seg.length3d() * 3.0D) + 8;
            segments++;
            t.index++;
            t.placed++;
        }
        long now = System.currentTimeMillis();
        if (now - t.lastProgress > 250L || t.index >= t.plan.segments.size()) {
            t.lastProgress = now;
            Packets.Progress p = new Packets.Progress();
            p.done = t.index;
            p.total = t.plan.segments.size();
            p.blocks = t.undo.size();
            Packets.sendTo(t.player, p);
        }
        if (t.index >= t.plan.segments.size()) {
            this.finish(t);
        }
    }

    private boolean ensureLoaded(Task t, PlanSegment seg) {
        int x0 = Math.min(seg.start.blockX, seg.end.blockX) - 2;
        int x1 = Math.max(seg.start.blockX, seg.end.blockX) + 2;
        int z0 = Math.min(seg.start.blockZ, seg.end.blockZ) - 2;
        int z1 = Math.max(seg.start.blockZ, seg.end.blockZ) + 2;
        int y = Math.min(seg.start.blockY, seg.end.blockY);
        boolean all = true;
        for (int cx = x0 >> 4; cx <= (x1 >> 4); cx++) {
            for (int cz = z0 >> 4; cz <= (z1 >> 4); cz++) {
                if (!t.player.world.isBlockLoaded(new BlockPos(cx << 4, y, cz << 4))) {
                    if (TrackToolConfig.forceLoadChunks) {
                        // Loading generates the chunk if needed; this is what
                        // makes "the whole curve is always laid" true.
                        try {
                            t.player.world.getChunk(cx, cz);
                        } catch (Throwable e) {
                            return false;
                        }
                    } else {
                        all = false;
                    }
                }
            }
        }
        return all || TrackToolConfig.forceLoadChunks;
    }

    private void finish(Task t) {
        t.done = true;
        TrackToolCore.info("job finished for %s: %d segments, %d blocks, %d ms",
                t.player.getName(), t.placed, t.undo.size(),
                System.currentTimeMillis() - t.startTime);
        SelectionManager.INSTANCE.pushUndo(t.session, t.undo);
        Packets.Result r = new Packets.Result();
        r.ok = true;
        r.blocks = t.undo.size();
        r.segments = t.placed;
        r.millis = System.currentTimeMillis() - t.startTime;
        r.messageKey = "tracktool.msg.placed";
        Packets.sendTo(t.player, r);
        t.player.sendMessage(new TextComponentTranslation("tracktool.msg.placed",
                String.valueOf(t.placed), String.valueOf(t.undo.size())));
        SelectionManager.INSTANCE.sync(t.player, t.session);
    }

    private void rollback(Task t) {
        t.done = true;
        t.rolledBack = true;
        try {
            RailPlacer.restore(t.player.world, t.undo);
        } catch (Throwable e) {
            TrackToolCore.warn("rollback failed: %s", e.toString());
        }
        Packets.Result r = new Packets.Result();
        r.ok = false;
        r.messageKey = t.failKey;
        r.segments = t.placed;
        r.blocks = 0;
        Packets.sendTo(t.player, r);
        t.player.sendMessage(new TextComponentTranslation("tracktool.msg.rollback",
                String.valueOf(t.placed)));
        SelectionManager.INSTANCE.sync(t.player, t.session);
    }
}
