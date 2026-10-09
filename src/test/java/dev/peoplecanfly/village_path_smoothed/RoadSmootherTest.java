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
	public void waterAnchorsStayFixed() {
		RoadNetwork net = straightRoad(8, z -> 64, "plains");
		RoadCell anchor = null;
		for (RoadCell cell : net.cells) {
			if (cell.z == 4 && cell.x == 1) {
				anchor = cell;
			}
		}
		List<RoadCell> rebuilt = new ArrayList<>();
		for (RoadCell cell : net.cells) {
			rebuilt.add(new RoadCell(cell.x, cell.z, cell.style, cell.baseP, cell == anchor ? true : false));
		}
		RoadNetwork net2 = RoadNetwork.build(rebuilt, 4);
		RoadSmoother.solve(net2, params(Objective.MIN_DEVIATION));
		for (RoadCell cell : net2.cells) {
			if (cell.anchor) {
				assertEquals("water crossing cells are fixed to the water surface", cell.baseP, cell.solvedP);
			}
		}
		assertAdjacentConstraint(net2, false);
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

		// wall: side drops at least wallMinDropHeight (default 3) below the road
		assertFalse(RoadWritePolicy.wallApplies(2 * 63, roadWhole, 3)); // 2 below: no
		assertTrue(RoadWritePolicy.wallApplies(2 * 62, roadWhole, 3));  // 3 below: yes
		assertFalse(RoadWritePolicy.wallApplies(2 * 50, roadWhole, -1)); // disabled
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
		// A7: SMOOTH_FIRST puts the total step count strictly above the
		// deviation, so on the same terrain it must not produce more steps than
		// the legacy MIN_DEVIATION objective (which weighs deviation and steps
		// in the same rank)
		IntUnaryOperator terrain = z -> 64 + z / 3 + ((z % 5) == 0 ? 1 : 0);
		RoadNetwork minDev = straightRoad(60, terrain, "plains");
		RoadNetwork smooth = straightRoad(60, terrain, "plains");
		RoadSmoother.solve(minDev, params(Objective.MIN_DEVIATION));
		RoadSmoother.solve(smooth, params(Objective.SMOOTH_FIRST));
		long stepsMinDev = totalSteps(minDev);
		long stepsSmooth = totalSteps(smooth);
		assertTrue("SMOOTH_FIRST steps " + stepsSmooth + " should be <= MIN_DEVIATION steps " + stepsMinDev, stepsSmooth <= stepsMinDev);
		// the cliff penalty stays on top: no soft violations on a legal profile
		assertAdjacentConstraint(smooth, false);
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
	public void fallbackReanchorUsesCorrectParityAndBudget() {
		int topY = 64;
		// fill cap 8, clear cap 6: a target more than 10 blocks above/below is over budget
		assertFalse(RoadWritePolicy.overBudget(2 * topY, topY, 8, 6));
		assertFalse(RoadWritePolicy.overBudget(2 * topY + 2 * 10, topY, 8, 6)); // exactly cap+2: still affordable
		assertTrue(RoadWritePolicy.overBudget(2 * topY + 2 * 11, topY, 8, 6));  // 11 above: over
		assertTrue(RoadWritePolicy.overBudget(2 * topY - 2 * 9, topY, 8, 6));   // 9 below: over (clear cap 6)
		// unlimited direction never triggers
		assertFalse(RoadWritePolicy.overBudget(2 * topY + 1000, topY, -1, 6));
		assertFalse(RoadWritePolicy.overBudget(2 * topY - 1000, topY, 8, -1));
		// parity: even P -> whole block, odd P -> bottom slab
		assertFalse(RoadWritePolicy.fallbackSlab(2 * topY));
		assertTrue(RoadWritePolicy.fallbackSlab(2 * topY - 1));
	}
}
