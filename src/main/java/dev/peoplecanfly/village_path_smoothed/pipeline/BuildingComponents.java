/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.FeaturePoolElementVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.SinglePoolElementVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.StructureTemplateVpsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
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
		/** Median of the component pieces' bounding-box minY; the base the road profile aligns to. */
		public int baseY;
		/** Chosen vertical shift (whole blocks). */
		public int dy;
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
	 * base. A standalone hay pile stays where vanilla put it and keeps its
	 * protected box ({@link #protectedBoxes}), so the road still yields to it.
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

	/** The village decor hay pile; anything else in the decor pool is left where vanilla put it. */
	public static boolean isHayPile(FeaturePoolElement element) {
		try {
			Holder<?> holder = ((FeaturePoolElementVpsAccess) element).village_path_smoothed$getFeature();
			return holder != null && holder.unwrapKey()
					.map(VpsCompat::registryKeyId)
					.map("minecraft:pile_hay"::equals)
					.orElse(false);
		} catch (Throwable t) {
			return false; // an accessor failure must cost the hay pile, not the village
		}
	}

	/**
	 * The hay pile feature piece. It is NOT movable (user decision 2026-10-16),
	 * but it still wins its column: {@link #protectedBoxes} keeps its box so the
	 * writer's plan-A skip keeps the road off the pile.
	 */
	public static boolean isHayPile(StructurePiece piece) {
		return piece instanceof PoolElementStructurePiece pool
				&& pool.getElement() instanceof FeaturePoolElement feature
				&& isHayPile(feature);
	}

	/**
	 * 3D bounding boxes {minX, maxX, minY, maxY, minZ, maxZ} of every PROTECTED
	 * piece: the movable buildings plus the immovable hay piles (user decision
	 * 2026-10-16 - the pile itself no longer moves, but it still wins its
	 * column). The writer refuses to write a road column whose road block would
	 * land inside one of these: the piece always wins (plan A, user decision
	 * 2026-10-15). The boxes are read live off the pieces, so on the T1.5 path
	 * they already reflect any vertical shift (a hay pile on a seat rides the
	 * seat via {@code T15Planner#settleSeats}).
	 */
	public static int[][] protectedBoxes(List<StructurePiece> pieces) {
		List<int[]> out = new ArrayList<>();
		for (StructurePiece piece : pieces) {
			if (isMovable(piece) || isHayPile(piece)) {
				BoundingBox box = piece.getBoundingBox();
				out.add(new int[]{box.minX(), box.maxX(), box.minY(), box.maxY(), box.minZ(), box.maxZ()});
			}
		}
		return out.toArray(new int[0][]);
	}

	private static final int[][] NO_DOORS = new int[0][];

	/**
	 * Lower door halves {x, z} per movable piece index, read from the piece's
	 * template (F-C, door two-symptom recheck 2026-10-15): the jigsaw connection
	 * sits 2-7 blocks from the real door on 53 of the 120 vanilla house
	 * templates, so the junction is not a door anchor. The template cell is
	 * rotated with the piece the same way RoadCollector rotates road cells
	 * (pivot ZERO, no mirror - exactly {@code SinglePoolElement#getSettings});
	 * only the XZ position is needed. Pieces without a template, without doors
	 * or with a failing load map to an empty array - callers fall back to the
	 * junctions (farms and pens carry no doors either).
	 */
	public static Map<Integer, int[][]> templateDoors(List<StructurePiece> pieces, StructureTemplateManager templateManager) {
		Map<Integer, int[][]> out = new HashMap<>();
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
				out.put(i, NO_DOORS); // runtime pool element without a location
				continue;
			}
			try {
				StructureTemplate template = VpsCompat.loadTemplate(templateManager, location);
				List<StructureTemplate.Palette> palettes = ((StructureTemplateVpsAccess) template).village_path_smoothed$getPalettes();
				if (palettes == null || palettes.isEmpty()) {
					out.put(i, NO_DOORS);
					continue;
				}
				StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(pool.getRotation());
				StructureTemplate.Palette palette = settings.getRandomPalette(palettes, pool.getPosition());
				List<int[]> doors = new ArrayList<>();
				for (StructureTemplate.StructureBlockInfo info : palette.blocks()) {
					//#if MC >= 12001
					//$$ BlockState state = info.state();
					//$$ BlockPos infoPos = info.pos();
					//#else
					BlockState state = info.state;
					BlockPos infoPos = info.pos;
					//#endif
					if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
							|| state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
						continue; // upper half or not a door at all
					}
					BlockPos worldPos = StructureTemplate.calculateRelativePosition(settings, infoPos).offset(pool.getPosition());
					doors.add(new int[]{worldPos.getX(), worldPos.getZ()});
				}
				out.put(i, doors.toArray(new int[0][]));
			} catch (Throwable t) {
				// an unloadable template must cost the door anchors, not the village
				out.put(i, NO_DOORS);
			}
		}
		return out;
	}

	/**
	 * Door-front cells {frontX, frontZ, outwardDX, outwardDZ} of every movable
	 * piece. Since F-C (door two-symptom recheck 2026-10-15) the anchor is the
	 * piece's template door block: one front cell per lower door half, one step
	 * outside the wall along the dominant axis from the footprint centre to the
	 * door. Template door facings are deliberately NOT used - measured on the
	 * 120 vanilla house templates, only 30% of the unambiguous door leaves face
	 * outward, 56% face inward.
	 *
	 * <p>A piece without template doors (a farm or a pen, or a template that
	 * failed to load) falls back to its non-RIGID junctions - each connection to
	 * a street/terminator/centre piece rather than to another building - with
	 * the same footprint-centre axis rule. Every door of the template gets a
	 * front, including the ones facing RIGID attachments or plazas (R7 fix).
	 *
	 * <p>The writer suppresses the railing on the door front (within one block)
	 * so a doorway can never be fenced in (user decision 2026-10-15; the 3x3
	 * box shape is kept per the R8 decision of 2026-10-15).
	 */
	public static int[][] doorFronts(List<StructurePiece> pieces, Map<Integer, int[][]> doorsByPiece) {
		List<int[]> out = new ArrayList<>();
		for (int i = 0; i < pieces.size(); i++) {
			StructurePiece piece = pieces.get(i);
			if (!isMovable(piece) || !(piece instanceof PoolElementStructurePiece pool)) {
				continue;
			}
			BoundingBox box = piece.getBoundingBox();
			int cx = (box.minX() + box.maxX()) / 2;
			int cz = (box.minZ() + box.maxZ()) / 2;
			int[][] pieceDoors = doorsByPiece != null ? doorsByPiece.get(i) : null;
			if (pieceDoors != null && pieceDoors.length > 0) {
				for (int[] door : pieceDoors) {
					addDoorFront(out, door[0], door[1], cx, cz);
				}
			} else {
				for (JigsawJunction junction : pool.getJunctions()) {
					if (junction.getDestProjection() == StructureTemplatePool.Projection.RIGID) {
						continue; // attaches to another building, not a door
					}
					addDoorFront(out, junction.getSourceX(), junction.getSourceZ(), cx, cz);
				}
			}
		}
		return out.toArray(new int[0][]);
	}

	/** One front cell one step outside the wall along the dominant axis from the footprint centre to the anchor. */
	private static void addDoorFront(List<int[]> out, int ax, int az, int cx, int cz) {
		int dx = Integer.compare(ax, cx);
		int dz = Integer.compare(az, cz);
		if (Math.abs(ax - cx) < Math.abs(az - cz)) {
			dx = 0; // z is the dominant axis
		} else {
			dz = 0; // x is the dominant axis (ties pick x)
		}
		if (dx == 0 && dz == 0) {
			return; // anchor at the footprint centre: no direction to derive
		}
		out.add(new int[]{ax + dx, az + dz, dx, dz});
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
			int[] baseYs = new int[component.pieceIndices.size()];
			for (int k = 0; k < baseYs.length; k++) {
				baseYs[k] = pieces.get(component.pieceIndices.get(k)).getBoundingBox().minY();
			}
			Arrays.sort(baseYs);
			component.baseY = baseYs[baseYs.length / 2];
		}
		return new ArrayList<>(byRoot.values());
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
