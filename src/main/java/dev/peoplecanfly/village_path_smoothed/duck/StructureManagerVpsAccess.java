/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.duck;

import net.minecraft.world.level.LevelAccessor;

/**
 * Duck interface mixed into StructureManager to expose its owning level, so
 * the structure-generation step can resolve the MinecraftServer (the level is
 * the ServerLevel during worldgen).
 */
public interface StructureManagerVpsAccess {
	LevelAccessor village_path_smoothed$getLevel();
}
