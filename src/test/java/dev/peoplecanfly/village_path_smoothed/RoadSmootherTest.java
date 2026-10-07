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
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.assertEquals;
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
}
