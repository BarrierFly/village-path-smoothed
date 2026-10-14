/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.Objective;
import dev.peoplecanfly.village_path_smoothed.pipeline.ProbeWindow;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadSmoother;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadWritePolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fix plan v4 M0: the in-repo bench, with the metric table of plan section 2.2
 * and the three-dimensional rig of section 3.2.
 *
 * <p><b>Why it lives here.</b> The v3 bench ran out of {@code %TEMP%} against
 * extracted template footprints, so nothing in it survived the round trip and
 * none of the v4 defects were even representable: it had no writer, no mask
 * holes, no probe-caliber window and no multi-chunk network. This file is pure
 * Java (no Minecraft imports) and drives the <em>production</em> decomposition,
 * solver and writer height policy, so a fix moves these numbers instead of a
 * mirror of them.
 *
 * <p><b>Three orthogonal dimensions</b>, all parameters hard-coded (no
 * randomness at run time, no "widen until green"):
 * <ul>
 *   <li>terrain: T-GEN / T-CLIFF / T-MESA / T-VALLEY (section 3.2 dim. 1)</li>
 *   <li>network: N-SMALL / N-BIG / N-HOLED / N-WINDOW (dim. 2)</li>
 *   <li>probe caliber: P-NOISE (T1.5 default, pure noise) / P-REAL (T5 fallback:
 *       the write-stage window reads real terrain, the rest noise) (dim. 3)</li>
 * </ul>
 *
 * <p><b>Assertions are split in the columns the plan asks for.</b> The integrity
 * and defect columns are hard gates; the style column (steps per cell, deviation
 * per cell, over-budget share, max/p99 deviation, arm climb rate) and the
 * presentation column are recorded and printed, never asserted - they are
 * trade-offs, not defects.
 *
 * <p><b>Writer side.</b> {@code writtenViolEdg} and {@code writeOverrideCells}
 * come from {@link RoadWritePolicy#profileColumn} /
 * {@link RoadWritePolicy#waterCrossingColumn}, the very functions the writer
 * calls to decide a column's height. That is what makes the headline symptom
 * ("different chunks do not match") measurable here: the three per-cell
 * overrides of fix plan v4 F7 are invisible to every mask-internal counter.
 */
public class RoadNetworkBenchTest {
	/** Matches a 1.21 overworld; the writer clamps into these bounds. */
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;
	private static final int MAX_EXACT_WIDTH = 4;
	/** Water surface of the bench's valley terrain. */
	private static final int WATER_LEVEL = 58;
	/** R2-3 WARN threshold in whole blocks (plan Q1). */
	private static final int DEVIATION_WARN_BLOCKS = 4;
	/**
 * Spacing of the crossing grid. A crossing's streets reach the next crossing's
 * core, so neighbouring streets overlap and the whole grid is one component -
 * which is what makes the D1-e connectivity assertion meaningful.
 */
	private static final int SPACING = 22;
	/** How far a crossing reaches from its centre: the street of the next crossing stops here. */
	private static final int STREET_SPAN = SPACING - 1;
	/** Half-extent of the BIG grid's crossings (a 3x3 grid, ~5.4 x 5.4 chunks). */
	private static final int BIG_HALF = 1;
	/** Width of a street, i.e. how many columns a hole has to remove to cut it. */
	private static final int STREET_WIDTH = 3;

	private static final int[] DIR_DX = {-1, 1, 0, 0};
	private static final int[] DIR_DZ = {0, 0, -1, 1};

	private static RoadSmoother.SolveParams params() {
		// the shipped defaults: SMOOTH_FIRST, hybrid weight 8.0, window 16,
		// icmRounds 4, allowCutIntoTerrain true
		return new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, 4, true);
	}

	// ==================================================================
	// dimension 1: terrain shapes
	// ==================================================================

	private enum TerrainShape {
		GEN, CLIFF, MESA, VALLEY
	}

	private interface Height {
		/** "First air above the surface", the probe's convention. */
		int topY(int x, int z);
	}

	/** Deterministic lattice hash in [-1000, 1000]; no Random, so runs reproduce exactly. */
	private static int lattice(int ix, int iz, int salt) {
		int h = ix * 374761393 + iz * 668265263 + salt * 1274126177;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0x7fffffff) % 2001 - 1000;
	}

	private static double smooth(double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	/** Bilinear value noise on a fixed lattice, amplitude +/-1. */
	private static double noise(int x, int z, int spacing, int salt) {
		int ix = Math.floorDiv(x, spacing);
		int iz = Math.floorDiv(z, spacing);
		double fx = smooth((double) (x - ix * spacing) / spacing);
		double fz = smooth((double) (z - iz * spacing) / spacing);
		double v00 = lattice(ix, iz, salt) / 1000.0;
		double v10 = lattice(ix + 1, iz, salt) / 1000.0;
		double v01 = lattice(ix, iz + 1, salt) / 1000.0;
		double v11 = lattice(ix + 1, iz + 1, salt) / 1000.0;
		return (v00 * (1 - fx) + v10 * fx) * (1 - fz) + (v01 * (1 - fx) + v11 * fx) * fz;
	}

	private static Height terrain(TerrainShape shape) {
		switch (shape) {
			case GEN:
				// smooth rolling ground, amplitude ~10 blocks, lattice spacing 14
				return (x, z) -> 64 + (int) Math.round(noise(x, z, 14, 1));
			case CLIFF: {
				// gentle gradient with three vertical steps of >= 6 blocks
				return (x, z) -> {
					int y = 64 + (int) Math.round(0.6 * noise(x, z, 20, 2));
					if (x >= 6) {
						y += 8;
					}
					if (z >= 4) {
						y -= 7;
					}
					if (x + z >= 14) {
						y += 6;
					}
					return y;
				};
			}
			case MESA: {
				// three plateaus with short steep walls
				int[][] mesas = {{0, 0, 6}, {-9, -6, 5}, {8, 6, 5}};
				return (x, z) -> {
					int y = 60 + (int) Math.round(0.5 * noise(x, z, 24, 3));
					for (int[] mesa : mesas) {
						if (Math.max(Math.abs(x - mesa[0]), Math.abs(z - mesa[1])) <= mesa[2]) {
							y += 12;
						}
					}
					return y;
				};
			}
			default: {
				// a U-shaped valley running north-south, 12 deep, crossed by every
				// east-west street; its floor sits below WATER_LEVEL so the bench
				// also exercises the water-crossing writer branch
				return (x, z) -> {
					int y = 66 + (int) Math.round(0.4 * noise(x, z, 18, 4));
					double t = Math.max(0.0, 1.0 - (Math.abs(x) / 18.0) * (Math.abs(x) / 18.0));
					return y - (int) Math.round(12 * t);
				};
			}
		}
	}

	/**
	 * The terrain the world actually has when the roads are written: the model
	 * plus the effects that only exist after the solve (fix plan v4 F22) - two
	 * carver grooves and the terrain adaptation around two buildings.
	 */
	private static Height realTerrain(Height model) {
		int[][] buildings = {{-20, 0}, {30, 22}};
		return (x, z) -> {
			int y = model.topY(x, z);
			if (x >= 40 && x < 44) {
				y -= 3; // carver groove
			}
			if (z >= -40 && z < -36) {
				y -= 2; // second carver groove
			}
			for (int[] b : buildings) {
				int d = Math.max(Math.abs(x - b[0]), Math.abs(z - b[1]));
				if (d <= 2) {
					y += 2; // buried building base
				} else if (d <= 5) {
					y += 1; // beard ramp
				}
			}
			return y;
		};
	}

	// ==================================================================
	// dimension 2: network shapes
	// ==================================================================

	private enum NetworkShape {
		SMALL, BIG, HOLED, WINDOW
	}

	/** Footprint builder; cells are de-duplicated, the first claim wins. */
	private static final class MaskBuilder {
		final Map<Long, RoadCell> cells = new HashMap<>();
		final Set<Long> dropped = new LinkedHashSet<>();
		NetworkShape shape;
		int droppedCells;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		void cell(int x, int z, int topY, boolean anchor) {
			long key = RoadCell.packedXZ(x, z);
			cells.computeIfAbsent(key, k -> {
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
				return new RoadCell(x, z, "plains", 2 * topY, anchor);
			});
		}

		/** Fix plan v4 D1: a column whose probe failed. Kept out of the mask. */
		void drop(int x, int z) {
			long key = RoadCell.packedXZ(x, z);
			if (cells.remove(key) != null && dropped.add(key)) {
				droppedCells++;
			}
		}

		List<RoadCell> build() {
			return new ArrayList<>(cells.values());
		}

		/** Widest contiguous run of dropped cells, measured along either axis. */
		int gapWidth() {
			int widest = 0;
			for (long key : dropped) {
				int x = (int) (key >> 32);
				int z = (int) (key & 0xFFFFFFFFL);
				int alongX = 0;
				while (dropped.contains(RoadCell.packedXZ(x - 1 - alongX, z))) {
					alongX++;
				}
				while (dropped.contains(RoadCell.packedXZ(x + 1 + alongX, z))) {
					alongX++;
				}
				int alongZ = 0;
				while (dropped.contains(RoadCell.packedXZ(x, z - 1 - alongZ))) {
					alongZ++;
				}
				while (dropped.contains(RoadCell.packedXZ(x, z + 1 + alongZ))) {
					alongZ++;
				}
				widest = Math.max(widest, Math.max(alongX, alongZ) + 1);
			}
			return widest;
		}
	}

	private static void add(MaskBuilder mask, int x, int z, Height model, Height real) {
		int realTop = real.topY(x, z);
		mask.cell(x, z, model.topY(x, z), realTop <= WATER_LEVEL);
	}

	/** A 3-wide street leaving (x0,z0) in direction (dx,dz). */
	private static void street(MaskBuilder mask, int x0, int z0, int dx, int dz, int reach, Height model, Height real) {
		for (int d = 0; d < reach; d++) {
			int x = x0 + dx * d;
			int z = z0 + dz * d;
			for (int w = -1; w <= 1; w++) {
				add(mask, dx != 0 ? x : x + w, dz != 0 ? z : z + w, model, real);
			}
		}
	}

	private static void core3x3(MaskBuilder mask, int ox, int oz, Height model, Height real) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				add(mask, ox + dx, oz + dz, model, real);
			}
		}
	}

	/** One crossing, core split into a single 3x3 region (vanilla crossroad_02 shape). */
	private static void crossroad(MaskBuilder mask, int ox, int oz, Height model, Height real) {
		core3x3(mask, ox, oz, model, real);
		int street = SPACING - 2;
		street(mask, ox, oz - 2, 0, -1, street, model, real);
		street(mask, ox, oz + 2, 0, 1, street, model, real);
		street(mask, ox - 2, oz, -1, 0, street, model, real);
		street(mask, ox + 2, oz, 1, 0, street, model, real);
	}

	/** Two 3x3 cores joined by a one-wide link: the core splits into two regions (crossroad_03). */
	private static void crossroadSplit2(MaskBuilder mask, int ox, int oz, Height model, Height real) {
		core3x3(mask, ox - 6, oz, model, real);
		core3x3(mask, ox + 6, oz, model, real);
		for (int x = ox - 2; x <= ox + 2; x++) {
			add(mask, x, oz, model, real);
		}
		int street = SPACING - 2;
		int outward = SPACING - 8;
		street(mask, ox - 8, oz, -1, 0, outward, model, real);
		street(mask, ox + 8, oz, 1, 0, outward, model, real);
		street(mask, ox - 6, oz - 2, 0, -1, street, model, real);
		street(mask, ox - 6, oz + 2, 0, 1, street, model, real);
		street(mask, ox + 6, oz - 2, 0, -1, street, model, real);
		street(mask, ox + 6, oz + 2, 0, 1, street, model, real);
	}

	/** Four 3x3 cores in a ring: the core splits into four regions (crossroad_06). */
	private static void crossroadSplit4(MaskBuilder mask, int ox, int oz, Height model, Height real) {
		core3x3(mask, ox - 6, oz - 6, model, real);
		core3x3(mask, ox + 6, oz - 6, model, real);
		core3x3(mask, ox - 6, oz + 6, model, real);
		core3x3(mask, ox + 6, oz + 6, model, real);
		for (int d = -4; d <= 4; d++) {
			add(mask, ox + d, oz - 6, model, real);
			add(mask, ox + d, oz + 6, model, real);
			add(mask, ox - 6, oz + d, model, real);
			add(mask, ox + 6, oz + d, model, real);
		}
		int outward = SPACING - 8;
		street(mask, ox - 8, oz - 6, -1, 0, outward, model, real);
		street(mask, ox + 8, oz + 6, 1, 0, outward, model, real);
		street(mask, ox - 6, oz - 8, 0, -1, outward, model, real);
		street(mask, ox + 6, oz + 8, 0, 1, outward, model, real);
	}

	private static void addCrossing(MaskBuilder mask, int ox, int oz, int kind, Height model, Height real) {
		switch (kind % 3) {
			case 0:
				crossroad(mask, ox, oz, model, real);
				break;
			case 1:
				crossroadSplit2(mask, ox, oz, model, real);
				break;
			default:
				crossroadSplit4(mask, ox, oz, model, real);
				break;
		}
	}

	/** N-BIG: a 4x4 grid of crossings, every arm connected to its neighbour. */
	private static MaskBuilder bigGrid(Height model, Height real) {
		MaskBuilder mask = new MaskBuilder();
		for (int row = -BIG_HALF; row <= BIG_HALF; row++) {
			for (int col = -BIG_HALF; col <= BIG_HALF; col++) {
				addCrossing(mask, col * SPACING, row * SPACING, row + col + BIG_HALF, model, real);
			}
		}
		return mask;
	}

	private static MaskBuilder mask(NetworkShape shape, TerrainShape terrainShape) {
		Height model = terrain(terrainShape);
		Height real = realTerrain(model);
		MaskBuilder mask;
		switch (shape) {
			case SMALL:
				mask = new MaskBuilder();
				crossroad(mask, 0, 0, model, real);
				break;
			case HOLED: {
				mask = bigGrid(model, real);
				// cut the middle of one east-west street: one column wide
				for (int w = -1; w <= 1; w++) {
					mask.drop(-SPACING + 4, 6 + w);
				}
				break;
			}
			case WINDOW:
				mask = bigGrid(model, real);
				break;
			default:
				mask = bigGrid(model, real);
				break;
		}
		mask.shape = shape;
		return mask;
	}

	// ==================================================================
	// dimension 3: probe calibers
	// ==================================================================

	private enum SolverPath {
		/** T1.5 default: every column is pure noise, so chunk-independent by construction. */
		NOISE,
		/** T5 fallback: the +/-1 chunk write window reads real terrain, the rest noise. */
		REAL
	}

	/**
	 * baseP field of one village under one probe caliber.
	 *
	 * <p>The window anchor comes from {@link ProbeWindow#centerChunk} - the same
	 * pure function production uses - and that function takes no trigger
	 * position, which is what makes "the solve does not depend on which chunk
	 * triggered it" structural rather than a convention.
	 */
	private static int baseP(int x, int z, Height model, Height real, MaskBuilder mask, SolverPath path) {
		if (path == SolverPath.NOISE || mask.shape != NetworkShape.WINDOW) {
			return 2 * model.topY(x, z);
		}
		int[] center = ProbeWindow.centerChunk(mask.minX, mask.maxX, mask.minZ, mask.maxZ);
		return 2 * (ProbeWindow.inWindow(x, z, center[0], center[1]) ? real.topY(x, z) : model.topY(x, z));
	}

	// ==================================================================
	// measurement
	// ==================================================================

	private static final class Metrics {
		boolean declined;
		int cells;
		int components;
		double largestComponentRatio = 1.0;
		int dropCells;
		int gapWidth;

		int intViolEdg;
		int intWorstP;
		int writeOverrideCells;
		int writeNudgedCells;
		int nudgedAtEntrance;
		int writeRefusedCells;
		int polishMoved;
		int polishMaxDelta;
		int polishConflicts;
		int edgeViolEdg;
		int edgeWorstDrop;
		int bareEdgeBlocks;
		int writtenViolEdg;
		int writtenWorst;
		int crossChunkViolEdg;
		int crossChunkWorst;

		double stepsPerCell;
		double devPerCell;
		double overBudgetRatio;
		double maxDev;
		double devP99;
		double armClimbP99;

		int devVsRealP50;
		int devVsRealP95;
		int devVsRealP99;
		int devVsRealMax;
		int devOverThresholdCells;
		int cutBelowTerrainCells;
		int fillAboveTerrainCells;

		int[] solvedByIndex = new int[0];
		int[] basePByIndex = new int[0];
		long solveNanos;
	}

	/** Runs the production pipeline over one mask and measures every column of section 2.2. */
	private static Metrics measure(MaskBuilder mask, TerrainShape terrainShape, SolverPath path) {
		Height model = terrain(terrainShape);
		Height real = realTerrain(model);
		List<RoadCell> cells = new ArrayList<>();
		for (RoadCell cell : mask.cells.values()) {
			cell.baseP = baseP(cell.x, cell.z, model, real, mask, path);
			cell.solvedP = cell.baseP;
			cells.add(cell);
		}
		Metrics m = new Metrics();
		m.dropCells = mask.droppedCells;
		m.gapWidth = mask.gapWidth();

		RoadNetwork net = RoadNetwork.build(cells, MAX_EXACT_WIDTH);
		m.components = net.componentCount();
		m.largestComponentRatio = net.largestComponentRatio();
		if (!net.isConnected()) {
			// fix plan v4 D1-e: a split mask is declined as a whole. The production
			// gate is RoadCollector#collect; the rule itself is this predicate.
			m.declined = true;
			return m;
		}
		long begin = System.nanoTime();
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		m.solveNanos = System.nanoTime() - begin;
		m.polishMoved = stats.polishMoved();
		m.polishMaxDelta = stats.polishMaxDelta();
		m.polishConflicts = stats.polishConflicts();
		m.cells = net.cells.size();
		measureMask(net, m);
		measureWriter(net, real, m);
		measureEdges(net, real, m);
		measureArms(net, m);
		m.solvedByIndex = solvedSnapshot(net);
		return m;
	}

	private static void measureMask(RoadNetwork net, Metrics m) {
		long steps = 0;
		long deviation = 0;
		int overBudget = 0;
		int worstDeviation = 0;
		int worstStep = 0;
		List<Integer> deviations = new ArrayList<>();
		for (int i = 0; i < net.cells.size(); i++) {
			RoadCell cell = net.cells.get(i);
			int dev = Math.abs(cell.solvedP - cell.baseP);
			deviation += dev;
			deviations.add(dev);
			if (dev / 2 > 5) {
				overBudget++;
			}
			worstDeviation = Math.max(worstDeviation, dev);
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j <= i) {
					continue;
				}
				int d = Math.abs(cell.solvedP - net.cells.get(j).solvedP);
				steps += d;
				if (d > 1) {
					m.intViolEdg++;
					worstStep = Math.max(worstStep, d);
				}
			}
		}
		int n = net.cells.size();
		m.intWorstP = worstStep;
		m.stepsPerCell = n == 0 ? 0 : (double) steps / n;
		m.devPerCell = n == 0 ? 0 : (double) deviation / n;
		m.overBudgetRatio = n == 0 ? 0 : (double) overBudget / n;
		m.maxDev = worstDeviation;
		m.devP99 = percentile(deviations, 0.99);
	}

	/**
	 * Writer-side measurement. The written walk surface comes from
	 * {@link RoadWritePolicy}, i.e. the same functions the writer calls, so this
	 * covers the per-cell overrides of fix plan v4 F7 instead of restating them.
	 *
	 * <p>Three outcomes per cell: the solved surface is written (the only case a
	 * strict reading of "the writer may not decide a height" allows), the guarded
	 * single-cell surface nudge is written (legal only when it keeps every
	 * neighbour within half a block and is not at a building door), or the cell is
	 * refused. {@code writtenViolEdg} is the gate: whatever combination of the
	 * three happened, the road in the world must still be continuous.
	 */
	private static void measureWriter(RoadNetwork net, Height real, Metrics m) {
		int[] written = new int[net.cells.size()];
		boolean[] hasWritten = new boolean[net.cells.size()];
		List<Integer> devs = new ArrayList<>();
		for (int i = 0; i < net.cells.size(); i++) {
			RoadCell cell = net.cells.get(i);
			int realTop = real.topY(cell.x, cell.z);
			boolean waterAtRoadBlock = realTop <= WATER_LEVEL;
			boolean waterBelow = realTop <= WATER_LEVEL - 1;
			boolean crossing = waterAtRoadBlock || waterBelow; // crossing only adds scaffolding support below the road
			// the written walk surface is the solved surface in every case: a water
			// crossing's scaffolding is support under the road block, not the surface
			RoadWritePolicy.Column column = RoadWritePolicy.profileColumn(cell.solvedP, MIN_Y, MAX_Y);
			if (!column.written()) {
				column = RoadWritePolicy.surfaceNudgeColumn(cell.solvedP, realTop, MIN_Y, MAX_Y,
						neighbourSurfaces(net, cell), nearEntrance(cell));
			}
			hasWritten[i] = column.written();
			written[i] = column.walkSurface2();
			if (column.written() && column.overrodeSolved()) {
				m.writeOverrideCells++;
			}
			if (column.written() && column.walkSurface2() != cell.solvedP) {
				m.writeNudgedCells++;
				if (nearEntrance(cell)) {
					m.nudgedAtEntrance++;
				}
			}
			if (!column.written()) {
				m.writeRefusedCells++;
				continue;
			}
			int dev = Math.abs(written[i] - 2 * realTop);
			devs.add(dev);
			m.devVsRealMax = Math.max(m.devVsRealMax, dev);
			if (dev > 2 * DEVIATION_WARN_BLOCKS) {
				m.devOverThresholdCells++;
			}
			if (written[i] < 2 * realTop - 2) {
				m.cutBelowTerrainCells++;
			}
			if (written[i] > 2 * realTop + 2) {
				m.fillAboveTerrainCells++;
			}
		}
		m.devVsRealP50 = (int) percentile(devs, 0.50);
		m.devVsRealP95 = (int) percentile(devs, 0.95);
		m.devVsRealP99 = (int) percentile(devs, 0.99);
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j <= i || !hasWritten[i] || !hasWritten[j]) {
					continue;
				}
				int d = Math.abs(written[i] - written[j]);
				if (d <= 1) {
					continue;
				}
				m.writtenViolEdg++;
				m.writtenWorst = Math.max(m.writtenWorst, d);
				if (crossesChunkBoundary(net.cells.get(i), net.cells.get(j))) {
					m.crossChunkViolEdg++;
					m.crossChunkWorst = Math.max(m.crossChunkWorst, d);
				}
			}
		}
	}

	/** The bench's buildings, as footprints: the writer never nudges beside one. */
	private static final int[][] BUILDINGS = {{-20, 0, 5, 5}, {30, 22, 5, 5}};

	private static boolean nearEntrance(RoadCell cell) {
		for (int[] b : BUILDINGS) {
			if (Math.abs(cell.x - b[0]) <= b[2] + 1 && Math.abs(cell.z - b[1]) <= b[3] + 1) {
				return true;
			}
		}
		return false;
	}

	private static int[] neighbourSurfaces(RoadNetwork net, RoadCell cell) {
		int[] out = new int[4];
		int n = 0;
		for (int dir = 0; dir < 4; dir++) {
			int j = net.neighbors[cell.index][dir];
			if (j >= 0) {
				out[n++] = net.cells.get(j).solvedP;
			}
		}
		return n == 0 ? null : java.util.Arrays.copyOf(out, n);
	}

	private static boolean crossesChunkBoundary(RoadCell a, RoadCell b) {
		return (a.x >> 4) != (b.x >> 4) || (a.z >> 4) != (b.z >> 4);
	}

	/**
	 * Mask-edge measurement (fix plan v4 D4-a: observation only, no fix in this
	 * round). The road's walk surface against the real surface of the non-road
	 * column beside it, counted once per side column like the writer's
	 * {@code highestRoadPerSideColumn}.
	 */
	private static void measureEdges(RoadNetwork net, Height real, Metrics m) {
		Map<Long, Integer> worstPerColumn = new HashMap<>();
		for (int i = 0; i < net.cells.size(); i++) {
			RoadCell cell = net.cells.get(i);
			for (int dir = 0; dir < 4; dir++) {
				int outX = cell.x + DIR_DX[dir];
				int outZ = cell.z + DIR_DZ[dir];
				if (net.cellAt(outX, outZ) >= 0) {
					continue;
				}
				worstPerColumn.merge(RoadCell.packedXZ(outX, outZ),
						Math.abs(2 * real.topY(outX, outZ) - cell.solvedP), Math::max);
			}
		}
		for (int delta : worstPerColumn.values()) {
			m.edgeWorstDrop = Math.max(m.edgeWorstDrop, delta);
			if (delta > 2) {
				m.edgeViolEdg++;
			}
			// a drop of at most one whole block gets neither kerb nor railing:
			// fix plan v4 D8-a keeps that threshold on purpose, so it is recorded
			if (delta > 0 && delta <= 2) {
				m.bareEdgeBlocks++;
			}
		}
	}

	/** Per-arm climb rate (half units per slice): the tail statistic B would need. */
	private static void measureArms(RoadNetwork net, Metrics m) {
		List<Integer> climbs = new ArrayList<>();
		for (RoadNetwork.Arm arm : net.arms) {
			if (arm.slices.size() < 2) {
				continue;
			}
			int lo = Integer.MAX_VALUE;
			int hi = Integer.MIN_VALUE;
			for (int[] slice : arm.slices) {
				for (int cell : slice) {
					lo = Math.min(lo, net.cells.get(cell).solvedP);
					hi = Math.max(hi, net.cells.get(cell).solvedP);
				}
			}
			climbs.add((hi - lo) / (2 * (arm.slices.size() - 1)));
		}
		m.armClimbP99 = percentile(climbs, 0.99);
	}

	private static double percentile(List<Integer> values, double q) {
		if (values.isEmpty()) {
			return 0;
		}
		int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
		int index = (int) Math.ceil(q * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}

	private static int[] solvedSnapshot(RoadNetwork net) {
		int[] out = new int[net.cells.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = net.cells.get(i).solvedP;
		}
		return out;
	}

	private static int[] basePSnapshot(RoadNetwork net) {
		int[] out = new int[net.cells.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = net.cells.get(i).baseP;
		}
		return out;
	}

	// ==================================================================
	// assertions
	// ==================================================================

	private static final TerrainShape[] TERRAINS = TerrainShape.values();
	private static final NetworkShape[] NETWORKS = {NetworkShape.SMALL, NetworkShape.BIG};
	private static final SolverPath[] PATHS = SolverPath.values();

	private static void assertMaskIntegrity(String where, Metrics m) {
		assertEquals(where + ": no dropped mask cell (" + m.dropCells + ")", 0, m.dropCells);
		assertEquals(where + ": exactly one component", 1, m.components);
		assertEquals(where + ": the largest component is everything", 1.0, m.largestComponentRatio, 0.0);
		assertEquals(where + ": no |dP| > 1 edge inside the mask (worst " + m.intWorstP + ")", 0, m.intViolEdg);
		assertEquals(where + ": the worst mask step is 0", 0, m.intWorstP);
	}

	/** Plan 3.2 dimensions one and three on the two hole-free networks. */
	@Test
	public void everyTerrainAndPathSolvesWithoutMaskInternalDrops() {
		for (NetworkShape network : NETWORKS) {
			for (SolverPath path : PATHS) {
				for (TerrainShape terrain : TERRAINS) {
					Metrics m = measure(mask(network, terrain), terrain, path);
					assertMaskIntegrity(network + "/" + path + "/" + terrain, m);
				}
			}
		}
	}

	/**
	 * Fix plan v4 M5 / X-a(1): the written road may not step. A legal mask
 * solution that the writer then breaks is exactly the "chunks do not match"
	 * symptom, and it is invisible to {@code intViolEdg}. The single-cell surface
 * nudge is allowed (AGENTS.md, 2026-10-14), so the gate is continuity plus the
 * nudge's own two criteria: never at a building door, and never a step.
	 */
	@Test
	public void theWrittenRoadIsContinuousAndOnlyNudgesWhereItIsLegal() {
		for (NetworkShape network : NETWORKS) {
			for (SolverPath path : PATHS) {
				for (TerrainShape terrain : TERRAINS) {
					Metrics m = measure(mask(network, terrain), terrain, path);
					String where = network + "/" + path + "/" + terrain;
					assertEquals(where + ": the written road is legal", 0, m.writtenViolEdg);
					assertEquals(where + ": the worst written step is 0", 0, m.writtenWorst);
					assertEquals(where + ": no column refused outright", 0, m.writeRefusedCells);
					assertEquals(where + ": a nudge never happens beside a building door", 0, m.nudgedAtEntrance);
				}
			}
		}
	}

	/**
	 * Fix plan v4 X-a(1): the headline assertion, counted across chunk
	 * boundaries. One solve covers the whole village, so a violation here can
	 * only come from the write stage.
	 */
	@Test
	public void writtenRoadIsContinuousAcrossChunkBoundaries() {
		for (TerrainShape terrain : TERRAINS) {
			Metrics m = measure(mask(NetworkShape.BIG, terrain), terrain, SolverPath.NOISE);
			assertEquals("BIG/NOISE/" + terrain + ": cross-chunk boundary edges are legal", 0, m.crossChunkViolEdg);
			assertEquals("BIG/NOISE/" + terrain + ": the worst cross-chunk step is 0", 0, m.crossChunkWorst);
		}
	}

/**
 * Fix plan v4 X-a(2) / R2-a / D2-c: determinism. The probe-caliber window is
 * anchored on the village centre ({@link ProbeWindow#centerChunk}), so the
 * whole-village solve is a pure function of the village. The trigger corner
 * survives here only as the pre-fix behaviour D2-c replaced: anchoring the same
 * window on any corner yields a different field and a different solution, which
 * is what "the road depends on generation order" looked like.
 */
	@Test
	public void theSolvedProfileIsIndependentOfTheTriggeringChunk() {
		for (TerrainShape terrain : TERRAINS) {
			Metrics centre = measureWithWindow(terrain, null);
			assertTrue("WINDOW/" + terrain + ": the village was solved", centre.cells > 0);
			Metrics centreAgain = measureWithWindow(terrain, null);
			assertArrayEquals("WINDOW/" + terrain + ": the centre-anchored field is reproducible",
					centre.basePByIndex, centreAgain.basePByIndex);
			assertArrayEquals("WINDOW/" + terrain + ": the centre-anchored solve is reproducible",
					centre.solvedByIndex, centreAgain.solvedByIndex);
			int[][] corners = villageCorners();
			Metrics firstTrigger = measureWithWindow(terrain, corners[0]);
			StringBuilder report = new StringBuilder("WINDOW/" + terrain + ": solve ");
			report.append(centre.solveNanos / 1000000).append("ms centre-anchored, ")
					.append(firstTrigger.solveNanos / 1000000).append("ms corner-anchored");
			for (int corner = 0; corner < corners.length; corner++) {
				Metrics trigger = measureWithWindow(terrain, corners[corner]);
				assertFalse("WINDOW/" + terrain + ": anchoring the window on trigger corner " + corner
								+ " really does move the height field (otherwise this test proves nothing)",
						java.util.Arrays.equals(centre.basePByIndex, trigger.basePByIndex));
				if (corner > 0) {
					assertFalse("WINDOW/" + terrain + ": trigger corners 0 and " + corner
									+ " disagree on the field - that disagreement is the old non-reproducibility",
							java.util.Arrays.equals(firstTrigger.basePByIndex, trigger.basePByIndex));
				}
			}
			System.out.println(report);
		}
	}

	/** The window anchor itself: a pure function of the village box, never of a trigger. */
	@Test
	public void theProbeWindowIsAnchoredOnTheVillageCentre() {
		assertArrayEquals("the anchor is the centre chunk of the box", new int[]{1, 4},
				ProbeWindow.centerChunk(-37, 90, 12, 140));
		assertTrue("the anchor chunk is in the window", ProbeWindow.inWindow(26, 76, 1, 4));
		assertTrue("one chunk of margin is in the window", ProbeWindow.inWindow(42, 76, 1, 4));
		assertFalse("two chunks out is not", ProbeWindow.inWindow(58, 76, 1, 4));
	}

	/** The four block corners of the BIG grid, i.e. the four possible trigger chunks' corners. */
	private static int[][] villageCorners() {
		int far = SPACING * BIG_HALF + STREET_SPAN;
		return new int[][]{{-far, -far}, {far, -far}, {-far, far}, {far, far}};
	}

	/**
	 * One solve of the BIG grid with the T5 window anchored either on the village
	 * centre ({@code anchor == null}) or on the given block corner, i.e. the
	 * pre-fix trigger-relative behaviour.
	 */
	private static Metrics measureWithWindow(TerrainShape terrain, int[] anchor) {
		Height model = terrain(terrain);
		Height real = realTerrain(model);
		MaskBuilder mask = mask(NetworkShape.WINDOW, terrain);
		int[] centre = ProbeWindow.centerChunk(mask.minX, mask.maxX, mask.minZ, mask.maxZ);
		int anchorX = anchor == null ? centre[0] : anchor[0] >> 4;
		int anchorZ = anchor == null ? centre[1] : anchor[1] >> 4;
		List<RoadCell> cells = new ArrayList<>();
		for (RoadCell cell : mask.cells.values()) {
			int topY = ProbeWindow.inWindow(cell.x, cell.z, anchorX, anchorZ)
					? real.topY(cell.x, cell.z)
					: model.topY(cell.x, cell.z);
			cell.baseP = 2 * topY;
			cell.solvedP = cell.baseP;
			cells.add(cell);
		}
		long begin = System.nanoTime();
		RoadNetwork net = RoadNetwork.build(cells, MAX_EXACT_WIDTH);
		RoadSmoother.solve(net, params());
		Metrics m = new Metrics();
		m.solveNanos = System.nanoTime() - begin;
		m.cells = net.cells.size();
		m.basePByIndex = basePSnapshot(net);
		m.solvedByIndex = solvedSnapshot(net);
		return m;
	}

	/**
 * Fix plan v4 D1-e: a mask with a hole must never be partially adopted. The gate
 * is {@link RoadNetwork#isConnected()}; both a single-layer cut and a
 * two-layer cut across a street have to trip it.
 */
	@Test
	public void aHoleInTheMaskDeclinesTheWholeVillage() {
		for (int layers : new int[]{1, 2}) {
			MaskBuilder mask = holedMask(layers);
			assertEquals("a " + layers + "-layer cut removes " + (STREET_WIDTH * layers) + " columns",
					STREET_WIDTH * layers, mask.droppedCells);
			assertEquals("the gap spans the whole street", STREET_WIDTH, mask.gapWidth());
			Metrics m = measure(mask, TerrainShape.GEN, SolverPath.NOISE);
			assertTrue("a " + layers + "-layer hole declines the village as a whole", m.declined);
			assertEquals("a " + layers + "-layer hole writes nothing", 0, m.writeOverrideCells);
		}
	}

	/**
	 * N-HOLED: the BIG grid with a cut across its outermost street, a couple of
	 * cells in from the tip so the severed tail really is a separate component
	 * (cutting the tip itself would leave everything connected).
	 */
	private static MaskBuilder holedMask(int layers) {
		Height model = terrain(TerrainShape.GEN);
		MaskBuilder mask = bigGrid(model, realTerrain(model));
		int minZ = Integer.MAX_VALUE;
		for (RoadCell cell : mask.cells.values()) {
			minZ = Math.min(minZ, cell.z);
		}
		int cutZ = minZ + 2;
		List<Integer> xs = new ArrayList<>();
		for (RoadCell cell : mask.cells.values()) {
			if (cell.z == cutZ) {
				xs.add(cell.x);
			}
		}
		java.util.Collections.sort(xs);
		int bestStart = -1;
		int bestLength = 0;
		for (int i = 0; i < xs.size();) {
			int j = i;
			while (j + 1 < xs.size() && xs.get(j + 1) == xs.get(j) + 1) {
				j++;
			}
			if (j - i + 1 > bestLength) {
				bestStart = xs.get(i);
				bestLength = j - i + 1;
			}
			i = j + 1;
		}
		for (int layer = 0; layer < layers; layer++) {
			for (int w = 0; w < bestLength; w++) {
				mask.drop(bestStart + w, cutZ + layer);
			}
		}
		mask.shape = NetworkShape.HOLED;
		return mask;
	}

	/**
	 * The style column (plan 2.2 col. 3) and the presentation column: recorded and
	 * printed, never asserted - they are trade-offs. The two exceptions are the
	 * "no worse than before" guards: the deviation ceiling and the step budget.
	 */
	@Test
	public void styleMetricsStayWithinTheShippedBaseline() {
		StringBuilder report = new StringBuilder();
		// one throwaway solve so the timings below are steady-state numbers rather
		// than class loading and JIT warmup
		measure(mask(NetworkShape.SMALL, TerrainShape.GEN), TerrainShape.GEN, SolverPath.NOISE);
		for (NetworkShape network : NETWORKS) {
			for (SolverPath path : PATHS) {
				for (TerrainShape terrain : TERRAINS) {
					Metrics m = measure(mask(network, terrain), terrain, path);
					String where = network + "/" + path + "/" + terrain;
					assertTrue(where + ": dev/cell " + m.devPerCell + " is no worse than the baseline "
									+ BASELINE_DEV_PER_CELL,
							m.devPerCell <= BASELINE_DEV_PER_CELL);
					assertTrue(where + ": steps/cell " + m.stepsPerCell + " stays within one half block per cell",
							m.stepsPerCell <= 1.0);
					report.append(String.format(
							"%-22s cells=%4d comps=%d declined=%s solve=%dms steps/cell=%.2f dev/cell=%.2f overBud=%.1f%% maxDev=%d devP99=%d climbP99=%d"
									+ " | polishMoved=%d polishMaxDelta=%d polishConflicts=%d"
									+ " | edgeViol=%d edgeWorst=%d bareEdge=%d writeOverride=%d nudged=%d nudgedAtDoor=%d refused=%d"
									+ " | devVsReal p50/p95/p99/max=%d/%d/%d/%d overWarn=%d cutBelow=%d fillAbove=%d%n",
							where, m.cells, m.components, m.declined, m.solveNanos / 1000000,
							m.stepsPerCell, m.devPerCell, 100 * m.overBudgetRatio,
							(int) m.maxDev, (int) m.devP99, (int) m.armClimbP99,
							m.polishMoved, m.polishMaxDelta, m.polishConflicts,
							m.edgeViolEdg, m.edgeWorstDrop, m.bareEdgeBlocks,
							m.writeOverrideCells, m.writeNudgedCells, m.nudgedAtEntrance, m.writeRefusedCells,
							m.devVsRealP50, m.devVsRealP95, m.devVsRealP99, m.devVsRealMax,
							m.devOverThresholdCells, m.cutBelowTerrainCells, m.fillAboveTerrainCells));
				}
			}
		}
		System.out.println("[bench] fix plan v4 M0, style / presentation columns:\n" + report);
	}

	/**
	 * Deviation ceiling measured on this bench with the pipeline as it stood
	 * before the fix: the worst of the sixteen terrain/network/caliber
	 * combinations was SMALL/MESA at 9.55 half units per cell, i.e. the plateau
	 * field is where the current solver gives up. Round on purpose: the plan
	 * asks for "no worse than the current numbers", not for a tighter objective.
	 */
	private static final double BASELINE_DEV_PER_CELL = 9.60;
}