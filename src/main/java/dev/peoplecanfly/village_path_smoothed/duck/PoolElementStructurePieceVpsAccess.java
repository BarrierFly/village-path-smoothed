/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.duck;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Duck interface mixed into PoolElementStructurePiece; the piece already holds
 * the StructureTemplateManager it was created with, which saves us from
 * reaching into the (version-divergent) MinecraftServer accessors.
 */
public interface PoolElementStructurePieceVpsAccess {
	StructureTemplateManager village_path_smoothed$getTemplateManager();
}
