/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

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
			
			if(--managedTicksLeft < 0)
				clearManagedRotation();
			
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
		if(!applied)
			return;
		
		LocalPlayer player = WurstClient.MC.player;
		player.setYRot(realYaw);
		player.setXRot(realPitch);
		fakeRotation = false;
		applied = false;
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
		LocalPlayer player = WurstClient.MC.player;
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		float fromYaw = managed ? managedYaw : player.getYRot();
		float fromPitch = managed ? managedPitch : player.getXRot();
		
		// linear turn with a limit of 180° on each axis
		float diffYaw = Mth.wrapDegrees(needed.yaw() - fromYaw);
		float diffPitch = Mth.wrapDegrees(needed.pitch() - fromPitch);
		float length = (float)Math.hypot(diffYaw, diffPitch);
		if(length > 1e-5F)
		{
			float maxYaw = Math.abs(diffYaw / length) * 180;
			float maxPitch = Math.abs(diffPitch / length) * 180;
			diffYaw = Mth.clamp(diffYaw, -maxYaw, maxYaw);
			diffPitch = Mth.clamp(diffPitch, -maxPitch, maxPitch);
		}
		
		// fix the GCD of the mouse sensitivity, like a real mouse would
		float gcd = getMouseGcd();
		diffYaw = Math.round(diffYaw / gcd) * gcd;
		diffPitch = Math.round(diffPitch / gcd) * gcd;
		
		managedYaw = fromYaw + diffYaw;
		managedPitch = Mth.clamp(fromPitch + diffPitch, -90, 90);
		managedTicksLeft = ticksUntilReset;
		this.movementCorrection = movementCorrection;
		managed = true;
	}
	
	public void clearManagedRotation()
	{
		managed = false;
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
