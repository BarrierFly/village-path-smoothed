/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.Objective;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadSmoother;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadWritePolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fix plan v4 A-1 (decision N3-a) and the writer's height policy D6-b.
 *
 * <p>These are the two places where "the profile must never step" and "the writer
 * must never decide a height" are actually enforced, so they are pinned here
 * rather than only through the bench.
 */
public class RoadPolishTest {
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;

	private static RoadSmoother.SolveParams params() {
		return new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, 4, true);
	}

	private static List<RoadCell> street(int length, java.util.function.IntUnaryOperator height) {
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < length; z++) {
			cells.add(new RoadCell(0, z, "plains", 2 * height.applyAsInt(z), false));
		}
		return cells;
	}

	private static int violations(RoadNetwork net) {
		int count = 0;
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j > i && Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP) > 1) {
					count++;
				}
			}
		}
		return count;
	}

	private static int worstStep(RoadNetwork net) {
		int worst = 0;
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j >= 0) {
					worst = Math.max(worst, Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP));
				}
			}
		}
		return worst;
	}

	/** The headline invariant: whatever the terrain does, the profile comes back legal. */
	@Test
	public void thePolishMakesEveryProfileLegal() {
		for (int cliff : new int[]{2, 4, 8, 20, 60}) {
			List<RoadCell> cells = street(40, z -> z < 20 ? 64 : 64 + cliff);
			RoadNetwork net = RoadNetwork.build(cells, 4);
			RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
			assertEquals("a " + cliff + "-block cliff leaves no violating edge", 0, violations(net));
			assertEquals("the stats see the same", 0, stats.residualViolations());
			assertTrue("worst step stays within one half block (" + worstStep(net) + ")", worstStep(net) <= 1);
		}
	}

	/**
	 * Decision N3-a: the polish adopts its result unconditionally. Where the
	 * block pipeline genuinely leaves a residual, the cells it had to drag are
	 * counted rather than hidden.
	 */
	@Test
	public void thePolishIsCountedWhenItMovesCells() {
		// a crossing whose arms disagree by 20 blocks: the shape review 3 found
		// unsatisfiable for the arm DP and the ICM alone
		List<RoadCell> cells = new ArrayList<>();
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				cells.add(new RoadCell(x, z, "plains", 128, false));
			}
		}
		for (int d = 3; d <= 12; d++) {
			for (int w = -1; w <= 1; w++) {
				cells.add(new RoadCell(w, -d, "plains", 128, false));
				cells.add(new RoadCell(w, d, "plains", 168, false));
				cells.add(new RoadCell(-d, w, "plains", 128, false));
				cells.add(new RoadCell(d, w, "plains", 128, false));
			}
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("legal whatever the pipeline left behind", 0, violations(net));
		assertTrue("the outlier arm had to be dragged (" + stats.polishMoved() + ")", stats.polishMoved() > 0);
		assertTrue("the largest move is counted, not hidden (" + stats.polishMaxDelta() + " half units)",
				stats.polishMaxDelta() > 1);
		assertTrue("p90 delta is reported", stats.polishDeltaP90() >= 0);
	}

	/** A profile the optimiser already made legal must not be touched. */
	@Test
	public void aFlatProfileIsNotTouched() {
		RoadNetwork net = RoadNetwork.build(street(30, z -> 64), 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("nothing to do", 0, stats.polishMoved());
		assertEquals(0, stats.polishMaxDelta());
		assertEquals(0, stats.polishConflicts());
	}

	/** Running the solver twice must give the same profile: the polish is a fixed point. */
	@Test
	public void thePolishIsAFixedPoint() {
		List<RoadCell> cells = street(40, z -> z < 20 ? 64 : 76);
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params());
		int[] once = new int[net.cells.size()];
		for (int i = 0; i < once.length; i++) {
			once[i] = net.cells.get(i).solvedP;
		}
		RoadSmoother.solve(net, params());
		int[] twice = new int[net.cells.size()];
		for (int i = 0; i < twice.length; i++) {
			twice[i] = net.cells.get(i).solvedP;
		}
		assertArrayEqualsMsg("re-solving is idempotent", once, twice);
	}

	/** Two components are legalised independently; production declines such a mask (D1-e). */
	@Test
	public void everyComponentIsLegalisedFromItsOwnSeed() {
		List<RoadCell> cells = new ArrayList<>();
		cells.addAll(street(20, z -> 64));
		for (RoadCell cell : street(20, z -> 90)) {
			cells.add(new RoadCell(cell.x + 10, cell.z, cell.style, cell.baseP, false));
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals(2, net.componentCount());
		RoadSmoother.solve(net, params());
		assertEquals("both components are legal on their own", 0, violations(net));
	}

	/**
	 * G1 + G2 (four-item confirmation section 6.3, 2026-10-16): a seat clamped
	 * into a one-odd-value-wide interval keeps its even value one half unit
	 * above the clamp and counts the conflict so the sweep engages. The sweep
	 * cannot repair the seat<->pin edge (both ends immovable), so the residual
	 * must stay VISIBLE: the final verification counts edges against pinned
	 * cells since G2 - the old code skipped every edge of a pinned cell, so
	 * this residual only showed up when the pin happened to sort after the
	 * seat (packedXZ order here puts the pin first, which used to hide it).
	 */
	@Test
	public void aSeatSqueezedByTwoPinsKeepsItsEvenValueAndTheResidualStaysVisible() {
		// pins 130 and 132 differ by exactly one whole block: the seat between
		// them has the single clamp value 131, odd, so no even value fits. F(1,-1)
		// sits at the component's box centre, so IT is the BFS seed - the seat is
		// visited afterwards and clamped against BOTH pins (a seed is never
		// clamped, and without F the seat would be the seed and the squeeze
		// would never fire).
		RoadCell p1 = new RoadCell(0, 0, "plains", 130, false);
		p1.plazaPinned = true;
		p1.plazaPinP = 130;
		RoadCell p2 = new RoadCell(2, 0, "plains", 132, false);
		p2.plazaPinned = true;
		p2.plazaPinP = 132;
		RoadCell seat = new RoadCell(1, 0, "plains", 131, false);
		seat.seat = true;
		seat.seatBlock = "grass_block";
		RoadCell free = new RoadCell(1, -1, "plains", 130, false);
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(List.of(p1, free, seat, p2)), 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("the width-0 even snap counts its conflict", 1, stats.polishConflicts());
		assertEquals("the seat keeps the even walk surface above the clamp", 132, seat.solvedP);
		// the sweep repairs the free side (free -> 131) but cannot move the
		// pinned pin or the seat, so the seat<->pin residual stays and - since
		// G2 - is COUNTED regardless of the pin's index order
		assertEquals("the seat<->plaza residual is visible", 1, stats.residualViolations());
		assertEquals("the residual is exactly one whole block", 2, stats.worstStep());
		assertEquals("the independent edge walk sees the same edge", 1, violations(net));
	}

	/**
	 * G1 corner from the other side: when the even above still fits the clamp
	 * (interval wider than one value) the snap is legal and must NOT count a
	 * conflict - the counter is reserved for genuine width-0 squeezes.
	 */
	@Test
	public void anEvenSnapInsideTheClampCountsNoConflict() {
		// pins 130 and 131 leave the seat the clamp [130, 131]; its DP value
		// (even, near baseP 133) clamps to the odd 131 and snaps down to 130 -
		// INSIDE the clamp, so no conflict may be counted. F keeps the seat from
		// being the BFS seed (a seed is never clamped).
		RoadCell p1 = new RoadCell(0, 0, "plains", 130, false);
		p1.plazaPinned = true;
		p1.plazaPinP = 130;
		RoadCell p2 = new RoadCell(2, 0, "plains", 131, false);
		p2.plazaPinned = true;
		p2.plazaPinP = 131;
		RoadCell seat = new RoadCell(1, 0, "plains", 133, false);
		seat.seat = true;
		seat.seatBlock = "grass_block";
		RoadCell free = new RoadCell(1, -1, "plains", 130, false);
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(List.of(p1, free, seat, p2)), 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("the even snap fits the clamp - no conflict", 0, stats.polishConflicts());
		assertEquals("the seat sits on the even value inside the clamp", 130, seat.solvedP);
		assertEquals("the profile is legal", 0, stats.residualViolations());
	}

	private static void assertArrayEqualsMsg(String message, int[] expected, int[] actual) {
		org.junit.Assert.assertArrayEquals(message, expected, actual);
	}

	// ------------------------------------------------------------------
	// writer height policy (D6-b)
	// ------------------------------------------------------------------

	@Test
	public void theProfileColumnCarriesTheSolvedSurface() {
		RoadWritePolicy.Column whole = RoadWritePolicy.profileColumn(140, MIN_Y, MAX_Y);
		assertTrue(whole.written());
		assertFalse(whole.overrodeSolved());
		assertEquals("a whole block at Y=69", 140, whole.walkSurface2());
		assertFalse(whole.slab());

		RoadWritePolicy.Column slab = RoadWritePolicy.profileColumn(141, MIN_Y, MAX_Y);
		assertTrue(slab.slab());
		assertEquals(141, slab.walkSurface2());
		assertEquals(70, RoadWritePolicy.roadBlockY(slab.walkSurface2(), true));
	}

	/** An out-of-world height is refused, never clamped: clamping was the writer deciding. */
	@Test
	public void aHeightOutsideTheWorldIsRefusedNotClamped() {
		RoadWritePolicy.Column column = RoadWritePolicy.profileColumn(-200, MIN_Y, MAX_Y);
		assertFalse(column.written());
		assertEquals(RoadWritePolicy.UNWRITTEN, column.walkSurface2());
	}

	/** The single-cell nudge: legal only when it cannot step and is not at a door. */
	@Test
	public void theNudgeIsOnlyTakenWhenItCannotStep() {
		int solved = 138; // walk surface 69.0, two half units below the nudge target
		RoadWritePolicy.Column legal = RoadWritePolicy.surfaceNudgeColumn(solved, 70, MIN_Y, MAX_Y,
				new int[]{140, 141, 139}, false);
		assertTrue("the real surface is a legal height for these neighbours", legal.written());
		assertEquals("the nudge writes the real surface", 140, legal.walkSurface2());
		assertTrue("and it is reported as an override of the solution", legal.overrodeSolved());

		RoadWritePolicy.Column step = RoadWritePolicy.surfaceNudgeColumn(solved, 70, MIN_Y, MAX_Y,
				new int[]{130}, false);
		assertFalse("a neighbour 5 blocks away would step", step.written());

		RoadWritePolicy.Column atDoor = RoadWritePolicy.surfaceNudgeColumn(solved, 70, MIN_Y, MAX_Y,
				new int[]{140}, true);
		assertFalse("never beside a building entrance", atDoor.written());

		assertTrue("with no neighbours the nudge is legal",
				RoadWritePolicy.surfaceNudgeColumn(solved, 70, MIN_Y, MAX_Y, null, false).written());
	}

	/**
	 * User decision 2026-10-15: a water crossing is never below the water surface.
	 * A profile at or below it is raised to the water surface (the scaffold sits
	 * there); a profile above it keeps its solved surface and becomes a bridge deck.
	 */
	@Test
	public void aWaterCrossingIsNeverBelowTheWaterSurface() {
		// profile at the water surface: unchanged
		assertEquals("at the water surface stays", 140, RoadWritePolicy.crossingWrittenWalkSurface2(140, 140));
		// profile below water: raised to the water surface
		assertEquals("below water is raised", 140, RoadWritePolicy.crossingWrittenWalkSurface2(138, 140));
		// profile above water (even a slab half a unit up): kept, bridge deck
		assertEquals("above water is kept", 141, RoadWritePolicy.crossingWrittenWalkSurface2(141, 140));
		assertEquals("well above water is kept", 150, RoadWritePolicy.crossingWrittenWalkSurface2(150, 140));
	}

	/** The neighbour-legality rule itself, stated on its own. */
	@Test
	public void neighbourLegalityIsHalfABlock() {
		assertTrue("one half unit away is still legal", RoadWritePolicy.nudgeKeepsNeighboursLegal(100, new int[]{99, 100, 101}));
		assertFalse("two half units away is a step", RoadWritePolicy.nudgeKeepsNeighboursLegal(100, new int[]{98}));
		assertTrue("no neighbours cannot violate anything", RoadWritePolicy.nudgeKeepsNeighboursLegal(100, null));
		assertTrue("an empty neighbour list is not a violation", RoadWritePolicy.nudgeKeepsNeighboursLegal(100, new int[0]));
	}
}