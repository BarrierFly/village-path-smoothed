/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The road mask rebuilt from street templates, decomposed into
 * arms (thin strips solved by exact slice DP) and regions (wide areas solved
 * by iterative coordinate minimization). Pure data, no Minecraft imports.
 *
 * Decomposition rule (plan section 5.1.4): a cell belongs to a region core when
 * BOTH its horizontal run and its vertical run are longer than
 * {@code maxExactWidth}; everything else forms arm clusters, which are sliced
 * along their dominant axis. A cluster that is too wide to slice in both axes
 * is solved as a region too.
 */
public final class RoadNetwork {
	public static final int DIR_WEST = 0;
	public static final int DIR_EAST = 1;
	public static final int DIR_NORTH = 2;
	public static final int DIR_SOUTH = 3;
	private static final int[] DIR_DX = {-1, 1, 0, 0};
	private static final int[] DIR_DZ = {0, 0, -1, 1};

	public final List<RoadCell> cells = new ArrayList<>();
	/** 4-neighbour cell indices per cell; missing neighbours are -1. */
	public final int[][] neighbors;
	public final List<Arm> arms = new ArrayList<>();
	/** Region cell index arrays. */
	public final List<int[]> regions = new ArrayList<>();
	/**
	 * 4-connected groups of plaza cells (plan C, 2026-10-15): each group is
	 * solved flat at ONE pinned level and its owning rigid piece(s) are moved to
	 * render exactly that level. Built by {@link #build} from the cells'
	 * {@code plaza} flags.
	 */
	public final List<int[]> plazaGroups = new ArrayList<>();
	/**
	 * The FULL base footprint of one multi-block street decor piece: every road
	 * cell inside the decor's XZ bounding box, including air columns (audit
	 * 2026-10-21). The seats and planks/stair columns are a subset; the air
	 * columns are what the old actual-block pin missed, so a 3x3 lamp or 3x6
	 * platform could still slope through its own base.
	 *
	 * <p>On T1.5 the group is a MOVABLE flat unit: the polish flattens it to one
	 * EVEN level and may move it as a whole, then the rigid decor piece is moved
	 * to that level. That is the user's "fixed shape, vertically movable,
	 * even-surface local structure". On T5 no piece may move, so the same
	 * footprint is pinned flat instead ({@code StreetDecor#pinBaseFootprints}).
	 */
	public final List<DecorBaseGroup> decorBaseGroups = new ArrayList<>();
	/** True on the T1.5 final solve: decor base groups may move as whole flat bodies. */
	public boolean decorBaseGroupsMovable;

	/**
	 * 2026-10-22: one flat, vertically movable unit per source structure whose
	 * ground columns overlap road cells. The unit contains only the ACTUAL
	 * overlap cells of that structure; the structure ground is rendered at the
	 * solved level and the surrounding road solves against the unit.
	 */
	public final List<GroundOverlapGroup> groundOverlapGroups = new ArrayList<>();

	public static final class GroundOverlapGroup {
		public final long key;
		public final int[] cells;
		public final boolean movable;

		public GroundOverlapGroup(long key, int[] cells, boolean movable) {
			this.key = key;
			this.cells = cells;
			this.movable = movable;
		}
	}

	/**
	 * A street-template y=0 column that is deliberately not road (farmland,
	 * water, terracotta, ...). When another piece's road cell claims the same
	 * column, the scenery ground is the surface the player must see and the
	 * overlap cell is rewritten at the solved height.
	 */
	public static final class SceneryGround {
		public final int pieceIndex;
		public final String blockId;
		/** World Y the template puts the y0 block at before gravity (fallback only). */
		public final int templateGroundY;
		/** Template block offsets above the ground block (0 = the ground). */
		public final int[] aboveLocalYs;

		public SceneryGround(int pieceIndex, String blockId, int templateGroundY, int[] aboveLocalYs) {
			this.pieceIndex = pieceIndex;
			this.blockId = blockId;
			this.templateGroundY = templateGroundY;
			this.aboveLocalYs = aboveLocalYs;
		}
	}

	/** Scenery columns by packed XZ; the collector marks overlap cells from it. */
	public final Map<Long, SceneryGround> streetSceneryGrounds = new HashMap<>();

	/** One decor piece's full base footprint (cell indices into {@link #cells}). */
	public static final class DecorBaseGroup {
		public final int pieceIndex;
		public final int[] cells;

		public DecorBaseGroup(int pieceIndex, int[] cells) {
			this.pieceIndex = pieceIndex;
			this.cells = cells;
		}
	}

	/**
	 * World XZ columns ({@link RoadCell#packedXZ}) of every y=0 block of a
	 * {@code town_centers} piece - the plaza piece's own footprint, including the
	 * well/fountain water and jigsaw cells that are not road cells. The writer
	 * never dresses a side toward one of these columns: a kerb there would wrap
	 * the plaza's own well/fountain mouth and seal it (2026-10-17).
	 */
	public final java.util.Set<Long> plazaFootprint = new java.util.HashSet<>();
	/**
	 * Target pool ids ({@code minecraft:village/plains/decor}, ...) of every
	 * up-facing y=0 street jigsaw collected by {@link RoadCollector}. The decor
	 * footprint resolver loads these pools to recognise the child pieces that
	 * actually stand on a seat.
	 */
	public final java.util.Set<String> decorPools = new java.util.HashSet<>();
	/**
	 * Every up-facing y=0 street jigsaw column ({@link RoadCell#packedXZ}),
	 * including the {@code structure_void} seats that do not join the mask. The
	 * decor resolver uses them to find the child piece of a seat even when the
	 * seat itself has no road cell (the snowy square overlap case).
	 */
	public final java.util.Set<Long> allSeatColumns = new java.util.HashSet<>();
	/**
	 * y=0 street columns that are deliberately NOT road: farmland, water and
	 * terracotta scenery inside a street template, plus the {@code structure_void}
	 * seat columns. They keep their vanilla block and the writer never dresses a
	 * road side toward them (no kerb, no railing).
	 */
	public final java.util.Set<Long> streetScenery = new java.util.HashSet<>();
	/**
	 * Cells that belong to a region core. The region solver owns the seed of
	 * exactly these cells; arm cells - including arms that fall back to the
	 * region solver because their band blew the state budget - keep the phase-1
	 * arm solution, and the ICM re-optimises them from boundary references like
	 * any other cell.
	 */
	public final BitSet regionCells = new BitSet();
	/**
	 * True only on the T1.5 phase-1 solve: the plaza groups have not been placed
	 * yet, so the polish may move a whole group up/down by whole blocks to meet
	 * its roads. The T1.5 shift then renders the group at the adjusted pin, and
	 * the T5 / phase-2 solves leave it false because those pieces are already the
	 * rigid truth.
	 */
	public boolean plazaPinsMovable;

	private final Map<Long, Integer> cellIndexLookup;

	private RoadNetwork(Map<Long, Integer> cellIndexLookup, int[][] neighbors) {
		this.cellIndexLookup = cellIndexLookup;
		this.neighbors = neighbors;
	}

	public int cellAt(int x, int z) {
		return cellIndexLookup.getOrDefault(RoadCell.packedXZ(x, z), -1);
	}

	/**
	 * Distinct 4-neighbour cell indices of {@code ownColumns} that are not in
	 * {@code ownColumns}. This is the fixture-connection primitive: two
	 * structures are connected only through solve cells that share a horizontal
	 * side, never through proximity or a diagonal corner. The caller passes its
	 * own footprint columns, so internal base cells never count as connections.
	 */
	public int[] externalNeighbours(Set<Long> ownColumns) {
		Set<Integer> out = new HashSet<>();
		for (long column : ownColumns) {
			int x = (int) (column >> 32);
			int z = (int) column;
			for (int dir = 0; dir < 4; dir++) {
				int nextX = x + DIR_DX[dir];
				int nextZ = z + DIR_DZ[dir];
				int index = cellAt(nextX, nextZ);
				if (index < 0 || ownColumns.contains(RoadCell.packedXZ(nextX, nextZ))) {
					continue;
				}
				out.add(index);
			}
		}
		int[] indices = new int[out.size()];
		int n = 0;
		for (int index : out) {
			indices[n++] = index;
		}
		Arrays.sort(indices);
		return indices;
	}

	/**
	 * The 4-connected subset of {@code footprint} that contains at least one of
	 * {@code starts}. A decor base group uses this so a disconnected bbox ring
	 * (a lawn island around a seat) is a different structure and cannot pull the
	 * decor away from its own seat/base cells.
	 */
	public Set<Long> connectedColumns(Set<Long> footprint, Set<Long> starts) {
		Set<Long> out = new HashSet<>();
		Deque<Long> queue = new ArrayDeque<>();
		for (long start : starts) {
			if (footprint.contains(start) && out.add(start)) {
				queue.add(start);
			}
		}
		while (!queue.isEmpty()) {
			long column = queue.poll();
			int x = (int) (column >> 32);
			int z = (int) column;
			for (int dir = 0; dir < 4; dir++) {
				long next = RoadCell.packedXZ(x + DIR_DX[dir], z + DIR_DZ[dir]);
				if (footprint.contains(next) && out.add(next)) {
					queue.add(next);
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// connectivity (fix plan v4 D1-e)
	// ------------------------------------------------------------------

	/**
	 * Number of 4-connected components of the mask.
	 *
	 * <p>Fix plan v4 D1/D1-e: a dropped probe cell does not just lose one cell,
	 * it splits the network. The two halves then have no edge between them at
	 * all, each solves on its own, and the dropped column keeps a full-height
	 * vanilla road in between - a drop that every |dP| counter is blind to. So
	 * connectivity is a first-class property of the mask, not something the
	 * solver is expected to notice.
	 */
	public int componentCount() {
		boolean[] seen = new boolean[neighbors.length];
		int components = 0;
		for (int start = 0; start < neighbors.length; start++) {
			if (seen[start]) {
				continue;
			}
			components++;
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(start);
			seen[start] = true;
			while (!stack.isEmpty()) {
				int i = stack.pop();
				for (int dir = 0; dir < 4; dir++) {
					int j = neighbors[i][dir];
					if (j >= 0 && !seen[j]) {
						seen[j] = true;
						stack.push(j);
					}
				}
			}
		}
		return components;
	}

	/** True when the mask is one single 4-connected component (or empty). */
	public boolean isConnected() {
		return componentCount() <= 1;
	}

	/** Size of the largest 4-connected component divided by the cell count; 1.0 when empty. */
	public double largestComponentRatio() {
		int n = neighbors.length;
		if (n == 0) {
			return 1.0;
		}
		int[] labels = componentLabels();
		int[] sizes = new int[componentCount()];
		for (int label : labels) {
			sizes[label]++;
		}
		int largest = 0;
		for (int size : sizes) {
			largest = Math.max(largest, size);
		}
		return (double) largest / n;
	}

	/** Component index per cell (fix plan v4 D1-e diagnostics). */
	public int[] componentLabels() {
		int[] labels = new int[neighbors.length];
		java.util.Arrays.fill(labels, -1);
		int next = 0;
		for (int start = 0; start < neighbors.length; start++) {
			if (labels[start] >= 0) {
				continue;
			}
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(start);
			labels[start] = next;
			while (!stack.isEmpty()) {
				int i = stack.pop();
				for (int dir = 0; dir < 4; dir++) {
					int j = neighbors[i][dir];
					if (j >= 0 && labels[j] < 0) {
						labels[j] = next;
						stack.push(j);
					}
				}
			}
			next++;
		}
		return labels;
	}

	/**
	 * Middle cells of straight 3-wide road sections (user decision 2026-10-17:
	 * the ceiling lights go on the middle cell only, and only on a straight
	 * 3-wide stretch - not at junctions or corners).
	 *
	 * <p>An arm slice is the cross-section of the road: exactly three cells and
	 * this cell in the middle. The slices one step before and after it must also
	 * be exactly three cells wide, with the same perpendicular coordinate and
	 * adjacent sweep coordinates. A corner shifts the middle cell, and a junction
	 * widens at least one slice, so both are excluded by construction.
	 */
	public BitSet threeWideMiddleCells() {
		BitSet out = new BitSet(cells.size());
		for (Arm arm : arms) {
			for (int k = 1; k + 1 < arm.slices.size(); k++) {
				int[] slice = arm.slices.get(k);
				if (slice.length != 3) {
					continue;
				}
				int[] before = arm.slices.get(k - 1);
				int[] after = arm.slices.get(k + 1);
				if (before.length != 3 || after.length != 3) {
					continue;
				}
				int middle = slice[1];
				if (regionCells.get(middle)) {
					continue; // a junction/region core
				}
				RoadCell mid = cells.get(middle);
				RoadCell prev = cells.get(before[1]);
				RoadCell next = cells.get(after[1]);
				if (sameCrossAxis(arm.sweepAxis, mid, prev) && sameCrossAxis(arm.sweepAxis, mid, next)
						&& sweepStep(arm.sweepAxis, mid, prev) == 1
						&& sweepStep(arm.sweepAxis, mid, next) == 1) {
					out.set(middle);
				}
			}
		}
		return out;
	}

	/** True when the two cells share the coordinate across the sweep axis. */
	private static boolean sameCrossAxis(int sweepAxis, RoadCell a, RoadCell b) {
		return sweepAxis == 0 ? a.x == b.x : a.z == b.z;
	}

	/** Absolute step along the sweep axis (0 = z, 1 = x). */
	private static int sweepStep(int sweepAxis, RoadCell a, RoadCell b) {
		return Math.abs(sweepAxis == 0 ? a.z - b.z : a.x - b.x);
	}

	/**
	 * Widest break in the mask: the largest number of consecutive missing
	 * columns between two road cells that belong to <em>different</em> 4-connected
	 * components, in blocks. Zero when the mask is one component.
	 *
	 * <p>Fix plan v4 gapWidth. Only a break between components counts: empty
	 * space at the edge of the mask is where the road legitimately stops, and
	 * counting it would drown the real signal.
	 */
	public int widestBreak() {
		if (neighbors.length == 0 || isConnected()) {
			return 0;
		}
		int[] labels = componentLabels();
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (RoadCell cell : cells) {
			minX = Math.min(minX, cell.x);
			maxX = Math.max(maxX, cell.x);
			minZ = Math.min(minZ, cell.z);
			maxZ = Math.max(maxZ, cell.z);
		}
		// a ray cannot leave the bounding box, and the box bounds the walk: a
		// sparse mask would otherwise make this quadratic in its own extent
		int limit = Math.max(maxX - minX, maxZ - minZ);
		int widest = 0;
		for (int i = 0; i < neighbors.length; i++) {
			RoadCell a = cells.get(i);
			for (int dir = 0; dir < 4; dir++) {
				int gap = 0;
				for (int step = 1; step <= limit; step++) {
					int next = cellAt(a.x + DIR_DX[dir] * step, a.z + DIR_DZ[dir] * step);
					if (next < 0) {
						gap++; // empty column: the gap may be longer
						continue;
					}
					// a road column ends the run; it only counts as a break when it
					// belongs to another component
					if (gap > 0) {
						if (labels[next] != labels[i]) {
							widest = Math.max(widest, gap);
						}
						break;
					}
				}
			}
		}
		return widest;
	}

	public static final class Arm {
		/** Cell indices per slice, slices ordered along the sweep axis. */
		public final List<int[]> slices;
		/** Sweep axis: 0 = slices grouped by z, 1 = slices grouped by x. */
		public final int sweepAxis;
		/** True when the arm touches a region and will be re-solved after the regions. */
		public boolean touchesRegion;

		Arm(List<int[]> slices, int sweepAxis) {
			this.slices = slices;
			this.sweepAxis = sweepAxis;
		}
	}

	public static RoadNetwork build(List<RoadCell> roadCells, int maxExactWidth) {
		roadCells.sort(Comparator.comparingLong((RoadCell c) -> RoadCell.packedXZ(c.x, c.z)));
		Map<Long, Integer> lookup = new HashMap<>();
		for (int i = 0; i < roadCells.size(); i++) {
			RoadCell cell = roadCells.get(i);
			cell.index = i;
			lookup.put(RoadCell.packedXZ(cell.x, cell.z), i);
		}

		int n = roadCells.size();
		int[][] neighbors = new int[n][4];
		for (int i = 0; i < n; i++) {
			RoadCell cell = roadCells.get(i);
			for (int dir = 0; dir < 4; dir++) {
				neighbors[i][dir] = lookup.getOrDefault(RoadCell.packedXZ(cell.x + DIR_DX[dir], cell.z + DIR_DZ[dir]), -1);
			}
		}

		RoadNetwork network = new RoadNetwork(lookup, neighbors);
		network.cells.addAll(roadCells);
		if (n == 0) {
			return network;
		}

		int[] xRun = computeRuns(neighbors, DIR_WEST, DIR_EAST);
		int[] zRun = computeRuns(neighbors, DIR_NORTH, DIR_SOUTH);

		boolean[] core = new boolean[n];
		for (int i = 0; i < n; i++) {
			core[i] = xRun[i] > maxExactWidth && zRun[i] > maxExactWidth;
		}

		// Region cores and arm clusters. Arm clusters that cannot be sliced on
		// either axis are treated as regions. The region membership pass runs
		// after this classification so arms see the final region set.
		boolean[] visitedCore = new boolean[n];
		for (int i = 0; i < n; i++) {
			if (core[i] && !visitedCore[i]) {
				network.regions.add(collectComponent(i, core, visitedCore, neighbors));
			}
		}
		boolean[] nonCore = new boolean[n];
		for (int i = 0; i < n; i++) {
			nonCore[i] = !core[i];
		}
		boolean[] visitedArm = new boolean[n];
		List<int[]> armComponents = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (core[i] || visitedArm[i]) {
				continue;
			}
			armComponents.add(collectComponent(i, nonCore, visitedArm, neighbors));
		}

		List<Arm> pendingArms = new ArrayList<>();
		for (int[] component : armComponents) {
			int[] axisOut = new int[1];
			List<int[]> slices = trySliceComponent(component, roadCells, maxExactWidth, axisOut);
			if (slices != null) {
				pendingArms.add(new Arm(slices, axisOut[0]));
			} else {
				network.regions.add(component);
			}
		}

		boolean[] regionMembership = new boolean[n];
		for (int[] region : network.regions) {
			for (int cell : region) {
				regionMembership[cell] = true;
				network.regionCells.set(cell);
			}
		}
		for (Arm arm : pendingArms) {
			for (int[] slice : arm.slices) {
				for (int cell : slice) {
					for (int dir = 0; dir < 4; dir++) {
						int j = neighbors[cell][dir];
						if (j >= 0 && regionMembership[j]) {
							arm.touchesRegion = true;
						}
					}
				}
			}
			network.arms.add(arm);
		}

		network.buildPlazaGroups(neighbors);
		network.buildGroundOverlapGroups();
		return network;
	}

	/** Group key prefixes so a scenery piece and a fixture piece never collide. */
	public static long sceneryGroupKey(int pieceIndex) {
		return 0x2000_0000_0000_0000L | (pieceIndex & 0xFFFF_FFFFL);
	}

	public static long fixtureGroupKey(int pieceIndex) {
		return 0x1000_0000_0000_0000L | (pieceIndex & 0xFFFF_FFFFL);
	}

	/**
	 * Rebuilds the flat units from the cells' {@code groundGroupKey}. Groups are
	 * derived from the cells so a network rebuild (adding fixture bases) keeps
	 * the unit membership without remapping stale cell indices.
	 */
	public void buildGroundOverlapGroups() {
		groundOverlapGroups.clear();
		Map<Long, List<Integer>> byKey = new HashMap<>();
		Map<Long, Boolean> movableByKey = new HashMap<>();
		for (RoadCell cell : cells) {
			if (!cell.groundOverlap || cell.groundGroupKey == Long.MIN_VALUE) {
				continue;
			}
			byKey.computeIfAbsent(cell.groundGroupKey, k -> new ArrayList<>()).add(cell.index);
			movableByKey.merge(cell.groundGroupKey, cell.groundGroupMovable, (a, b) -> a && b);
		}
		for (Map.Entry<Long, List<Integer>> entry : byKey.entrySet()) {
			List<Integer> group = entry.getValue();
			group.sort(null);
			int[] indices = new int[group.size()];
			for (int i = 0; i < indices.length; i++) {
				indices[i] = group.get(i);
			}
			groundOverlapGroups.add(new GroundOverlapGroup(entry.getKey(), indices,
					movableByKey.getOrDefault(entry.getKey(), false)));
		}
		groundOverlapGroups.sort(Comparator.comparingLong(g -> g.key));
	}

	/**
	 * 4-connected components among the {@code plaza} cells, through plaza cells
	 * only (a street cell wedged into the plaza splits the group - its own seam
	 * keeps the two sides consistent).
	 */
	private void buildPlazaGroups(int[][] neighbors) {
		boolean[] seen = new boolean[cells.size()];
		for (int start = 0; start < cells.size(); start++) {
			if (!cells.get(start).plaza || seen[start]) {
				continue;
			}
			Deque<Integer> stack = new ArrayDeque<>();
			List<Integer> group = new ArrayList<>();
			stack.push(start);
			seen[start] = true;
			while (!stack.isEmpty()) {
				int i = stack.pop();
				group.add(i);
				for (int dir = 0; dir < 4; dir++) {
					int j = neighbors[i][dir];
					if (j >= 0 && cells.get(j).plaza && !seen[j]) {
						seen[j] = true;
						stack.push(j);
					}
				}
			}
			plazaGroups.add(toSortedArray(group));
		}
	}

	/**
	 * Pins every plaza group to ONE flat EVEN walk surface (plan C, user decision
	 * 2026-10-15; even since 2026-10-17): the solver's own choice of plaza level
	 * is the group's median - of the noise-probed terrain ({@code fromBaseP},
	 * T1.5 phase 1) or of the level the rigid pieces actually render at
	 * ({@code fromBaseP = false}, T1.5 phase 2 after the pieces were moved and
	 * the whole T5 path, where nothing may move). The even rounding matches the
	 * full-block platform. On phase 1 {@link #plazaPinsMovable} lets the polish
	 * shift the whole group before placement; on phase 2 / T5 the pin is the
	 * physical render level and stays hard.
	 */
	public void applyPlazaPins(boolean fromBaseP) {
		for (int[] group : plazaGroups) {
			int[] values = new int[group.length];
			for (int k = 0; k < group.length; k++) {
				RoadCell cell = cells.get(group[k]);
				values[k] = fromBaseP ? cell.baseP : cell.plazaVanillaP;
			}
			Arrays.sort(values);
			int level = values[values.length / 2];
			// A plaza platform renders full blocks, so a half-unit (odd) pin would
			// ask the rigid piece to render a slab level it cannot have. Round to
			// the even walk surface below the median; the T1.5 shift and the
			// phase-2 re-pin then agree bit for bit instead of differing by the
			// half step an odd phase-1 pin used to leave behind.
			level &= ~1;
			for (int idx : group) {
				RoadCell cell = cells.get(idx);
				cell.plazaPinned = true;
				cell.plazaPinP = level;
			}
		}
	}

	/** Run lengths through each cell along one axis. */
	private static int[] computeRuns(int[][] neighbors, int backDir, int fwdDir) {
		int n = neighbors.length;
		int[] runs = new int[n];
		for (int i = 0; i < n; i++) {
			if (runs[i] != 0) {
				continue;
			}
			int back = 0;
			int j = neighbors[i][backDir];
			while (j >= 0) {
				back++;
				j = neighbors[j][backDir];
			}
			int fwd = 0;
			j = neighbors[i][fwdDir];
			while (j >= 0) {
				fwd++;
				j = neighbors[j][fwdDir];
			}
			int length = back + 1 + fwd;
			runs[i] = length;
			j = neighbors[i][backDir];
			while (j >= 0) {
				runs[j] = length;
				j = neighbors[j][backDir];
			}
			j = neighbors[i][fwdDir];
			while (j >= 0) {
				runs[j] = length;
				j = neighbors[j][fwdDir];
			}
		}
		return runs;
	}

	private static int[] collectComponent(int start, boolean[] membership, boolean[] visited, int[][] neighbors) {
		Deque<Integer> stack = new ArrayDeque<>();
		List<Integer> out = new ArrayList<>();
		stack.push(start);
		visited[start] = true;
		while (!stack.isEmpty()) {
			int i = stack.pop();
			out.add(i);
			for (int dir = 0; dir < 4; dir++) {
				int j = neighbors[i][dir];
				if (j >= 0 && !visited[j] && membership[j]) {
					visited[j] = true;
					stack.push(j);
				}
			}
		}
		return toSortedArray(out);
	}

	private static int[] toSortedArray(List<Integer> values) {
		int[] arr = new int[values.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = values.get(i);
		}
		Arrays.sort(arr);
		return arr;
	}

	/**
	 * Groups the component into slices along an axis. Returns null when no axis
	 * produces slices that are all within the exact-DP width limit. The chosen
	 * axis is stored in {@code axisOut[0]} on success.
	 */
	private static List<int[]> trySliceComponent(int[] component, List<RoadCell> cells, int maxExactWidth, int[] axisOut) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (int cell : component) {
			RoadCell c = cells.get(cell);
			minX = Math.min(minX, c.x);
			maxX = Math.max(maxX, c.x);
			minZ = Math.min(minZ, c.z);
			maxZ = Math.max(maxZ, c.z);
		}
		int firstTry = (maxX - minX) >= (maxZ - minZ) ? 1 : 0;
		for (int attempt = 0; attempt < 2; attempt++) {
			int axis = attempt == 0 ? firstTry : 1 - firstTry;
			List<int[]> slices = sliceAlong(component, cells, axis);
			boolean fits = true;
			for (int[] slice : slices) {
				if (slice.length > maxExactWidth) {
					fits = false;
					break;
				}
			}
			if (fits) {
				axisOut[0] = axis;
				return slices;
			}
		}
		return null;
	}

	/** Groups cells by coordinate along {@code axis} (0 = group by z, 1 = group by x). */
	private static List<int[]> sliceAlong(int[] component, List<RoadCell> cells, int axis) {
		Map<Integer, List<Integer>> byCoord = new HashMap<>();
		for (int cell : component) {
			RoadCell c = cells.get(cell);
			int coord = axis == 0 ? c.z : c.x;
			byCoord.computeIfAbsent(coord, k -> new ArrayList<>()).add(cell);
		}
		List<Integer> coords = new ArrayList<>(byCoord.keySet());
		coords.sort(null);
		List<int[]> slices = new ArrayList<>(coords.size());
		for (int coord : coords) {
			List<Integer> group = byCoord.get(coord);
			group.sort(null);
			slices.add(toSortedArray(group));
		}
		return slices;
	}
}
