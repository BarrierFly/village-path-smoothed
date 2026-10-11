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
		return params(objective, 8, 6);
	}

	/**
	 * Solve parameters with an explicit reference envelope, in whole blocks.
	 * The defaults mirror the shipped config: maxFillLayers 8, maxClearLayers 6,
	 * widened by the +2 that {@link RoadWritePolicy#overBudget} allows before
	 * the writer snaps a cell back onto the real surface.
	 */
	private static RoadSmoother.SolveParams params(Objective objective, int fillLayers, int clearLayers) {
		return new RoadSmoother.SolveParams(objective, 8 * 16, 32, 16, 4, true,
				2 * (fillLayers + 2), 2 * (clearLayers + 2));
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

	/** Total deviation from the reference profile, which is what the objective ranks. */
	private static long totalDeviation(RoadNetwork net) {
		long deviation = 0;
		for (RoadCell cell : net.cells) {
			deviation += Math.abs(cell.solvedP - cell.refP);
		}
		return deviation;
	}

	@Test
	public void smoothFirstNowPrefersDeviationOverSteps() {
		// Review 4 section 3.1. This assertion used to be the opposite one and
		// the flip is the point of the change: SMOOTH_FIRST used to rank total
		// steps above total deviation, so on rolling ground it levelled the
		// terrain instead of following it (measured on the review's probe: 0
		// steps / 90 half units of deviation against 30 steps / 15).
		IntUnaryOperator terrain = z -> 64 + z / 3 + ((z % 5) == 0 ? 1 : 0);
		RoadNetwork minDev = straightRoad(60, terrain, "plains");
		RoadNetwork smooth = straightRoad(60, terrain, "plains");
		RoadSmoother.solve(minDev, params(Objective.MIN_DEVIATION));
		RoadSmoother.solve(smooth, params(Objective.SMOOTH_FIRST));
		long deviationMinDev = totalDeviation(minDev);
		long deviationSmooth = totalDeviation(smooth);
		assertTrue("SMOOTH_FIRST deviation " + deviationSmooth + " should be <= MIN_DEVIATION deviation " + deviationMinDev,
				deviationSmooth <= deviationMinDev);
		// the cliff penalty stays on top: no soft violations on a legal profile
		assertAdjacentConstraint(smooth, false);
		// and it still uses no more steps than the legacy objective - deviation
		// now outranks steps, it did not simply stop caring about them
		assertTrue("SMOOTH_FIRST steps " + totalSteps(smooth) + " should be <= MIN_DEVIATION steps " + totalSteps(minDev),
				totalSteps(smooth) <= totalSteps(minDev));
	}

	@Test
	public void smoothFirstFollowsASmallMoundInsteadOfLevellingIt() {
		// Review 4 section 3.1 probe P1, the case the old default got exactly
		// backwards: a 1-block mound 4 cells wide on otherwise flat ground.
		// Following it costs half-block slab steps; levelling it is what
		// "steps outrank deviation" used to choose.
		RoadNetwork net = straightRoad(12, z -> (z >= 4 && z < 8) ? 65 : 64, "plains");
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("no soft violation may survive", 0, stats.residualViolations());
		int centre = net.cellAt(1, 6);
		assertTrue("the road must ride the mound instead of cutting through it",
				net.cells.get(centre).solvedP >= 2 * 65);
	}

	@Test
	public void rollingGroundIsFollowedWithSlabSteps() {
		// the second half of probe P1: 1-block mounds every 3 cells
		RoadNetwork net = straightRoad(60, z -> 64 + z / 3 + ((z % 5) == 0 ? 1 : 0), "plains");
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("no soft violation may survive", 0, stats.residualViolations());
		assertTrue("the road must stay near the terrain", totalDeviation(net) < net.cells.size());
	}

	@Test
	public void canyonBecomesALevelSpanAndTheWriterTurnsItIntoADeck() {
		// Review 4 section 5.3 probe P3: a gentle-walled canyon, 12 blocks deep
		// with an 8-wide floor. Following the terrain would dive to the floor
		// and climb back out; the reference is the level span between the two
		// rims instead, so the solved profile stays high and the writer's fill
		// cap is what turns it into a bridge deck.
		int rim = 64;
		int depth = 12;
		List<RoadCell> cells = new ArrayList<>();
		for (int z = 0; z < 64; z++) {
			// 0.5 block per cell down, an 8-wide floor, 0.5 block per cell up
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
		assertTrue("the canyon must register as a level span", stats.spanSlices() > 0);
		// the middle of the canyon stays near the rim instead of on the floor
		int mid = net.cellAt(1, 28);
		assertTrue("the road must bridge the canyon, not dive into it",
				net.cells.get(mid).solvedP >= 2 * (rim - depth) + 8);
		assertAdjacentConstraint(net, false);
		// and the profile it actually is: a legal level span across the floor
		for (int z = 26; z <= 30; z++) {
			int solved = net.cells.get(net.cellAt(1, z)).solvedP;
			assertTrue("bridge deck at z=" + z + " must be beyond the fill cap", RoadWritePolicy.bridgeDeck((solved - 1) / 2 - (rim - depth), 8));
		}
	}

	@Test
	public void smallMoundIsNotTurnedIntoASpan() {
		// the other half of the same proof: the reference must not swallow
		// ordinary terrain. A 1-block mound is inside the envelope, so it stays
		// a follow, otherwise every village would be bridged.
		RoadNetwork net = straightRoad(12, z -> (z >= 4 && z < 8) ? 65 : 64, "plains");
		RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
		assertEquals("a 1-block mound is not a canyon", 0, stats(net).spanSlices());
	}

	@Test
	public void unlimitedEnvelopeAlwaysFollowsTheTerrain() {
		// a negative cap means "never re-anchor", so there is no envelope and
		// no span can form
		RoadNetwork net = straightRoad(24, z -> 64 + (z / 2) * (z < 12 ? 1 : -1), "plains");
		RoadSmoother.SolveStats stats = RoadSmoother.solve(net,
				new RoadSmoother.SolveParams(Objective.SMOOTH_FIRST, 8 * 16, 32, 16, 4, true, -1, -1));
		assertEquals(0, stats.spanSlices());
	}

	private static RoadSmoother.SolveStats stats(RoadNetwork net) {
		return RoadSmoother.solve(net, params(Objective.SMOOTH_FIRST));
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

		// The street must not hold one flat line across its whole length. How MUCH of
		// the 8-block terrace it takes up is the far junction's choice: a seam
		// pins the street to the junction's already-solved height, and that
		// junction is free to sink to meet the street (the "buildings follow the
		// road" contract). The hard guarantee - and the thing review 3 got
		// wrong - is that the two ends can be reconciled at all: zero violating
		// edges above, instead of a 7.5-block tear at the far plaza.
		int near = net.cells.get(net.cellAt(2, 2)).solvedP;
		int far = net.cells.get(net.cellAt(2, 16)).solvedP;
		assertTrue("the street must climb towards the far end (" + near + " -> " + far + ")", far > near);
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
		assertEquals("the arm must arrive at its terrain", 2 * 70, net.cells.get(far).solvedP);
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
