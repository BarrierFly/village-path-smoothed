/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.FeaturePoolElementVpsAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.ArrayList;
import java.util.Arrays;
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

	/**
	 * A piece T1.5 is allowed to move vertically.
	 *
	 * <p>RIGID {@code SinglePoolElement} pieces are the buildings. RIGID
	 * {@code FeaturePoolElement} pieces are the village {@code decor} pool's
	 * placed features, and only the hay pile ({@code minecraft:pile_hay}) is
	 * taken: its origin is the road surface and it is 1x1x1, so it is a real
	 * road-side object that otherwise stays at the vanilla terrain height while
	 * the road around it moves (user decision 2026-10-14). The decorative trees
	 * and flowers are deliberately left alone - a moved tree would fight the
	 * vanilla terrain adaptation, which raises the ground it stands on.
	 */
	public static boolean isMovable(StructurePiece piece) {
		if (piece instanceof PoolElementStructurePiece pool
				&& pool.getElement() instanceof FeaturePoolElement feature) {
			return feature.getProjection() == StructureTemplatePool.Projection.RIGID && isHayPile(feature);
		}
		return isRigid(piece);
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
