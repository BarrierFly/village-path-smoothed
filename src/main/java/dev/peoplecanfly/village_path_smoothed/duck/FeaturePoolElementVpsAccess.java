/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.duck;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Duck interface mixed into FeaturePoolElement so the T1.5 planner can tell the
 * village decor's hay pile ({@code minecraft:pile_hay}) from the decorative
 * trees and flowers that share the same pool element type. The field is named
 * "feature" in every supported version.
 */
public interface FeaturePoolElementVpsAccess {
	Holder<PlacedFeature> village_path_smoothed$getFeature();
}
