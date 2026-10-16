/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"auto clutch", "AutoClutch", "void save"})
public final class ClutchHack extends Hack implements UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"§lVoid§r mode only clutches when there is nothing below you"
			+ " but the void.\n\n"
			+ "§lFall distance§r mode also clutches when the ground"
			+ " below you is further away than the minimum fall distance.",
		Mode.values(), Mode.VOID);
	
	private final SliderSetting minFallDistance =
		new SliderSetting("Min fall distance",
			"Only used in §lFall distance§r mode. Clutches when the"
				+ " ground below you is further away than this.",
			10, 3, 40, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final SliderSetting prediction = new SliderSetting("Prediction",
		"How many ticks ahead Clutch predicts where you will be falling.", 3, 1,
		10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting range =
		new SliderSetting("Range", 4.5, 3, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"How fast your server-side rotation turns towards the blocks that"
			+ " Clutch places.",
		60, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final SliderSetting returnSpeed = new SliderSetting("Return speed",
		"How fast your server-side rotation turns back to your real rotation"
			+ " once Clutch is done.",
		30, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final CheckboxSetting movementCorrection =
		new CheckboxSetting("Movement correction",
			"Moves you in the direction of your server-side rotation while"
				+ " clutching, the way the server expects it. Needed for"
				+ " anti-cheats like Grim.",
			true);
	
	private final CheckboxSetting switchBack =
		new CheckboxSetting("Switch back",
			"Switches back to the item you were holding once Clutch is done.",
			true);
	
	private static final int MAX_SEARCH_NODES = 512;
	
	// ticks without clutching before switching back to the original item
	private static final int SWITCH_BACK_DELAY = 10;
	
	// how far a chain can be extended before Clutch starts a new one instead
	private static final int MAX_EXTEND_STEPS = 3;
	
	// how far ahead to check if you are about to land on something
	private static final int LANDING_TICKS = 10;
	
	private boolean clutching;
	private int originalSlot = -1;
	private int usedSlot = -1;
	private int idleTicks;
	
	// blocks placed during the current clutch
	private final HashSet<BlockPos> placedBlocks = new HashSet<>();
	private double lastGroundY = Double.NaN;
	
	public ClutchHack()
	{
		super("Clutch");
		setCategory(Category.BLOCKS);
		addSetting(mode);
		addSetting(minFallDistance);
		addSetting(prediction);
		addSetting(range);
		addSetting(turnSpeed);
		addSetting(returnSpeed);
		addSetting(movementCorrection);
		addSetting(switchBack);
	}
	
	@Override
	protected void onEnable()
	{
		clutching = false;
		originalSlot = -1;
		placedBlocks.clear();
		lastGroundY = Double.NaN;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		forceSwitchBack();
	}
	
	public boolean isClutching()
	{
		return isEnabled() && clutching;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player.onGround())
		{
			lastGroundY = player.getY();
			
			// a new jump or fall starts a new chain
			placedBlocks.clear();
		}
		
		int blockSlot = findBlockSlot(player);
		if(blockSlot == -1 || !isAboutToFall(player))
		{
			stopClutching();
			return;
		}
		
		// Build at the height of the block you were last standing on, so the
		// chain can start right at the edge you fell off. Once you have fallen
		// below that height, build right under your feet instead.
		int feetLevel = Mth.floor(player.getY()) - 1;
		int maxY =
			Double.isNaN(lastGroundY) ? feetLevel : Mth.floor(lastGroundY) - 1;
		int targetY = Math.min(maxY, feetLevel);
		
		// Target every block under your predicted hitbox, not just the one
		// under its center. When you fall along a block boundary, the center
		// keeps switching between two columns, which used to start a second
		// chain next to the first one.
		AABB predicted = simulate(player, prediction.getValueI()).box();
		List<BlockPos> targets = getBlocksUnder(predicted, targetY);
		for(BlockPos target : targets)
			if(!BlockUtils.getState(target).canBeReplaced())
			{
				// you will land on that block
				stopClutching();
				return;
			}
			
		// Prefer extending the chain that is already being built. Otherwise,
		// when the target moves sideways (along a long edge or when falling
		// diagonally), the closest support could be the edge again, which
		// would start a second chain next to the first one.
		// The face to place on doesn't have to be visible yet, as long as it
		// will be once you get to the predicted position (e.g. once you have
		// moved past the last block of the chain). Clutch then aims at it and
		// waits instead of building somewhere else.
		Vec3 futureEyes = new Vec3((predicted.minX + predicted.maxX) / 2,
			predicted.minY + player.getEyeHeight(),
			(predicted.minZ + predicted.maxZ) / 2);
		Placement placement = null;
		if(!placedBlocks.isEmpty())
			placement = findNextPlacement(targets, maxY, player,
				placedBlocks::contains, MAX_EXTEND_STEPS, futureEyes);
		if(placement == null)
			placement = findNextPlacement(targets, maxY, player, pos -> true,
				Integer.MAX_VALUE, futureEyes);
		if(placement == null)
		{
			stopClutching();
			return;
		}
		
		clutching = true;
		idleTicks = 0;
		
		WURST.getRotationFaker().faceVectorSmoothly(placement.hitVec(),
			turnSpeed.getValueF(), 2, movementCorrection.isChecked(),
			returnSpeed.getValueF());
		
		// only place once the rotation that will be sent this tick actually
		// looks at the block, so the placement matches what the server sees
		BlockHitResult hit = raycast(WURST.getRotationFaker().getServerYaw(),
			WURST.getRotationFaker().getServerPitch());
		if(!hits(hit, placement))
			return;
		
		if(place(player, blockSlot, hit))
			placedBlocks.add(placement.pos());
	}
	
	private void stopClutching()
	{
		// the rotation turns back to the camera by itself once it expires
		clutching = false;
		
		// Don't switch back right away. Clutch often pauses for a tick or two
		// in the middle of a clutch, which used to switch back and forth
		// between the blocks and the original item. Wait until you land or
		// Clutch has been idle for a while instead.
		if(originalSlot == -1)
			return;
		
		LocalPlayer player = MC.player;
		if(player == null)
		{
			originalSlot = -1;
			return;
		}
		
		if(++idleTicks < SWITCH_BACK_DELAY && !player.onGround())
			return;
		
		// don't switch back if you have switched to something else yourself
		if(switchBack.isChecked()
			&& player.getInventory().getSelectedSlot() == usedSlot)
			player.getInventory().setSelectedSlot(originalSlot);
		
		originalSlot = -1;
	}
	
	private void forceSwitchBack()
	{
		idleTicks = SWITCH_BACK_DELAY;
		stopClutching();
	}
	
	private boolean isAboutToFall(LocalPlayer player)
	{
		if(player.onGround() || player.getAbilities().flying
			|| player.isFallFlying() || player.isInWater() || player.isInLava()
			|| player.isPassenger() || player.onClimbable())
			return false;
		
		// about to land on something (e.g. the chain that Clutch just built)
		if(simulate(player, LANDING_TICKS).landed())
			return false;
		
		Vec3 predicted = predictPosition(player);
		AABB box = player.getBoundingBox().move(predicted.x - player.getX(), 0,
			predicted.z - player.getZ());
		
		double depth = mode.getSelected() == Mode.VOID
			? box.minY - MC.level.getMinY() + 1 : minFallDistance.getValue();
		
		AABB below = box.expandTowards(0, -depth, 0);
		return BlockUtils.getBlockCollisions(below).findAny().isEmpty();
	}
	
	/**
	 * Predicts the horizontal position a few ticks ahead.
	 */
	private Vec3 predictPosition(LocalPlayer player)
	{
		AABB box = simulate(player, prediction.getValueI()).box();
		return new Vec3((box.minX + box.maxX) / 2, player.getY(),
			(box.minZ + box.maxZ) / 2);
	}
	
	/**
	 * Simulates your fall with vanilla's air physics for the given number of
	 * ticks, stopping early if you would land on something.
	 */
	private FallSimulation simulate(LocalPlayer player, int ticks)
	{
		AABB box = player.getBoundingBox();
		Vec3 v = player.getDeltaMovement();
		double vx = v.x;
		double vy = v.y;
		double vz = v.z;
		
		for(int i = 0; i < ticks; i++)
		{
			// horizontal movement, stopped by walls
			AABB moved = box.move(vx, 0, vz);
			if(collides(moved))
			{
				vx = 0;
				vz = 0;
			}else
				box = moved;
			
			// vertical movement
			if(vy < 0 && collides(box.expandTowards(0, vy, 0)))
				return new FallSimulation(box, true);
			
			if(vy > 0 && collides(box.expandTowards(0, vy, 0)))
				vy = 0;
			else
				box = box.move(0, vy, 0);
			
			if(box.minY < MC.level.getMinY())
				break;
			
			vx *= 0.91;
			vz *= 0.91;
			vy = (vy - 0.08) * 0.98;
		}
		
		return new FallSimulation(box, false);
	}
	
	private boolean collides(AABB box)
	{
		return BlockUtils.getBlockCollisions(box).findAny().isPresent();
	}
	
	/**
	 * Returns the blocks at the given height that are under the given box.
	 */
	private List<BlockPos> getBlocksUnder(AABB box, int y)
	{
		ArrayList<BlockPos> blocks = new ArrayList<>();
		int minX = Mth.floor(box.minX + 1e-3);
		int maxX = Mth.floor(box.maxX - 1e-3);
		int minZ = Mth.floor(box.minZ + 1e-3);
		int maxZ = Mth.floor(box.maxZ - 1e-3);
		
		for(int x = minX; x <= maxX; x++)
			for(int z = minZ; z <= maxZ; z++)
				blocks.add(new BlockPos(x, y, z));
			
		return blocks;
	}
	
	/**
	 * Searches outwards from the targets for the closest position that can be
	 * placed against an existing block. Placing there every tick builds a
	 * chain from that block to one of the targets.
	 */
	private Placement findNextPlacement(List<BlockPos> targets, int maxY,
		LocalPlayer player, Predicate<BlockPos> supportFilter, int maxSteps,
		Vec3 futureEyes)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		double rangeSq = range.getValueSq();
		AABB playerBox = player.getBoundingBox();
		
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashMap<BlockPos, Integer> steps = new HashMap<>();
		for(BlockPos target : targets)
		{
			queue.add(target);
			steps.put(target, 0);
		}
		
		Placement best = null;
		int bestSteps = 0;
		
		while(!queue.isEmpty() && steps.size() < MAX_SEARCH_NODES)
		{
			BlockPos pos = queue.poll();
			int posSteps = steps.get(pos);
			
			// all positions that are this close have been checked, pick the
			// best of them
			if(best != null && posSteps > bestSteps)
				break;
				
			// can't place a block inside yourself (yet), but a chain can
			// still go through there
			if(!new AABB(pos).intersects(playerBox))
			{
				Placement placement =
					getPlacement(pos, eyes, rangeSq, supportFilter, futureEyes);
				if(placement != null
					&& (best == null || placement.visible() && !best.visible()))
				{
					best = placement;
					bestSteps = posSteps;
				}
			}
			
			if(posSteps >= maxSteps)
				continue;
			
			for(Direction dir : Direction.values())
			{
				BlockPos next = pos.relative(dir);
				
				// don't build chains above the block you were standing on
				if(next.getY() > maxY || steps.containsKey(next))
					continue;
				
				if(!BlockUtils.getState(next).canBeReplaced())
					continue;
				
				if(eyes.distanceToSqr(Vec3.atCenterOf(next)) > rangeSq)
					continue;
				
				steps.put(next, posSteps + 1);
				queue.add(next);
			}
		}
		
		return best;
	}
	
	/**
	 * Returns how to place a block at the given position against one of its
	 * neighbors, or null if no neighbor can be used.
	 */
	private Placement getPlacement(BlockPos pos, Vec3 eyes, double rangeSq,
		Predicate<BlockPos> supportFilter, Vec3 futureEyes)
	{
		Placement best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		for(Direction dir : Direction.values())
		{
			BlockPos neighbor = pos.relative(dir);
			if(!supportFilter.test(neighbor)
				|| !BlockUtils.canBeClicked(neighbor)
				|| BlockUtils.getState(neighbor).canBeReplaced()
				|| BlockUtils.isInteractive(BlockUtils.getState(neighbor)))
				continue;
			
			// the face of the neighbor that touches pos
			Direction side = dir.getOpposite();
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 hitVec = Vec3.atCenterOf(neighbor).add(normal.scale(0.5));
			
			// whether that face is facing you, i.e. can be clicked right now,
			// or will be soon
			boolean visible = eyes.subtract(hitVec).dot(normal) > 0;
			if(!visible && futureEyes.subtract(hitVec).dot(normal) <= 0)
				continue;
			
			double distSq = eyes.distanceToSqr(hitVec);
			if(distSq > rangeSq)
				continue;
			
			// prefer faces that are visible, then the closest one
			if(best != null && (best.visible() && !visible
				|| best.visible() == visible && distSq >= bestDistSq))
				continue;
			
			best = new Placement(pos, neighbor, side, hitVec, visible);
			bestDistSq = distSq;
		}
		
		return best;
	}
	
	private BlockHitResult raycast(float yaw, float pitch)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 look = new Rotation(yaw, pitch).toLookVec();
		return BlockUtils.raycast(eyes, eyes.add(look.scale(range.getValue())));
	}
	
	private boolean hits(BlockHitResult hit, Placement placement)
	{
		return hit != null && hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().equals(placement.neighbor())
			&& hit.getDirection() == placement.side();
	}
	
	private boolean place(LocalPlayer player, int blockSlot, BlockHitResult hit)
	{
		int selected = player.getInventory().getSelectedSlot();
		if(selected != blockSlot)
		{
			if(originalSlot == -1)
				originalSlot = selected;
			
			// vanilla sends the slot to the server in useItemOn()
			player.getInventory().setSelectedSlot(blockSlot);
		}
		usedSlot = blockSlot;
		
		// same as vanilla's startUseItem(), without the extra useItem()
		InteractionResult result =
			MC.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		if(!(result instanceof InteractionResult.Success success))
			return false;
		
		if(success.swingSource() == InteractionResult.SwingSource.CLIENT)
			player.swing(InteractionHand.MAIN_HAND);
		
		return true;
	}
	
	private int findBlockSlot(LocalPlayer player)
	{
		// prefer the block you're already holding
		int selected = player.getInventory().getSelectedSlot();
		if(isUsableBlock(player.getInventory().getItem(selected)))
			return selected;
		
		for(int i = 0; i < 9; i++)
			if(isUsableBlock(player.getInventory().getItem(i)))
				return i;
			
		return -1;
	}
	
	private boolean isUsableBlock(ItemStack stack)
	{
		if(stack.isEmpty() || !(stack.getItem() instanceof BlockItem item))
			return false;
		
		Block block = item.getBlock();
		if(block instanceof FallingBlock)
			return false;
		
		return block.defaultBlockState().isCollisionShapeFullBlock(
			EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}
	
	private record FallSimulation(AABB box, boolean landed)
	{}
	
	private record Placement(BlockPos pos, BlockPos neighbor, Direction side,
		Vec3 hitVec, boolean visible)
	{}
	
	private enum Mode
	{
		VOID("Void"),
		FALL_DISTANCE("Fall distance");
		
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
