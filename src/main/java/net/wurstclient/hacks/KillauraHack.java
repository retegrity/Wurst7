/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.PauseAttackOnContainersSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.AttackUtils;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"kill aura", "ForceField", "force field", "CrystalAura",
	"crystal aura", "AutoCrystal", "auto crystal"})
public final class KillauraHack extends Hack
	implements UpdateListener, HandleInputListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Attack range",
		"Determines how far Killaura will reach to attack entities.\n"
			+ "Anything that is further away than the specified value will not be attacked.",
		5, 1, 10, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting targetRange = new SliderSetting("Target range",
		"How many blocks beyond the attack range Killaura will already turn"
			+ " towards entities.\n\n"
			+ "Entities in this extra range are not attacked yet, but your"
			+ " rotation is already facing them when they come into attack"
			+ " range.",
		0, 0, 5, 0.05,
		ValueDisplay.DECIMAL.withSuffix(" blocks").withLabel(0, "off"));
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final SliderSetting speedRandMS =
		new SliderSetting("Speed randomization",
			"Helps you bypass anti-cheat plugins by varying the delay between"
				+ " attacks.\n\n" + "\u00b1100ms is recommended for Vulcan.\n\n"
				+ "0 (off) is fine for NoCheat+, AAC, Grim, Verus, Spartan, and"
				+ " vanilla servers.",
			100, 0, 1000, 50, ValueDisplay.INTEGER.withPrefix("\u00b1")
				.withSuffix("ms").withLabel(0, "off"));
	
	private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
		"Determines which entity will be attacked first.\n"
			+ "\u00a7lDistance\u00a7r - Attacks the closest entity.\n"
			+ "\u00a7lAngle\u00a7r - Attacks the entity that requires the least head movement.\n"
			+ "\u00a7lHealth\u00a7r - Attacks the weakest entity.",
		Priority.values(), Priority.ANGLE);
	
	private final SliderSetting fov =
		new SliderSetting("FOV", 360, 30, 360, 10, ValueDisplay.DEGREES);
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericCombatDescription(this), SwingHand.CLIENT);
	
	private final CheckboxSetting damageIndicator = new CheckboxSetting(
		"Damage indicator",
		"Renders a colored box within the target, inversely proportional to its remaining health.",
		true);
	
	private final PauseAttackOnContainersSetting pauseOnContainers =
		new PauseAttackOnContainersSetting(true);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"Ensures that you don't reach through blocks when attacking.\n\n"
				+ "Slower but can help with anti-cheat plugins.",
			false);
	
	private final CheckboxSetting movementCorrection = new CheckboxSetting(
		"Movement correction",
		"Calculates your movement from the rotation that the server sees"
			+ " instead of where your camera points, and adjusts your keys so"
			+ " you still walk the way you intended.\n\n"
			+ "Helps against anti-cheat plugins that check whether your"
			+ " movement matches your rotation.",
		false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericMeleeCombat();
	
	private static final int ROTATION_TICKS = 5;
	
	private Entity target;
	private Entity renderTarget;
	
	public KillauraHack()
	{
		super("Killaura");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(targetRange);
		addSetting(speed);
		addSetting(speedRandMS);
		addSetting(priority);
		addSetting(fov);
		addSetting(swingHand);
		addSetting(damageIndicator);
		addSetting(pauseOnContainers);
		addSetting(checkLOS);
		addSetting(movementCorrection);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		speed.resetTimer(speedRandMS.getValue());
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		target = null;
		renderTarget = null;
		WURST.getRotationFaker().clearManagedRotation();
	}
	
	@Override
	public void onUpdate()
	{
		speed.updateTimer();
		
		target = null;
		renderTarget = null;
		
		if(pauseOnContainers.shouldPause())
			return;
		
		// don't fight over the rotation with Clutch
		if(WURST.getHax().clutchHack.isClutching()
			|| WURST.getHax().anchorAuraHack.isBusy())
			return;
		
		Stream<Entity> stream = EntityUtils.getMeleeAttackableEntities();
		
		if(fov.getValue() < 360.0)
			stream = stream.filter(e -> RotationUtils.getAngleToLookVec(
				e.getBoundingBox().getCenter()) <= fov.getValue() / 2.0);
		
		List<Entity> entities = entityFilters.applyTo(stream).toList();
		
		// Entities within attack range come first. Entities in the extra
		// target range are only faced, not attacked.
		double attackRangeSq = range.getValueSq();
		double aimRange = range.getValue() + targetRange.getValue();
		double aimRangeSq = aimRange * aimRange;
		
		Comparator<Entity> comparator = priority.getSelected().comparator;
		Entity candidate = entities.stream()
			.filter(e -> EntityUtils.distanceToHitboxSq(e) <= attackRangeSq)
			.min(comparator).orElse(null);
		boolean inAttackRange = candidate != null;
		
		if(candidate == null)
			candidate = entities.stream()
				.filter(e -> EntityUtils.distanceToHitboxSq(e) <= aimRangeSq)
				.min(comparator).orElse(null);
		
		renderTarget = candidate;
		if(candidate == null)
			return;
		
		// aim at the closest point of the hitbox
		Vec3 hitVec = getClosestPoint(candidate.getBoundingBox());
		if(checkLOS.isChecked() && !BlockUtils.hasLineOfSight(hitVec))
			return;
		
		// keep looking at the target on every tick, not just when attacking
		WURST.getRotationFaker().faceVectorSilently(hitVec, ROTATION_TICKS,
			movementCorrection.isChecked());
		
		if(!inAttackRange || !speed.isTimeToAttack())
			return;
		
		target = candidate;
		WURST.getHax().autoSwordHack.setSlot(target);
	}
	
	private Vec3 getClosestPoint(AABB box)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		
		// slightly smaller box, so the point is never on the very edge
		AABB inner = box.deflate(0.01);
		Vec3 closest = new Vec3(Mth.clamp(eyes.x, inner.minX, inner.maxX),
			Mth.clamp(eyes.y, inner.minY, inner.maxY),
			Mth.clamp(eyes.z, inner.minZ, inner.maxZ));
		
		// eyes inside the hitbox, so aim at the center
		if(closest.distanceToSqr(eyes) < 1e-8)
			return box.getCenter();
		
		return closest;
	}
	
	@Override
	public void onHandleInput()
	{
		if(target == null)
			return;
		
		if(WURST.getHax().criticalsHack.shouldDelayAttack())
			return;
		
		if(AttackUtils.shouldDelayAttack())
			return;
		
		AttackUtils.attackAndSwing(target,
			() -> swingHand.swing(InteractionHand.MAIN_HAND));
		
		target = null;
		speed.resetTimer(speedRandMS.getValue());
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(renderTarget == null || !damageIndicator.isChecked())
			return;
		
		float p = 1;
		if(renderTarget instanceof LivingEntity le && le.getMaxHealth() > 1e-5)
			p = 1 - le.getHealth() / le.getMaxHealth();
		float red = p * 2F;
		float green = 2 - red;
		float[] rgb = {red, green, 0};
		int quadColor = RenderUtils.toIntColor(rgb, 0.25F);
		int lineColor = RenderUtils.toIntColor(rgb, 0.5F);
		
		AABB box = EntityUtils.getLerpedBox(renderTarget, partialTicks);
		if(p < 1)
			box = box.deflate((1 - p) * 0.5 * box.getXsize(),
				(1 - p) * 0.5 * box.getYsize(), (1 - p) * 0.5 * box.getZsize());
		
		RenderUtils.drawSolidBox(matrixStack, box, quadColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, false);
	}
	
	private enum Priority
	{
		DISTANCE("Distance", EntityUtils::distanceToHitboxSq),
		
		ANGLE("Angle",
			e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter())),
		
		HEALTH("Health", e -> e instanceof LivingEntity
			? ((LivingEntity)e).getHealth() : Integer.MAX_VALUE);
		
		private final String name;
		private final Comparator<Entity> comparator;
		
		private Priority(String name, ToDoubleFunction<Entity> keyExtractor)
		{
			this.name = name;
			comparator = Comparator.comparingDouble(keyExtractor);
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
