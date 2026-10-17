/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Village style -> material mapping (plan sections 2.1-A5, 5.5, updated
 * 2026-10-17): full-block dirt roads are mud bricks, their half-step slabs stay
 * mud_brick_slab, and the fill below the walk surface stays dirt_path. The
 * desert road keeps smooth_sandstone / smooth_sandstone_slab. Kerb stones follow
 * the Q06 decisions (savanna uses terracotta); railings use persistent leaves
 * per Q09.
 */
public final class RoadMaterialTable {
	public enum Style {
		PLAINS, DESERT, SAVANNA, SNOWY, TAIGA, UNKNOWN
	}

	public static final class StyleMaterials {
		/** The block written at the walk surface (a full-block road cell). */
		public final Block roadBlock;
		/**
		 * The block written as support fill below the walk surface. It stays the
		 * vanilla road material even where the surface changed (dirt path for the
		 * non-desert styles, smooth sandstone for the desert).
		 */
		public final Block fillBlock;
		public final Block slabBlock;
		public final Block kerbBlock;
		public final Block leavesBlock;

		StyleMaterials(Block roadBlock, Block fillBlock, Block slabBlock, Block kerbBlock, Block leavesBlock) {
			this.roadBlock = roadBlock;
			this.fillBlock = fillBlock;
			this.slabBlock = slabBlock;
			this.kerbBlock = kerbBlock;
			this.leavesBlock = leavesBlock;
		}

		public boolean isRoadSurface(BlockState state) {
			return state.is(this.roadBlock);
		}
	}

	private final StyleMaterials plains;
	private final StyleMaterials desert;
	private final StyleMaterials savanna;
	private final StyleMaterials snowy;
	private final StyleMaterials taiga;
	private final StyleMaterials unknown;

	private RoadMaterialTable(StyleMaterials plains, StyleMaterials desert, StyleMaterials savanna, StyleMaterials snowy, StyleMaterials taiga) {
		this.plains = plains;
		this.desert = desert;
		this.savanna = savanna;
		this.snowy = snowy;
		this.taiga = taiga;
		this.unknown = plains;
	}

	public static RoadMaterialTable create(VillagePathSmoothedConfig config) {
		StyleMaterials plains = new StyleMaterials(
				Blocks.MUD_BRICKS, Blocks.DIRT_PATH, Blocks.MUD_BRICK_SLAB,
				resolve(config.kerb.blockByStyle.plains, Blocks.COBBLESTONE),
				resolve(config.railing.leavesByStyle.plains, Blocks.OAK_LEAVES));
		StyleMaterials desert = new StyleMaterials(
				Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB,
				resolve(config.kerb.blockByStyle.desert, Blocks.SMOOTH_SANDSTONE),
				resolve(config.railing.leavesByStyle.desert, Blocks.ACACIA_LEAVES));
		StyleMaterials savanna = new StyleMaterials(
				Blocks.MUD_BRICKS, Blocks.DIRT_PATH, Blocks.MUD_BRICK_SLAB,
				resolve(config.kerb.blockByStyle.savanna, Blocks.TERRACOTTA),
				resolve(config.railing.leavesByStyle.savanna, Blocks.ACACIA_LEAVES));
		StyleMaterials snowy = new StyleMaterials(
				Blocks.MUD_BRICKS, Blocks.DIRT_PATH, Blocks.MUD_BRICK_SLAB,
				resolve(config.kerb.blockByStyle.snowy, Blocks.COBBLESTONE),
				resolve(config.railing.leavesByStyle.snowy, Blocks.SPRUCE_LEAVES));
		StyleMaterials taiga = new StyleMaterials(
				Blocks.MUD_BRICKS, Blocks.DIRT_PATH, Blocks.MUD_BRICK_SLAB,
				resolve(config.kerb.blockByStyle.taiga, Blocks.COBBLESTONE),
				resolve(config.railing.leavesByStyle.taiga, Blocks.SPRUCE_LEAVES));
		return new RoadMaterialTable(plains, desert, savanna, snowy, taiga);
	}

	private static Block resolve(String id, Block fallback) {
		if (id == null) {
			return fallback;
		}
		Block block = VpsCompat.blockById(id.trim().toLowerCase());
		return block != null ? block : fallback;
	}

	public StyleMaterials forStyle(String styleName) {
		switch (styleName == null ? "" : styleName) {
			case "plains":
				return this.plains;
			case "desert":
				return this.desert;
			case "savanna":
				return this.savanna;
			case "snowy":
				return this.snowy;
			case "taiga":
				return this.taiga;
			default:
				return this.unknown;
		}
	}

	public static Style styleOf(String styleName) {
		switch (styleName == null ? "" : styleName) {
			case "plains":
				return Style.PLAINS;
			case "desert":
				return Style.DESERT;
			case "savanna":
				return Style.SAVANNA;
			case "snowy":
				return Style.SNOWY;
			case "taiga":
				return Style.TAIGA;
			default:
				return Style.UNKNOWN;
		}
	}
}
