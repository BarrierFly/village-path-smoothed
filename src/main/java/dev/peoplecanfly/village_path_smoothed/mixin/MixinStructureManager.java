/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.duck.StructureManagerVpsAccess;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructureManager.class)
public abstract class MixinStructureManager implements StructureManagerVpsAccess {
	@Accessor("level")
	public abstract LevelAccessor village_path_smoothed$level();

	@Override
	public LevelAccessor village_path_smoothed$getLevel() {
		return this.village_path_smoothed$level();
	}
}
