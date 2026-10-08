/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.itemesp;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState>
{
	/**
	 * Makes dropped items show a nameplate if enabled in ItemESP.
	 */
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onShouldShowName(T entity, double distanceSq,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(entity instanceof ItemEntity
			&& WurstClient.INSTANCE.getHax().itemEspHack.shouldShowNames())
			cir.setReturnValue(true);
	}
	
	/**
	 * Shows the stack size in front of the item name.
	 */
	@Inject(
		method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetNameTag(T entity, CallbackInfoReturnable<Component> cir)
	{
		if(!(entity instanceof ItemEntity itemEntity)
			|| !WurstClient.INSTANCE.getHax().itemEspHack.shouldShowNames())
			return;
		
		ItemStack stack = itemEntity.getItem();
		Component name = stack.getHoverName();
		if(stack.getCount() > 1)
			name = Component.literal(stack.getCount() + "x ").append(name);
		
		cir.setReturnValue(name);
	}
}
