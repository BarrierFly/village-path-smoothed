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
import dev.peoplecanfly.village_path_smoothed.terrain.VpsProbe;
import dev.peoplecanfly.village_path_smoothed.terrain.WaterCrossings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
	/**
	 * Fix plan v4 D1-c: cells whose real-height probe failed or read garbage and
	 * were re-probed on the noise caliber instead of being dropped. Should be far
	 * smaller than {@link #PROBE_SKIPPED_CELLS}; a non-zero value is the difference
	 * between "a hole in the mask" and "a noisier height".
	 */
	public static final AtomicLong NOISE_FALLBACK_CELLS = new AtomicLong();
	// Mask integrity per village (fix plan v4 D1-e, reworked 2026-10-14). A split
	// mask is OBSERVED, not fatal: the components are independent road networks
	// with no edge between them, so each is solved and written on its own.
	/** Villages whose mask is not one 4-connected component. */
	public static final AtomicLong NETWORKS_SPLIT = new AtomicLong();
	/** Sum of mask cells / cells in the largest component, so the ratio is reconstructible. */
	public static final AtomicLong MASK_CELLS_TOTAL = new AtomicLong();
	public static final AtomicLong MASK_CELLS_LARGEST_COMPONENT = new AtomicLong();
	/** Widest break (plan gapWidth): sum for an average, max for the worst village. */
	public static final AtomicLong GAP_WIDTH_SUM = new AtomicLong();
	public static final AtomicLong GAP_WIDTH_MAX = new AtomicLong();
	/** Matches the {@code town_centers} template folder (fix plan v4 D5-c). */
	private static final Pattern TOWN_CENTERS = Pattern.compile("/town_centers/");
	/** Fix plan v2 R5.3: 4-adjacency edges whose two ends use different probe calibers (real heightmap vs noise). */
	public static final AtomicLong PROBE_BOUNDARY_SWITCHES = new AtomicLong();
	/** Fix plan v2 R5.3: columns rewritten by the boundary alignment pass (distinct cells). */
	public static final AtomicLong PROBE_MISMATCH_COLUMNS = new AtomicLong();
	/** Fix plan v2 R5.2: alignment pass iteration cap. */
	static final int PROBE_ALIGNMENT_ROUNDS = 4;

	/**
	 * Road materials accepted on a {@code town_centers} y=0 cell (fix plan v4
	 * D5-c).
	 *
	 * <p>Deliberately narrower than the street rule. Street templates <em>are</em>
	 * roads, so "any solid y=0 block" is safe there; a centre piece's y=0 layer
	 * also holds the fountain basin, the water, the bell and the logs, and a
	 * claimed column has its vanilla y=0 block suppressed by the gravity processor
	 * and rewritten by the road writer. Taking only the plaza material keeps the
	 * fountain itself vanilla while the ground around it joins the road network.
	 *
	 * <p>Lazy on purpose: touching {@link Blocks} in this class's static
	 * initialiser would break every pure unit test that only wants
	 * {@link #alignProbeBoundaries}, because the block registry needs the game
	 * bootstrap.
	 */
	private static final class TownCenterMaterials {
		private static final java.util.Set<Block> ROAD = java.util.Set.of(
				Blocks.DIRT_PATH,
				Blocks.GRASS_BLOCK,
				Blocks.DIRT,
				Blocks.COARSE_DIRT,
				Blocks.ROOTED_DIRT,
				Blocks.COBBLESTONE,
				Blocks.MOSSY_COBBLESTONE,
				Blocks.STONE_BRICKS,
				Blocks.SANDSTONE,
				Blocks.SMOOTH_SANDSTONE,
				Blocks.ANDESITE,
				Blocks.POLISHED_ANDESITE,
				Blocks.STONE,
				Blocks.COBBLED_DEEPSLATE
		);

		private TownCenterMaterials() {
		}
	}

	/** Cells taken from a {@code town_centers} piece (fix plan v4 D5-c). */
	public static final AtomicLong TOWN_CENTER_CELLS = new AtomicLong();
	/** Centre-piece cells skipped because their y=0 block is not road material. */
	public static final AtomicLong TOWN_CENTER_NON_ROAD_SKIPPED = new AtomicLong();
	/**
	 * Decor seats taken into the mask (原版多向道路结构调查 2026-10-15, reworked
	 * 2026-10-16): a street piece's up-facing y=0 jigsaw whose final_state is
	 * one of the base materials. 167 such seats exist per vanilla version
	 * (survey section 5.2).
	 */
	public static final AtomicLong SEAT_CELLS = new AtomicLong();
	/**
	 * R1-3 (four-symptom fix plan 2026-10-16): seat columns whose height probe
	 * failed and were dropped, so the column keeps whatever claimed it (the road
	 * cell, or the vanilla block). A non-zero value distinguishes "rare" from
	 * "systematic".
	 */
	public static final AtomicLong SEAT_PROBE_DROPPED = new AtomicLong();
	/**
	 * R1-2 (2026-10-16): y=0 blocks of a street template that the old
	 * "any non-marker block is road" rule would have taken as road, but that are
	 * non-solid/replaceable (a plant, a torch, a dead bush). They are excluded
	 * now; the count plus {@link #y0BlockSkipReport} is the y=0 histogram the
	 * plan asks for.
	 */
	public static final AtomicLong TEMPLATE_Y0_NON_SOLID_SKIPPED = new AtomicLong();

	/**
	 * The base materials a street seat's jigsaw renders ({@code final_state}).
	 * The four terrain bases plus the two road-material bases: the 44 dirt_path
	 * + 2 cobblestone y=0 up-facing jigsaws per version used to stay OUT of the
	 * mask (agent-recorded "user decision 2026-10-15"), and join it since the
	 * user decision of 2026-10-16 (report §2.3): a base connected to the road
	 * belongs to the solve, whatever block it renders.
	 */
	private static final java.util.Set<String> SEAT_BASE_MATERIALS = java.util.Set.of(
			"grass_block",
			"dirt",
			"sand",
			"smooth_sandstone",
			"dirt_path",
			"cobblestone"
	);

	/** One collected decor seat: world column, owning piece, road style, base block id. */
	private record Seat(int x, int z, int pieceIndex, String style, String block) {
	}

	/** A collected cell's height probe, shared by road cells and fixed base seats. */
	private record ProbedHeight(int surfaceTopY, boolean realHeight) {
	}

	/**
	 * Which y=0 blocks the town-centre rule actually rejected, by block id (fix
	 * plan v4 F5).
	 *
	 * <p>The road-material white list was written from experience, not by reading
	 * the six versions' {@code town_centers} templates, so it can miss a plaza
	 * material (the centre piece then silently stays out of the road network) or
	 * wrongly include a fountain block. The count alone cannot tell those apart -
	 * only the ids can, so {@code /vps debug towncenters} prints them and the
	 * white list is extended from evidence instead of from guesswork.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> TOWN_CENTER_SKIPPED_BLOCKS =
			new java.util.concurrent.ConcurrentHashMap<>();

	private static void recordTownCenterSkip(BlockState state) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		TOWN_CENTER_SKIPPED_BLOCKS.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicLong())
				.incrementAndGet();
	}

	/** R1-2: y=0 non-solid/replaceable street blocks skipped, by id (the y=0 histogram). */
	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> Y0_NON_SOLID_SKIPPED_BLOCKS =
			new java.util.concurrent.ConcurrentHashMap<>();

	private static void recordY0NonSolidSkip(BlockState state) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		Y0_NON_SOLID_SKIPPED_BLOCKS.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicLong())
				.incrementAndGet();
	}

	/** R1-2: the y=0 non-solid/replaceable street blocks skipped, most frequent first (for /vps debug y0). */
	public static List<String> y0NonSolidSkipReport() {
		List<String> out = new ArrayList<>();
		Y0_NON_SOLID_SKIPPED_BLOCKS.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.limit(24)
				.forEach(entry -> out.add(entry.getKey() + " x" + entry.getValue()));
		return out;
	}

	/** The rejected {@code town_centers} blocks, most frequent first (for /vps debug towncenters). */
	public static List<String> townCenterSkipReport() {
		List<String> out = new ArrayList<>();
		TOWN_CENTER_SKIPPED_BLOCKS.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.limit(24)
				.forEach(entry -> out.add(entry.getKey() + " x" + entry.getValue()));
		return out;
	}

	private static final int[] DIR_DX = {-1, 1, 0, 0};
	private static final int[] DIR_DZ = {0, 0, -1, 1};

	private RoadCollector() {
	}

	public static RoadNetwork collect(StructureStart start, VpsProbe ctx, VillagePathSmoothedConfig config) {
		Pattern pattern = patternFor(config);
		StructureTemplateManager templateManager = null;
		Map<Long, RoadCell> cellsByPos = new HashMap<>();
		List<Seat> seats = new ArrayList<>();
		int pieceCount = 0;

		// RIGID piece boxes are the beard sources: vanilla adapts the noise
		// terrain toward their bases, so the noise probe must be compensated
		// near them (BeardifierMarker is a no-op inside getBaseHeight). The
		// vertical fields (minY + groundLevelDelta) match what vanilla
		// Beardifier#compute uses for p = y - (minY + groundLevelDelta);
		// review 3 P1-B needs them for the beard_thin closed form.
		List<int[]> beardBoxes = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) {
			if (piece instanceof PoolElementStructurePiece poolPiece
					&& poolPiece.getElement() instanceof SinglePoolElement
					&& poolPiece.getElement().getProjection() == net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.RIGID) {
				BoundingBox box = piece.getBoundingBox();
				beardBoxes.add(new int[]{box.minX(), box.maxX(), box.minZ(), box.maxZ(), box.minY(), poolPiece.getGroundLevelDelta()});
			}
		}
		ctx.setBeardBoxes(beardBoxes.toArray(new int[0][]));

		for (int pieceIndex = 0; pieceIndex < start.getPieces().size(); pieceIndex++) {
			StructurePiece piece = start.getPieces().get(pieceIndex);
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
			boolean townCentre = TOWN_CENTERS.matcher(location).find();
			if (templateManager == null) {
				templateManager = ((PoolElementStructurePieceVpsAccess) poolPiece).village_path_smoothed$getTemplateManager();
			}
			if (templateManager == null) {
				continue;
			}
			pieceCount++;
			collectPiece(poolPiece, singleElement, location, style, townCentre, pieceIndex, templateManager, ctx, config, cellsByPos, seats);
		}

		// Decor seats join the mask AFTER the road cells and WIN a shared column
		// (user decision 2026-10-16, report §2.3): the street piece already
		// provides the base, so the road must not pave over it - a road cell on
		// the seat's column (a crossroad overlap) is REPLACED by the seat cell.
		// A dropped seat probe keeps whatever claimed the column (never an
		// unwritten hole), and the first seat keeps the column when two seats
		// overlap. Seats are NORMAL solver cells - they ride the solved profile
		// under the even-face constraint; the writer renders their vanilla block
		// at the solved height and the RIGID decor piece on top is shifted to
		// match (plan, 2026-10-16).
		for (Seat seat : seats) {
			long key = RoadCell.packedXZ(seat.x(), seat.z());
			RoadCell existing = cellsByPos.get(key);
			if (existing != null && existing.seat) {
				continue; // two overlapping seats: the first claim keeps the column
			}
			ProbedHeight probe = probeCellHeight(ctx, config, seat.x(), seat.z());
			if (probe == null) {
				SEAT_PROBE_DROPPED.incrementAndGet(); // R1-3: keep the road cell / the vanilla block
				continue;
			}
			RoadCell cell = new RoadCell(seat.x(), seat.z(), seat.style(), 2 * probe.surfaceTopY(), false);
			cell.seat = true;
			cell.seatBlock = seat.block();
			cell.realHeight = probe.realHeight();
			cell.pieceIndex = seat.pieceIndex();
			cellsByPos.put(key, cell);
		}

		VillagePathSmoothed.LOGGER.debug("[VPS] village at {}: {} street pieces, {} road cells", start.getChunkPos(), pieceCount, cellsByPos.size());
		int max = Math.max(0, config.diagnostics.maxRoadCellsPerVillage);
		if (max > 0 && cellsByPos.size() > max) {
			VillagePathSmoothed.LOGGER.warn("[VPS] village at {} has {} road cells (>{}, cap); skipping it", start.getChunkPos(), cellsByPos.size(), max);
			return RoadNetwork.build(new ArrayList<>(), config.roadSmoothing.maxExactSectionWidth);
		}
		List<RoadCell> cells = new ArrayList<>(cellsByPos.values());
		alignProbeBoundaries(cells);
		RoadNetwork network = RoadNetwork.build(cells, config.roadSmoothing.maxExactSectionWidth);
		recordIntegrity(network);
		// Fix plan v4 D1-e used to hand a split mask back to vanilla whole "instead
		// of smoothing half of it". Real village masks split routinely, though, and
		// not because of a dropped probe: vanilla routes side-streets through
		// houses, which are not in the mask, so the street clusters of one village
		// are genuinely separate 4-connected components. Declining on that turned
		// "a few separated side-streets" into "the whole village is vanilla" - the
		// mod looked like it did nothing at all. The components have no edge
		// between them, so solving each one on its own is exactly what an
		// independent street needs; the solver already seeds one polish walk per
		// component. A real hole (a dropped probe column) still shows up in
		// dropped cells / widest break and costs one vanilla column, which
		// ProbeHeights makes rare. Observed, not fatal.
		if (!network.isConnected()) {
			VillagePathSmoothed.LOGGER.warn("[VPS] village at {}: the road mask has {} disconnected components (widest break {} blocks); solving each component on its own - they are separated by non-road blocks, so this is normal",
					start.getChunkPos(), network.componentCount(), network.widestBreak());
		}
		return network;
	}

	/** Feeds the plan's integrity column (dropCells, component ratio, gapWidth). */
	private static void recordIntegrity(RoadNetwork network) {
		if (network.cells.isEmpty()) {
			return;
		}
		if (network.componentCount() > 1) {
			NETWORKS_SPLIT.incrementAndGet();
		}
		MASK_CELLS_TOTAL.addAndGet(network.cells.size());
		MASK_CELLS_LARGEST_COMPONENT.addAndGet(largestComponentCells(network));
		int gap = network.widestBreak();
		if (gap > 0) {
			GAP_WIDTH_SUM.addAndGet(gap);
			GAP_WIDTH_MAX.accumulateAndGet(gap, Math::max);
		}
	}

	private static int largestComponentCells(RoadNetwork network) {
		int[] labels = network.componentLabels();
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int label : labels) {
			sizes.merge(label, 1, Integer::sum);
		}
		int largest = 0;
		for (int size : sizes.values()) {
			largest = Math.max(largest, size);
		}
		return largest;
	}

	/**
	 * Fix plan v2 R5: one alignment pass over the probed heights, run after all
	 * pieces are collected and before the network is built.
	 *
	 * <p>The +/-1 chunk real-heightmap window cannot be widened (vanilla's
	 * WorldGenRegion pins reads to the generating step's direct dependencies),
	 * so a road network mixes both probe calibers: real columns inside the
	 * window, noise columns outside. The two disagree systematically at the
	 * boundary (the noise probe misses carvers and structure bearding), and the
	 * hard caliber switch used to bake a step into the network that the solver
	 * then had to explain away. Instead, every noise column directly adjacent
	 * to a real column whose baseP differs by 2 or more half units inherits the
	 * real value, iterated up to {@value #PROBE_ALIGNMENT_ROUNDS} rounds or
	 * until nothing changes. Pure - operates on the collected cells only, so it
	 * is unit tested without Minecraft (ProbeBoundaryAlignmentTest).
	 *
	 * <p>On the T1.5 path (VpsNoiseContext) every column is noise-probed, so
	 * the pass is a no-op there by construction.
	 *
	 * @param cells the collected road cells, modified in place; solvedP is kept
	 *              in sync with baseP until the solver runs
	 */
	public static void alignProbeBoundaries(List<RoadCell> cells) {
		if (cells.isEmpty()) {
			return;
		}
		Map<Long, Integer> index = new HashMap<>();
		for (int i = 0; i < cells.size(); i++) {
			index.put(RoadCell.packedXZ(cells.get(i).x, cells.get(i).z), i);
		}
		// diagnostic: how many 4-adjacency edges cross the probe caliber boundary
		for (int i = 0; i < cells.size(); i++) {
			RoadCell a = cells.get(i);
			for (int dir = 0; dir < 4; dir++) {
				int j = index.getOrDefault(RoadCell.packedXZ(a.x + DIR_DX[dir], a.z + DIR_DZ[dir]), -1);
				if (j > i && cells.get(j).realHeight != a.realHeight) {
					PROBE_BOUNDARY_SWITCHES.incrementAndGet();
				}
			}
		}
		boolean[] rewritten = new boolean[cells.size()];
		for (int round = 0; round < PROBE_ALIGNMENT_ROUNDS; round++) {
			boolean changed = false;
			for (int i = 0; i < cells.size(); i++) {
				RoadCell a = cells.get(i);
				if (a.realHeight) {
					continue; // real columns are the truth; they never move
				}
				for (int dir = 0; dir < 4; dir++) {
					int j = index.getOrDefault(RoadCell.packedXZ(a.x + DIR_DX[dir], a.z + DIR_DZ[dir]), -1);
					if (j < 0) {
						continue;
					}
					RoadCell b = cells.get(j);
					if (b.realHeight && Math.abs(a.baseP - b.baseP) >= 2) {
						a.baseP = b.baseP;
						a.solvedP = b.baseP; // stays in sync until the solver runs
						if (!rewritten[i]) {
							rewritten[i] = true;
							PROBE_MISMATCH_COLUMNS.incrementAndGet();
						}
						changed = true;
					}
				}
			}
			if (!changed) {
				return;
			}
		}
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
			boolean townCentre,
			int pieceIndex,
			StructureTemplateManager templateManager,
			VpsProbe ctx,
			VillagePathSmoothedConfig config,
			Map<Long, RoadCell> cellsByPos,
			List<Seat> seats
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
			if (townCentre) {
				// fix plan v4 D5-c: only the plaza joins the mask
				if (!config.roadSmoothing.includeTownCentersRoadMaterials || !TownCenterMaterials.ROAD.contains(infoState.getBlock())) {
					TOWN_CENTER_NON_ROAD_SKIPPED.incrementAndGet();
					recordTownCenterSkip(infoState);
					continue;
				}
			} else if (infoState.is(Blocks.JIGSAW)) {
				// A street piece's y=0 jigsaw is a DECOR SEAT: placement replaces it
				// with its final_state (the lamp/tree/villager base block), so it
				// must not be paved over. Qualifying seats join the mask as NORMAL
				// solver cells that ride the solved profile (2026-10-16); other
				// jigsaws stay excluded exactly as before.
				collectSeat(infoState, info, infoPos, settings, offset, pieceIndex, style, seats);
				continue;
			} else if (!isRoadSurfaceCell(infoState, style, config)) {
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

			ProbedHeight probe = probeCellHeight(ctx, config, x, z);
			if (probe == null) {
				continue;
			}
			int surfaceTopY = probe.surfaceTopY();
			boolean realHeight = probe.realHeight();
			boolean anchor = false;
			if (realHeight) {
				// over-water cells are fixed anchors; the same fluid predicate
				// as the writer (A2) is used here: water at the vanilla road
				// block height OR one below it. The block read is guarded like
				// the probe above: an unreadable chunk must cost one cell, not
				// the whole village solve (report 5-2)
				try {
					boolean waterAtWt = ctx.blockState(x, surfaceTopY - 1, z).is(Blocks.WATER);
					boolean waterBelow = ctx.blockState(x, surfaceTopY - 2, z).is(Blocks.WATER);
					if (WaterCrossings.isCrossing(waterAtWt, waterBelow)) {
						anchor = true;
					}
				} catch (Throwable t) {
					VillagePathSmoothed.LOGGER.debug("[VPS] anchor check failed at {} {}; treating the cell as non-anchor", x, z, t);
				}
			} else {
				// outside the readable window: approximate the crossing from the
				// noise water column (surface above ocean floor)
				anchor = ctx.noiseHasWaterColumn(x, z);
			}
			RoadCell cell = new RoadCell(x, z, style, 2 * surfaceTopY, anchor);
			cell.realHeight = realHeight;
			cell.pieceIndex = pieceIndex;
			if (townCentre) {
				// plan C (user decision 2026-10-15): the plaza is solved flat at a
				// pinned level and rendered by its own rigid piece - the writer never
				// writes a road block on it. Remember which platform renders this
				// column and at what walk surface, so the pin can follow the piece
				// across the T1.5 shift (re-read live after any move).
				cell.plaza = true;
				cell.plazaVanillaP = 2 * (piece.getBoundingBox().minY() + 1);
				TOWN_CENTER_CELLS.incrementAndGet();
			}
			cellsByPos.put(key, cell);
		}
	}

	/**
	 * Shared height probe for one collected column (road cell or fixed base
	 * seat): the real/noise caliber dance of fix plan v4 D1-c, extracted
	 * verbatim from the road-cell path so both cell kinds probe identically.
	 * Returns null when the column must be dropped (the caller counts it as
	 * probe-skipped and leaves the vanilla block).
	 */
	private static ProbedHeight probeCellHeight(VpsProbe ctx, VillagePathSmoothedConfig config, int x, int z) {
		int surfaceTopY;
		boolean realHeight = ctx.hasRealHeight(x, z);
		int noiseTopY = VpsGenContext.NOISE_UNAVAILABLE;
		try {
			surfaceTopY = ctx.surfaceTopY(x, z, config);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.warn("[VPS] height probe failed at {} {} while collecting; retrying on the noise caliber", x, z, t);
			surfaceTopY = VpsGenContext.NOISE_UNAVAILABLE;
		}
		// Analysis report section 5-2: a probe exactly at the dimension floor
		// is the signature of a lazily-primed all-air chunk (firstAvailable =
		// min_y). VpsGenContext already cross-checks floor-adjacent reads
		// against the noise height; what still arrives here at the exact floor
		// has no better source to confirm it. A genuinely low world (superflat)
		// sits ABOVE the floor and is unaffected.
		if (surfaceTopY != VpsGenContext.NOISE_UNAVAILABLE
				&& HeightSanity.isExactFloorProbe(surfaceTopY, ctx.getMinY())) {
			VillagePathSmoothed.LOGGER.warn("[VPS] floor-level probe at {} {} ({}); looks like an empty chunk, re-probing on the noise caliber", x, z, surfaceTopY);
			surfaceTopY = VpsGenContext.NOISE_UNAVAILABLE;
		}
		if (surfaceTopY == VpsGenContext.NOISE_UNAVAILABLE) {
			noiseTopY = ctx.noiseSurfaceTopY(x, z, config);
		}
		// fix plan v4 D1-c: a failed probe used to drop the column. The noise
		// caliber is a pure function of x/z, so a noisier height is always
		// available before the column is given up - a noisier height costs
		// deviation, a dropped column splits the mask.
		ProbeHeights.Outcome outcome = ProbeHeights.resolve(surfaceTopY, noiseTopY, ctx.getMinY(), VpsGenContext.NOISE_UNAVAILABLE);
		if (outcome == ProbeHeights.Outcome.USE_NOISE) {
			NOISE_FALLBACK_CELLS.incrementAndGet();
			return new ProbedHeight(noiseTopY, false);
		}
		if (outcome == ProbeHeights.Outcome.DROP) {
			PROBE_SKIPPED_CELLS.incrementAndGet();
			return null;
		}
		return new ProbedHeight(surfaceTopY, realHeight);
	}

	/**
	 * A street piece's up-facing y=0 jigsaw whose {@code final_state} is one of
	 * the base materials is a DECOR SEAT (vanilla multi-way road survey
	 * 2026-10-15, section 7): placement replaces the jigsaw with that block and
	 * the decor piece (lamp, tree, flower, villager) stands on it. The cell
	 * joins the mask as a NORMAL solver cell - it rides the solved profile, the
	 * writer renders the seat block at the solved height and the RIGID decor
	 * piece on top is shifted to match (plan 2026-10-16).
	 */
	private static void collectSeat(BlockState state, StructureTemplate.StructureBlockInfo info, BlockPos infoPos,
			StructurePlaceSettings settings, BlockPos offset, int pieceIndex, String style, List<Seat> seats) {
		if (!state.hasProperty(BlockStateProperties.ORIENTATION)) {
			return;
		}
		try {
			if (state.getValue(BlockStateProperties.ORIENTATION).front() != Direction.UP) {
				return;
			}
		} catch (Throwable t) {
			return; // an unreadable orientation must cost the seat, not the village
		}
		String finalState;
		try {
			//#if MC >= 12001
			//$$ CompoundTag nbt = info.nbt();
			//#else
			CompoundTag nbt = info.nbt;
			//#endif
			//#if MC >= 12110
			//$$ finalState = nbt == null ? null : nbt.getString("final_state").orElse(null);
			//#else
			finalState = nbt == null ? null : nbt.getString("final_state");
			//#endif
		} catch (Throwable t) {
			return;
		}
		if (finalState == null) {
			return;
		}
		String id = finalState.startsWith("minecraft:") ? finalState.substring("minecraft:".length()) : finalState;
		if (!SEAT_BASE_MATERIALS.contains(id)) {
			return; // an unknown final_state is not a surveyed seat base
		}
		BlockPos worldPos = StructureTemplate.calculateRelativePosition(settings, infoPos).offset(offset);
		seats.add(new Seat(worldPos.getX(), worldPos.getZ(), pieceIndex, style, id));
		SEAT_CELLS.incrementAndGet();
	}

	private static boolean isRoadSurfaceCell(BlockState state, String style, VillagePathSmoothedConfig config) {
		switch (config.roadSmoothing.roadSurfaceDetection) {
			case TEMPLATE_Y0_ROAD_MATERIAL: {
				Block road = style.equals("desert") ? Blocks.SMOOTH_SANDSTONE : Blocks.DIRT_PATH;
				return state.is(road);
			}
			default:
				// y==0 solid: LegacySinglePoolElement never overwrites terrain with
				// template air, so anything that is not a marker block is road.
				// R1-2 (2026-10-16): a non-solid/replaceable y=0 block (a plant
				// such as a dead bush, a torch, a snow layer) is NOT road - the old
				// rule took it and the road writer then paved it over. Excluded by
				// block semantics, not by a material whitelist, so a legitimate
				// street material is never dropped.
				if (state.canBeReplaced()) {
					if (!state.isAir()) {
						TEMPLATE_Y0_NON_SOLID_SKIPPED.incrementAndGet();
						recordY0NonSolidSkip(state);
					}
					return false;
				}
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
		// Fix plan analysis 2026-10-15 (section 4.2): this used to compare against
		// the pre-D5-c default string, so the current default was judged "custom"
		// and the non-village fast path in VpsPipeline#prepare never fired - every
		// mineshaft ran a collect and polluted VILLAGES_SOLVED. Compare against the
		// constant the field default actually uses.
		return !VillagePathSmoothedConfig.RoadSmoothing.DEFAULT_ROAD_PIECES_REGEX.equals(config.roadSmoothing.roadPiecesRegex);
	}

	/** Piece-bounding-box based quick check used for the /vps dump command. */
	public static boolean isStreetPiece(StructurePiece piece, VillagePathSmoothedConfig config) {
		return piece instanceof PoolElementStructurePiece poolPiece
				&& poolPiece.getElement() instanceof SinglePoolElement singleElement
				&& ((SinglePoolElementVpsAccess) singleElement).village_path_smoothed$getTemplateLocation() != null
				&& patternFor(config).matcher(((SinglePoolElementVpsAccess) singleElement).village_path_smoothed$getTemplateLocation()).find();
	}
}
