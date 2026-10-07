/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import com.mojang.datafixers.util.Either;
import dev.peoplecanfly.village_path_smoothed.duck.SinglePoolElementVpsAccess;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the template location of a pool element. The field is named "template"
 * in every supported version; the id type around it changed its name in 1.21.11.
 */
@Mixin(SinglePoolElement.class)
public abstract class MixinSinglePoolElement implements SinglePoolElementVpsAccess {
	//#if MC >= 12111
	//$$ @Accessor("template")
	//$$ public abstract Either<net.minecraft.resources.Identifier, StructureTemplate> village_path_smoothed$template();
	//#else
	@Accessor("template")
	public abstract Either<net.minecraft.resources.ResourceLocation, StructureTemplate> village_path_smoothed$template();
	//#endif

	@Override
	public String village_path_smoothed$getTemplateLocation() {
		//#if MC >= 12111
		//$$ Either<net.minecraft.resources.Identifier, StructureTemplate> template = this.village_path_smoothed$template();
		//$$ return template == null ? null : template.left().map(Object::toString).orElse(null);
		//#else
		Either<net.minecraft.resources.ResourceLocation, StructureTemplate> template = this.village_path_smoothed$template();
		return template == null ? null : template.left().map(Object::toString).orElse(null);
		//#endif
	}
}
