/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Write-time classifier for "non-terrain decoration" (user decision 2026-10-17).
 *
 * <p>The road writer may clear, fill, pave, kerb and line only blocks that are
 * natural terrain. Everything else - logs, leaves, plants, crops, vines,
 * furniture, lamps, modded decoration blocks - keeps its block: the operation
 * skips it instead of replacing it. This is deliberately a terrain whitelist,
 * so an unknown modded block defaults to protected rather than destroyed.
 *
 * <p>The whitelist is built from vanilla's own terrain tags: dirt, base stone
 * (overworld and nether), sand, snow, ice, terracotta, the sculk replaceables
 * (clay, gravel, calcite, dripstone, sandstone, end stone, smooth basalt, ...)
 * and the ore tags. Common terrain blocks are also listed explicitly so a
 * future tag reshuffle cannot silently turn them into protected decoration.
 * The mod's own road structures (mud bricks, smooth sandstone, the default
 * cobblestone kerb, scaffolding, the dirt-path fill) are replaceable too, so a
 * shared side column keeps the documented "low kerb AND high railing" rule.
 * Water/lava are intentionally not protected: the writer treats fluids as
 * terrain for the clear/ceiling rules.
 *
 * <p>This class answers the MATERIAL question only. The write-time context
 * ({@code VpsGenContext#isProtectedDecoration}) additionally protects the
 * identified street-decor blocks and the exactly recorded pile blocks, which
 * can be terrain by material (a decor's cobblestone base, a pile's snow layer).
 */
public final class FeatureProtection {
	private FeatureProtection() {
	}

	/**
	 * True when the writer must leave this block alone. Air and fluids are not
	 * decorations; any non-terrain, non-fluid block is.
	 */
	public static boolean isProtectedDecoration(BlockState state) {
		if (state == null || state.isAir()) {
			return false;
		}
		Block block = state.getBlock();
		if (block == Blocks.WATER || block == Blocks.LAVA) {
			return false;
		}
		return isProtectedDecoration(false, false, isTerrain(state));
	}

	/**
	 * Pure form of the decision, kept separate from the block/tag lookup so the
	 * policy itself can be unit tested without a loaded Minecraft registry.
	 *
	 * @param air true for air (never protected: the road may fill it)
	 * @param fluidBlock true for the water/lava blocks (never protected)
	 * @param terrain true when the block is natural terrain (never protected)
	 */
	public static boolean isProtectedDecoration(boolean air, boolean fluidBlock, boolean terrain) {
		return !air && !fluidBlock && !terrain;
	}

	/** Natural terrain the writer may replace; everything else is a decoration. */
	private static boolean isTerrain(BlockState state) {
		if (state.is(BlockTags.DIRT)
				|| state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| state.is(BlockTags.BASE_STONE_NETHER)
				|| state.is(BlockTags.SAND)
				|| state.is(BlockTags.STONE_ORE_REPLACEABLES)
				|| state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
				|| state.is(BlockTags.SCULK_REPLACEABLE)
				|| state.is(BlockTags.SNOW)
				|| state.is(BlockTags.ICE)
				|| state.is(BlockTags.TERRACOTTA)) {
			return true;
		}
		// 26.3 collapsed the per-ore tags into one parent tag.
		//#if MC >= 260000
		//$$ if (state.is(BlockTags.ORES)) {
		//$$ 	return true;
		//$$ }
		//#else
		if (state.is(BlockTags.COAL_ORES)
				|| state.is(BlockTags.IRON_ORES)
				|| state.is(BlockTags.COPPER_ORES)
				|| state.is(BlockTags.GOLD_ORES)
				|| state.is(BlockTags.REDSTONE_ORES)
				|| state.is(BlockTags.EMERALD_ORES)
				|| state.is(BlockTags.LAPIS_ORES)
				|| state.is(BlockTags.DIAMOND_ORES)) {
			return true;
		}
		//#endif
		Block block = state.getBlock();
		return block == Blocks.GRAVEL
				|| block == Blocks.CLAY
				|| block == Blocks.CALCITE
				|| block == Blocks.DRIPSTONE_BLOCK
				|| block == Blocks.SMOOTH_BASALT
				|| block == Blocks.SANDSTONE
				|| block == Blocks.RED_SANDSTONE
				|| block == Blocks.NETHERRACK
				|| block == Blocks.BASALT
				|| block == Blocks.BLACKSTONE
				|| block == Blocks.SOUL_SAND
				|| block == Blocks.SOUL_SOIL
				|| block == Blocks.END_STONE
				|| block == Blocks.OBSIDIAN
				|| block == Blocks.CRYING_OBSIDIAN
				|| block == Blocks.BEDROCK
				|| block == Blocks.MAGMA_BLOCK
				|| block == Blocks.GLOWSTONE
				|| block == Blocks.SHROOMLIGHT
				|| block == Blocks.PURPUR_BLOCK
				|| block == Blocks.AMETHYST_BLOCK
				|| block == Blocks.BUDDING_AMETHYST
				|| block == Blocks.ANCIENT_DEBRIS
				|| block == Blocks.NETHER_QUARTZ_ORE
				|| block == Blocks.NETHER_WART_BLOCK
				|| block == Blocks.WARPED_WART_BLOCK
				|| block == Blocks.MUD
				|| block == Blocks.PACKED_MUD
				|| block == Blocks.MOSS_BLOCK
				|| block == Blocks.MANGROVE_ROOTS
				|| block == Blocks.MUDDY_MANGROVE_ROOTS
				|| block == Blocks.SNOW_BLOCK
				|| block == Blocks.POWDER_SNOW
				|| block == Blocks.PACKED_ICE
				|| block == Blocks.BLUE_ICE
				|| block == Blocks.FROSTED_ICE
				// the mod's own road structures are not decorations
				|| block == Blocks.DIRT_PATH
				|| block == Blocks.MUD_BRICKS
				|| block == Blocks.MUD_BRICK_SLAB
				|| block == Blocks.SMOOTH_SANDSTONE
				|| block == Blocks.SMOOTH_SANDSTONE_SLAB
				|| block == Blocks.COBBLESTONE
				|| block == Blocks.SCAFFOLDING;
	}
}
