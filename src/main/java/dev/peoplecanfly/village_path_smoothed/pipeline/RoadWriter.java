/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
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
	/** Aggregated diagnostic: cells re-anchored onto the real surface at write time. */
	public static final AtomicLong REANCHORED_WRITES = new AtomicLong();
	private static volatile boolean warnedClampedWrites;
	private static volatile boolean warnedReanchoredWrites;

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
			BlockState vanilla = ctx.blockState(x, topY - 1, z);
			if (RoadMaterialTable.isPlanks(vanilla) || vanilla.is(Blocks.WATER)) {
				placeScaffolding(cell, ctx, x, z, topY, config);
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
		// ---- write-time reconciliation against the real surface (report:
		// "parts of roads sit 1 block low"). The solve anchors cells outside
		// the readable window on the (beard-compensated) noise height; when a
		// column's real surface deviates from the solved walk surface by more
		// than 2 blocks the profile cannot be honoured without a visible bare
		// cliff - re-anchor the road onto the real surface instead of digging a
		// trench. A 1-block deviation stays: it is indistinguishable per-cell
		// from the intended sunken-road gradient (root fix is the beard
		// compensation on the noise anchor, not this net)
		int walkSurface = (p + 1) / 2;
		if (config.roadSmoothing.enabled && Math.abs(walkSurface - topY) > 2) {
			// keep the solved parity: whole-block roads snap flush (road block
			// at topY-1, walk at topY); slab roads snap to a bottom slab at
			// topY-1 (walk at topY-0.5), never floating a slab above ground
			p = (p & 1) == 0 ? 2 * topY : 2 * topY - 1;
			roadTopBlockY = (p - 1) / 2;
			slab = true;
			REANCHORED_WRITES.incrementAndGet();
			if (!warnedReanchoredWrites) {
				warnedReanchoredWrites = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} re-anchored onto the real surface (solved walk surface {} vs real {}); the noise anchor was likely off - check roadSmoothing.beardCompensation", x, z, walkSurface, topY);
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
				applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY);
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
				// line the tunnel roof with the style's kerb material (report:
				// "包边覆盖隧道顶"; the side kerb below keeps replacing #dirt only)
				setBlock(ctx, x, clearTop + 1, z, materials.kerbBlock.defaultBlockState());
			}
		}
		VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();

		// ---- kerbs and railings on side edges (5.5)
		applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY);
	}

	private static void applyKerbsAndRailings(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras, int roadTopBlockY) {
		int walkSurfaceY = roadTopBlockY + 1; // S; top face of the road block
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
			// side surface minus road surface in whole blocks of drop
			int blocks = (outTopY - 1) - walkSurfaceY;

			if (config.kerb.enabled && blocks > config.kerb.minDropHeight) {
				placeKerb(ctx, outX, outZ, roadTopBlockY, outTopY, materials, kerbExtras);
			}
			if (config.railing.enabled && blocks < -config.railing.minDropHeight) {
				placeRailing(ctx, outX, outZ, walkSurfaceY, materials);
			}
		}
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

	private static void placeScaffolding(RoadCell cell, VpsGenContext ctx, int x, int z, int topY, VillagePathSmoothedConfig config) {
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
		boolean waterlogged = ctx.blockState(x, wt, z).is(Blocks.WATER) || ctx.level.getFluidState(new BlockPos(x, wt, z)).is(net.minecraft.tags.FluidTags.WATER);
		BlockState state = Blocks.SCAFFOLDING.defaultBlockState()
				.setValue(ScaffoldingBlock.DISTANCE, distance)
				.setValue(ScaffoldingBlock.BOTTOM, bottom)
				.setValue(ScaffoldingBlock.WATERLOGGED, waterlogged);
		setBlock(ctx, x, wt, z, state);
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
