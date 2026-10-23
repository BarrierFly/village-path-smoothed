/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.SinglePoolElementVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.StructureTemplateVpsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups the RIGID pieces of a jigsaw structure into rigid connected
 * components (plan section 3.10, D5=a). Two RIGID pieces belong to the same
 * component when they are joined by a RIGID-to-RIGID jigsaw junction: the
 * parent's junction records {@code destProjection == RIGID}, the child's too,
 * their {@code deltaY} values are opposite and their source positions are one
 * block apart. A whole component must shift by one dy or its parts detach.
 */
public final class BuildingComponents {
	public static final class Component {
		public final List<Integer> pieceIndices = new ArrayList<>();
		public int minX = Integer.MAX_VALUE;
		public int maxX = Integer.MIN_VALUE;
		public int minZ = Integer.MAX_VALUE;
		public int maxZ = Integer.MIN_VALUE;
		/**
		 * Vanilla base walk level of the MAIN building piece:
		 * {@code bbox.minY + getGroundLevelDelta()} (door/basis fix plan
		 * 2026-10-17, D1). This is exactly the level vanilla's terrain
		 * adaptation aligns the piece to; the old "median minY + 1"
		 * approximation is gone.
		 */
		public int baseWalkY;
		/** Index (into the piece list) of the main building piece, or -1 for an all-1x1 component. */
		public int basePieceIndex = -1;
		/** Cumulative vertical shift (whole blocks) from the vanilla position. */
		public int dy;
		/** Part of {@link #dy} already propagated to the jigsaw junctions. */
		public int syncedDy;
	}

	private BuildingComponents() {
	}

	/** Matches the {@code town_centers} template folder (fix plan v4 D5-c). */
	private static final java.util.regex.Pattern TOWN_CENTERS = java.util.regex.Pattern.compile("/town_centers/");

	/**
	 * A piece T1.5 is allowed to move vertically: a RIGID {@code SinglePoolElement}
	 * building, and nothing else (user decision 2026-10-16, report §3.3-2).
	 *
	 * <p>The hay pile ({@code minecraft:pile_hay}) is deliberately NOT moved any
	 * more: moving it was an unrequested 5c82bf5 behaviour ("非预期，干草堆不应搬动").
	 * The only hay that still moves is one standing ON a mask seat, and only
	 * because it rides the seat's own solved level ({@code T15Planner#settleSeats})
	 * - the 2026-10-16 seat decision keeps the decor on a seat following the
	 * base. A standalone hay pile stays where vanilla put it; the pile's really
	 * placed blocks are recorded at placement and protected at write time
	 * (audit 2026-10-20), so the road still yields to it block by block.
	 * The decorative trees and flowers are still left alone - a moved tree would
	 * fight the vanilla terrain adaptation, which raises the ground it stands on.
	 *
	 * <p>Plan C (user decision 2026-10-15): a {@code town_centers} piece is NOT
	 * a movable building any more. The plaza is solved flat at a pinned level
	 * and its pieces are moved by the dedicated plaza-group shift (which keeps
	 * the well rim and the fountain together as one rigid body); treating it as
	 * a building made the writer skip every plaza column ("road cells yielded")
	 * and the mask claimed cells it could never write.
	 */
	public static boolean isMovable(StructurePiece piece) {
		if (piece instanceof PoolElementStructurePiece pool) {
			if (pool.getElement() instanceof SinglePoolElement element && isTownCentre(element)) {
				return false;
			}
		}
		return isRigid(piece);
	}

	/** True when the element's template lives in the {@code town_centers} pool folder. */
	public static boolean isTownCentre(SinglePoolElement element) {
		try {
			String location = ((SinglePoolElementVpsAccess) element).village_path_smoothed$getTemplateLocation();
			return location != null && TOWN_CENTERS.matcher(location).find();
		} catch (Throwable t) {
			return false; // an accessor failure must cost the building status, not the village
		}
	}

	/**
	 * 3D bounding boxes {minX, maxX, minY, maxY, minZ, maxZ} of every movable
	 * building that is NOT an identified decor piece. The writer refuses to
	 * write a road column whose road block would land inside one of these: the
	 * building always wins (plan A, user decision 2026-10-15).
	 *
	 * <p>Audit 2026-10-20 decisions 1/4: decor pieces are excluded here (their
	 * actual blocks are protected individually by {@link StreetDecor}, so the
	 * bbox no longer creates holes), and the old hay-pile 1x1 box is gone - a
	 * pile's really placed blocks are recorded by the placement hook and
	 * protected by position. {@code excludedDecorPieces} are piece indices from
	 * {@link StreetDecor.Resolved#protectedPieceIndices} (seat decor, fixture
	 * lamps and recognised-but-unresolved decor). Only a piece that could not be
	 * recognised as decor stays in this list and keeps the bbox fallback.
	 */
	public static int[][] protectedBoxes(List<StructurePiece> pieces, Set<Integer> excludedDecorPieces) {
		List<int[]> boxes = new ArrayList<>();
		List<Integer> owners = new ArrayList<>();
		for (int i = 0; i < pieces.size(); i++) {
			StructurePiece piece = pieces.get(i);
			if (!isMovable(piece)) {
				continue;
			}
			BoundingBox box = piece.getBoundingBox();
			boxes.add(new int[]{box.minX(), box.maxX(), box.minY(), box.maxY(), box.minZ(), box.maxZ()});
			owners.add(i);
		}
		int[] ownerArray = new int[owners.size()];
		for (int i = 0; i < ownerArray.length; i++) {
			ownerArray[i] = owners.get(i);
		}
		return RoadWritePolicy.selectPlanABoxes(boxes.toArray(new int[0][]), ownerArray, excludedDecorPieces);
	}

	/** No identified decoration: the historical building-only boxes. */
	public static int[][] protectedBoxes(List<StructurePiece> pieces) {
		return protectedBoxes(pieces, Set.of());
	}

	private static final int[][] NONE = new int[0][];

	/**
	 * The template scan of every movable piece (door/basis fix plan 2026-10-17,
	 * section 3.2): the REAL lower door halves and the {@code building_entrance}
	 * jigsaw positions plus their outward front directions (already rotated by
	 * the piece's rotation).
	 *
	 * <p>The old scan accepted every lower double-block half, so tall grass and
	 * large ferns were treated as doors. Only blocks in {@link BlockTags#DOORS}
	 * count now; the skipped lower halves are counted
	 * ({@code NON_DOOR_LOWER_HALF_SKIPPED}).
	 */
	public static final class TemplateScan {
		/** Piece index -> {x, z, floorY} real lower door halves. */
		public final Map<Integer, int[][]> doors = new HashMap<>();
		/** Piece index -> {x, z, frontDX, frontDZ} {@code building_entrance} jigsaws. */
		public final Map<Integer, int[][]> entrances = new HashMap<>();
	}

	/**
	 * Reads the real doors and {@code building_entrance} jigsaws of every
	 * movable piece. The template cell is rotated with the piece the same way
	 * RoadCollector rotates road cells (pivot ZERO, no mirror - exactly
	 * {@code SinglePoolElement#getSettings}). Pieces without a template, without
	 * doors or with a failing load map to empty arrays - callers fall back to
	 * the junctions (farms and pens carry no doors either).
	 */
	public static TemplateScan scanTemplates(List<StructurePiece> pieces, StructureTemplateManager templateManager) {
		TemplateScan out = new TemplateScan();
		if (templateManager == null) {
			return out;
		}
		for (int i = 0; i < pieces.size(); i++) {
			StructurePiece piece = pieces.get(i);
			if (!isMovable(piece) || !(piece instanceof PoolElementStructurePiece pool)
					|| !(pool.getElement() instanceof SinglePoolElement element)) {
				continue;
			}
			String location = ((SinglePoolElementVpsAccess) element).village_path_smoothed$getTemplateLocation();
			if (location == null) {
				out.doors.put(i, NONE); // runtime pool element without a location
				continue;
			}
			try {
				StructureTemplate template = VpsCompat.loadTemplate(templateManager, location);
				List<StructureTemplate.Palette> palettes = ((StructureTemplateVpsAccess) template).village_path_smoothed$getPalettes();
				if (palettes == null || palettes.isEmpty()) {
					out.doors.put(i, NONE);
					continue;
				}
				StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pool.getRotation());
				StructureTemplate.Palette palette = settings.getRandomPalette(palettes, pool.getPosition());
				List<int[]> doors = new ArrayList<>();
				List<int[]> entrances = new ArrayList<>();
				for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
					//#if MC >= 12001
					//$$ BlockState state = info.state();
					//$$ BlockPos infoPos = info.pos();
					//#else
					BlockState state = info.state;
					BlockPos infoPos = info.pos;
					//#endif
					boolean lowerHalf = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
							&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
					if (lowerHalf && !state.is(BlockTags.DOORS)) {
						// tall grass / large fern and other double plants
						VpsPipeline.NON_DOOR_LOWER_HALF_SKIPPED.incrementAndGet();
						continue;
					}
					if (lowerHalf) {
						BlockPos worldPos = StructureTemplate.calculateRelativePosition(settings, infoPos).offset(pool.getPosition());
						// {x, z, floorY}: the lower door half's world Y is the actual
						// door floor (R4-2, 2026-10-16) - the sign prints it after the
						// component shift so assumption and reality can be compared
						doors.add(new int[]{worldPos.getX(), worldPos.getZ(), worldPos.getY()});
						continue;
					}
					if (!state.is(net.minecraft.world.level.block.Blocks.JIGSAW)) {
						continue;
					}
					String name = jigsawName(info);
					if (!"minecraft:building_entrance".equals(name)) {
						continue;
					}
					BlockPos worldPos = StructureTemplate.calculateRelativePosition(settings, infoPos).offset(pool.getPosition());
					Direction front = pool.getRotation().rotate(JigsawBlock.getFrontFacing(state));
					entrances.add(new int[]{worldPos.getX(), worldPos.getZ(), front.getStepX(), front.getStepZ()});
				}
				out.doors.put(i, doors.toArray(new int[0][]));
				if (!entrances.isEmpty()) {
					out.entrances.put(i, entrances.toArray(new int[0][]));
				}
			} catch (Throwable t) {
				// an unloadable template must cost the door anchors, not the village
				out.doors.put(i, NONE);
			}
		}
		return out;
	}

	/** The jigsaw's {@code name} NBT, version-correct ({@code Optional<String>} from 1.21.10). */
	private static String jigsawName(StructureTemplate.StructureBlockInfo info) {
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
		//$$ return nbt.getString("name").orElse(null);
		//#else
		return nbt.getString("name");
		//#endif
	}

	/**
	 * The outward step {dx, dz} from the footprint centre to an anchor: one
	 * step along the dominant axis, ties picking x. Shared by the door fronts
	 * and the debug door signs (user request 2026-10-16) so both describe the
	 * same direction. Null when the anchor sits at the footprint centre.
	 */
	public static int[] outwardAxis(int ax, int az, int cx, int cz) {
		int dx = Integer.compare(ax, cx);
		int dz = Integer.compare(az, cz);
		if (Math.abs(ax - cx) < Math.abs(az - cz)) {
			dx = 0; // z is the dominant axis
		} else {
			dz = 0; // x is the dominant axis (ties pick x)
		}
		return dx == 0 && dz == 0 ? null : new int[]{dx, dz};
	}

	public static List<Component> group(List<StructurePiece> pieces) {
		int n = pieces.size();
		boolean[] rigid = new boolean[n];
		for (int i = 0; i < n; i++) {
			rigid[i] = isMovable(pieces.get(i));
		}
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) {
			parent[i] = i;
		}
		for (int i = 0; i < n; i++) {
			if (!rigid[i]) {
				continue;
			}
			List<JigsawJunction> ji = junctions(pieces.get(i));
			if (ji.isEmpty()) {
				continue;
			}
			for (int j = i + 1; j < n; j++) {
				if (!rigid[j]) {
					continue;
				}
				if (pairs(ji, junctions(pieces.get(j)))) {
					union(parent, i, j);
				}
			}
		}

		Map<Integer, Component> byRoot = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			if (!rigid[i]) {
				continue;
			}
			int root = find(parent, i);
			Component component = byRoot.computeIfAbsent(root, k -> new Component());
			component.pieceIndices.add(i);
			BoundingBox box = pieces.get(i).getBoundingBox();
			component.minX = Math.min(component.minX, box.minX());
			component.maxX = Math.max(component.maxX, box.maxX());
			component.minZ = Math.min(component.minZ, box.minZ());
			component.maxZ = Math.max(component.maxZ, box.maxZ());
		}
		for (Component component : byRoot.values()) {
			int size = component.pieceIndices.size();
			// Door/basis fix plan 2026-10-17 D1: the base is the MAIN building
			// piece - the largest footprint (then the larger Y span, then the
			// lower packedXZ) - and its vanilla walk level
			// minY + groundLevelDelta. That is exactly what JigsawPlacement and
			// Beardifier call the ground level; the old median minY + 1 was only
			// an approximation and a 1x1 decor could still skew it.
			int bestPiece = -1;
			long bestArea = -1;
			int bestYSpan = -1;
			long bestPacked = Long.MAX_VALUE;
			int[] walkLevels = new int[size];
			boolean[] nonDecor = new boolean[size];
			for (int k = 0; k < size; k++) {
				int pieceIndex = component.pieceIndices.get(k);
				StructurePiece piece = pieces.get(pieceIndex);
				BoundingBox box = piece.getBoundingBox();
				walkLevels[k] = box.minY() + groundLevelDelta(piece);
				boolean oneByOne = box.minX() == box.maxX() && box.minZ() == box.maxZ();
				nonDecor[k] = !oneByOne;
				if (oneByOne) {
					continue;
				}
				long area = (long) (box.maxX() - box.minX() + 1) * (box.maxZ() - box.minZ() + 1);
				int ySpan = box.maxY() - box.minY() + 1;
				long packed = RoadCell.packedXZ(box.minX(), box.minZ());
				if (bestPiece < 0 || area > bestArea
						|| (area == bestArea && ySpan > bestYSpan)
						|| (area == bestArea && ySpan == bestYSpan && packed < bestPacked)) {
					bestPiece = pieceIndex;
					bestArea = area;
					bestYSpan = ySpan;
					bestPacked = packed;
				}
			}
			if (bestPiece >= 0) {
				component.basePieceIndex = bestPiece;
				component.baseWalkY = pieces.get(bestPiece).getBoundingBox().minY() + groundLevelDelta(pieces.get(bestPiece));
				boolean mismatch = false;
				for (int k = 0; k < size; k++) {
					if (nonDecor[k] && walkLevels[k] != component.baseWalkY) {
						mismatch = true;
						break;
					}
				}
				if (mismatch) {
					VpsPipeline.BASE_PLANE_MISMATCH.incrementAndGet();
					long seen = VpsPipeline.BASE_PLANE_MISMATCH.get();
					if (seen % 100 == 1) {
						dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed.LOGGER.warn(
								"[VPS] building component pieces disagree on minY + groundLevelDelta; using the main piece {} (walk Y {})",
								bestPiece, component.baseWalkY);
					}
				}
			} else {
				// all-1x1 component (vanilla has no such building): median fallback
				int[] sorted = Arrays.copyOf(walkLevels, size);
				Arrays.sort(sorted);
				component.baseWalkY = sorted[size / 2];
			}
		}
		return new ArrayList<>(byRoot.values());
	}

	/** The piece's vanilla ground-level delta, or 1 for a non-pool piece. */
	private static int groundLevelDelta(StructurePiece piece) {
		if (piece instanceof PoolElementStructurePiece pool) {
			return pool.getGroundLevelDelta();
		}
		return 1;
	}

	private static boolean isRigid(StructurePiece piece) {
		return piece instanceof PoolElementStructurePiece pool
				&& pool.getElement() instanceof SinglePoolElement element
				&& element.getProjection() == StructureTemplatePool.Projection.RIGID;
	}

	private static List<JigsawJunction> junctions(StructurePiece piece) {
		if (piece instanceof PoolElementStructurePiece pool) {
			return pool.getJunctions();
		}
		return List.of();
	}

	private static boolean pairs(List<JigsawJunction> a, List<JigsawJunction> b) {
		for (JigsawJunction ja : a) {
			if (ja.getDestProjection() != StructureTemplatePool.Projection.RIGID) {
				continue;
			}
			for (JigsawJunction jb : b) {
				if (jb.getDestProjection() != StructureTemplatePool.Projection.RIGID) {
					continue;
				}
				if (ja.getDeltaY() == -jb.getDeltaY()
						&& Math.abs(ja.getSourceX() - jb.getSourceX()) <= 1
						&& Math.abs(ja.getSourceZ() - jb.getSourceZ()) <= 1) {
					return true;
				}
			}
		}
		return false;
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b) {
		int ra = find(parent, a);
		int rb = find(parent, b);
		if (ra != rb) {
			parent[ra] = rb;
		}
	}
}
