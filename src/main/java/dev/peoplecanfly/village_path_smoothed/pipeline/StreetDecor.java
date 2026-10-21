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

		private Piece(int pieceIndex, String templateLocation, boolean multiBlock) {
			this.pieceIndex = pieceIndex;
			this.templateLocation = templateLocation;
			this.multiBlock = multiBlock;
		}
	}

	public static final class Resolved {
		public final List<Piece> pieces;
		public final Set<Integer> pieceIndices;
		public final Set<Long> blocks;
		public final Set<Long> columns;
		/** One tight box per actual block column: {minX,maxX,minY,maxY,minZ,maxZ}. */
		public final int[][] columnBoxes;

		private Resolved(List<Piece> pieces, Set<Integer> pieceIndices, Set<Long> blocks,
				Set<Long> columns, int[][] columnBoxes) {
			this.pieces = pieces;
			this.pieceIndices = pieceIndices;
			this.blocks = blocks;
			this.columns = columns;
			this.columnBoxes = columnBoxes;
		}
	}

	private static final Resolved EMPTY = new Resolved(List.of(), Set.of(), Set.of(), Set.of(), new int[0][]);

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
			if (seats.isEmpty()) {
				continue; // the same template used outside a seat connection is not decor
			}
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
			found.add(decor);
		}
		return assemble(found, pieces);
	}

	/** Recomputes the blocks of an already identified decor set at the pieces' current positions. */
	public static Resolved refresh(Resolved previous, List<StructurePiece> pieces,
			StructureTemplateManager templateManager) {
		if (previous.pieces.isEmpty()) {
			return previous;
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
		return assemble(refreshed, pieces);
	}

	/**
	 * T5 flat-base replacement for the old actual-block pin: every road cell in
	 * the decor's XZ bbox (seat, planks/stairs and the air columns over road) is
	 * pinned to the same even walk level. The full footprint is what makes a
	 * 3x3 lamp or a 3x6 platform read as one flat base instead of a pin island.
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
			for (int x = piece.minX; x <= piece.maxX; x++) {
				for (int z = piece.minZ; z <= piece.maxZ; z++) {
					long column = RoadCell.packedXZ(x, z);
					if (seen.add(column)) {
						pinned += pinCell(network, column, pinP);
					}
				}
			}
		}
		return pinned;
	}

	/**
	 * T1.5 replacement for the immovable pin: the FULL base footprint of each
	 * multi-block decor becomes one MOVABLE flat unit in the final solve. The
	 * polish flattens it to one even level and may move it as a whole, exactly
	 * like a small plaza; the rigid decor is moved to that level afterwards by
	 * {@link #settleBases}. Air columns inside the bbox are included because
	 * they are part of the base the player sees.
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
			for (int x = piece.minX; x <= piece.maxX; x++) {
				for (int z = piece.minZ; z <= piece.maxZ; z++) {
					long column = RoadCell.packedXZ(x, z);
					if (used.contains(column)) {
						continue;
					}
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
	 * Counts the remaining fixture-to-road mismatches after the solve/settle.
	 * The nearest road/seat cell OUTSIDE the decor's own base footprint is the
	 * connection the player sees; a difference of more than half a block is
	 * counted even when the two cells are diagonal (the solver's edge walk only
	 * sees 4-adjacency, which is exactly the blind spot this counter covers).
	 */
	public static void recordRoadMismatch(Resolved resolved, RoadNetwork network) {
		for (Piece piece : resolved.pieces) {
			java.util.Set<Long> own = new HashSet<>();
			own.addAll(piece.seatColumns);
			own.addAll(piece.columns);
			for (int x = piece.minX; x <= piece.maxX; x++) {
				for (int z = piece.minZ; z <= piece.maxZ; z++) {
					own.add(RoadCell.packedXZ(x, z));
				}
			}
			RoadCell nearest = null;
			int bestDistance = Integer.MAX_VALUE;
			for (long column : piece.seatColumns) {
				int seatX = (int) (column >> 32);
				int seatZ = (int) column;
				for (int radius = 1; radius <= 3 && nearest == null; radius++) {
					for (int dx = -radius; dx <= radius; dx++) {
						for (int dz = -radius; dz <= radius; dz++) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
								continue;
							}
							int x = seatX + dx;
							int z = seatZ + dz;
							if (own.contains(RoadCell.packedXZ(x, z))) {
								continue;
							}
							int index = network.cellAt(x, z);
							if (index < 0) {
								continue;
							}
							int distance = Math.abs(dx) + Math.abs(dz);
							if (distance < bestDistance) {
								bestDistance = distance;
								nearest = network.cells.get(index);
							}
						}
					}
				}
				if (nearest != null) {
					break;
				}
			}
			if (nearest == null) {
				VpsPipeline.DECOR_ROAD_ISOLATED_PIECES.incrementAndGet();
				continue;
			}
			int diff = Math.abs(nearest.solvedP - 2 * piece.baseWalkY);
			if (diff > 1) {
				VpsPipeline.DECOR_ROAD_MISMATCH_PIECES.incrementAndGet();
				VpsPipeline.DECOR_ROAD_MISMATCH_WORST.accumulateAndGet(diff, Math::max);
			}
		}
	}

	/** Seat cells with no 4-adjacent mask cell at all: the solvable-network blind spot. */
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

	private static Resolved assemble(List<Piece> found, List<StructurePiece> pieces) {
		if (found.isEmpty()) {
			return EMPTY;
		}
		Set<Integer> indices = new HashSet<>();
		Set<Long> blocks = new HashSet<>();
		Set<Long> columns = new HashSet<>();
		Map<Long, int[]> ranges = new HashMap<>();
		for (Piece piece : found) {
			indices.add(piece.pieceIndex);
			blocks.addAll(piece.blocks);
			columns.addAll(piece.columns);
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
		return new Resolved(List.copyOf(found), indices, blocks, columns, boxes);
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
					addBlock(decor, StructureTemplate.calculateRelativePosition(settings, infoPos).offset(origin));
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
