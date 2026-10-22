/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.SinglePoolElementVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.StructureTemplateVpsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The actual block footprint of the decor pieces standing on street seats
 * (audit 2026-10-20, decisions 1/2/4; user decisions: block-level plan A with
 * support fill, T5 pin seat + footprint, T1.5 seat sync).
 *
 * <p>A decor piece is recognised by the target pool of an up-facing y0 street
 * jigsaw ({@link RoadNetwork#decorPools}) and by its bounding box containing
 * one of those seat columns ({@link RoadNetwork#allSeatColumns}). The footprint
 * is the set of blocks the template really places: palette blocks at the
 * piece's rotation/position plus the jigsaw's {@code final_state} block (a
 * decor template's own jigsaw renders cobblestone/fence/... there). Air and
 * {@code structure_void} render nothing and are not part of it.
 *
 * <p>Plan A no longer skips a decor piece's whole bbox. The writer protects
 * these blocks individually (see {@code VpsGenContext#isProtectedDecoration})
 * and still writes the road block and the support fill below/around them, so
 * the old bbox holes under lamp arms, trapdoor rings and stairs are gone.
 */
public final class StreetDecor {
	public static final class Piece {
		public final int pieceIndex;
		public final String templateLocation;
		/** True for a decor template larger than 1x1 (bbox footprint); 1x1 pieces ride the seat. */
		public final boolean multiBlock;
		/** Walk surface the decor's base sits on: {@code bbox.minY + groundLevelDelta}. */
		public int baseWalkY;
		/** The piece's XZ bounding box: the full base footprint lives inside it. */
		public int minX;
		public int maxX;
		public int minZ;
		public int maxZ;
		/** World columns with at least one real template block ({@link RoadCell#packedXZ}). */
		public final Set<Long> columns = new LinkedHashSet<>();
		/** World block positions of the real template blocks ({@link RoadCell#packedXYZ}). */
		public final Set<Long> blocks = new LinkedHashSet<>();
		/** Per-column actual block Y range {minY, maxY}, keyed by column. */
		public final Map<Long, int[]> columnYRanges = new HashMap<>();
		/** Seat columns inside the piece's bbox (whether the template has a block there or not). */
		public final Set<Long> seatColumns = new LinkedHashSet<>();
		/** Jigsaw-final base block id per packed XYZ; restored if placement left that cell air. */
		public final Map<Long, String> baseBlocks = new HashMap<>();
		/** Exact template block Ys per packed XZ column (for the fixture-ground overlap). */
		public final Map<Long, List<Integer>> columnBlockYs = new HashMap<>();

		private Piece(int pieceIndex, String templateLocation, boolean multiBlock) {
			this.pieceIndex = pieceIndex;
			this.templateLocation = templateLocation;
			this.multiBlock = multiBlock;
		}
	}

	/**
	 * 2026-10-22: a fixture base column used outside a street seat (a house
	 * lamp). If a road cell claims the same column, this is the structure ground
	 * the writer renders at the solved height; the template offsets above the
	 * base block move with it.
	 */
	public static final class FixtureGround {
		public final int pieceIndex;
		public final int baseBlockY;
		public final String blockId;
		public final int[] aboveLocalYs;

		public FixtureGround(int pieceIndex, int baseBlockY, String blockId, int[] aboveLocalYs) {
			this.pieceIndex = pieceIndex;
			this.baseBlockY = baseBlockY;
			this.blockId = blockId;
			this.aboveLocalYs = aboveLocalYs;
		}
	}

	public static final class Resolved {
		public final List<Piece> pieces;
		public final Set<Integer> pieceIndices;
		public final Set<Long> blocks;
		public final Set<Long> columns;
		/** Jigsaw-final base block id per packed XYZ, merged from the pieces. */
		public final Map<Long, String> baseBlocks;
		/**
		 * Fixture base cells for decor used outside a street seat: packed XZ to
		 * the base walk Y. The solve pins these non-road cells so the adjacent
		 * roads are re-solved within half a block of the real base.
		 */
		public final Map<Long, Integer> fixtureBases;
		/** Base block ids of the fixture pieces (house decor), for the writer restore. */
		public final Map<Long, String> fixtureBaseBlocks;
		/** Full fixture-ground payload per packed XZ column, for the overlap rewrite. */
		public final Map<Long, FixtureGround> fixtureGrounds;
		/** One tight box per actual block column: {minX,maxX,minY,maxY,minZ,maxZ}. */
		public final int[][] columnBoxes;

		private Resolved(List<Piece> pieces, Set<Integer> pieceIndices, Set<Long> blocks,
				Set<Long> columns, Map<Long, String> baseBlocks, Map<Long, Integer> fixtureBases,
				Map<Long, String> fixtureBaseBlocks, Map<Long, FixtureGround> fixtureGrounds,
				int[][] columnBoxes) {
			this.pieces = pieces;
			this.pieceIndices = pieceIndices;
			this.blocks = blocks;
			this.columns = columns;
			this.baseBlocks = baseBlocks;
			this.fixtureBases = fixtureBases;
			this.fixtureBaseBlocks = fixtureBaseBlocks;
			this.fixtureGrounds = fixtureGrounds;
			this.columnBoxes = columnBoxes;
		}
	}

	private static final Resolved EMPTY = new Resolved(List.of(), Set.of(), Set.of(), Set.of(),
			Map.of(), Map.of(), Map.of(), Map.of(), new int[0][]);

	private StreetDecor() {
	}

	/**
	 * Recognises the decor pieces of one village and computes their footprint at
	 * the pieces' CURRENT positions. Call {@link #refresh} after a T1.5 move to
	 * move the footprint with the pieces.
	 */
	public static Resolved identify(List<StructurePiece> pieces, RoadNetwork network,
			StructureTemplateManager templateManager, RegistryAccess registries) {
		if (registries == null || network.decorPools.isEmpty() || network.allSeatColumns.isEmpty()) {
			return EMPTY;
		}
		Set<String> decorTemplates = decorTemplateLocations(network, registries);
		if (decorTemplates.isEmpty()) {
			return EMPTY;
		}
		List<Piece> found = new ArrayList<>();
		Map<Long, String> baseOnly = new HashMap<>();
		Map<Long, Integer> fixtureBases = new HashMap<>();
		Map<Long, FixtureGround> fixtureGrounds = new HashMap<>();
		for (int i = 0; i < pieces.size(); i++) {
			StructurePiece piece = pieces.get(i);
			if (!(piece instanceof PoolElementStructurePiece pool)
					|| !(pool.getElement() instanceof SinglePoolElement element)) {
				continue;
			}
			String location;
			try {
				location = ((SinglePoolElementVpsAccess) element).village_path_smoothed$getTemplateLocation();
			} catch (Throwable t) {
				continue;
			}
			if (location == null || !decorTemplates.contains(location)) {
				continue;
			}
			BoundingBox box = piece.getBoundingBox();
			Set<Long> seats = seatColumnsIn(box, network.allSeatColumns);
			boolean multiBlock = box.minX() != box.maxX() || box.minZ() != box.maxZ();
			Piece decor = new Piece(i, location, multiBlock);
			decor.minX = box.minX();
			decor.maxX = box.maxX();
			decor.minZ = box.minZ();
			decor.maxZ = box.maxZ();
			decor.seatColumns.addAll(seats);
			if (!computeBlocks(decor, pool, templateManager)) {
				VpsPipeline.DECOR_RESOLVE_FAILED.incrementAndGet();
				continue; // unloadable template: leave the piece to the old bbox fallback
			}
			if (seats.isEmpty()) {
				// 2026-10-21: the same template used outside a street seat (a
				// house lamp) is not road-network decor, but its jigsaw-final
				// base block is still restored if placement left it air.
				baseOnly.putAll(decor.baseBlocks);
				for (Map.Entry<Long, String> base : decor.baseBlocks.entrySet()) {
					long key = base.getKey();
					long column = RoadCell.packedXZ(RoadCell.unpackX(key), RoadCell.unpackZ(key));
					fixtureBases.put(column, decor.baseWalkY);
					fixtureGrounds.put(column, fixtureGround(i, RoadCell.unpackY(key),
							base.getValue(), decor.columnBlockYs.get(column)));
				}
				continue;
			}
			found.add(decor);
		}
		return assemble(found, pieces, baseOnly, fixtureBases, fixtureGrounds);
	}

	private static FixtureGround fixtureGround(int pieceIndex, int baseY, String blockId, List<Integer> columnYs) {
		List<Integer> above = new ArrayList<>();
		if (columnYs != null) {
			for (int y : columnYs) {
				if (y > baseY) {
					above.add(y - baseY);
				}
			}
		}
		above.sort(null);
		int[] offsets = new int[above.size()];
		for (int k = 0; k < offsets.length; k++) {
			offsets[k] = above.get(k);
		}
		return new FixtureGround(pieceIndex, baseY, blockId, offsets);
	}

	/** Recomputes the blocks of an already identified decor set at the pieces' current positions. */
	public static Resolved refresh(Resolved previous, List<StructurePiece> pieces,
			StructureTemplateManager templateManager) {
		if (previous.pieces.isEmpty()) {
			return previous;
		}
		// Keep the base-only (house decor) cells; only the street pieces move.
		Map<Long, String> baseOnly = new HashMap<>(previous.baseBlocks);
		Map<Long, Integer> fixtureBases = new HashMap<>(previous.fixtureBases);
		Map<Long, FixtureGround> fixtureGrounds = new HashMap<>(previous.fixtureGrounds);
		for (Piece old : previous.pieces) {
			baseOnly.keySet().removeAll(old.baseBlocks.keySet());
		}
		List<Piece> refreshed = new ArrayList<>(previous.pieces.size());
		for (Piece old : previous.pieces) {
			if (old.pieceIndex < 0 || old.pieceIndex >= pieces.size()) {
				continue;
			}
			StructurePiece piece = pieces.get(old.pieceIndex);
			if (!(piece instanceof PoolElementStructurePiece pool)
					|| !(pool.getElement() instanceof SinglePoolElement)) {
				continue;
			}
			Piece decor = new Piece(old.pieceIndex, old.templateLocation, old.multiBlock);
			BoundingBox box = piece.getBoundingBox();
			decor.minX = box.minX();
			decor.maxX = box.maxX();
			decor.minZ = box.minZ();
			decor.maxZ = box.maxZ();
			decor.seatColumns.addAll(old.seatColumns);
			if (computeBlocks(decor, pool, templateManager)) {
				refreshed.add(decor);
			} else {
				VpsPipeline.DECOR_RESOLVE_FAILED.incrementAndGet();
			}
		}
		return assemble(refreshed, pieces, baseOnly, fixtureBases, fixtureGrounds);
	}

	/**
	 * The decor's own solve cells for the 2026-10-21 connection semantics: the
	 * 4-connected subset of the bbox network cells that contains a seat column
	 * or an actual template block column. A bbox ring separated from the seat by
	 * non-road columns is a different structure; it must not move the decor and
	 * it is not a connection.
	 */
	private static Set<Long> baseColumns(RoadNetwork network, Piece piece) {
		Set<Long> footprint = new HashSet<>();
		for (int x = piece.minX; x <= piece.maxX; x++) {
			for (int z = piece.minZ; z <= piece.maxZ; z++) {
				if (network.cellAt(x, z) >= 0) {
					footprint.add(RoadCell.packedXZ(x, z));
				}
			}
		}
		Set<Long> starts = new HashSet<>();
		for (long column : piece.seatColumns) {
			int x = (int) (column >> 32);
			int z = (int) column;
			if (network.cellAt(x, z) >= 0) {
				starts.add(column);
			}
		}
		for (long column : piece.columns) {
			int x = (int) (column >> 32);
			int z = (int) column;
			if (network.cellAt(x, z) >= 0) {
				starts.add(column);
			}
		}
		return network.connectedColumns(footprint, starts);
	}

	/**
	 * T5 flat-base replacement for the old actual-block pin: every connected
	 * road cell of the decor's XZ bbox (seat, planks/stairs and the air columns
	 * over road) is pinned to the same even walk level. The component is the
	 * base the player sees; a disconnected bbox ring is a different structure.
	 * Returns the number of cells pinned.
	 */
	public static int pinBaseFootprints(Resolved resolved, RoadNetwork network) {
		int pinned = 0;
		java.util.Set<Long> seen = new HashSet<>();
		for (Piece piece : resolved.pieces) {
			if (!piece.multiBlock) {
				continue;
			}
			int pinP = 2 * piece.baseWalkY;
			for (long column : baseColumns(network, piece)) {
				if (seen.add(column)) {
					pinned += pinCell(network, column, pinP);
				}
			}
		}
		return pinned;
	}

	/**
	 * T1.5 replacement for the immovable pin: the connected base footprint of
	 * each multi-block decor becomes one MOVABLE flat unit in the final solve.
	 * The polish flattens it to one even level and may move it as a whole,
	 * exactly like a small plaza; the rigid decor is moved to that level
	 * afterwards by {@link #settleBases}. Air columns over road inside the
	 * component are included; a disconnected ring is not this decor's base.
	 */
	public static void buildBaseGroups(Resolved resolved, RoadNetwork network) {
		if (resolved.pieces.isEmpty()) {
			return;
		}
		network.decorBaseGroupsMovable = true;
		java.util.Set<Long> used = new HashSet<>();
		for (Piece piece : resolved.pieces) {
			if (!piece.multiBlock) {
				continue; // a 1x1 seat is the seat rider / T5 pin
			}
			List<Integer> cells = new ArrayList<>();
			List<Long> columns = new ArrayList<>(baseColumns(network, piece));
			columns.sort(Long::compare);
			for (long column : columns) {
				if (used.contains(column)) {
					continue;
				}
				int x = (int) (column >> 32);
				int z = (int) column;
				int index = network.cellAt(x, z);
				if (index < 0) {
					continue;
				}
				RoadCell cell = network.cells.get(index);
				if (cell.plaza) {
					continue; // the plaza group owns this cell
				}
				cells.add(index);
			}
			if (cells.isEmpty()) {
				continue;
			}
			int[] indices = new int[cells.size()];
			for (int i = 0; i < indices.length; i++) {
				indices[i] = cells.get(i);
				RoadCell cell = network.cells.get(indices[i]);
				used.add(RoadCell.packedXZ(cell.x, cell.z));
			}
			network.decorBaseGroups.add(new RoadNetwork.DecorBaseGroup(piece.pieceIndex, indices));
			VpsPipeline.DECOR_BASE_GROUPS.incrementAndGet();
			VpsPipeline.DECOR_BASE_GROUP_CELLS.addAndGet(indices.length);
		}
	}

	/**
	 * 2026-10-21: adds fixture base cells (decor used outside a street seat,
	 * e.g. a house lamp) to the network as pinned non-road solve cells. The
	 * solver then re-solves the 4-adjacent roads within half a block of the
	 * real base; the writer skips these cells. The network is rebuilt only
	 * when at least one cell is new.
	 */
	public static RoadNetwork addFixtureBases(RoadNetwork network, Resolved resolved, int maxWidth, boolean movable) {
		if (resolved.fixtureBases.isEmpty()) {
			return network;
		}
		List<RoadCell> cells = new ArrayList<>(network.cells);
		int added = 0;
		boolean changed = false;
		for (Map.Entry<Long, Integer> entry : resolved.fixtureBases.entrySet()) {
			long column = entry.getKey();
			int x = (int) (column >> 32);
			int z = (int) column;
			int index = network.cellAt(x, z);
			FixtureGround ground = resolved.fixtureGrounds.get(column);
			if (index >= 0) {
				RoadCell cell = network.cells.get(index);
				if (cell.seat || cell.plaza || cell.fixtureBase || cell.groundOverlap
						|| ground == null) {
					continue; // an existing structure already owns this cell
				}
				// The actual overlap: the structure ground and the road cell share
				// this column. Solve them as one flat unit and render the ground.
				cell.groundOverlap = true;
				cell.groundBlock = ground.blockId;
				cell.groundOldBlockY = ground.baseBlockY;
				cell.groundAboveLocalYs = ground.aboveLocalYs;
				cell.groundGroupKey = RoadNetwork.fixtureGroupKey(ground.pieceIndex);
				cell.groundGroupMovable = movable;
				cell.baseP = 2 * entry.getValue();
				cell.solvedP = cell.baseP;
				VpsPipeline.FIXTURE_BASE_OVERLAP_CELLS.incrementAndGet();
				changed = true;
				continue;
			}
			RoadCell cell = new RoadCell(x, z, "plains", 2 * entry.getValue(), false);
			cell.fixtureBase = true;
			cell.plazaPinned = true;
			cell.plazaPinP = 2 * entry.getValue();
			cells.add(cell);
			added++;
		}
		if (added == 0 && !changed) {
			return network;
		}
		RoadNetwork rebuilt = RoadNetwork.build(cells, maxWidth);
		rebuilt.plazaFootprint.addAll(network.plazaFootprint);
		rebuilt.streetScenery.addAll(network.streetScenery);
		rebuilt.streetSceneryGrounds.putAll(network.streetSceneryGrounds);
		rebuilt.allSeatColumns.addAll(network.allSeatColumns);
		rebuilt.decorPools.addAll(network.decorPools);
		return rebuilt;
	}

	/**
	 * Moves every multi-block decor to the level its base group settled on.
	 * The group is flat and even, so this is a whole-block move; the refreshed
	 * footprint then matches the road/seat the writer renders. A world-height
	 * clamp is counted, never silently ignored.
	 */
	public static Resolved settleBases(Resolved resolved, RoadNetwork network, List<StructurePiece> pieces,
			StructureTemplateManager templateManager, int minY, int maxY) {
		if (resolved.pieces.isEmpty() || network.decorBaseGroups.isEmpty()) {
			return resolved;
		}
		Map<Integer, Integer> groupLevels = new HashMap<>();
		for (RoadNetwork.DecorBaseGroup group : network.decorBaseGroups) {
			if (group.cells.length > 0) {
				groupLevels.put(group.pieceIndex, network.cells.get(group.cells[0]).solvedP);
			}
		}
		for (Piece piece : resolved.pieces) {
			if (!piece.multiBlock) {
				continue;
			}
			Integer level = groupLevels.get(piece.pieceIndex);
			if (level == null || piece.pieceIndex < 0 || piece.pieceIndex >= pieces.size()) {
				continue;
			}
			int desiredWalk = level >> 1; // the group is even; arithmetic shift also floors correctly below Y=0
			int dy = desiredWalk - piece.baseWalkY;
			if (dy == 0) {
				continue;
			}
			BoundingBox box = pieces.get(piece.pieceIndex).getBoundingBox();
			int lo = minY - box.minY();
			int hi = maxY - box.maxY();
			int clamped = lo > hi ? 0 : Math.max(lo, Math.min(hi, dy));
			if (clamped != dy) {
				VpsPipeline.DECOR_SETTLE_CLAMPED.incrementAndGet();
			}
			if (clamped != 0) {
				pieces.get(piece.pieceIndex).move(0, clamped, 0);
				VpsPipeline.DECOR_SETTLED_PIECES.incrementAndGet();
			}
		}
		return refresh(resolved, pieces, templateManager);
	}

	/**
	 * Counts the fixture-to-road relationship under the 2026-10-21 semantics:
	 * two structures in the road network are connected only through
	 * <b>horizontally adjacent (4-adjacency)</b> cells that are part of the
	 * solve. The decor's own solve cells are the network cells inside its base
	 * footprint - the seat for a 1x1 piece, the full footprint (seat, actual
	 * blocks and air columns over road) for a multi-block piece. Seats count as
	 * cells and as neighbours.
	 *
	 * <p>Every 4-neighbour of that footprint that is not part of the decor is a
	 * connection. The solver's edge invariant already requires {@code |dP| <= 1}
	 * on each such edge, subject to each structure's own shape constraints
	 * (flat plaza/base, pins, seat parity); this counter reports the residuals
	 * and compares every neighbour, not just the nearest one. A piece with no
	 * external neighbour is isolated. Proximity and diagonal corners are not
	 * connections: the old Chebyshev-3 seat search treated lawn/diagonal seats
	 * as connected and could miss a road beside a multi-block footprint far
	 * from the seat.
	 */
	public static void recordRoadMismatch(Resolved resolved, RoadNetwork network) {
		for (Piece piece : resolved.pieces) {
			Set<Long> ownColumns = baseColumns(network, piece);
			int[] external = network.externalNeighbours(ownColumns);
			if (external.length == 0) {
				VpsPipeline.DECOR_ROAD_ISOLATED_PIECES.incrementAndGet();
				continue;
			}
			int worst = 0;
			for (int index : external) {
				int diff = Math.abs(network.cells.get(index).solvedP - 2 * piece.baseWalkY);
				worst = Math.max(worst, diff);
			}
			if (worst > 1) {
				VpsPipeline.DECOR_ROAD_MISMATCH_PIECES.incrementAndGet();
				VpsPipeline.DECOR_ROAD_MISMATCH_WORST.accumulateAndGet(worst, Math::max);
			}
		}
	}

	/**
	 * Seat cells with no 4-adjacent mask cell: the solver has no edge that could
	 * constrain them. This is a seat-level observation; the structure-level
	 * connection used by {@link #recordRoadMismatch} additionally excludes the
	 * decor's own base footprint, so a seat inside a multi-block base with only
	 * internal neighbours is not counted here but its structure can still be
	 * isolated.
	 */
	public static void recordIsolatedSeats(RoadNetwork network) {
		for (RoadCell cell : network.cells) {
			if (!cell.seat) {
				continue;
			}
			boolean linked = false;
			if (cell.index >= 0 && cell.index < network.neighbors.length) {
				for (int dir = 0; dir < 4; dir++) {
					if (network.neighbors[cell.index][dir] >= 0) {
						linked = true;
						break;
					}
				}
			}
			if (!linked) {
				VpsPipeline.DECOR_SEATS_ISOLATED.incrementAndGet();
			}
		}
	}

	private static int pinCell(RoadNetwork network, long column, int pinP) {
		int x = (int) (column >> 32);
		int z = (int) column;
		int index = network.cellAt(x, z);
		if (index < 0) {
			return 0;
		}
		RoadCell cell = network.cells.get(index);
		if (cell.plaza) {
			return 0;
		}
		cell.plazaPinned = true;
		cell.plazaPinP = pinP;
		return 1;
	}

	private static Resolved assemble(List<Piece> found, List<StructurePiece> pieces,
			Map<Long, String> baseOnly, Map<Long, Integer> fixtureBases,
			Map<Long, FixtureGround> fixtureGrounds) {
		Map<Long, String> baseBlocks = new HashMap<>(baseOnly);
		if (found.isEmpty()) {
			if (baseBlocks.isEmpty() && fixtureBases.isEmpty()) {
				return EMPTY;
			}
			return new Resolved(List.of(), Set.of(), Set.of(), Set.of(), baseBlocks,
					fixtureBases, baseOnly, fixtureGrounds, new int[0][]);
		}
		Set<Integer> indices = new HashSet<>();
		Set<Long> blocks = new HashSet<>();
		Set<Long> columns = new HashSet<>();
		Map<Long, int[]> ranges = new HashMap<>();
		for (Piece piece : found) {
			indices.add(piece.pieceIndex);
			blocks.addAll(piece.blocks);
			columns.addAll(piece.columns);
			baseBlocks.putAll(piece.baseBlocks);
			for (Map.Entry<Long, int[]> entry : piece.columnYRanges.entrySet()) {
				int[] range = ranges.get(entry.getKey());
				if (range == null) {
					ranges.put(entry.getKey(), new int[]{entry.getValue()[0], entry.getValue()[1]});
				} else {
					range[0] = Math.min(range[0], entry.getValue()[0]);
					range[1] = Math.max(range[1], entry.getValue()[1]);
				}
			}
		}
		int[][] boxes = new int[ranges.size()][];
		int n = 0;
		for (Map.Entry<Long, int[]> entry : ranges.entrySet()) {
			long column = entry.getKey();
			int x = (int) (column >> 32);
			int z = (int) column;
			boxes[n++] = new int[]{x, x, entry.getValue()[0], entry.getValue()[1], z, z};
		}
		return new Resolved(List.copyOf(found), indices, blocks, columns, baseBlocks,
				fixtureBases, baseOnly, fixtureGrounds, boxes);
	}

	/** Loads every template location that any collected decor pool can produce. */
	private static Set<String> decorTemplateLocations(RoadNetwork network, RegistryAccess registries) {
		Set<String> out = new HashSet<>();
		for (String poolId : network.decorPools) {
			StructureTemplatePool pool = VpsCompat.templatePool(registries, poolId);
			if (pool == null) {
				continue;
			}
			for (StructurePoolElement element : VpsCompat.poolTemplates(pool)) {
				if (!(element instanceof SinglePoolElement single)) {
					continue;
				}
				try {
					String location = ((SinglePoolElementVpsAccess) single).village_path_smoothed$getTemplateLocation();
					if (location != null) {
						out.add(location);
					}
				} catch (Throwable ignored) {
					// an accessor failure costs that pool entry, not the village
				}
			}
		}
		return out;
	}

	private static Set<Long> seatColumnsIn(BoundingBox box, Set<Long> seatColumns) {
		Set<Long> out = new HashSet<>();
		for (long column : seatColumns) {
			int x = (int) (column >> 32);
			int z = (int) column;
			if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
				out.add(column);
			}
		}
		return out;
	}

	private static boolean computeBlocks(Piece decor, PoolElementStructurePiece pool,
			StructureTemplateManager templateManager) {
		StructureTemplate template;
		try {
			template = VpsCompat.loadTemplate(templateManager, decor.templateLocation);
		} catch (Throwable t) {
			return false;
		}
		List<StructureTemplate.Palette> palettes =
				((StructureTemplateVpsAccess) template).village_path_smoothed$getPalettes();
		if (palettes == null || palettes.isEmpty()) {
			return false;
		}
		StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pool.getRotation());
		StructureTemplate.Palette palette = settings.getRandomPalette(palettes, pool.getPosition());
		BlockPos origin = pool.getPosition();
		for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
			//#if MC >= 12001
			//$$ BlockPos infoPos = info.pos();
			//$$ BlockState infoState = info.state();
			//#else
			BlockPos infoPos = info.pos;
			BlockState infoState = info.state;
			//#endif
			if (infoState.is(Blocks.JIGSAW)) {
				// The jigsaw itself is replaced by its final_state at placement.
				String finalState = nbtString(info, "final_state");
				if (isRendered(finalState)) {
					BlockPos world = StructureTemplate.calculateRelativePosition(settings, infoPos).offset(origin);
					addBlock(decor, world);
					String id = finalState.startsWith("minecraft:")
							? finalState.substring("minecraft:".length())
							: finalState;
					int bracket = id.indexOf('[');
					if (bracket >= 0) {
						id = id.substring(0, bracket);
					}
					if (!id.isEmpty()) {
						decor.baseBlocks.put(RoadCell.packedXYZ(world.getX(), world.getY(), world.getZ()), id);
					}
				}
				continue;
			}
			if (infoState.isAir() || infoState.is(Blocks.STRUCTURE_VOID)) {
				continue;
			}
			addBlock(decor, StructureTemplate.calculateRelativePosition(settings, infoPos).offset(origin));
		}
		decor.baseWalkY = pool.getBoundingBox().minY() + pool.getGroundLevelDelta();
		return true;
	}

	private static void addBlock(Piece decor, BlockPos worldPos) {
		long column = RoadCell.packedXZ(worldPos.getX(), worldPos.getZ());
		decor.columns.add(column);
		decor.blocks.add(RoadCell.packedXYZ(worldPos.getX(), worldPos.getY(), worldPos.getZ()));
		decor.columnBlockYs.computeIfAbsent(column, k -> new ArrayList<>()).add(worldPos.getY());
		int[] range = decor.columnYRanges.get(column);
		if (range == null) {
			decor.columnYRanges.put(column, new int[]{worldPos.getY(), worldPos.getY()});
		} else {
			range[0] = Math.min(range[0], worldPos.getY());
			range[1] = Math.max(range[1], worldPos.getY());
		}
	}

	private static boolean isRendered(String finalState) {
		if (finalState == null || finalState.isEmpty()) {
			return false;
		}
		String id = finalState.startsWith("minecraft:") ? finalState.substring("minecraft:".length()) : finalState;
		int bracket = id.indexOf('[');
		if (bracket >= 0) {
			id = id.substring(0, bracket);
		}
		return !id.equals("air") && !id.equals("cave_air") && !id.equals("void_air") && !id.equals("structure_void");
	}

	private static String nbtString(StructureTemplate.StructureBlockInfo info, String key) {
		CompoundTag nbt;
		//#if MC >= 12001
		//$$ nbt = info.nbt();
		//#else
		nbt = info.nbt;
		//#endif
		if (nbt == null) {
			return null;
		}
		//#if MC >= 12110
		//$$ return nbt.getString(key).orElse(null);
		//#else
		return nbt.getString(key);
		//#endif
	}
}
