/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.duck.PoolElementStructurePieceVpsAccess;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoolElementStructurePiece.class)
public abstract class MixinPoolElementStructurePiece implements PoolElementStructurePieceVpsAccess {
	@Accessor("structureTemplateManager")
	public abstract StructureTemplateManager village_path_smoothed$templateManager();

	@Override
	public StructureTemplateManager village_path_smoothed$getTemplateManager() {
		return this.village_path_smoothed$templateManager();
	}
}
