/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.Objective;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadSmoother;

import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Junction regression bench (review 3, test plan T1/T2).
 *
 * <p>Shapes are the vanilla ones measured in the plan (straight streets are 3
 * wide, small crossroads 4x4-5x5, large crossroads 16x16), so a square core
 * plus four 3-wide arms reproduces a real village junction. Heights are
 * half-block walk-surface units, i.e. {@code 2 * walkSurfaceY}.
 *
 * <p>Every test here is a CHARACTERIZATION test: it pins the behaviour the
 * solver has today, including the two defects the review identified, so that
 * whoever fixes them has to update the expectation deliberately. The tests
 * marked DEFECT document behaviour that must not survive the fix.
 */
public class RoadJunctionTest {
	private static final int MAX_EXACT_WIDTH = 4;

	private static RoadSmoother.SolveParams params(Objective objective) {
		return new RoadSmoother.SolveParams(objective, 8 * 16, 32, 16, 4, true);
	}

	// ------------------------------------------------------------------
	// bench helpers
	// ------------------------------------------------------------------

	/** Duplicate-free cell set: RoadNetwork.build does not deduplicate. */
	private static final class Mask {
		final List<RoadCell> cells = new ArrayList<>();
		private final Set<Long> seen = new HashSet<>();

		void add(int x, int z, int topY) {
			add(x, z, topY, false);
		}

		void add(int x, int z, int topY, boolean anchor) {
			if (seen.add(RoadCell.packedXZ(x, z))) {
				cells.add(new RoadCell(x, z, "plains", 2 * topY, anchor));
			}
		}
	}

	/**
	 * Square core {@code [-half,half]^2} (the crossing area, one region) plus
	 * four arms of {@code armWidth} cells reaching {@code armLength} cells out.
	 * Terrain comes from {@code terrain} so a test can tilt one direction only.
	 */
	private static Mask junction(int half, int armWidth, int armLength, BiFunction<Integer, Integer, Integer> terrain) {
		Mask m = new Mask();
		for (int x = -half; x <= half; x++) {
			for (int z = -half; z <= half; z++) {
				m.add(x, z, terrain.apply(x, z));
			}
		}
		for (int d = 1; d <= armLength; d++) {
			for (int w = 0; w < armWidth; w++) {
				int off = w - armWidth / 2;
				m.add(off, -half - d, terrain.apply(off, -half - d));
				m.add(off, half + d, terrain.apply(off, half + d));
				m.add(-half - d, off, terrain.apply(-half - d, off));
				m.add(half + d, off, terrain.apply(half + d, off));
			}
		}
		return m;
	}

	/** Flat core at {@code plazaY}, one arm height per direction. */
	private static Mask junction(int half, int armLength, int north, int south, int west, int east, int plazaY) {
		return junction(half, 3, armLength, (x, z) -> {
			if (z < -half) {
				return north;
			}
			if (z > half) {
				return south;
			}
			if (x < -half) {
				return west;
			}
			if (x > half) {
				return east;
			}
			return plazaY;
		});
	}

	/** seam / region-internal / arm-internal violation counts plus the worst step. */
	private static final class Tally {
		int seam;
		int regionInternal;
		int armInternal;
		int worst;

		int total() {
			return seam + regionInternal + armInternal;
		}
	}

	private static Tally solveAndTally(Mask mask, RoadSmoother.SolveParams p) {
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(mask.cells), MAX_EXACT_WIDTH);
		BitSet region = regionMask(net);
		RoadSmoother.solve(net, p);
		Tally t = new Tally();
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j <= i) {
					continue;
				}
				int d = Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP);
				if (d <= 1) {
					continue;
				}
				boolean ri = region.get(i);
				boolean rj = region.get(j);
				if (ri && rj) {
					t.regionInternal++;
				} else if (!ri && !rj) {
					t.armInternal++;
				} else {
					t.seam++;
				}
				t.worst = Math.max(t.worst, d);
			}
		}
		return t;
	}

	private static Tally solve(Mask mask) {
		return solveAndTally(mask, params(Objective.SMOOTH_FIRST));
	}

	private static BitSet regionMask(RoadNetwork net) {
		BitSet bits = new BitSet(net.cells.size());
		for (int[] region : net.regions) {
			for (int cell : region) {
				bits.set(cell);
			}
		}
		return bits;
	}

	// ------------------------------------------------------------------
	// T1: decomposition shape
	// ------------------------------------------------------------------

	@Test
	public void junctionDecomposesIntoOneRegionAndFourArms() {
		// review 3 conclusion 1: the crossing is a region, the streets are arms
		RoadNetwork net = RoadNetwork.build(
				new ArrayList<>(junction(2, 6, 64, 64, 64, 64, 64).cells), MAX_EXACT_WIDTH);
		assertEquals("a 5x5 core plus four 3-wide arms is exactly one crossing", 1, net.regions.size());
		assertEquals(25, net.regions.get(0).length);
		assertEquals("one arm per street", 4, net.arms.size());
		for (RoadNetwork.Arm arm : net.arms) {
			assertTrue("arms next to a crossing are re-solved against the seam", arm.touchesRegion);
			assertEquals("a 6-long 3-wide arm", 6, arm.slices.size());
		}
	}

	@Test
	public void armsWiderThanMaxExactWidthCollapseIntoTheRegion() {
		// the core test is xRun > 4 AND zRun > 4, so a 5-wide street is "core"
		// everywhere and never becomes an arm: the exact slice DP is unreachable
		RoadNetwork narrow = RoadNetwork.build(
				new ArrayList<>(junction(2, 6, 64, 64, 64, 64, 64).cells), MAX_EXACT_WIDTH);
		assertEquals(4, narrow.arms.size());
		RoadNetwork wide = RoadNetwork.build(
				new ArrayList<>(junction(2, 5, 6, (x, z) -> 64).cells), MAX_EXACT_WIDTH);
		assertEquals("5-wide arms are core cells, so no arm survives", 0, wide.arms.size());
	}

	// ------------------------------------------------------------------
	// T1: the seam cliff (review 3 F1 + F4)
	// ------------------------------------------------------------------

	@Test
	public void junctionIsSmoothWhenNoDirectionDiffersByMoreThanTwoBlocks() {
		for (int diff = -2; diff <= 2; diff++) {
			Tally t = solve(junction(2, 6, 64, 64 + diff, 64, 64, 64));
			assertEquals("S arm differs by " + diff + " blocks: no cliff anywhere", 0, t.total());
		}
	}

	/**
	 * DEFECT (review 3 F1 + F4). The seam can only absorb
	 * {@code min(dpWindowHalfUnits, 4) + 1} half units; with the default window
	 * that is 2.5 blocks. One block more and the candidate band (terrain-anchored,
	 * hard-capped at {@code margin = min(windowHalf, 4)} half units) no longer
	 * intersects the seam hard bound, so {@code candidatesFor} silently keeps the
	 * first pass and the direction is left with a full-height cliff - with no
	 * counter and no log.
	 */
	@Test
	public void junctionSeamCliffsAsSoonAsOneDirectionDiffersByThreeBlocks() {
		for (int diff = 3; diff <= 8; diff++) {
			Tally t = solve(junction(2, 6, 64, 64 + diff, 64, 64, 64));
			assertEquals("diff " + diff + ": the cliff sits on the seam", 3, t.seam);
			assertEquals("diff " + diff + ": the region stays internally legal", 0, t.regionInternal);
			assertEquals("diff " + diff + ": the arms stay internally legal", 0, t.armInternal);
			assertEquals("cliff depth is the WHOLE height difference", 2 * diff, t.worst);
		}
	}

	/** Same cliff downwards and with two opposed outliers. */
	@Test
	public void seamCliffIsSymmetricAndCountsOneEdgePerOutlierDirection() {
		Tally down = solve(junction(2, 6, 64, 64 - 4, 64, 64, 64));
		assertEquals(3, down.seam);
		assertEquals(8, down.worst);

		Tally opposed = solve(junction(2, 6, 68, 60, 64, 64, 64));
		assertEquals("north and south both break", 6, opposed.seam);
		assertEquals(8, opposed.worst);

		Tally diagonal = solve(junction(2, 6, 64, 64, 60, 68, 64));
		assertEquals("east and west both break", 6, diagonal.seam);
		assertEquals(8, diagonal.worst);
	}

	/**
	 * The threshold is a pure function of the arm's candidate-band margin, which
	 * is {@code min(dpWindowHalfUnits, 4)} half units. That is the whole proof of
	 * the causal chain: shrink the window and the cliff threshold drops with it.
	 */
	@Test
	public void seamCliffThresholdIsExactlyTheArmBandMargin() {
		for (int windowHalf = 1; windowHalf <= 8; windowHalf++) {
			int margin = Math.min(windowHalf, 4);
			int firstBrokenDiff = (margin + 1) / 2 + 1;
			for (int diff = 1; diff <= 4; diff++) {
				Tally t = solveAndTally(junction(2, 6, 64, 64 + diff, 64, 64, 64),
						new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, windowHalf, 4, true));
				if (diff < firstBrokenDiff) {
					assertEquals("window " + windowHalf + " diff " + diff + " must stay legal", 0, t.total());
				} else {
					assertEquals("window " + windowHalf + " diff " + diff + " must cliff", 3, t.seam);
				}
			}
		}
	}

	/** Nothing else in the solver can move the cliff: not the objective, penalty or ICM rounds. */
	@Test
	public void seamCliffIgnoresEverySolverKnob() {
		Mask m = junction(2, 6, 64, 68, 64, 64, 64);
		for (Objective objective : Objective.values()) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(objective, 8 * 16, 32, 16, 4, true));
			assertEquals(objective + " must not fix the seam", 8, t.worst);
		}
		for (int penalty : new int[]{1, 4, 32, 256}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, penalty, 16, 4, true));
			assertEquals("penalty " + penalty + " must not fix the seam", 8, t.worst);
		}
		for (int rounds : new int[]{0, 1, 4, 12}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, rounds, true));
			assertEquals("icmRounds " + rounds + " must not fix the seam", 8, t.worst);
		}
		for (int window : new int[]{4, 8, 16, 32, 64}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, window, 4, true));
			assertEquals("dpWindowHalfUnits " + window + " must not fix the seam", 8, t.worst);
		}
	}

	/**
	 * Only the OUTLIER direction breaks. The platform height is the median of
	 * the boundary arm heights, so three agreeing directions pull the crossing
	 * to their level and the fourth is left hanging - that is exactly the
	 * "only some directions drop" symptom.
	 */
	@Test
	public void onlyTheOutlierDirectionBreaks() {
		for (int diff = 2; diff <= 8; diff++) {
			Mask mask = junction(2, 6, 64, 64 + diff, 64, 64, 64);
			RoadNetwork net = RoadNetwork.build(new ArrayList<>(mask.cells), MAX_EXACT_WIDTH);
			RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
			BitSet region = regionMask(net);
			int[] brokenPerDirection = new int[4];
			int total = 0;
			for (int i = 0; i < net.cells.size(); i++) {
				for (int dir = 0; dir < 4; dir++) {
					int j = net.neighbors[i][dir];
					if (j <= i) {
						continue;
					}
					if (Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP) <= 1
							|| region.get(i) == region.get(j)) {
						continue;
					}
					RoadCell outer = region.get(i) ? net.cells.get(j) : net.cells.get(i);
					brokenPerDirection[directionIndex(outer.x, outer.z)]++;
					total++;
				}
			}
			assertEquals("diff " + diff + ": south north east west", diff >= 3 ? 3 : 0, brokenPerDirection[3]);
			assertEquals("diff " + diff + ": north never breaks", 0, brokenPerDirection[0]);
			assertEquals("diff " + diff + ": east never breaks", 0, brokenPerDirection[1]);
			assertEquals("diff " + diff + ": west never breaks", 0, brokenPerDirection[2]);
			assertEquals("diff " + diff + ": nothing else breaks", diff >= 3 ? 3 : 0, total);
		}
	}

	/** A 5x5 core at y=64 with only the south arm lifted by {@code diff}. */
	private static Mask armWidth(int width, int diff) {
		return junction(2, width, 6, (x, z) -> z > 2 ? 64 + diff : 64);
	}

	/** north, east, south, west - the index order {@link #directionIndex} returns. */
	private static int directionIndex(int x, int z) {
		if (z < 0) {
			return 0;
		}
		if (z > 0) {
			return 3;
		}
		return x > 0 ? 1 : 2;
	}

	/** A crossing that agrees with all four arms follows them, however high it sits. */
	@Test
	public void crossingMovesFreelyWhenEveryArmAgrees() {
		for (int lift = -8; lift <= 8; lift += 2) {
			Tally t = solve(junction(2, 6, 64 + lift, 64 + lift, 64 + lift, 64 + lift, 64 + lift));
			assertEquals("uniform lift " + lift, 0, t.total());
		}
	}

	/** Core size does not matter: the failing edge is the seam, not the crossing interior. */
	@Test
	public void seamCliffThresholdIsIndependentOfTheCrossingSize() {
		for (int half = 2; half <= 8; half += 2) {
			Tally ok = solve(junction(half, 6, 64, 66, 64, 64, 64));
			assertEquals("core " + (2 * half + 1) + " at +2", 0, ok.total());
			Tally bad = solve(junction(half, 6, 64, 67, 64, 64, 64));
			assertEquals("core " + (2 * half + 1) + " at +3", 3, bad.seam);
			assertEquals("core " + (2 * half + 1) + " at +3: no interior cliff", 0, bad.regionInternal);
		}
	}

	/** Neither does the arm width or length - one edge per arm cell row, whatever that is. */
	@Test
	public void seamCliffScalesWithTheArmWidthOnly() {
		assertEquals("1-wide arm", 1, solve(armWidth(1, 4)).seam);
		assertEquals("4-wide arm", 4, solve(armWidth(4, 4)).seam);
		for (int length : new int[]{1, 2, 3, 6, 12}) {
			assertEquals("arm length " + length, 3, solve(junction(2, length, 64, 68, 64, 64, 64)).seam);
		}
	}

	// ------------------------------------------------------------------
	// T1: what the crossing region does with its own ramp (review 3 F2)
	// ------------------------------------------------------------------

	/**
	 * The crossing is initialised to a single platform height (the median of the
	 * boundary arm heights) and stays flat there: an internal ramp across the
	 * crossing is not followed, and the whole error is pushed onto the two seams
	 * as cliffs. F2's "interior cliff" is the rare variant - this shows the
	 * common shape.
	 */
	@Test
	public void crossingStaysFlatAtTheBoundaryMedian() {
		Mask ramp = junction(2, 3, 6, (x, z) -> {
			if (z <= -2) {
				return 64;
			}
			if (z >= 2) {
				return 72;
			}
			return 64 + (z + 2) * 2; // 64, 66, 68, 70, 72 across the core
		});
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(ramp.cells), MAX_EXACT_WIDTH);
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		int[] core = net.regions.get(0);
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int cell : core) {
			min = Math.min(min, net.cells.get(cell).solvedP);
			max = Math.max(max, net.cells.get(cell).solvedP);
		}
		assertEquals("the crossing is one flat platform", min, max);
		assertEquals("the platform is the boundary median (68), not the ramp", 2 * 68, min);
	}

	/** Interior cliffs do exist, but only ever 1 half unit and only one edge at a time. */
	@Test
	public void interiorCliffsAreRareAndTiny() {
		Mask m = junction(4, 3, 8, (x, z) -> {
			if (Math.abs(x) <= 4 && Math.abs(z) <= 4) {
				return 64;
			}
			return 68;
		});
		Tally t = solve(m);
		assertEquals("at most one interior edge", 1, t.regionInternal);
		assertEquals("and never more than one half block", 2, t.worst);
	}

	/** A cliff crossing the middle of the crossing is absorbed when it is shallow enough. */
	@Test
	public void shallowStepInsideTheCrossingIsAbsorbed() {
		for (int s = 0; s <= 2; s++) {
			final int step = s;
			Tally t = solve(junction(2, 3, 6, (x, z) -> 64 + (z > 0 ? step : 0)));
			assertEquals("step " + step, 0, t.total());
		}
	}

	// ------------------------------------------------------------------
	// T2: does an arm really leave cross-slice edges unconstrained?
	// ------------------------------------------------------------------

	/**
	 * T2 verdict: NO. {@code buildKernel} aligns slice positions by the
	 * non-sweep coordinate, and since slices are groups of that coordinate, the
	 * alignment covers EVERY 4-adjacency edge between two consecutive slices of
	 * the same arm. The review's "misaligned slices escape the constraint"
	 * hypothesis does not reproduce; only edges to cells outside the arm (the
	 * seams, covered by phase 3) are outside the kernel.
	 */
	@Test
	public void armKernelCoversEveryCrossSliceEdge() {
		List<Mask> shapes = new ArrayList<>();
		// straight 3-wide
		shapes.add(strip(20, z -> 64 + z / 4, 1));
		// sideways shift in the middle (terminator-style misalignment)
		shapes.add(shiftedStrip());
		// widening then narrowing
		shapes.add(stepWidthStrip(1));
		// narrowing to a single cell
		shapes.add(tipStrip());

		for (Mask shape : shapes) {
			RoadNetwork net = RoadNetwork.build(new ArrayList<>(shape.cells), MAX_EXACT_WIDTH);
			for (RoadNetwork.Arm arm : net.arms) {
				int unaligned = 0;
				for (int s = 1; s < arm.slices.size(); s++) {
					int[] a = arm.slices.get(s - 1);
					int[] b = arm.slices.get(s);
					for (int cellA : a) {
						RoadCell ca = net.cells.get(cellA);
						for (int cellB : b) {
							RoadCell cb = net.cells.get(cellB);
							if (Math.abs(ca.x - cb.x) + Math.abs(ca.z - cb.z) != 1) {
								continue; // not 4-adjacent
							}
							int coordA = arm.sweepAxis == 0 ? ca.x : ca.z;
							int coordB = arm.sweepAxis == 0 ? cb.x : cb.z;
							if (coordA != coordB) {
								unaligned++;
							}
						}
					}
				}
				assertEquals("every cross-slice edge of an arm is kernel aligned", 0, unaligned);
			}
		}
	}

	/** T2 verdict: such shapes solve cleanly, they are not a cliff source. */
	@Test
	public void misalignedArmsStillSolve() {
		assertEquals(0, solve(shiftedStrip()).total());
		assertEquals(0, solve(stepWidthStrip(1)).total());
		assertEquals(0, solve(tipStrip()).total());
	}

	private static Mask strip(int length, java.util.function.IntUnaryOperator terrain, int halfWidth) {
		Mask m = new Mask();
		for (int z = 0; z < length; z++) {
			for (int x = -halfWidth; x <= halfWidth; x++) {
				m.add(x, z, terrain.applyAsInt(z));
			}
		}
		return m;
	}

	private static Mask shiftedStrip() {
		Mask m = new Mask();
		for (int z = 0; z < 12; z++) {
			int shift = z < 6 ? 0 : 1;
			int topY = 64 + (z < 6 ? 0 : 1);
			for (int w = -1; w <= 1; w++) {
				m.add(w + shift, z, topY);
			}
		}
		return m;
	}

	private static Mask stepWidthStrip(int halfWidth) {
		Mask m = new Mask();
		for (int z = 0; z < 12; z++) {
			int w = z < 6 ? 1 : halfWidth;
			int topY = z < 6 ? 64 : 66;
			for (int x = -w; x <= w; x++) {
				m.add(x, z, topY);
			}
		}
		return m;
	}

	private static Mask tipStrip() {
		Mask m = new Mask();
		for (int z = 0; z < 12; z++) {
			int topY = 64 + (z < 6 ? 0 : 2);
			for (int x = -1; x <= 1; x++) {
				if (z < 6 || x == 0) {
					m.add(x, z, topY);
				}
			}
		}
		return m;
	}
}