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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.wurstclient.WurstClient;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin
{
	/**
	 * Stops dropped items from being culled when the terrain around them is
	 * hidden behind other blocks, so that their names stay visible if
	 * ItemESP is showing them.
	 */
	@WrapOperation(
		method = "isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"))
	private boolean showItemsBehindBlocks(LevelRenderer renderer, BlockPos pos,
		Operation<Boolean> original, @Local(argsOnly = true) Entity entity)
	{
		if(entity instanceof ItemEntity
			&& WurstClient.INSTANCE.getHax().itemEspHack.shouldShowNames())
			return true;
		
		return original.call(renderer, pos);
	}
}
