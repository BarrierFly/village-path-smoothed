/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.duck.FeaturePoolElementVpsAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the placed feature a decor pool element carries, so the planner can
 * recognise the hay pile. The field is named "feature" in every supported
 * version.
 */
@Mixin(FeaturePoolElement.class)
public abstract class MixinFeaturePoolElement implements FeaturePoolElementVpsAccess {
	@Accessor("feature")
	public abstract Holder<PlacedFeature> village_path_smoothed$feature();

	@Override
	public Holder<PlacedFeature> village_path_smoothed$getFeature() {
		return this.village_path_smoothed$feature();
	}
}
