/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
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

	/** "minecraft:foo" form of a registry key, for any key type. */
	public static String registryKeyId(ResourceKey<?> key) {
		//#if MC >= 12111
		//$$ return key.identifier().toString();
		//#else
		return key.location().toString();
		//#endif
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

	/**
	 * Resolves a template pool id such as {@code minecraft:village/plains/decor}
	 * from the server's registries. Returns null when the id is unknown or the
	 * API lookup fails - callers then fall back to the old whole-bbox protection.
	 */
	public static StructureTemplatePool templatePool(RegistryAccess access, String id) {
		if (access == null || id == null) {
			return null;
		}
		try {
			//#if MC >= 12111
			//$$ return access.lookupOrThrow(Registries.TEMPLATE_POOL)
			//$$ 		.getValue(net.minecraft.resources.Identifier.parse(id));
			//#elseif MC >= 12109
			//$$ return access.lookupOrThrow(Registries.TEMPLATE_POOL)
			//$$ 		.getValue(net.minecraft.resources.ResourceLocation.parse(id));
			//#elseif MC >= 12101
			//$$ return access.registryOrThrow(Registries.TEMPLATE_POOL)
			//$$ 		.get(net.minecraft.resources.ResourceLocation.parse(id));
			//#else
			return access.registryOrThrow(Registries.TEMPLATE_POOL)
					.get(new net.minecraft.resources.ResourceLocation(id));
			//#endif
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * The templates a pool can produce. 1.19.4 has no public getter for the raw
	 * entries, so this tries the modern accessor and falls back to reflection on
	 * the stable {@code rawTemplates}/{@code templates} fields. An empty list
	 * makes the decor resolver fall back to the old whole-bbox protection.
	 */
	@SuppressWarnings("unchecked")
	public static java.util.List<net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement> poolTemplates(
			StructureTemplatePool pool) {
		if (pool == null) {
			return java.util.List.of();
		}
		try {
			java.lang.reflect.Method getter = pool.getClass().getMethod("getTemplates");
			Object raw = getter.invoke(pool);
			java.util.List<net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement> out =
					flattenPoolEntries(raw);
			if (!out.isEmpty()) {
				return out;
			}
		} catch (Throwable ignored) {
			// fall through to the field access
		}
		for (String fieldName : new String[]{"rawTemplates", "templates"}) {
			try {
				java.lang.reflect.Field field = StructureTemplatePool.class.getDeclaredField(fieldName);
				field.setAccessible(true);
				java.util.List<net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement> out =
						flattenPoolEntries(field.get(pool));
				if (!out.isEmpty()) {
					return out;
				}
			} catch (Throwable ignored) {
				// try the next field
			}
		}
		return java.util.List.of();
	}

	@SuppressWarnings("unchecked")
	private static java.util.List<net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement> flattenPoolEntries(
			Object raw) {
		if (!(raw instanceof java.util.List<?> list)) {
			return java.util.List.of();
		}
		java.util.List<net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement> out =
				new java.util.ArrayList<>(list.size());
		for (Object entry : list) {
			Object element = entry;
			if (entry instanceof com.mojang.datafixers.util.Pair<?, ?> pair) {
				element = pair.getFirst();
			}
			if (element instanceof net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement structureElement) {
				out.add(structureElement);
			}
		}
		return out;
	}
}
