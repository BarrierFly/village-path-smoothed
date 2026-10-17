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
 * <p>Review 3 pinned the seam-cliff defects with characterization tests whose
 * expectations had to be flipped deliberately by the fix (P0-A). The cliff
 * expectations are gone: an outlier direction is now absorbed into a legal
 * ramp plus a bounded body deviation, and what still cannot be satisfied
 * (opposed plazas on a short crossing) is shallow and counted in the solve
 * stats instead of silent.
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
	 * The review 3 F1+F4 fix: the seam band extends toward the seam hard bound
	 * along the legal-slope cone, so the whole height difference becomes a
	 * legal ramp plus a bounded body deviation instead of a full-height cliff.
	 *
	 * <p>The arm ends up strictly below (above) its terrain: SMOOTH_FIRST spends
	 * as few steps as the seam bound and the slope cone allow and then holds,
	 * which the writer surfaces as fill/cut, not as a wall (reverted semantics,
	 * fix plan v2 R1 / N3).
	 */
	@Test
	public void seamAbsorbsTheOutlierDirectionIntoABodyDeviation() {
		for (int diff = 3; diff <= 8; diff++) {
			Mask mask = junction(2, 6, 64, 64 + diff, 64, 64, 64);
			Tally t = solve(mask);
			assertEquals("diff " + diff + ": no seam cliff", 0, t.seam);
			assertEquals("diff " + diff + ": the region stays internally legal", 0, t.regionInternal);
			assertEquals("diff " + diff + ": the arms stay internally legal", 0, t.armInternal);
			assertEquals("cliff depth is gone entirely", 0, t.worst);
			RoadNetwork net = RoadNetwork.build(new ArrayList<>(mask.cells), MAX_EXACT_WIDTH);
			RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
			int armMax = Integer.MIN_VALUE;
			for (RoadCell cell : net.cells) {
				if (cell.z > 2) {
					armMax = Math.max(armMax, cell.solvedP);
				}
			}
			// the arm holds strictly below its terrain: steps outrank deviation,
			// so it stays near the crossing's level instead of climbing into the
			// outlier direction (fix plan v2 R1)
			assertTrue("diff " + diff + ": the arm holds strictly below its terrain (" + armMax + ")",
					armMax < 2 * (64 + diff));
		}
	}

	/** Absorption works downwards too, and opposed plazas degrade gracefully. */
	@Test
	public void absorptionIsSymmetricAndOpposedPlazasDegradeGracefully() {
		Tally down = solve(junction(2, 6, 64, 64 - 4, 64, 64, 64));
		assertEquals("single outlier below: fully absorbed", 0, down.total());
		assertEquals(0, down.worst);

		// two plazas 4 blocks apart sharing one 5x5 crossing is geometrically
		// unsatisfiable at 1 half unit per cell; the residual must stay shallow
		// and off the seams (it is counted in the solve stats, see the stats test)
		Tally opposed = solve(junction(2, 6, 68, 60, 64, 64, 64));
		assertEquals("no seam cliff between opposed plazas", 0, opposed.seam);
		assertTrue("opposed plazas: shallow residual only, was " + opposed.worst, opposed.worst <= 2);

		Tally diagonal = solve(junction(2, 6, 64, 64, 60, 68, 64));
		assertEquals("no seam cliff between diagonal plazas", 0, diagonal.seam);
		assertTrue("diagonal plazas: shallow residual only, was " + diagonal.worst, diagonal.worst <= 2);
	}

	/**
	 * The old cliff threshold was {@code min(dpWindowHalfUnits, 4) + 1} half
	 * units - the proof of the F4 causal chain. With the seam extension the
	 * threshold is set by the arm's LENGTH (the slope cone has one half unit
	 * per slice to work with), not by any solver knob, so every window absorbs
	 * every height difference this bench can express.
	 */
	@Test
	public void seamAbsorptionNoLongerDependsOnTheWindow() {
		for (int windowHalf = 1; windowHalf <= 8; windowHalf++) {
			for (int diff = 1; diff <= 4; diff++) {
				Tally t = solveAndTally(junction(2, 6, 64, 64 + diff, 64, 64, 64),
						new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, windowHalf, 4, true));
				assertEquals("window " + windowHalf + " diff " + diff + ": no seam cliff", 0, t.seam);
				if (windowHalf >= 4) {
					assertEquals("window " + windowHalf + " diff " + diff + " must absorb fully", 0, t.total());
				} else {
					// a tiny window freezes the ICM to its seed (candidates reach
					// only baseP +/- windowHalf): the residual stays shallow and
					// interior - the review's "shrinking the window makes it
					// worse" caveat, now bounded to a couple of half units
					// instead of full-height cliffs
					assertTrue("window " + windowHalf + " diff " + diff + " worst " + t.worst, t.worst <= 5);
				}
			}
		}
	}

	/**
	 * The absorption is knob-independent. HYBRID is the one objective that may
	 * still jump: its cost ranks the deviation weight (w1 = 128) together with
	 * the cliff penalty, so holding the ramp below terrain costs more than one
	 * jump. Even then the jump is half the old cliff and shows up in the stats.
	 */
	@Test
	public void seamAbsorptionAlsoIgnoresEverySolverKnob() {
		Mask m = junction(2, 6, 64, 68, 64, 64, 64);
		for (Objective objective : new Objective[]{Objective.SMOOTH_FIRST, Objective.MIN_DEVIATION, Objective.SHORTEST_CATCHUP}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(objective, 8 * 16, 32, 16, 4, true));
			assertEquals(objective + " must absorb the seam", 0, t.total());
		}
		Tally hybrid = solveAndTally(m, new RoadSmoother.SolveParams(Objective.HYBRID, 8 * 16, 32, 16, 4, true));
		assertTrue("HYBRID worst " + hybrid.worst + " must stay under the old full cliff (8)", hybrid.worst < 8);
		for (int penalty : new int[]{1, 4, 32, 256}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, penalty, 16, 4, true));
			assertEquals("penalty " + penalty + " must absorb the seam", 0, t.total());
		}
		for (int rounds : new int[]{0, 1, 4, 12}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, rounds, true));
			assertEquals("icmRounds " + rounds + ": no seam cliff", 0, t.seam);
			if (rounds < 4) {
				// little or no ICM refinement: the hard passes alone keep a
				// shallow interior residual instead of the old full-height cliff
				assertTrue("icmRounds " + rounds + " worst " + t.worst + " must stay shallow", t.worst <= 5);
			} else {
				assertEquals("icmRounds " + rounds + " must absorb the seam", 0, t.total());
			}
		}
		for (int window : new int[]{4, 8, 16, 32, 64}) {
			Tally t = solveAndTally(m, new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, window, 4, true));
			assertEquals("dpWindowHalfUnits " + window + " must absorb the seam", 0, t.total());
		}
	}

	/**
	 * The review 3 symptom "only some directions drop" is gone: with the seam
	 * band extension no direction breaks at all, whatever the platform height
	 * the three agreeing directions pull the crossing to.
	 */
	@Test
	public void noDirectionBreaksAnymore() {
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
			assertEquals("diff " + diff + ": south never breaks", 0, brokenPerDirection[3]);
			assertEquals("diff " + diff + ": north never breaks", 0, brokenPerDirection[0]);
			assertEquals("diff " + diff + ": east never breaks", 0, brokenPerDirection[1]);
			assertEquals("diff " + diff + ": west never breaks", 0, brokenPerDirection[2]);
			assertEquals("diff " + diff + ": nothing breaks", 0, total);
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

	/** Core size does not matter: the outlier direction is absorbed for every core size. */
	@Test
	public void absorptionIsIndependentOfTheCrossingSize() {
		for (int half = 2; half <= 8; half += 2) {
			Tally ok = solve(junction(half, 6, 64, 66, 64, 64, 64));
			assertEquals("core " + (2 * half + 1) + " at +2", 0, ok.total());
			Tally bad = solve(junction(half, 6, 64, 67, 64, 64, 64));
			assertEquals("core " + (2 * half + 1) + " at +3: no seam cliff", 0, bad.seam);
			assertEquals("core " + (2 * half + 1) + " at +3: no interior cliff", 0, bad.regionInternal);
			assertEquals("core " + (2 * half + 1) + " at +3: fully absorbed", 0, bad.total());
		}
	}

	/** Neither does the arm width or length - every row absorbs, whatever the geometry. */
	@Test
	public void absorptionIsIndependentOfArmWidthAndLength() {
		assertEquals("1-wide arm", 0, solve(armWidth(1, 4)).total());
		assertEquals("4-wide arm", 0, solve(armWidth(4, 4)).total());
		for (int length : new int[]{1, 2, 3, 6, 12}) {
			assertEquals("arm length " + length, 0, solve(junction(2, length, 64, 68, 64, 64, 64)).total());
		}
	}

	// ------------------------------------------------------------------
	// T1: what the crossing region does with its own ramp (review 3 F2)
	// ------------------------------------------------------------------

	/**
	 * Review 3 F2 fix (P0-B), completed by review 4: the crossing is seeded with
	 * a boundary gradient, that seed is legalised into a staircase and becomes
	 * the crossing's reference, and the ICM is anchored on it. So the crossing
	 * stays inside the band its two arms can reach with near-legal ramps and the
	 * seams stay clean. Review 4 removed the "one legalisable span" width bound:
	 * a crossing is two-dimensional and a legal staircase over a 5x5 core can
	 * legitimately span more than its four rows, so the meaningful statement is
	 * that it stays between the two arm levels and is internally legal.
	 */
	@Test
	public void crossingPlatformIsReachableFromBothArms() {
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
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		int[] core = net.regions.get(0);
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int cell : core) {
			min = Math.min(min, net.cells.get(cell).solvedP);
			max = Math.max(max, net.cells.get(cell).solvedP);
		}
		assertTrue("the platform never sinks below the low arm (was " + min + ")", min >= 2 * 64);
		assertTrue("the platform never climbs above the high arm (was " + max + ")", max <= 2 * 72);
		Tally t = tally(net);
		assertEquals("no seam cliff on either side", 0, t.seam);
		assertTrue("the residual is shallow and small (was " + t.total() + " edges, worst " + t.worst + ")",
				t.total() <= 2 && t.worst <= 2);
		assertEquals("and the stats see exactly that", t.total(), stats.residualViolations());
	}

	/** Interior steps do exist, but only ever one edge and one whole block at a time. */
	@Test
	public void interiorCliffsAreRareAndTiny() {
		Mask m = junction(4, 3, 8, (x, z) -> {
			if (Math.abs(x) <= 4 && Math.abs(z) <= 4) {
				return 64;
			}
			return 68;
		});
		Tally t = solve(m);
		assertTrue("at most one interior edge (was " + t.regionInternal + ")", t.regionInternal <= 1);
		assertTrue("and never more than one whole block (2 half units)", t.worst <= 2);
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
	// review 3 P0-A item 3: the solve stats make residual failures visible
	// ------------------------------------------------------------------

	/**
	 * The returned stats must agree with an independent edge tally: this is the
	 * counter /vps stats exposes, so a drift between the two would hide real
	 * failures again.
	 */
	@Test
	public void solveStatsCrossCheckTheIndependentTally() {
		Mask flat = junction(2, 6, 64, 64, 64, 64, 64);
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(flat.cells), MAX_EXACT_WIDTH);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		Tally t = tally(net);
		assertEquals(0, stats.candidateContradictions());
		assertEquals("stats see exactly the violations the tally sees", t.total(), stats.residualViolations());
		assertEquals(t.worst, stats.worstStep());

		RoadNetwork opposed = RoadNetwork.build(
				new ArrayList<>(junction(2, 6, 68, 60, 64, 64, 64).cells), MAX_EXACT_WIDTH);
		RoadSmoother.SolveStats opposedStats = RoadSmoother.solve(opposed, params(Objective.SMOOTH_FIRST));
		Tally opposedTally = tally(opposed);
		assertEquals(opposedTally.total(), opposedStats.residualViolations());
		assertEquals(opposedTally.worst, opposedStats.worstStep());
		// Review 3 left a counted residual here because the crossing seed was a
		// raw average of two legal staircases and therefore not itself legal;
		// review 4 legalises it before it becomes the region's reference, and
		// this shape is now fully solvable. Keeping a "there must be a residual"
		// assertion would pin the defect.
		assertEquals("the opposed-plaza shape is legal now, not merely shallow", 0, opposedStats.residualViolations());
	}

	/**
	 * allowCut = false is an explicit user choice: the road never digs below the
	 * terrain, so an outlier direction that would require cutting keeps a cliff -
	 * but the cliff is COUNTED now instead of silent.
	 *
	 * <p>Review 4 changed how deep that cliff is. It used to be exactly the full
	 * height difference because the band merged the whole seam interval and the
	 * resulting candidate contradiction pinned the arm body at its terrain. With
	 * the seam cone intersecting the band instead, and the seam height feeding
	 * the reference, the arm now stops nearer the junction: the cliff is smaller
	 * but the mode still behaves the way the flag promises, so the assertion is
	 * a bound, not the old exact value.
	 */
	/**
	 * allowCut = false forbids digging below the terrain, not filling above the
	 * low side. The corridor legalisation raises the low arm instead of leaving
	 * the old seam cliff, and the flag's own promise is asserted cell by cell.
	 * (A floor that genuinely cannot be bridged keeps its residual - that case
	 * is pinned in RoadSmootherTest.)
	 */
	@Test
	public void allowCutFalseBridgesTheOutlierByFillingInsteadOfCutting() {
		RoadNetwork net = RoadNetwork.build(
				new ArrayList<>(junction(2, 6, 64, 68, 64, 64, 64).cells), MAX_EXACT_WIDTH);
		RoadSmoother.SolveParams noCut = new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, 4, false);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, noCut);
		Tally t = tally(net);
		assertEquals("the no-cut outlier is bridged by fill", 0, t.seam);
		assertEquals("the verifier sees the same legal profile", 0, stats.residualViolations());
		for (RoadCell cell : net.cells) {
			assertTrue("no-cut never cuts below the terrain", cell.solvedP >= cell.baseP);
		}
	}

	private static Tally tally(RoadNetwork net) {
		BitSet region = regionMask(net);
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