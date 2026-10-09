/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import dev.peoplecanfly.village_path_smoothed.terrain.WaterCrossings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rewrites road cells inside one chunk's writable area (plan sections 5.4-5.6).
 *
 * Slabs are always bottom slabs (no stairs, no orientation), so placement is
 * independent of the piece rotation. Writing uses the vanilla structure flags
 * (UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE): no client updates during worldgen and
 * heightmaps are left alone, exactly like StructureTemplate#placeInWorld.
 */
public final class RoadWriter {
	private static final int WRITE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** Aggregated diagnostic: cells clamped to a dimension bound instead of being written at the solved height. */
	public static final AtomicLong CLAMPED_WRITES = new AtomicLong();
	/** Aggregated diagnostic: cells whose target walk surface is beyond any affordable fill/cut and were snapped back onto the real surface with the correct parity. */
	public static final AtomicLong FALLBACK_REANCHORS = new AtomicLong();
	/** Aggregated diagnostic: cells whose fill/cut exceeded the configured cap (bridge deck / tunnel). */
	public static final AtomicLong OVER_BUDGET_CELLS = new AtomicLong();
	private static volatile boolean warnedClampedWrites;
	private static volatile boolean warnedFallbackReanchors;

	private RoadWriter() {
	}

	public static void write(RoadNetwork net, VpsGenContext ctx, BoundingBox chunkBB, VillagePathSmoothedConfig config) {
		RoadMaterialTable table = RoadMaterialTable.create(config);
		List<Block> kerbExtras = resolveKerbExtras(config);
		for (RoadCell cell : net.cells) {
			if (cell.x < chunkBB.minX() || cell.x > chunkBB.maxX() || cell.z < chunkBB.minZ() || cell.z > chunkBB.maxZ()) {
				continue; // written when this cell's own chunk generates
			}
			try {
				writeCell(net, cell, ctx, config, table, kerbExtras);
			} catch (Throwable t) {
				VillagePathSmoothed.LOGGER.debug("[VPS] failed to write road cell {} {}", cell.x, cell.z, t);
			}
		}
	}

	private static void writeCell(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable table, List<Block> kerbExtras) {
		int x = cell.x;
		int z = cell.z;
		int topY = ctx.realSurfaceTopY(x, z);
		RoadMaterialTable.StyleMaterials materials = table.forStyle(cell.style);

		// ---- water crossing: scaffolding instead of the vanilla planks (5.6)
		if (config.waterCrossing.enabled) {
			int wt = topY - 1; // vanilla puts the planks at the water surface
			boolean waterAtWt = isWater(ctx, x, wt, z);
			boolean waterBelow = isWater(ctx, x, wt - 1, z);
			boolean crossing = WaterCrossings.isCrossing(waterAtWt, waterBelow);
			// entry also keeps the vanilla plank bridges (dry planks are counted
			// separately so /vps stats can characterise the real distribution)
			BlockState vanilla = ctx.blockState(x, wt, z);
			if (crossing || RoadMaterialTable.isPlanks(vanilla)) {
				if (waterAtWt) {
					VpsPipeline.SCAFFOLD_WATER_SELF.incrementAndGet();
				} else if (waterBelow) {
					VpsPipeline.SCAFFOLD_WATER_BELOW.incrementAndGet();
				} else {
					VpsPipeline.SCAFFOLD_DRY.incrementAndGet();
				}
				placeScaffolding(cell, ctx, x, z, topY, crossing, config);
				VpsPipeline.SCAFFOLDS_PLACED.incrementAndGet();
				return;
			}
		}

		// ---- profile rewrite (5.4); skipped when smoothing is disabled
		int p = cell.solvedP;
		int roadTopBlockY;
		boolean slab;
		if ((p & 1) == 0) {
			roadTopBlockY = p / 2 - 1;
			slab = false;
		} else {
			roadTopBlockY = (p - 1) / 2;
			slab = true;
		}
		// ---- fallback re-anchor (A5): only when the target is beyond every
		// affordable fill/cut (no terrain shaping exists any more, so long
		// grades are carried by the fill/cut budget; a cell that no budget can
		// reach snaps back onto the real surface with the CORRECT parity).
		// Even P -> whole block at topY-1 (walk at topY); odd P -> bottom slab
		// at topY-1 (walk at topY-0.5). This is the safety net for hidden
		// carvers / badly distorted noise, and keeps "never dig a trench, never
		// float a slab" true.
		if (config.roadSmoothing.enabled
				&& RoadWritePolicy.overBudget(p, topY, config.roadSmoothing.maxFillLayers, config.roadSmoothing.maxClearLayers)) {
			slab = RoadWritePolicy.fallbackSlab(p);
			roadTopBlockY = topY - 1;
			FALLBACK_REANCHORS.incrementAndGet();
			if (!warnedFallbackReanchors) {
				warnedFallbackReanchors = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} re-anchored: solved walk surface {} is beyond the fill/cut budget (real surface {}); the noise anchor was likely off", x, z, p / 2.0, topY);
			}
		}
		roadTopBlockY = Math.max(ctx.getMinY(), Math.min(ctx.getMaxY(), roadTopBlockY));
		// Report 2.3/5-④: silent clamping was garbage-in-garbage-out with zero
		// log clues. The probe-side garbage filters (VpsGenContext/RoadCollector)
		// should keep this near zero now; counting + a throttled warn keeps any
		// residual case diagnosable
		if (roadTopBlockY <= ctx.getMinY() || roadTopBlockY >= ctx.getMaxY()) {
			CLAMPED_WRITES.incrementAndGet();
			if (!warnedClampedWrites) {
				warnedClampedWrites = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} clamps to a dimension bound (y={}); this normally means a garbage height survived the probe filters", x, z, roadTopBlockY);
			}
		}

		if (config.roadSmoothing.enabled) {
			Block roadBlock = materials.roadBlock;
			if ("STYLE_ROAD".equals(config.roadSmoothing.fillMaterial)) {
				// support columns use the style's road material
			} else {
				Block configured = VpsCompat.blockById(config.roadSmoothing.fillMaterial);
				if (configured != null) {
					roadBlock = configured;
				}
			}

			// fill support below the road block up to the vanilla surface; when
			// the fill exceeds maxFillLayers the column becomes a bridge deck
			// instead (whole-block road: the single road block; slab road: the
			// road slab rides on an upside-down top slab of the same material).
			// The deck stays at the SOLVED height so the half-step constraint to
			// neighbouring cells is preserved
			int fillLayers = roadTopBlockY - topY;
			if (RoadWritePolicy.bridgeDeck(fillLayers, config.roadSmoothing.maxFillLayers)) {
				OVER_BUDGET_CELLS.incrementAndGet();
				if (slab) {
					setBlock(ctx, x, roadTopBlockY - 1, z, materials.slabBlock
							.defaultBlockState()
							.setValue(SlabBlock.TYPE, SlabType.TOP));
				}
				BlockState roadState = slab
						? materials.slabBlock.defaultBlockState()
						: roadBlock.defaultBlockState();
				setBlock(ctx, x, roadTopBlockY, z, roadState);
				VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();
				applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY, slab);
				return;
			}
			for (int y = topY - 1; y < roadTopBlockY; y++) {
				setBlock(ctx, x, y, z, roadBlock.defaultBlockState());
			}
			// road block itself (replaces the vanilla road block / grass patch hole)
			BlockState roadState = slab
					? materials.slabBlock.defaultBlockState()
					: roadBlock.defaultBlockState();
			setBlock(ctx, x, roadTopBlockY, z, roadState);
			// clear whatever the vanilla generation left above the road block
			// (old road block, grass patch holes, planks) - this also restores the
			// grass patch holes (Q12) because the road block is written over them;
			// with a clear cap the remaining terrain becomes a tunnel roof lined
			// with the kerb material
			int clearTop = RoadWritePolicy.clearTopY(roadTopBlockY, topY, config.roadSmoothing.maxClearLayers);
			for (int y = roadTopBlockY + 1; y <= clearTop; y++) {
				setBlock(ctx, x, y, z, Blocks.AIR.defaultBlockState());
			}
			if (RoadWritePolicy.tunnelRoof(roadTopBlockY, topY, clearTop, config.roadSmoothing.maxClearLayers)) {
				OVER_BUDGET_CELLS.incrementAndGet();
				// line the tunnel roof with the style's kerb material (report:
				// "包边覆盖隧道顶"; the side kerb below keeps replacing #dirt only)
				setBlock(ctx, x, clearTop + 1, z, materials.kerbBlock.defaultBlockState());
			}
		}
		VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();

		// ---- kerbs and railings on side edges (5.5)
		applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY, slab);
	}

	private static void applyKerbsAndRailings(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras, int roadTopBlockY, boolean slab) {
		// walk surfaces in half-block units: a slab walk surface is 0.5 above
		// the block Y, a whole block is 1.0 (A1: the old code compared
		// "roadTopBlockY + 1" which is 0.5 too high for slabs and off by one
		// whole block for the kerb/railing thresholds)
		int sRoad2 = RoadWritePolicy.roadWalkSurface2(roadTopBlockY, slab);
		int[] dx = {-1, 1, 0, 0};
		int[] dz = {0, 0, -1, 1};
		for (int dir = 0; dir < 4; dir++) {
			if (net.cellAt(cell.x + dx[dir], cell.z + dz[dir]) >= 0) {
				continue; // road continues this way: no side edge
			}
			int outX = cell.x + dx[dir];
			int outZ = cell.z + dz[dir];
			int outTopY;
			try {
				outTopY = ctx.realSurfaceTopY(outX, outZ); // +/-1 chunk of the current chunk: readable
			} catch (Throwable t) {
				continue;
			}
			int sSide2 = 2 * outTopY;

			if (config.kerb.enabled && RoadWritePolicy.kerbApplies(sSide2, sRoad2, config.kerb.minDropHeight)) {
				placeKerb(ctx, outX, outZ, roadTopBlockY, outTopY, materials, kerbExtras);
			}
			if (config.railing.enabled && RoadWritePolicy.railingApplies(sSide2, sRoad2, config.railing.minDropHeight)) {
				placeRailing(ctx, outX, outZ, roadTopBlockY + 1, materials);
			}
			// elevated road: a solid wall at the road block level on the side
			// column so the sides are not just a row of floating leaves (A1)
			if (config.kerb.enabled && RoadWritePolicy.wallApplies(sSide2, sRoad2, config.kerb.wallMinDropHeight)) {
				placeWall(ctx, outX, outZ, roadTopBlockY, materials);
			}
		}
	}

	/** Elevated-road edge wall: one kerb-material block at the road block level on the side column. */
	private static void placeWall(VpsGenContext ctx, int x, int z, int y, RoadMaterialTable.StyleMaterials materials) {
		BlockState existing = ctx.blockState(x, y, z);
		if (!existing.isAir() && !existing.canBeReplaced()) {
			return;
		}
		setBlock(ctx, x, y, z, materials.kerbBlock.defaultBlockState());
		VpsPipeline.WALLS_PLACED.incrementAndGet();
	}

	/** Replaces dirt-tag blocks on the side column from the road block up to the side surface (Q07/Q08: no upper limit). */
	private static void placeKerb(VpsGenContext ctx, int x, int z, int roadTopBlockY, int outTopY,
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras) {
		boolean placed = false;
		for (int y = Math.max(ctx.getMinY(), roadTopBlockY); y <= Math.min(ctx.getMaxY(), outTopY - 1); y++) {
			BlockState existing = ctx.blockState(x, y, z);
			if (existing.is(BlockTags.DIRT) || kerbExtras.contains(existing.getBlock())) {
				setBlock(ctx, x, y, z, materials.kerbBlock.defaultBlockState());
				placed = true;
			}
		}
		if (placed) {
			VpsPipeline.KERBS_PLACED.incrementAndGet();
		}
	}

	private static void placeRailing(VpsGenContext ctx, int x, int z, int walkSurfaceY, RoadMaterialTable.StyleMaterials materials) {
		BlockState existing = ctx.blockState(x, walkSurfaceY, z);
		if (!existing.isAir() && !existing.canBeReplaced()) {
			return;
		}
		BlockState leaves = materials.leavesBlock.defaultBlockState();
		if (leaves.hasProperty(LeavesBlock.PERSISTENT)) {
			leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
		}
		setBlock(ctx, x, walkSurfaceY, z, leaves);
		VpsPipeline.RAILINGS_PLACED.incrementAndGet();
	}

	private static void placeScaffolding(RoadCell cell, VpsGenContext ctx, int x, int z, int topY, boolean waterlogged, VillagePathSmoothedConfig config) {
		int wt = topY - 1; // vanilla puts the planks at the water surface
		BlockPos belowPos = new BlockPos(x, wt - 1, z);
		BlockState below = ctx.blockState(x, wt - 1, z);
		// vanilla ScaffoldingBlock#getDistance counts a column as supported when
		// the block below has a sturdy UP face - NOT when it is a full collision
		// cube, so slabs or snow-covered grass count as support too; our writer
		// must use the identical criterion or the first tick will recompute a
		// different distance than we placed (bottom-of-village drift)
		boolean supported = below.isFaceSturdy(ctx.level, belowPos, Direction.UP);
		int distance = supported
				? Math.max(0, Math.min(7, config.waterCrossing.distanceWhenSupported))
				: Math.max(0, Math.min(7, config.waterCrossing.distanceWhenUnsupported));
		// vanilla isBottom: distance > 0 && the block below is NOT scaffolding,
		// i.e. a hanging scaffold reads bottom=true (the old check here was
		// inverted: it flagged scaffolds resting on another scaffold)
		boolean bottom = distance > 0 && !below.is(Blocks.SCAFFOLDING);
		BlockState state = Blocks.SCAFFOLDING.defaultBlockState()
				.setValue(ScaffoldingBlock.DISTANCE, distance)
				.setValue(ScaffoldingBlock.BOTTOM, bottom)
				.setValue(ScaffoldingBlock.WATERLOGGED, waterlogged);
		setBlock(ctx, x, wt, z, state);
	}

	/** True when the column has water at this exact Y (block or fluid state). */
	private static boolean isWater(VpsGenContext ctx, int x, int y, int z) {
		if (ctx.blockState(x, y, z).is(Blocks.WATER)) {
			return true;
		}
		return ctx.level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER);
	}

	private static void setBlock(VpsGenContext ctx, int x, int y, int z, BlockState state) {
		if (y < ctx.getMinY() || y > ctx.getMaxY()) {
			return;
		}
		ctx.level.setBlock(new BlockPos(x, y, z), state, WRITE_FLAGS);
	}

	private static List<Block> resolveKerbExtras(VillagePathSmoothedConfig config) {
		List<Block> extras = new ArrayList<>();
		for (String entry : config.kerb.replaceableBlocks) {
			if (entry == null) {
				continue;
			}
			String trimmed = entry.trim().toLowerCase();
			if (trimmed.startsWith("#")) {
				if (!trimmed.equals("#minecraft:dirt")) {
					VillagePathSmoothed.LOGGER.debug("[VPS] ignoring unsupported kerb tag {}", entry);
				}
				continue; // the dirt tag itself is always honoured
			}
			Block block = VpsCompat.blockById(trimmed);
			if (block != null) {
				extras.add(block);
			}
		}
		return extras;
	}
}
