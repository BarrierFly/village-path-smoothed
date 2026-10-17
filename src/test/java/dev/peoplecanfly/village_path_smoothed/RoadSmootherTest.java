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
import dev.peoplecanfly.village_path_smoothed.terrain.BeardCompensation;
import dev.peoplecanfly.village_path_smoothed.terrain.HeightSanity;
import dev.peoplecanfly.village_path_smoothed.terrain.WaterCrossings;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-algorithm tests for the road network decomposition and profile solver
 * (plan section 8): step constraints, the Q01 example, sunken roads, cliffs,
 * wide regions and determinism.
 */
public class RoadSmootherTest {
	private static RoadSmoother.SolveParams params(Objective objective) {
		return new RoadSmoother.SolveParams(objective, 8 * 16, 32, 16, 4, true);
	}

	/** A 3-wide road along z with a terrain height function of z. */
	private static RoadNetwork straightRoad(int length, IntUnaryOperator terrainTopY, String style) {
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < length; z++) {
			int topY = terrainTopY.applyAsInt(z);
			for (int x = 0; x < 3; x++) {
				cells.add(new RoadCell(x, z, style, 2 * topY, false));
			}
		}
		return RoadNetwork.build(cells, 4);
	}

	private static void assertAdjacentConstraint(RoadNetwork net, boolean allowViolations) {
		assertViolationsAtMost(net, allowViolations ? 2 : 0);
	}

	@Test
	public void pinnedCellsSurviveTheConflictSweep() {
		// A pinned cell (a plaza group or a fixed base seat) must never be moved
		// by the polish - before the fix, a conflict between disagreeing pins let
		// the sweep walk the pins themselves. Line: pin 10, free, seed 13, free,
		// pin 14 with no-cut floors. The two pins are four edges apart and differ
		// by exactly four half units, so the road can ramp between them (filling
		// above the low pin's terrain is allowed by the flag); the pins hold and
		// the old residual is gone.
		List<RoadCell> cells = new ArrayList<>();
		int[] baseP = {10, 12, 13, 13, 14};
		for (int x = 0; x < 5; x++) {
			RoadCell cell = new RoadCell(x, 0, "plains", baseP[x], false);
			if (x == 0 || x == 4) {
				cell.plazaPinned = true;
				cell.plazaPinP = baseP[x];
			}
			cells.add(cell);
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.SolveParams noCut = new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, 4, false);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, noCut);
		assertEquals("the low pin holds", 10, net.cells.get(0).solvedP);
		assertEquals("the high pin holds", 14, net.cells.get(4).solvedP);
		// The no-cut floor at x=1 is 12 while the low pin forces at most 11:
		// the flag wins, so this residual is expected and must stay visible.
		assertTrue("the no-cut floor keeps its residual", stats.residualViolations() >= 1);
		for (RoadCell cell : net.cells) {
			assertTrue("no-cut never cuts below the terrain", cell.solvedP >= cell.baseP);
		}
	}

	@Test
	public void waterCellsNeverSolveBelowTheirSurface() {
		// plan E: the water counts as SOLID when the road height is determined -
		// even with allowCutIntoTerrain the solve may not dip a crossing below
		// the water surface. Without the floor SMOOTH_FIRST would flatten the
		// whole crossing down to the bank level (0 violations, 0 steps); with it
		// the profile climbs to the water instead and the crossing stays on it.
		List<RoadCell> cells = new ArrayList<>();
		for (int x = 0; x < 5; x++) {
			boolean water = x == 2;
			cells.add(new RoadCell(x, 0, "plains", water ? 30 : 20, water));
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertTrue("the crossing stays on the water surface", net.cells.get(2).solvedP >= 30);
	}

	@Test
	public void seatCellsSolveToWholeBlockLevelsAndRideTheRoad() {
		// plan 2026-10-16: a seat is a NORMAL solver cell - it rides the solved
		// profile (no vanilla pin) - but renders a FULL block, so its walk
		// surface is always even and the decor piece on top shifts by whole
		// blocks. A seat whose road was chased 14 half units above its vanilla
		// height follows the road; a seat on flat vanilla terrain stays put.
		List<RoadCell> cells = new ArrayList<>();
		for (int x = 0; x < 5; x++) {
			boolean seat = x == 2;
			RoadCell cell = new RoadCell(x, 0, "plains", seat ? 116 : 130, false);
			cell.seat = seat;
			cell.seatBlock = "grass_block";
			cells.add(cell);
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		int seat = net.cells.get(2).solvedP;
		assertEquals("the seat renders a full block (even walk surface)", 0, seat & 1);
		assertTrue("the seat rides the road (within half a block of both neighbours)",
				Math.abs(seat - net.cells.get(1).solvedP) <= 1 && Math.abs(seat - net.cells.get(3).solvedP) <= 1);
		List<RoadCell> flat = new ArrayList<>();
		for (int x = 0; x < 3; x++) {
			RoadCell cell = new RoadCell(x, 0, "plains", 116, false);
			cell.seat = x == 1;
			cell.seatBlock = "grass_block";
			flat.add(cell);
		}
		RoadNetwork flatNet = RoadNetwork.build(flat, 4);
		RoadSmoother.solve(flatNet, params(Objective.SMOOTH_FIRST));
		assertEquals("a seat on flat terrain does not move", 116, flatNet.cells.get(1).solvedP);
	}

	private static void assertViolationsAtMost(RoadNetwork net, int allowed) {
		int violations = 0;
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j > i) {
					int delta = Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP);
					if (delta > 1) {
						violations++;
					}
				}
			}
		}
		assertTrue("too many soft-constraint violations: " + violations, violations <= allowed);
	}

	@Test
	public void flatTerrainStaysAtVanillaHeight() {
		RoadNetwork net = straightRoad(10, z -> 64, "plains");
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		for (RoadCell cell : net.cells) {
			assertEquals(2 * 64, cell.solvedP);
		}
	}

	@Test
	public void gentleSlopeNeverExceedsHalfBlockPerCell() {
		// terrain rises 1 block every 4 cells: the road can follow exactly
		RoadNetwork net = straightRoad(20, z -> 64 + z / 4, "plains");
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		assertAdjacentConstraint(net, false);
		for (RoadCell cell : net.cells) {
			assertTrue("solved surface must stay near terrain", Math.abs(cell.solvedP - cell.baseP) <= 4);
		}
	}

	@Test
	public void q01ExampleMinDeviationSpreadsTheDrop() {
		// plan section 5.3: 20 flat cells at y=10, then a 2-block drop to y=8
		RoadNetwork net = straightRoad(40, z -> z < 20 ? 10 : 8, "plains");
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		assertAdjacentConstraint(net, false);

		long deviationB = 0;
		for (RoadCell cell : net.cells) {
			deviationB += Math.abs(cell.solvedP - cell.baseP);
		}
		RoadNetwork netA = straightRoad(40, z -> z < 20 ? 10 : 8, "plains");
		RoadSmoother.solve(netA, params(Objective.SHORTEST_CATCHUP));
		long deviationA = 0;
		for (RoadCell cell : netA.cells) {
			deviationA += Math.abs(cell.solvedP - cell.baseP);
		}
		// Q01 acceptance: MIN_DEVIATION's total deviation <= SHORTEST_CATCHUP's
		assertTrue("B deviation " + deviationB + " should be <= A deviation " + deviationA, deviationB <= deviationA);
	}

	@Test
	public void persistentSlopeSinksTheRoadAndKerbWallsWouldCoverIt() {
		// terrain rises 1 block per cell for a long stretch: the road can only
		// climb half a block per cell, so it sinks into the hillside (plan 5.2)
		RoadNetwork net = straightRoad(30, z -> 64 + z, "plains");
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		assertAdjacentConstraint(net, false);
		// the road climbs at most half a block per cell along z
		for (int z = 1; z < 30; z++) {
			for (int x = 0; x < 3; x++) {
				int prev = net.cellAt(x, z - 1);
				int cur = net.cellAt(x, z);
				int delta = net.cells.get(cur).solvedP - net.cells.get(prev).solvedP;
				assertTrue("forward step too large: " + delta, delta <= 1);
			}
		}
	}

	@Test
	public void cliffIsCrossedWithMinimalExcess() {
		// a 5-block drop in one cell: geometrically impossible for half steps,
		// so exactly one soft violation of 9 half units (10 drop - 1 allowed) is expected
		RoadNetwork net = straightRoad(6, z -> z < 3 ? 70 : 60, "plains");
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		// each of the 3 columns takes exactly one jump edge across the cliff
		assertViolationsAtMost(net, 3);

		RoadNetwork netA = straightRoad(6, z -> z < 3 ? 70 : 60, "plains");
		RoadSmoother.solve(netA, params(Objective.SHORTEST_CATCHUP));
		assertViolationsAtMost(netA, 3);
	}

	@Test
	public void wideRegionSatisfiesAllLateralConstraints() {
		// 16x16 crossroad core plus 3-wide entrance arms, terrain tilted by 3 blocks
		List<RoadCell> cells = new ArrayList<>();
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				int topY = 64 + (x + z) / 12;
				cells.add(new RoadCell(x, z, "plains", 2 * topY, false));
			}
		}
		for (int z = 8; z < 20; z++) {
			for (int x = -1; x <= 1; x++) {
				cells.add(new RoadCell(x, z, "plains", 2 * 64, false));
			}
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals("16x16 core must decompose into exactly one region", 1, net.regions.size());
		assertTrue("the entrance strip should be an arm", net.arms.size() >= 1);

		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		assertAdjacentConstraint(net, false);
	}

	@Test
	public void waterAnchorsAreSoftAndKeepTheCrossingFlat() {
		// Fix plan v2 R4 (N8): the water surface is the crossing cell's PREFERRED
		// height (its baseP), not a fixed anchor. Dipping to the water and back
		// would cost 8 half-steps per column; holding the terrain level costs
		// only counted deviation, and steps outrank deviation - so the road
		// stays flat and every anchored cell that left the water level is
		// counted in waterAnchorDeviations.
		List<RoadCell> rebuilt = new ArrayList<>();
		for (int z = 0; z < 8; z++) {
			for (int x = 0; x < 3; x++) {
				boolean water = z >= 3 && z <= 5;
				rebuilt.add(new RoadCell(x, z, "plains", water ? 2 * 60 : 2 * 64, water));
			}
		}
		RoadNetwork net2 = RoadNetwork.build(rebuilt, 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net2, params(Objective.SMOOTH_FIRST));
		assertAdjacentConstraint(net2, false);
		for (RoadCell cell : net2.cells) {
			if (cell.anchor) {
				assertEquals("the crossing rides level instead of dipping to the water", 2 * 64, cell.solvedP);
			}
		}
		assertEquals("every anchored cell left the water level", 9, stats.waterAnchorDeviations());
	}

	@Test
	public void solvingIsDeterministic() {
		RoadNetwork first = straightRoad(25, z -> 64 + ((z * 7) % 5) - 2, "savanna");
		RoadNetwork second = straightRoad(25, z -> 64 + ((z * 7) % 5) - 2, "savanna");
		RoadSmoother.solve(first, params(Objective.MIN_DEVIATION));
		RoadSmoother.solve(second, params(Objective.MIN_DEVIATION));
		for (int i = 0; i < first.cells.size(); i++) {
			assertEquals(first.cells.get(i).solvedP, second.cells.get(i).solvedP);
		}
	}

	@Test
	public void fourWideArmStillSolves() {
		// savanna has a 4-wide gradient segment (plan 3.7)
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < 12; z++) {
			int topY = 64 + z / 6;
			for (int x = 0; x < 4; x++) {
				cells.add(new RoadCell(x, z, "savanna", 2 * topY, false));
			}
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals(1, net.arms.size());
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		assertAdjacentConstraint(net, false);
	}

	@Test
	public void floorLevelProbesAreDistrustedOnlyWhenNoiseDisagrees() {
		// report 5-2: the empty-chunk signature is a probe exactly at the
		// dimension floor; genuinely low worlds (superflat) sit above it
		int minY = -64;
		assertTrue(HeightSanity.isExactFloorProbe(minY, minY));
		assertTrue(HeightSanity.isExactFloorProbe(minY - 1, minY));
		assertFalse(HeightSanity.isExactFloorProbe(minY + 1, minY));

		// real probe at the floor while noise disagrees: distrust the real value
		assertTrue(HeightSanity.shouldDistrustRealHeight(minY, minY + 40, minY));
		// no noise height available: never distrust (Integer.MIN_VALUE sentinel)
		assertFalse(HeightSanity.shouldDistrustRealHeight(minY, Integer.MIN_VALUE, minY));
		// noise agrees with a low-but-legit surface (not above it): trust it
		assertFalse(HeightSanity.shouldDistrustRealHeight(minY + 5, minY + 5, minY));
		// a legitimate negative noise height in a min_y=-64 world is NOT the
		// "unavailable" sentinel (the old -1 sentinel WAS this real height)
		assertTrue(HeightSanity.shouldDistrustRealHeight(minY, -24, minY));
		// noise lower than the real read (e.g. carved ground below canopy): trust the real one
		assertFalse(HeightSanity.shouldDistrustRealHeight(minY + 60, minY + 40, minY));
	}

	@Test
	public void extremeBaseHeightsDoNotWrapTheCostModel() {
		// report 5-5: cap-sized base differences used to overflow the
		// lexicographic long packing (penalty*excess*s1*sDev*s2); deviations
		// now saturate, so the solver still produces the cliff pattern instead
		// of garbage driven by wrapped costs
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < 24; z++) {
			int topY = z < 12 ? 1_000_000 : -1_000_000;
			for (int x = 0; x < 3; x++) {
				cells.add(new RoadCell(x, z, "plains", 2 * topY, false));
			}
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params(Objective.MIN_DEVIATION));
		// each of the 3 columns takes exactly one jump edge across the cliff
		assertViolationsAtMost(net, 3);

		List<RoadCell> cellsA = new ArrayList<>();
		for (int z = 0; z < 24; z++) {
			int topY = z < 12 ? 1_000_000 : -1_000_000;
			for (int x = 0; x < 3; x++) {
				cellsA.add(new RoadCell(x, z, "plains", 2 * topY, false));
			}
		}
		RoadNetwork netA = RoadNetwork.build(cellsA, 4);
		RoadSmoother.solve(netA, params(Objective.SHORTEST_CATCHUP));
		assertViolationsAtMost(netA, 3);
	}

	@Test
	public void fillAndClearCapsDecideBridgeDeckAndTunnelRoof() {
		// bridge deck only beyond the cap; negative cap means never
		assertTrue(RoadWritePolicy.bridgeDeck(4, 3));
		assertTrue(RoadWritePolicy.bridgeDeck(100, 3));
		assertFalse(RoadWritePolicy.bridgeDeck(3, 3)); // exactly at the cap: still landfill
		assertFalse(RoadWritePolicy.bridgeDeck(1, 3));
		assertFalse(RoadWritePolicy.bridgeDeck(1000, -1)); // unlimited

		// clear cap bounds the clear loop; negative cap clears up to the surface
		assertEquals(68, RoadWritePolicy.clearTopY(60, 75, 8)); // 60+8 < 74
		assertEquals(74, RoadWritePolicy.clearTopY(60, 75, 100)); // cap above surface: surface wins
		assertEquals(74, RoadWritePolicy.clearTopY(60, 75, -1)); // unlimited
		assertEquals(59, RoadWritePolicy.clearTopY(60, 60, 8)); // nothing to clear

		// tunnel roof only when capped clearing leaves terrain overhead
		assertTrue(RoadWritePolicy.tunnelRoof(60, 75, 68, 8));
		assertFalse(RoadWritePolicy.tunnelRoof(60, 75, 74, 8)); // cleared everything
		assertFalse(RoadWritePolicy.tunnelRoof(60, 75, 74, -1)); // unlimited never roofs
		assertFalse(RoadWritePolicy.tunnelRoof(60, 60, 59, 8)); // nothing above the road
	}

	@Test
	public void beardCompensationOnlyRaisesNearBuildings() {
		int[][] boxes = {{100, 110, 200, 210}}; // one building footprint 11x11
		// inside the footprint and within the radius: compensated
		assertTrue(BeardCompensation.isNearBuilding(boxes, 105, 205, 4));
		assertTrue(BeardCompensation.isNearBuilding(boxes, 99, 205, 4)); // 1 west of the wall
		assertTrue(BeardCompensation.isNearBuilding(boxes, 96, 205, 4)); // exactly at the radius
		assertFalse(BeardCompensation.isNearBuilding(boxes, 95, 205, 4)); // radius+1: outside
		assertFalse(BeardCompensation.isNearBuilding(boxes, 105, 195, 4));
		// diagonal counts by Chebyshev distance: (96,204) sits at (4,4) = on the
		// radius corner, (95,199) at (5,1) = outside
		assertTrue(BeardCompensation.isNearBuilding(boxes, 96, 204, 4));
		assertFalse(BeardCompensation.isNearBuilding(boxes, 95, 199, 4));
		// no boxes / disabled radius: never
		assertFalse(BeardCompensation.isNearBuilding(null, 105, 205, 4));
		assertFalse(BeardCompensation.isNearBuilding(boxes, 105, 205, -1));
		// compensation only raises, never lowers
		assertEquals(66, BeardCompensation.compensate(65, true, 1));
		assertEquals(65, BeardCompensation.compensate(65, false, 1));
		assertEquals(65, BeardCompensation.compensate(65, true, 0));
		assertEquals(65, BeardCompensation.compensate(65, true, -1));
	}

	@Test
	public void waterCrossingPredicateCoversBothBranches() {
		// A2: the old code only recognised the plank branch; both water
		// positions must count as a crossing
		assertTrue(WaterCrossings.isCrossing(true, false)); // water at the road block (planks replaced it)
		assertTrue(WaterCrossings.isCrossing(false, true)); // planks floating on the water below
		assertTrue(WaterCrossings.isCrossing(true, true));
		assertFalse(WaterCrossings.isCrossing(false, false));
	}

	@Test
	public void dropCriteriaUseHalfBlockWalkSurfaces() {
		// A1: whole road block at Y=64 -> walk surface 65.0 = 130 half units;
		// bottom slab at Y=64 -> walk surface 64.5 = 129
		assertEquals(130, RoadWritePolicy.roadWalkSurface2(64, false));
		assertEquals(129, RoadWritePolicy.roadWalkSurface2(64, true));

		int roadWhole = RoadWritePolicy.roadWalkSurface2(64, false); // 130
		int roadSlab = RoadWritePolicy.roadWalkSurface2(64, true);   // 129

		// kerb: side walk surface at least 2 whole blocks above the road
		assertFalse(RoadWritePolicy.kerbApplies(2 * 65, roadWhole, 1)); // 1 above: no
		assertTrue(RoadWritePolicy.kerbApplies(2 * 67, roadWhole, 1));  // 2 above: yes
		assertTrue(RoadWritePolicy.kerbApplies(2 * 67, roadSlab, 1));   // slab: 2.5 above

		// railing: road at least 2 whole blocks above the side
		assertFalse(RoadWritePolicy.railingApplies(2 * 64, roadWhole, 1)); // 1 below: no
		assertTrue(RoadWritePolicy.railingApplies(2 * 63, roadWhole, 1));  // 2 below: yes
	}

	private static long totalSteps(RoadNetwork net) {
		long steps = 0;
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j > i) {
					steps += Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP);
				}
			}
		}
		return steps;
	}

	@Test
	public void smoothFirstMinimisesTotalStepsOverDeviation() {
		// reverted semantics (fix plan v2 R1, N3): SMOOTH_FIRST puts the total
		// step count strictly above the deviation, so on the same terrain it must
		// not produce more steps than the legacy MIN_DEVIATION objective (which
		// weighs deviation and steps in the same rank)
		IntUnaryOperator terrain = z -> 64 + z / 3 + ((z % 5) == 0 ? 1 : 0);
		RoadNetwork minDev = straightRoad(60, terrain, "plains");
		RoadNetwork smooth = straightRoad(60, terrain, "plains");
		RoadSmoother.solve(minDev, params(Objective.MIN_DEVIATION));
		RoadSmoother.solve(smooth, params(Objective.SMOOTH_FIRST));
		long stepsMinDev = totalSteps(minDev);
		long stepsSmooth = totalSteps(smooth);
		assertTrue("SMOOTH_FIRST steps " + stepsSmooth + " should be <= MIN_DEVIATION steps " + stepsMinDev,
				stepsSmooth <= stepsMinDev);
		// the cliff penalty stays on top: no soft violations on a legal profile
		assertAdjacentConstraint(smooth, false);
	}

	@Test
	public void smoothFirstBridgesAPlateauWithFewerStepsThanAnyTerrainFollowingSolution() {
		// Fix plan v2 R1 / A5, the one positive assertion the old test set never
		// had: terrain that makes "hug the terrain" and "stay smooth" pull in
		// OPPOSITE directions. A 4-block plateau crossed by the road: every
		// terrain-following profile must climb 8 half units and come back down
		// (>= 16 steps); the smooth profile crosses flat (0 steps) and pays
		// deviation instead. Steps outrank deviation, so the smooth profile wins.
		IntUnaryOperator terrain = z -> (z >= 10 && z < 20) ? 68 : 64;
		RoadNetwork net = straightRoad(30, terrain, "plains");
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("the plateau crossing must be legal", 0, stats.residualViolations());
		assertTrue("any terrain-following solution needs >= 16 half-steps, smooth needs fewer, was " + totalSteps(net),
				totalSteps(net) < 16);
		int plateau = net.cellAt(1, 15);
		// the road may sit one half unit above flat (a bottom slab is the same
		// step count at less deviation) but must never climb onto the plateau
		assertTrue("the road cuts through the plateau instead of climbing it (was " + net.cells.get(plateau).solvedP + ")",
				net.cells.get(plateau).solvedP <= 2 * 64 + 1);
	}

	@Test
	public void smoothFirstLevelsASmallMoundInsteadOfRidingIt() {
		// the reverted answer to the old probe P1: on a 1-block mound the road
		// holds flat (0 steps) and pays deviation, because steps outrank
		// deviation (fix plan v2 R1, N3). The writer carries the 1-block fill
		// within maxFillLayers; it is not a bridge deck.
		RoadNetwork net = straightRoad(12, z -> (z >= 4 && z < 8) ? 65 : 64, "plains");
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("no soft violation may survive", 0, stats.residualViolations());
		int centre = net.cellAt(1, 6);
		assertTrue("the road must level the mound instead of riding it (was " + net.cells.get(centre).solvedP + ")",
				net.cells.get(centre).solvedP <= 2 * 64);
	}

	@Test
	public void rollingGroundStaysInsideTheTerrainChaseBand() {
		// reverted semantics: short bumps are levelled wherever the terrain
		// chase band still contains the flat line; a sustained grade is followed
		// only when the band forces it (fix plan v2 R1). Either way the road
		// never leaves the band around the terrain chase.
		RoadNetwork net = straightRoad(60, z -> 64 + z / 3 + ((z % 5) == 0 ? 1 : 0), "plains");
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("no soft violation may survive", 0, stats.residualViolations());
		for (RoadCell cell : net.cells) {
			assertTrue("the road stays inside the terrain chase band (cell " + cell.x + "," + cell.z
						+ " solved " + cell.solvedP + " vs base " + cell.baseP + ")",
					Math.abs(cell.solvedP - cell.baseP) <= 16);
		}
	}

	@Test
	public void gentleCanyonIsFollowedLegallyWithoutViolations() {
		// reverted semantics: the canyon walls here descend/ascend 1 half unit
		// per cell, so following them is a legal staircase. The candidate band is
		// built around the terrain chase, so the road dives instead of bridging
		// (bridging was the reference profile's job and is gone with it, fix plan
		// v2 R1). Legal steps only: no violation, no drop above half a block.
		int rim = 64;
		int depth = 12;
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < 64; z++) {
			int offset;
			if (z < 24) {
				offset = z / 2;
			} else if (z < 32) {
				offset = depth;
			} else {
				offset = depth - (z - 32) / 2;
			}
			int topY = rim - offset;
			for (int x = 0; x < 3; x++) {
				cells.add(new RoadCell(x, z, "plains", 2 * topY, false));
			}
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("the dive is a legal staircase", 0, stats.residualViolations());
		int mid = net.cellAt(1, 28);
		assertTrue("the road follows the canyon floor instead of bridging it (was " + net.cells.get(mid).solvedP + ")",
				net.cells.get(mid).solvedP < 2 * rim);
	}

	// ------------------------------------------------------------------
	// review 4 probe bench: the two junction topologies that were still
	// broken after review 3, reproduced as regressions
	// ------------------------------------------------------------------

	/** Two 5x5 junction cores joined by a 3-wide street, terrain per column. */
	private static RoadNetwork twoJunctionsWithStreet(int streetLength, IntUnaryOperator streetTerrain, int plazaAY, int plazaBY) {
		List<RoadCell> cells = new ArrayList<>();
		for (int x = 0; x <= 4; x++) {
			for (int z = -4; z <= 0; z++) {
				cells.add(new RoadCell(x, z, "plains", 2 * plazaAY, false));
			}
		}
		for (int z = 1; z <= streetLength; z++) {
			for (int x = 1; x <= 3; x++) {
				cells.add(new RoadCell(x, z, "plains", 2 * streetTerrain.applyAsInt(z), false));
			}
		}
		int last = streetLength + 1;
		for (int x = 0; x <= 4; x++) {
			for (int z = last; z <= last + 4; z++) {
				cells.add(new RoadCell(x, z, "plains", 2 * plazaBY, false));
			}
		}
		return RoadNetwork.build(cells, 4);
	}

	@Test
	public void probeM2StreetBetweenTwoJunctionsOverATerraceStaysLegal() {
		// Review 4 probe M2: a street clamped between two junctions with an
		// 8-block terrace wall in the middle of it. Review 3 fixed only the
		// single-outlier junction; this topology held a low flat line across
		// the wall and tore the far junction open - 13 violating edges, worst 15
		// half units. The cause was the seam hull collapsing two end seams into
		// one interval, plus an ICM candidate window centred on terrain.
		RoadNetwork net = twoJunctionsWithStreet(17, z -> (z >= 13 ? 72 : 64), 64, 72);
		assertEquals("both junctions must decompose into regions", 2, net.regions.size());
		assertEquals("the street between them must be one arm", 1, net.arms.size());

		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("a street pinned at both ends must not tear the junction", 0, stats.residualViolations());
		assertEquals(0, stats.worstStep());

		// Reverted semantics (fix plan v2 R1): the candidate band is built around
		// the terrain chase, so the street follows its terrain up the terrace and
		// the far junction absorbs the rest ("buildings follow the road"). The
		// hard guarantee is unchanged: zero violating edges above, instead of the
		// 7.5-block tear review 3 produced at the far plaza.
		int near = net.cells.get(net.cellAt(2, 2)).solvedP;
		int far = net.cells.get(net.cellAt(2, 16)).solvedP;
		assertTrue("the street climbs towards the far end (" + near + " -> " + far + ")", far > near);
	}

	@Test
	public void probeM2SurvivesEveryTerraceHeight() {
		// the review measured Delta = 4 absorbing and Delta >= 6 tearing; the
		// whole range must stay clean now
		for (int delta = 2; delta <= 10; delta++) {
			final int step = delta;
			RoadNetwork net = twoJunctionsWithStreet(17, z -> (z >= 13 ? 64 + step : 64), 64, 64 + step);
			RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
			assertEquals("delta " + delta + ": no violating edge", 0, stats.residualViolations());
		}
	}

	@Test
	public void probeM1LongArmOntoHigherTerrainReachesTheTerrain() {
		// Review 4 probe M1: a legal climb that stopped half way. A 20-cell arm
		// leaving a junction at 64 towards terrain 70 needs 12 half units and has
		// 20 cells of room, but the road held at 136 and stayed 2..5.5 blocks
		// below the terrain for the rest of its length - a "legal" profile that
		// simply gave up. Deviation outranking steps is what fixes it.
		RoadNetwork net = twoJunctionsWithStreet(20, z -> 70, 64, 70);
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("a climb that fits must not produce a violation", 0, stats.residualViolations());
		int far = net.cellAt(2, 20);
		// the system may sit one half unit below its terrain (a bottom slab) at
		// an equal step count and less total deviation, so allow slab parity
		assertTrue("the arm must arrive at its terrain (was " + net.cells.get(far).solvedP + ")",
				Math.abs(net.cells.get(far).solvedP - 2 * 70) <= 1);
	}

	@Test
	public void smoothFirstDoesNotWrapTheCostModel() {
		// extreme base heights must not overflow the SMOOTH_FIRST packing either
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < 24; z++) {
			int topY = z < 12 ? 1_000_000 : -1_000_000;
			for (int x = 0; x < 3; x++) {
				cells.add(new RoadCell(x, z, "plains", 2 * topY, false));
			}
		}
		RoadNetwork net = RoadNetwork.build(cells, 4);
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertViolationsAtMost(net, 3);
	}

	@Test
	public void overBudgetMarksUnaffordableCellsForObservation() {
		int groundTopY = 64;
		int ceilingTopY = 64; // an open column has no ceiling above the road
		// defaults: fill cap 3, clear cap 4 (visible cap-formed ceiling)
		assertFalse(RoadWritePolicy.overBudget(2 * groundTopY, groundTopY, ceilingTopY, 3, -1));
		assertFalse(RoadWritePolicy.overBudget(2 * groundTopY + 2 * 5, groundTopY, ceilingTopY, 3, -1)); // exactly cap+2: still affordable
		assertTrue(RoadWritePolicy.overBudget(2 * groundTopY + 2 * 6, groundTopY, ceilingTopY, 3, -1));  // 6 above: over
		// an unlimited (negative) cap never triggers, in either direction
		assertFalse(RoadWritePolicy.overBudget(2 * groundTopY - 2 * 100, groundTopY, ceilingTopY, 3, -1));
		assertFalse(RoadWritePolicy.overBudget(2 * groundTopY + 1000, groundTopY, ceilingTopY, -1, -1));
		// the cut direction reads the ceiling probe, not the ground probe: a road
		// below a hill is over budget only when the ceiling is that far above it
		int buried = 2 * 40; // walk surface far below the ground
		assertFalse("no ceiling above: nothing to cut", RoadWritePolicy.overBudget(buried, groundTopY, 41, 3, 2));
		assertTrue("ceiling 6 blocks above the road", RoadWritePolicy.overBudget(2 * 40, groundTopY, 40 + 6 + 2, 3, 2));
		// the old snap-back parity helper is gone with R3: over-budget cells keep
		// their solved height and are rendered as bridge deck / tunnel instead
	}

	// ------------------------------------------------------------------
	// plan C (user decision 2026-10-15): the plaza pin
	// ------------------------------------------------------------------

	/** A 4x4 plaza attached to a 3-wide street along z, terrain per column. */
	private static RoadNetwork plazaWithStreet(IntUnaryOperator streetTerrain, int[] plazaTerrainTopY) {
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 4; x++) {
				RoadCell cell = new RoadCell(x, z, "plains", 2 * plazaTerrainTopY[z * 4 + x], false);
				cell.plaza = true;
				cell.plazaVanillaP = 2 * (plazaTerrainTopY[z * 4 + x] + 1);
				cells.add(cell);
			}
		}
		for (int z = 4; z < 12; z++) {
			for (int x = 1; x <= 3; x++) {
				cells.add(new RoadCell(x, z, "plains", 2 * streetTerrain.applyAsInt(z), false));
			}
		}
		return RoadNetwork.build(cells, 4);
	}

	@Test
	public void plazaPinsFlatAtItsMedianTerrainAndRoadsMeetItLegally() {
		// plaza terrain rolls 64..67 (median 65/66 boundary); the street climbs
		// away to 70. The pin forces the plaza flat at the median of its baseP,
		// the street solves into |dP| <= 1 against the plaza edge.
		int[] plazaTerrain = {64, 65, 65, 66, 65, 66, 66, 67, 64, 65, 65, 66, 65, 66, 66, 67};
		RoadNetwork net = plazaWithStreet(z -> 70, plazaTerrain);
		assertEquals("one 4-connected plaza group", 1, net.plazaGroups.size());
		net.applyPlazaPins(true);
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));

		int pin = net.cells.get(net.cellAt(1, 1)).plazaPinP;
		for (RoadCell cell : net.cells) {
			if (cell.plaza) {
				assertEquals("the plaza solves flat at the pin", pin, cell.solvedP);
			}
		}
		assertViolationsAtMost(net, 0);
		// the first street cell in front of the plaza is within one half unit of it
		int seam = net.cells.get(net.cellAt(2, 4)).solvedP;
		assertTrue("street seam within a half step of the plaza (" + seam + " vs " + pin + ")",
				Math.abs(seam - pin) <= 1);
	}

	@Test
	public void plazaPinFromVanillaRealityIsWhatTheT5PathAndPhaseTwoUse() {
		int[] plazaTerrain = {64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64};
		RoadNetwork net = plazaWithStreet(z -> 60, plazaTerrain);
		// simulate the pieces rendering one block below the probed terrain
		for (RoadCell cell : net.cells) {
			if (cell.plaza) {
				cell.plazaVanillaP = 2 * 64;
			}
		}
		net.applyPlazaPins(false);
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		int pin = net.cells.get(net.cellAt(1, 1)).plazaPinP;
		assertEquals("pinned at the platform's real walk surface", 2 * 64, pin);
		assertViolationsAtMost(net, 0);
	}
}

