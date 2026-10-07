/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.duck.StructureTemplateVpsAccess;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(StructureTemplate.class)
public abstract class MixinStructureTemplate implements StructureTemplateVpsAccess {
	@Accessor("palettes")
	public abstract List<StructureTemplate.Palette> village_path_smoothed$palettes();

	@Override
	public List<StructureTemplate.Palette> village_path_smoothed$getPalettes() {
		return this.village_path_smoothed$palettes();
	}
}
