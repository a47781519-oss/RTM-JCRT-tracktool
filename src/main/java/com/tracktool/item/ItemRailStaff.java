package com.tracktool.item;

import com.tracktool.GuiBridge;
import com.tracktool.TrackToolConfig;
import com.tracktool.net.Packets;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The laying staff.
 *
 * <p>Right-clicking a rail asks the server to select the nearer end; the server
 * performs its own ray cast, so a modified client cannot select a rail on the
 * other side of the map. The GUI opens at the same time. The stack also carries
 * the rail model pack and ballast chosen in the GUI.</p>
 */
public class ItemRailStaff extends Item {

    public static final String TAG_RAIL = "RailResource";
    public static final String TAG_BALLAST = "BallastBlock";
    public static final String TAG_BALLAST_META = "BallastMeta";
    public static final String TAG_BALLAST_HEIGHT = "BallastHeight";

    public ItemRailStaff() {
        this.setMaxStackSize(1);
        this.setMaxDamage(0);
        this.setHasSubtypes(false);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) {
            // Rail blocks cannot be hit by Minecraft's own ray trace (their
            // collision box is degenerate), so a selection attempt is sent on
            // every right click and the server decides with its own ray cast.
            sendSelect(player);
            GuiBridge.open(player);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            sendSelect(player);
        }
        return EnumActionResult.SUCCESS;
    }

    private static void sendSelect(EntityPlayer player) {
        Vec3d eye = player.getPositionEyes(1.0F);
        Vec3d look = player.getLook(1.0F);
        Packets.Select pkt = new Packets.Select();
        pkt.ex = eye.x;
        pkt.ey = eye.y;
        pkt.ez = eye.z;
        pkt.dx = look.x;
        pkt.dy = look.y;
        pkt.dz = look.z;
        pkt.maxDist = TrackToolConfig.selectDistance;
        Packets.sendToServer(pkt);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               net.minecraft.client.util.ITooltipFlag flag) {
        String rail = getRailResource(stack);
        tooltip.add(I18n.translateToLocalFormatted("tracktool.tip.rail",
                rail == null || rail.isEmpty() ? I18n.translateToLocal("tracktool.tip.default") : rail));
        String ballast = getBallastName(stack);
        tooltip.add(I18n.translateToLocalFormatted("tracktool.tip.ballast",
                ballast == null || ballast.isEmpty() ? I18n.translateToLocal("tracktool.tip.default") : ballast));
        tooltip.add(I18n.translateToLocal("tracktool.tip.usage"));
    }

    /** Rail model pack stored in the stack, or "" when the pack default is used. */
    public static String getRailResource(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? "" : tag.getString(TAG_RAIL);
    }

    public static void setRailResource(ItemStack stack, String name) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setString(TAG_RAIL, name == null ? "" : name);
    }

    public static String getBallastName(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? "" : tag.getString(TAG_BALLAST);
    }

    public static int getBallastMeta(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? 0 : tag.getInteger(TAG_BALLAST_META);
    }

    public static float getBallastHeight(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null || !tag.hasKey(TAG_BALLAST_HEIGHT) ? 0.0625F : tag.getFloat(TAG_BALLAST_HEIGHT);
    }

    public static void setBallast(ItemStack stack, String blockName, int meta, float height) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setString(TAG_BALLAST, blockName == null ? "" : blockName);
        tag.setInteger(TAG_BALLAST_META, meta);
        tag.setFloat(TAG_BALLAST_HEIGHT, height <= 0.0F ? 0.0625F : height);
    }
}
