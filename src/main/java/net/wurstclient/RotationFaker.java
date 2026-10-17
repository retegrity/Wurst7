/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import java.util.Random;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.events.PostMotionListener;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

public final class RotationFaker
	implements PreMotionListener, PostMotionListener
{
	private boolean fakeRotation;
	private float serverYaw;
	private float serverPitch;
	private float realYaw;
	private float realPitch;
	private boolean applied;
	
	// Rotation that stays active for a few ticks, so other players see a
	// stable head direction instead of a rotation that snaps back every tick.
	private boolean managed;
	private float managedYaw;
	private float managedPitch;
	private int managedTicksLeft;
	private boolean movementCorrection;
	
	// when > 0, an expired managed rotation turns back to the camera rotation
	// at this speed (degrees per tick) instead of snapping back
	private float releaseSpeed;
	private boolean releasing;
	
	private final Random random = new Random();
	private float lastTurnYawDelta;
	private float lastTurnPitchDelta;
	
	@Override
	public void onPreMotion()
	{
		LocalPlayer player = WurstClient.MC.player;
		
		float yaw;
		float pitch;
		if(fakeRotation)
		{
			yaw = serverYaw;
			pitch = serverPitch;
			
		}else if(managed)
		{
			yaw = managedYaw;
			pitch = managedPitch;
			
		}else
			return;
		
		applied = true;
		realYaw = player.getYRot();
		realPitch = player.getXRot();
		player.setYRot(yaw);
		player.setXRot(pitch);
	}
	
	@Override
	public void onPostMotion()
	{
		LocalPlayer player = WurstClient.MC.player;
		if(applied)
		{
			player.setYRot(realYaw);
			player.setXRot(realPitch);
			fakeRotation = false;
			applied = false;
		}
		
		// Update the managed rotation for the next tick here, after this
		// tick's packet was sent, so that next tick's movement (which happens
		// before PreMotion) and next tick's packet use the same rotation.
		// Otherwise the anti-cheat's movement simulation doesn't match.
		if(!managed)
			return;
		
		if(!releasing && --managedTicksLeft < 0)
		{
			if(releaseSpeed <= 0)
			{
				clearManagedRotation();
				return;
			}
			
			releasing = true;
		}
		
		if(releasing)
			updateRelease(player);
	}
	
	/**
	 * Silently turns towards the given point, the way LiquidBounce's linear
	 * rotations do with both turn speeds at 180°: the rotation goes straight
	 * to the target and is corrected for the mouse sensitivity grid. It stays
	 * active for the given number of ticks after the last call.
	 *
	 * @param movementCorrection
	 *            whether the player's movement should be calculated from the
	 *            silent rotation instead of the camera rotation
	 */
	public void faceVectorSilently(Vec3 vec, int ticksUntilReset,
		boolean movementCorrection)
	{
		turnTowards(vec, 180, ticksUntilReset, movementCorrection, 0);
	}
	
	/**
	 * Like {@link #faceVectorSilently(Vec3, int, boolean)}, but turns by at
	 * most the given number of degrees per tick. Once the rotation expires,
	 * it turns back to the camera rotation at the given release speed instead
	 * of snapping back.
	 */
	public void faceVectorSmoothly(Vec3 vec, float maxTurn, int ticksUntilReset,
		boolean movementCorrection, float releaseSpeed)
	{
		turnTowards(vec, maxTurn, ticksUntilReset, movementCorrection,
			releaseSpeed);
	}
	
	private void turnTowards(Vec3 vec, float maxTurn, int ticksUntilReset,
		boolean movementCorrection, float releaseSpeed)
	{
		LocalPlayer player = WurstClient.MC.player;
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		float fromYaw = managed ? managedYaw : player.getYRot();
		float fromPitch = managed ? managedPitch : player.getXRot();
		
		// Vary limited turn speeds a little on every tick. With a fixed speed,
		// every long turn moves by exactly the same amount per tick, and
		// blocks placed in the middle of such turns get flagged by Grim's
		// DuplicateRotPlace check.
		if(maxTurn < 180)
			maxTurn *= 0.85F + random.nextFloat() * 0.3F;
		
		// wrapped, so it takes the shortest way to the target
		float toYaw = fromYaw + Mth.wrapDegrees(needed.yaw() - fromYaw);
		turnFrom(fromYaw, fromPitch, toYaw, needed.pitch(), maxTurn);
		lastTurnYawDelta = managedYaw - fromYaw;
		lastTurnPitchDelta = managedPitch - fromPitch;
		managedTicksLeft = ticksUntilReset;
		this.movementCorrection = movementCorrection;
		this.releaseSpeed = releaseSpeed;
		releasing = false;
		managed = true;
	}
	
	/**
	 * Linear turn with the given limit on each axis, corrected for the mouse
	 * sensitivity grid. Stores the result in managedYaw/managedPitch.
	 */
	private void turnFrom(float fromYaw, float fromPitch, float toYaw,
		float toPitch, float maxTurn)
	{
		float diffYaw = toYaw - fromYaw;
		float diffPitch = toPitch - fromPitch;
		float length = (float)Math.hypot(diffYaw, diffPitch);
		if(length > 1e-5F)
		{
			float maxYaw = Math.abs(diffYaw / length) * maxTurn;
			float maxPitch = Math.abs(diffPitch / length) * maxTurn;
			diffYaw = Mth.clamp(diffYaw, -maxYaw, maxYaw);
			diffPitch = Mth.clamp(diffPitch, -maxPitch, maxPitch);
		}
		
		// fix the GCD of the mouse sensitivity, like a real mouse would
		float gcd = getMouseGcd();
		diffYaw = Math.round(diffYaw / gcd) * gcd;
		diffPitch = Math.round(diffPitch / gcd) * gcd;
		
		managedYaw = fromYaw + diffYaw;
		managedPitch = Mth.clamp(fromPitch + diffPitch, -90, 90);
	}
	
	/**
	 * Turns the managed rotation back towards the camera rotation and clears
	 * it once it has arrived.
	 */
	private void updateRelease(LocalPlayer player)
	{
		// not wrapped, so the yaw ends up at exactly the camera's yaw value
		// and doesn't jump by 360° when the managed rotation is cleared
		// (Grim's AimModulo360 check)
		float gcd = getMouseGcd();
		float realYaw = player.getYRot();
		float realPitch = player.getXRot();
		if(Math.abs(realYaw - managedYaw) <= gcd
			&& Math.abs(realPitch - managedPitch) <= gcd)
		{
			clearManagedRotation();
			return;
		}
		
		turnFrom(managedYaw, managedPitch, realYaw, realPitch, releaseSpeed);
	}
	
	public void clearManagedRotation()
	{
		managed = false;
		releasing = false;
		movementCorrection = false;
	}
	
	/**
	 * The smallest rotation step that vanilla mouse movement can produce with
	 * the current sensitivity setting.
	 */
	private float getMouseGcd()
	{
		double sensitivity =
			WurstClient.MC.options.sensitivity().get() * 0.6 + 0.2;
		float gcd =
			(float)(sensitivity * sensitivity * sensitivity * 8.0) * 0.15F;
		return Math.max(gcd, 1e-4F);
	}
	
	public boolean isMovementCorrectionActive()
	{
		return managed && movementCorrection;
	}
	
	/**
	 * How much the last call to one of the face methods changed the yaw.
	 */
	public float getLastTurnYawDelta()
	{
		return lastTurnYawDelta;
	}
	
	/**
	 * How much the last call to one of the face methods changed the pitch.
	 */
	public float getLastTurnPitchDelta()
	{
		return lastTurnPitchDelta;
	}
	
	public float getManagedYaw()
	{
		return managedYaw;
	}
	
	public void faceVectorPacket(Vec3 vec)
	{
		Rotation needed = RotationUtils.getNeededRotations(vec);
		LocalPlayer player = WurstClient.MC.player;
		
		fakeRotation = true;
		serverYaw =
			RotationUtils.limitAngleChange(player.getYRot(), needed.yaw());
		serverPitch = needed.pitch();
	}
	
	public void faceVectorClient(Vec3 vec)
	{
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		LocalPlayer player = WurstClient.MC.player;
		player.setYRot(
			RotationUtils.limitAngleChange(player.getYRot(), needed.yaw()));
		player.setXRot(needed.pitch());
	}
	
	public void faceVectorClientIgnorePitch(Vec3 vec)
	{
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		LocalPlayer player = WurstClient.MC.player;
		player.setYRot(
			RotationUtils.limitAngleChange(player.getYRot(), needed.yaw()));
		player.setXRot(0);
	}
	
	public float getServerYaw()
	{
		if(fakeRotation)
			return serverYaw;
		
		return managed ? managedYaw : WurstClient.MC.player.getYRot();
	}
	
	public float getServerPitch()
	{
		if(fakeRotation)
			return serverPitch;
		
		return managed ? managedPitch : WurstClient.MC.player.getXRot();
	}
}
