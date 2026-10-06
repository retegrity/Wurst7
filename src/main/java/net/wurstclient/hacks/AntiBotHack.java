/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.FakePlayerEntity;

@SearchTags({"anti bot", "no npc", "ignore npc", "fake players", "npc filter"})
public final class AntiBotHack extends Hack
{
	public AntiBotHack()
	{
		super("AntiBot");
		setCategory(Category.OTHER);
	}
	
	/**
	 * Returns true if the given entity is an NPC/fake player that should be
	 * ignored. Real players always have an entry in the tab list, so any
	 * player entity without one is treated as a bot.
	 */
	public boolean isBot(Entity e)
	{
		if(!isEnabled())
			return false;
		
		if(!(e instanceof AbstractClientPlayer player)
			|| e instanceof FakePlayerEntity || e == MC.player)
			return false;
		
		if(MC.getConnection() == null)
			return false;
		
		return MC.getConnection().getPlayerInfo(player.getUUID()) == null;
	}
}
