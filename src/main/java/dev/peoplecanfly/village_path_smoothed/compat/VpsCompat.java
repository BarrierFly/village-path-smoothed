/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/**
 * Small static shims over the few APIs that differ between our target versions.
 * Everything here is resolved per version by the preprocessor. Convention of
 * the ReplayMod preprocessor: plain lines belong to the oldest branch, and
 * lines prefixed with //$$ are uncommented when the condition matches.
 */
public final class VpsCompat {
	private VpsCompat() {
	}

	public static StructureTemplate loadTemplate(StructureTemplateManager manager, String location) {
		//#if MC >= 12111
		//$$ return manager.getOrCreate(net.minecraft.resources.Identifier.parse(location));
		//#elseif MC >= 12101
		//$$ return manager.getOrCreate(net.minecraft.resources.ResourceLocation.parse(location));
		//#else
		return manager.getOrCreate(new net.minecraft.resources.ResourceLocation(location));
		//#endif
	}

	/** True if the given structure is one of the vanilla villages (minecraft:village_*). */
	public static boolean isVanillaVillageStructure(LevelReader level, Structure structure) {
		Optional<ResourceKey<Structure>> key;
		//#if MC >= 12111
		//$$ key = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey(structure);
		//#else
		key = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getResourceKey(structure);
		//#endif
		return key.map(VpsCompat::isVillageKey).orElse(false);
	}

	private static boolean isVillageKey(ResourceKey<Structure> key) {
		String namespace;
		String path;
		//#if MC >= 12111
		//$$ namespace = key.identifier().getNamespace();
		//$$ path = key.identifier().getPath();
		//#else
		namespace = key.location().getNamespace();
		path = key.location().getPath();
		//#endif
		return "minecraft".equals(namespace) && path.startsWith("village_");
	}

	/** Resolves a "minecraft:foo" block id; returns null when unknown. */
	public static Block blockById(String id) {
		try {
			//#if MC >= 12111
			//$$ return BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id));
			//#elseif MC >= 12109
			//$$ return BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.ResourceLocation.parse(id));
			//#elseif MC >= 12101
			//$$ return BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id));
			//#else
			return BuiltInRegistries.BLOCK.get(new net.minecraft.resources.ResourceLocation(id));
			//#endif
		} catch (Exception e) {
			return null;
		}
	}

	public static long blockPosAsLong(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}
}
