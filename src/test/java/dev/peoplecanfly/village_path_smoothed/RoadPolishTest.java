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

	/**
	 * 2026-10-22: a structure-ground overlap is one flat MOVABLE unit, so the
	 * solve can shift farm/fixture surfaces and the roads together. The unit
	 * must stay flat and on an even (full-block) surface.
	 */
	@Test
	public void aGroundOverlapUnitMovesFlatAndEven() {
		List<RoadCell> cells = street(12, z -> z < 6 ? 64 : 68);
		RoadCell a = cells.get(5);
		RoadCell b = cells.get(6);
		a.groundOverlap = true;
		b.groundOverlap = true;
		a.groundGroupKey = RoadNetwork.sceneryGroupKey(1);
		b.groundGroupKey = RoadNetwork.sceneryGroupKey(1);
		a.groundGroupMovable = true;
		b.groundGroupMovable = true;
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals("both overlap cells form one unit", 1, net.groundOverlapGroups.size());
		RoadSmoother.solve(net, params());
		assertEquals("the unit is flat", a.solvedP, b.solvedP);
		assertEquals("the unit stays on an even walk surface", 0, a.solvedP & 1);
		assertEquals("the profile is still legal", 0, violations(net));
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

	/**
	 * Audit 2026-10-21: a multi-block decor's FULL base footprint (including its
	 * air columns over road) is one movable flat unit. The polish must flatten it
	 * to one even level and still legalise the road it meets - this is what the
	 * old per-actual-block pin could not guarantee at a terrace edge.
	 */
	@Test
	public void aDecorBaseGroupIsFlatEvenAndLegal() {
		List<RoadCell> cells = new ArrayList<>();
		// The base footprint: three road cells at different terrain heights.
		for (int x = 0; x < 3; x++) {
			cells.add(new RoadCell(x, 0, "plains", 2 * (64 + 2 * x), false));
		}
		// A lower road band in front of the fixture.
		for (int x = -2; x <= 4; x++) {
			cells.add(new RoadCell(x, 1, "plains", 2 * 61, false));
			cells.add(new RoadCell(x, 2, "plains", 2 * 61, false));
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		int[] group = {net.cellAt(0, 0), net.cellAt(1, 0), net.cellAt(2, 0)};
		net.decorBaseGroups.add(new RoadNetwork.DecorBaseGroup(0, group));
		net.decorBaseGroupsMovable = true;

		RoadSmoother.solve(net, params());

		int level = net.cells.get(group[0]).solvedP;
		assertTrue("the base group levels on an even walk surface (" + level + ")", (level & 1) == 0);
		for (int idx : group) {
			assertEquals("the full base footprint is flat", level, net.cells.get(idx).solvedP);
		}
		assertEquals("the base group and the road meet legally", 0, violations(net));
		assertTrue("the profile stays within half a block (" + worstStep(net) + ")", worstStep(net) <= 1);
	}

	/**
	 * User decision 2026-10-21: a seat connected to the road by a shared side is
	 * a normal 4-adjacency edge, so the solver must keep the pair within half a
	 * block (the seat's flat/even shape constraints still apply).
	 */
	@Test
	public void aSeatConnectedToTheRoadStaysWithinHalfABlock() {
		List<RoadCell> cells = street(10, z -> 64);
		RoadCell seat = new RoadCell(1, 5, "plains", 2 * 70, false);
		seat.seat = true;
		seat.seatBlock = "grass_block";
		cells.add(seat);
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params());
		assertEquals("the side-adjacent seat is one more 4-adjacency edge", 0, violations(net));
		assertTrue("the pair stays within half a block", worstStep(net) <= 1);
	}

	/**
	 * 2026-10-21: a fixture base is a pinned non-road cell. The solver must
	 * re-solve the four roads around it within half a block of the base, even
	 * when the west side sits one half-block lower and the other three sides
	 * one block higher (the lamp-base corner reported in game).
	 */
	@Test
	public void aPinnedFixtureBasePullsTheSurroundingRoadsWithinHalfABlock() {
		List<RoadCell> cells = new ArrayList<>();
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				if (x == 0 && z == 0) {
					continue;
				}
				int walk = x < 0 ? 168 : (x > 0 || z != 0 ? 169 : 168);
				cells.add(new RoadCell(x, z, "plains", 2 * walk, false));
			}
		}
		RoadCell fixture = new RoadCell(0, 0, "plains", 2 * 168, false);
		fixture.fixtureBase = true;
		fixture.plazaPinned = true;
		fixture.plazaPinP = 2 * 168;
		cells.add(fixture);
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params());
		assertEquals("the pinned fixture is one more 4-adjacency edge", 0, violations(net));
		assertTrue("the four sides stay within half a block (" + worstStep(net) + ")", worstStep(net) <= 1);
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
	 * G1 + G2 (four-item confirmation section 6.3, 2026-10-16; refined by the
	 * 2026-10-17 seam-sign findings): a seat clamped into a one-odd-value-wide
	 * interval has NO even value that fits. It must still render an even walk
	 * surface and must take the even side CLOSEST to the adjacent road, not the
	 * old unconditional "even above". Here the free neighbour (130) and the low
	 * pin (130) are the nearer road, so the seat lands on 130 instead of 132.
	 * The sweep cannot repair the seat<->pin edge (both ends immovable for that
	 * edge), so the residual stays VISIBLE and counted.
	 */
	@Test
	public void aSeatSqueezedByTwoPinsTakesTheNearerEvenValueAndKeepsTheResidualVisible() {
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
		assertEquals("the seat takes the nearer even walk surface (the road side)", 130, seat.solvedP);
		// the sweep repairs the free side but cannot move the pinned pin or the
		// seat, so one seat<->pin residual stays and - since G2 - is COUNTED
		// regardless of the pin's index order
		assertEquals("the seat<->pin residual is visible", 1, stats.residualViolations());
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

	/**
	 * 2026-10-17 seam-sign fix: two plaza groups close together used to be pinned
	 * independently, so the single road cell between them could not be within
	 * half a block of both - the sign said PLAZA PIN. A T1.5 phase-1 group is not
	 * placed yet, so the whole group moves; the pairwise path constraint is
	 * {@code |A - B| <= distance} (even levels), and the middle cell bridges it.
	 */
	@Test
	public void twoMovablePlazaGroupsCloseTogetherAreCoordinatedBeforePlacement() {
		RoadCell left = new RoadCell(0, 0, "plains", 130, false);
		left.plaza = true;
		left.plazaPinned = true;
		left.plazaPinP = 130;
		left.plazaVanillaP = 130;
		RoadCell middle = new RoadCell(1, 0, "plains", 150, false);
		RoadCell right = new RoadCell(2, 0, "plains", 140, false);
		right.plaza = true;
		right.plazaPinned = true;
		right.plazaPinP = 140;
		right.plazaVanillaP = 140;
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(List.of(left, middle, right)), 4);
		net.plazaPinsMovable = true;
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("the plaza pair is reconciled", 0, stats.residualViolations());
		assertEquals("the left group stays flat", left.plazaPinP, left.solvedP);
		assertEquals("the right group stays flat", right.plazaPinP, right.solvedP);
		assertTrue("the two plaza levels are within the one-cell path", Math.abs(left.solvedP - right.solvedP) <= 2);
		assertEquals("the middle road cell bridges both seams", 0, violations(net));
	}

	/** Two adjacent full-block seats are ONE flat unit: a whole-block step between them is illegal. */
	@Test
	public void adjacentBareSeatsShareOneEvenLevel() {
		RoadCell a = new RoadCell(0, 0, "plains", 130, false);
		a.seat = true;
		a.seatBlock = "grass_block";
		RoadCell b = new RoadCell(1, 0, "plains", 138, false);
		b.seat = true;
		b.seatBlock = "grass_block";
		RoadCell road = new RoadCell(1, 1, "plains", 134, false);
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(List.of(a, b, road)), 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("the seats are flat", a.solvedP, b.solvedP);
		assertEquals("and even (full-block walk surfaces)", 0, a.solvedP & 1);
		assertEquals("no seat residual", 0, stats.residualViolations());
		assertEquals("the free road is legal too", 0, violations(net));
	}

	/** A seat next to a plaza takes the reachable even level on the plaza side. */
	@Test
	public void aSeatBesideAPlazaLandsOnAReachableEvenLevel() {
		RoadCell plaza = new RoadCell(0, 0, "plains", 130, false);
		plaza.plaza = true;
		plaza.plazaPinned = true;
		plaza.plazaPinP = 130;
		plaza.plazaVanillaP = 130;
		RoadCell seat = new RoadCell(1, 0, "plains", 140, false);
		seat.seat = true;
		seat.seatBlock = "grass_block";
		RoadCell road = new RoadCell(2, 0, "plains", 138, false);
		RoadNetwork net = RoadNetwork.build(new ArrayList<>(List.of(plaza, seat, road)), 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertTrue("the seat joins the plaza smoothly", Math.abs(seat.solvedP - plaza.solvedP) <= 1);
		assertEquals("the seat is even", 0, seat.solvedP & 1);
		assertEquals("no residual", 0, stats.residualViolations());
	}

	/**
	 * 2026-10-17 seam-sign fix for POLISH RESID: a cycle of ordinary road cells
	 * could leave a free<->free step after the old BFS+projection. The corridor
	 * legalisation has a feasible envelope, so no ordinary cell may keep one.
	 */
	@Test
	public void anOrdinaryRoadCycleLeavesNoFreeResidual() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(0, 0, "plains", 120, false));
		cells.add(new RoadCell(1, 0, "plains", 126, false));
		cells.add(new RoadCell(0, 1, "plains", 126, false));
		cells.add(new RoadCell(1, 1, "plains", 120, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params());
		assertEquals("no ordinary free<->free residual", 0, stats.residualViolations());
		assertEquals("and the independent walk agrees", 0, violations(net));
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