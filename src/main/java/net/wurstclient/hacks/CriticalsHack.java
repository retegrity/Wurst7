/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;

@SearchTags({"Crits"})
public final class CriticalsHack extends Hack
	implements PlayerAttacksEntityListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lPacket\u00a7r mode sends packets to server without actually moving you at all.\n\n"
			+ "\u00a7lMini Jump\u00a7r mode does a tiny jump that is just enough to get a critical hit.\n\n"
			+ "\u00a7lFull Jump\u00a7r mode makes you jump normally.\n\n"
			+ "\u00a7lSmart\u00a7r mode doesn't fake anything. Instead, combat hacks like Killaura and TriggerBot wait while you are rising in a jump and only hit once you start falling, so the hit is a real critical. Nothing changes while you are on the ground.",
		Mode.values(), Mode.PACKET);
	
	private static final int MAX_DELAY_TICKS = 30;
	private int delayStartTick = -1;
	
	public CriticalsHack()
	{
		super("Criticals");
		setCategory(Category.COMBAT);
		addSetting(mode);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + mode.getSelected() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
	}
	
	/**
	 * Used by combat hacks in Smart mode: returns true if they should hold
	 * off attacking right now because the player is still rising in a jump
	 * and would only land a normal hit.
	 */
	public boolean shouldDelayAttack()
	{
		if(!isEnabled() || mode.getSelected() != Mode.SMART)
			return false;
		
		LocalPlayer player = MC.player;
		if(player == null)
			return false;
		
		if(player.onGround())
		{
			delayStartTick = -1;
			return false;
		}
		
		// cases where a critical isn't possible, so waiting is pointless
		if(player.isInWater() || player.isInLava() || player.onClimbable()
			|| player.isFallFlying() || player.isPassenger()
			|| player.getAbilities().flying
			|| WURST.getHax().flightHack.isEnabled())
			return false;
		
		// already falling -> critical hit is possible
		if(player.fallDistance > 0)
		{
			delayStartTick = -1;
			return false;
		}
		
		// never hold back attacks for too long
		if(delayStartTick < 0)
			delayStartTick = player.tickCount;
		return player.tickCount - delayStartTick <= MAX_DELAY_TICKS;
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(mode.getSelected() == Mode.SMART)
			return;
		
		if(!(target instanceof LivingEntity))
			return;
		
		if(WURST.getHax().maceDmgHack.isEnabled()
			&& MC.player.getMainHandItem().is(Items.MACE))
			return;
		
		if(!MC.player.onGround())
			return;
		
		if(MC.player.isInWater() || MC.player.isInLava())
			return;
		
		switch(mode.getSelected())
		{
			case PACKET:
			doPacketJump();
			break;
			
			case MINI_JUMP:
			doMiniJump();
			break;
			
			case FULL_JUMP:
			doFullJump();
			break;
		}
	}
	
	private void doPacketJump()
	{
		sendFakeY(0.0625, true);
		sendFakeY(0, false);
		sendFakeY(1.1e-5, false);
		sendFakeY(0, false);
	}
	
	private void sendFakeY(double offset, boolean onGround)
	{
		MC.player.connection
			.send(new Pos(MC.player.getX(), MC.player.getY() + offset,
				MC.player.getZ(), onGround, MC.player.horizontalCollision));
	}
	
	private void doMiniJump()
	{
		MC.player.push(0, 0.1, 0);
		MC.player.fallDistance = 0.1F;
		MC.player.setOnGround(false);
	}
	
	private void doFullJump()
	{
		MC.player.jumpFromGround();
	}
	
	private enum Mode
	{
		PACKET("Packet"),
		MINI_JUMP("Mini Jump"),
		FULL_JUMP("Full Jump"),
		SMART("Smart");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
