/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.wurstclient.WurstClient;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

/**
 * Teleports the player up or down through blocks by sending spoofed
 * movement packets, instead of one real position update. Large downward
 * clips are split into safe steps so the server doesn't see an impossible
 * single-tick fall.
 */
public final class VClipCmd extends Command
{
	// packets larger than this vertical delta get split into steps
	private static final double DIRECT_FALL_LIMIT = 3.0;
	private static final int SEGMENT_BLOCKS = 10;
	private static final int MAX_PACKETS = 20;
	
	// used by "top"/"bottom" auto-scan
	private static final double SCAN_STEP = 0.5;
	private static final double SCAN_RANGE = 128.0;
	
	public VClipCmd()
	{
		super("vclip",
			"Teleports you up or down through blocks by spoofing\n"
				+ "movement packets.",
			".vclip <blocks>", ".vclip top", ".vclip bottom");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length != 1)
			throw new CmdSyntaxError();
		
		LocalPlayer player = WurstClient.MC.player;
		if(player == null || WurstClient.MC.getConnection() == null)
			throw new CmdError("Not in game.");
		
		double blocks;
		String arg = args[0].toLowerCase(Locale.ROOT);
		
		switch(arg)
		{
			case "top":
			blocks = findSafeVertical(player, true);
			if(Double.isNaN(blocks))
				throw new CmdError("No safe spot found above you.");
			break;
			
			case "bottom":
			blocks = findSafeVertical(player, false);
			if(Double.isNaN(blocks))
				throw new CmdError("No safe spot found below you.");
			break;
			
			default:
			try
			{
				blocks = Double.parseDouble(args[0]);
			}catch(NumberFormatException e)
			{
				throw new CmdSyntaxError();
			}
		}
		
		if(blocks == 0)
		{
			ChatUtils.error("Distance can't be zero.");
			return;
		}
		
		doClip(player, blocks);
	}
	
	/**
	 * Scans straight up or down from the player's current position for the
	 * nearest spot where their hitbox would have no collision and would be
	 * resting on something solid. Returns the vertical delta, or NaN if
	 * nothing was found within range.
	 */
	private double findSafeVertical(LocalPlayer player, boolean up)
	{
		Vec3 start = player.position();
		double direction = up ? 1 : -1;
		
		for(double offset = SCAN_STEP; offset <= SCAN_RANGE;
			offset += SCAN_STEP)
		{
			Vec3 candidate =
				new Vec3(start.x, start.y + direction * offset, start.z);
			BlockPos pos = BlockPos.containing(candidate);
			
			if(player.level() == null || !player.level().isLoaded(pos))
				continue;
			
			Vec3 delta = candidate.subtract(start);
			AABB movedBox = player.getBoundingBox().move(delta);
			
			boolean clear = player.level().noCollision(player, movedBox);
			boolean supported = !player.level().noCollision(player,
				movedBox.move(0.0, -0.0625, 0.0));
			
			if(clear && supported)
				return candidate.y - start.y;
		}
		
		return Double.NaN;
	}
	
	private void doClip(LocalPlayer player, double blocks)
	{
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		boolean onGround = player.onGround();
		boolean horizontalCollision = player.horizontalCollision;
		
		int packets;
		
		if(blocks < -DIRECT_FALL_LIMIT)
		{
			packets = sendFallSafeClip(player, x, y, z, blocks);
			
		}else
		{
			packets = (int)Math.ceil(Math.abs(blocks) / (double)SEGMENT_BLOCKS);
			if(packets < 1)
				packets = 1;
			if(packets > MAX_PACKETS)
				packets = 1;
			
			player.resetFallDistance();
			
			for(int i = 0; i < packets - 1; i++)
				sendPacket(new ServerboundMovePlayerPacket.StatusOnly(
					onGround, horizontalCollision));
			
			sendPacket(new ServerboundMovePlayerPacket.Pos(x, y + blocks, z,
				onGround, horizontalCollision));
			
			player.setPos(x, y + blocks, z);
			clearFallState(player);
		}
		
		String message = "VClip " + blocks + " (" + packets + " packet"
			+ (packets == 1 ? "" : "s") + ")";
		ChatUtils.message(message);
	}
	
	/**
	 * Steps a large downward clip in DIRECT_FALL_LIMIT-sized increments
	 * instead of one big jump, so the server never sees a single tick of
	 * free-fall larger than what vanilla physics could produce.
	 */
	private int sendFallSafeClip(LocalPlayer player, double x, double y,
		double z, double blocks)
	{
		clearFallState(player);
		
		int sent = 0;
		sendPacket(new ServerboundMovePlayerPacket.StatusOnly(true, false));
		sent++;
		
		double targetY = y + blocks;
		double travelled = 0.0;
		double remaining = targetY - y;
		
		while(Math.abs(travelled) < Math.abs(remaining))
		{
			double stepSize = Math.min(DIRECT_FALL_LIMIT,
				Math.abs(remaining) - Math.abs(travelled));
			travelled += Math.copySign(stepSize, remaining);
			
			sendPacket(new ServerboundMovePlayerPacket.Pos(x, y + travelled,
				z, true, false));
			sent++;
		}
		
		player.setPos(x, targetY, z);
		clearFallState(player);
		
		return sent;
	}
	
	private void sendPacket(Packet<?> packet)
	{
		WurstClient.MC.getConnection().send(packet);
	}
	
	private void clearFallState(LocalPlayer player)
	{
		player.resetFallDistance();
		Vec3 velocity = player.getDeltaMovement();
		if(velocity.y < 0.0)
			player.setDeltaMovement(velocity.x, 0.0, velocity.z);
	}
}
