/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.PoolElementStructurePieceVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.SinglePoolElementVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.StructureTemplateVpsAccess;
import dev.peoplecanfly.village_path_smoothed.terrain.HeightSanity;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Rebuilds the road mask from the structure start's pieces (plan section 5.1).
 *
 * The mask comes from the street templates, never from scanning for dirt_path
 * in the world: the vanilla street processors randomly replace 10-20% of the
 * road with grass blocks (holes!), dirt_path also appears in town centres and
 * other structures, and the template+piece transform is fully deterministic.
 */
public final class RoadCollector {
	/** Cells dropped because their height probe failed or read a floor-level garbage value; they stay vanilla. */
	public static final AtomicLong PROBE_SKIPPED_CELLS = new AtomicLong();

	private RoadCollector() {
	}

	public static RoadNetwork collect(StructureStart start, VpsGenContext ctx, VillagePathSmoothedConfig config) {
		Pattern pattern = patternFor(config);
		StructureTemplateManager templateManager = null;
		Map<Long, RoadCell> cellsByPos = new HashMap<>();
		int pieceCount = 0;

		for (StructurePiece piece : start.getPieces()) {
			if (!(piece instanceof PoolElementStructurePiece poolPiece)) {
				continue;
			}
			if (!(poolPiece.getElement() instanceof SinglePoolElement singleElement)) {
				continue;
			}
			String location = ((SinglePoolElementVpsAccess) singleElement).village_path_smoothed$getTemplateLocation();
			if (location == null) {
				continue; // runtime pool element without a location
			}
			var matcher = pattern.matcher(location);
			if (!matcher.find()) {
				continue;
			}
			String style = styleGroup(matcher);
			if (style == null) {
				continue;
			}
			if (templateManager == null) {
				templateManager = ((PoolElementStructurePieceVpsAccess) poolPiece).village_path_smoothed$getTemplateManager();
			}
			if (templateManager == null) {
				continue;
			}
			pieceCount++;
			collectPiece(poolPiece, singleElement, location, style, templateManager, ctx, config, cellsByPos);
		}

		VillagePathSmoothed.LOGGER.debug("[VPS] village at {}: {} street pieces, {} road cells", start.getChunkPos(), pieceCount, cellsByPos.size());
		int max = Math.max(0, config.diagnostics.maxRoadCellsPerVillage);
		if (max > 0 && cellsByPos.size() > max) {
			VillagePathSmoothed.LOGGER.warn("[VPS] village at {} has {} road cells (>{}, cap); skipping it", start.getChunkPos(), cellsByPos.size(), max);
			return RoadNetwork.build(new ArrayList<>(), config.roadSmoothing.maxExactSectionWidth);
		}
		return RoadNetwork.build(new ArrayList<>(cellsByPos.values()), config.roadSmoothing.maxExactSectionWidth);
	}

	private static String styleGroup(java.util.regex.Matcher matcher) {
		if (matcher.groupCount() >= 1) {
			return matcher.group(1);
		}
		return "";
	}

	private static void collectPiece(
			PoolElementStructurePiece piece,
			SinglePoolElement element,
			String location,
			String style,
			StructureTemplateManager templateManager,
			VpsGenContext ctx,
			VillagePathSmoothedConfig config,
			Map<Long, RoadCell> cellsByPos
	) {
		StructureTemplate template;
		try {
			template = VpsCompat.loadTemplate(templateManager, location);
		} catch (Exception e) {
			VillagePathSmoothed.LOGGER.warn("[VPS] could not load street template {}", location, e);
			return;
		}
		List<StructureTemplate.Palette> palettes = ((StructureTemplateVpsAccess) template).village_path_smoothed$getPalettes();
		if (palettes == null || palettes.isEmpty()) {
			return;
		}
		// mirror vanilla: palette selection uses the piece position
		StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(piece.getRotation());
		StructureTemplate.Palette palette = settings.getRandomPalette(palettes, piece.getPosition());

		BlockPos offset = piece.getPosition();
		for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
			//#if MC >= 12001
			//$$ BlockPos infoPos = info.pos();
			//$$ BlockState infoState = info.state();
			//#else
			BlockPos infoPos = info.pos;
			BlockState infoState = info.state;
			//#endif
			if (infoPos.getY() != 0) {
				continue;
			}
			if (!isRoadSurfaceCell(infoState, style, config)) {
				continue;
			}
			// world position of the template cell for this piece (pivot ZERO, no mirror,
			// exactly like SinglePoolElement.getSettings)
			BlockPos worldPos = StructureTemplate.calculateRelativePosition(settings, infoPos).offset(offset);
			int x = worldPos.getX();
			int z = worldPos.getZ();
			long key = RoadCell.packedXZ(x, z);
			if (cellsByPos.containsKey(key)) {
				continue; // overlapping pieces (crossroads) keep the first claim
			}

			int surfaceTopY;
			boolean anchor = false;
			try {
				surfaceTopY = ctx.surfaceTopY(x, z);
			} catch (Throwable t) {
				VillagePathSmoothed.LOGGER.warn("[VPS] height probe failed at {} {} while collecting", x, z, t);
				PROBE_SKIPPED_CELLS.incrementAndGet();
				continue;
			}
			// Analysis report section 5-2: a probe exactly at the dimension floor
			// is the signature of a lazily-primed all-air chunk (firstAvailable =
			// min_y). VpsGenContext already cross-checks floor-adjacent reads
			// against the noise height; what still arrives here at the exact
			// floor has no better source to confirm it, and no legitimate village
			// terrain sits at the dimension floor, so dropping the cell keeps the
			// vanilla road instead of building into the void. A genuinely low
			// world (superflat) sits ABOVE the floor and is unaffected.
			if (HeightSanity.isExactFloorProbe(surfaceTopY, ctx.getMinY())) {
				PROBE_SKIPPED_CELLS.incrementAndGet();
				VillagePathSmoothed.LOGGER.warn("[VPS] floor-level probe at {} {} ({}); looks like an empty chunk, dropping this road cell to keep it vanilla", x, z, surfaceTopY);
				continue;
			}
			if (ctx.hasRealHeight(x, z)) {
				// over-water cells are fixed anchors (vanilla put planks there);
				// the block read is guarded like the probe above: an unreadable
				// chunk must cost one cell, not the whole village solve (report 5-2)
				try {
					BlockState vanilla = ctx.blockState(x, surfaceTopY - 1, z);
					if (RoadMaterialTable.isPlanks(vanilla) || vanilla.is(Blocks.WATER)) {
						anchor = true;
					}
				} catch (Throwable t) {
					VillagePathSmoothed.LOGGER.debug("[VPS] anchor check failed at {} {}; treating the cell as non-anchor", x, z, t);
				}
			}
			cellsByPos.put(key, new RoadCell(x, z, style, 2 * surfaceTopY, anchor));
		}
	}

	private static boolean isRoadSurfaceCell(BlockState state, String style, VillagePathSmoothedConfig config) {
		switch (config.roadSmoothing.roadSurfaceDetection) {
			case TEMPLATE_Y0_ROAD_MATERIAL: {
				Block road = style.equals("desert") ? Blocks.SMOOTH_SANDSTONE : Blocks.DIRT_PATH;
				return state.is(road);
			}
			default:
				// y==0 solid: LegacySinglePoolElement never overwrites terrain with
				// template air, so anything that is not a marker block is road
				return !state.isAir()
						&& !state.is(Blocks.STRUCTURE_VOID)
						&& !state.is(Blocks.JIGSAW)
						&& !state.is(Blocks.STRUCTURE_BLOCK);
		}
	}

	private static volatile Pattern cachedPattern;
	private static volatile String cachedPatternSource;

	private static Pattern patternFor(VillagePathSmoothedConfig config) {
		String source = config.roadSmoothing.roadPiecesRegex;
		Pattern pattern = cachedPattern;
		if (pattern == null || !source.equals(cachedPatternSource)) {
			pattern = Pattern.compile(source);
			cachedPatternSource = source;
			cachedPattern = pattern;
		}
		return pattern;
	}

	public static boolean hasCustomRegex(VillagePathSmoothedConfig config) {
		return !"^minecraft:village/([a-z_]+)/(streets|terminators)/".equals(config.roadSmoothing.roadPiecesRegex);
	}

	/** Piece-bounding-box based quick check used for the /vps dump command. */
	public static boolean isStreetPiece(StructurePiece piece, VillagePathSmoothedConfig config) {
		return piece instanceof PoolElementStructurePiece poolPiece
				&& poolPiece.getElement() instanceof SinglePoolElement singleElement
				&& ((SinglePoolElementVpsAccess) singleElement).village_path_smoothed$getTemplateLocation() != null
				&& patternFor(config).matcher(((SinglePoolElementVpsAccess) singleElement).village_path_smoothed$getTemplateLocation()).find();
	}

	/** Unused but handy for tests/debug: the bounding box a piece occupies in world space. */
	public static BoundingBox pieceBox(PoolElementStructurePiece piece) {
		return piece.getBoundingBox();
	}
}
