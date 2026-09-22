package com.tracktool.client;

import com.tracktool.TrackToolConfig;
import com.tracktool.rail.RailEnd;
import com.tracktool.util.Geo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Draws the two things the player needs to see in the world:
 * <ul>
 *   <li>the selected rail ends as yellow wire frames,</li>
 *   <li>the planned track as a blue translucent ribbon, predicted locally from
 *       the very same parameters the server will use.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public class RailOverlayRenderer {

    private static final float RIBBON_HALF_WIDTH = 1.5F;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null || mc.world == null) {
            return;
        }
        if (!isHoldingStaff(player)) {
            return;
        }
        float pt = event.getPartialTicks();
        double vx = mc.getRenderManager().viewerPosX;
        double vy = mc.getRenderManager().viewerPosY;
        double vz = mc.getRenderManager().viewerPosZ;

        GlStateManager.pushMatrix();
        GlStateManager.translate(-vx, -vy, -vz);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();

        try {
            if (TrackToolConfig.previewEnabled && ClientState.hasPlan) {
                drawPreview();
            }
            drawEndpoints();
        } catch (Throwable t) {
            // Never let a render hiccup take the frame down.
        }

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static boolean isHoldingStaff(EntityPlayer player) {
        return isStaff(player.getHeldItemMainhand()) || isStaff(player.getHeldItemOffhand());
    }

    private static boolean isStaff(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof com.tracktool.item.ItemRailStaff;
    }

    private static void drawEndpoints() {
        List<RailEnd> ends = ClientState.state.ends;
        if (ends == null || ends.isEmpty()) {
            return;
        }
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.depthMask(false);
        GlStateManager.glLineWidth(3.0F);
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (RailEnd e : ends) {
            box(buf, e.x - 0.35D, e.y, e.z - 0.35D, e.x + 0.35D, e.y + 0.7D, e.z + 0.35D, 1.0F, 0.9F, 0.1F, 1.0F);
            // A short pole so the marker is findable from a distance.
            line(buf, e.x, e.y + 0.7D, e.z, e.x, e.y + 2.2D, e.z, 1.0F, 0.85F, 0.05F, 1.0F);
            // Cross-hair showing the outward heading.
            double hx = Geo.dirX(e.outwardYaw) * 1.4D;
            double hz = Geo.dirZ(e.outwardYaw) * 1.4D;
            line(buf, e.x, e.y + 0.1D, e.z, e.x + hx, e.y + 0.1D, e.z + hz, 1.0F, 1.0F, 0.2F, 1.0F);
        }
        tess.draw();
        GlStateManager.depthMask(true);
        GlStateManager.glLineWidth(1.0F);
    }

    private static void drawPreview() {
        List<double[]> pts = ClientState.plan.preview;
        if (pts == null || pts.size() < 2) {
            return;
        }
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.depthMask(false);

        // Translucent blue ribbon following the planned centre line.
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < pts.size() - 1; i++) {
            double[] a = pts.get(i);
            double[] b = pts.get(i + 1);
            if (Math.abs(a[0] - b[0]) > 40.0D || Math.abs(a[1] - b[1]) > 40.0D) {
                continue;
            }
            double nax = Geo.leftX(a[3]) * RIBBON_HALF_WIDTH;
            double naz = Geo.leftZ(a[3]) * RIBBON_HALF_WIDTH;
            double nbx = Geo.leftX(b[3]) * RIBBON_HALF_WIDTH;
            double nbz = Geo.leftZ(b[3]) * RIBBON_HALF_WIDTH;
            float ay = (float) (a[2] + 0.08D);
            float by = (float) (b[2] + 0.08D);
            // top face
            buf.pos(a[0] - nax, ay, a[1] - naz).color(0.25F, 0.55F, 1.0F, 0.35F).endVertex();
            buf.pos(b[0] - nbx, by, b[1] - nbz).color(0.25F, 0.55F, 1.0F, 0.35F).endVertex();
            buf.pos(b[0] + nbx, by, b[1] + nbz).color(0.25F, 0.55F, 1.0F, 0.35F).endVertex();
            buf.pos(a[0] + nax, ay, a[1] + naz).color(0.25F, 0.55F, 1.0F, 0.35F).endVertex();
            // side skirts, so the preview reads as a solid body
            float ad = (float) (a[2] - 0.35D);
            float bd = (float) (b[2] - 0.35D);
            buf.pos(a[0] + nax, ad, a[1] + naz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(b[0] + nbx, bd, b[1] + nbz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(b[0] + nbx, by, b[1] + nbz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(a[0] + nax, ay, a[1] + naz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(a[0] - nax, ay, a[1] - naz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(b[0] - nbx, by, b[1] - nbz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(b[0] - nbx, bd, b[1] - nbz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
            buf.pos(a[0] - nax, ad, a[1] - naz).color(0.15F, 0.35F, 0.85F, 0.30F).endVertex();
        }
        tess.draw();
        GlStateManager.depthMask(true);

        // Centre line on top, so the line shape is readable even at a distance.
        GlStateManager.glLineWidth(2.0F);
        buf.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (double[] p : pts) {
            buf.pos(p[0], p[2] + 0.12D, p[1]).color(0.45F, 0.75F, 1.0F, 0.9F).endVertex();
        }
        tess.draw();
        GlStateManager.glLineWidth(1.0F);
    }

    private static void box(BufferBuilder buf, double x0, double y0, double z0,
                            double x1, double y1, double z1, float r, float g, float b, float a) {
        line(buf, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buf, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buf, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buf, x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(buf, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buf, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buf, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buf, x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(buf, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buf, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buf, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buf, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(BufferBuilder buf, double x0, double y0, double z0,
                             double x1, double y1, double z1, float r, float g, float b, float a) {
        buf.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        buf.pos(x1, y1, z1).color(r, g, b, a).endVertex();
    }

    /** Convenience for other client code. */
    public static Vec3d eye(Minecraft mc, float pt) {
        return mc.player.getPositionEyes(pt);
    }
}
