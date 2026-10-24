/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.peoplecanfly.village_path_smoothed.pipeline.BuildingMask;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadWritePolicy;
import dev.peoplecanfly.village_path_smoothed.terrain.FeatureProtection;

/**
 * 2026-10-23: the writer's building test is exact building-body protection.
 * It is shared with the door target selector; a building still wins its
 * protected cells, but the empty part of its old bounding box does not.
 */
public class RoadWritePolicyTest {

	@Test
	public void insideBuildingIsExactAndHasNoYMargin() {
		BuildingMask mask = new BuildingMask();
		mask.addCell(0, 64, 0);
		mask.addCell(0, 65, 0);
		// solvedP 130 = walk 65 -> road block Y 64
		assertTrue(RoadWritePolicy.insideBuilding(mask, 0, 0, 130));
		// solvedP 132 -> road block Y 65
		assertTrue(RoadWritePolicy.insideBuilding(mask, 0, 0, 132));
		// one block below/above is NOT protected any more (no extra margin)
		assertFalse(RoadWritePolicy.insideBuilding(mask, 0, 0, 128));
		assertFalse(RoadWritePolicy.insideBuilding(mask, 0, 0, 134));
		// an empty neighbour cell is not protected
		assertFalse(RoadWritePolicy.insideBuilding(mask, 1, 0, 130));
		assertFalse(RoadWritePolicy.insideBuilding(null, 0, 0, 130));
		assertTrue(RoadWritePolicy.insideBuildingAt(mask, 0, 65, 0));
		assertFalse(RoadWritePolicy.insideBuildingAt(mask, 0, 66, 0));
	}

	@Test
	public void insideFootprintUsesTheProtectedColumnsOnly() {
		BuildingMask mask = new BuildingMask();
		mask.addCell(0, 64, 2);
		assertTrue(RoadWritePolicy.insideFootprint(mask, 0, 2));
		assertFalse(RoadWritePolicy.insideFootprint(mask, 1, 2));
		assertFalse(RoadWritePolicy.insideFootprint(null, 0, 2));
	}

	@Test
	public void decorColumnsOnlyFeedTheDoorExclusionNotPlanA() {
		BuildingMask buildings = new BuildingMask();
		buildings.addCell(10, 64, 0);
		BuildingMask exclusion = buildings.copyWithColumns(new int[][]{{5, 5, 64, 67, 5, 5}});
		// the lamp column excludes door targets ...
		assertTrue(RoadWritePolicy.insideFootprint(exclusion, 5, 5));
		// ... but is not plan-A protection: its actual blocks are protected by
		// the decor set, not by this mask.
		assertFalse(RoadWritePolicy.insideBuilding(exclusion, 5, 5, 130));
		assertFalse(buildings.hasColumn(5, 5));
	}

	@Test
	public void bodyCellsProtectBlocksAndAirEnclosedAlongFiveAxes() {
		// 5x5x5 solid cube with a 3x3x3 air room inside. The room's centre is
		// enclosed along all six axes (wall, wall, floor, roof, wall, wall).
		boolean[][][] room = solidCube(5);
		for (int x = 1; x <= 3; x++) {
			for (int y = 1; y <= 3; y++) {
				for (int z = 1; z <= 3; z++) {
					room[x][y][z] = false;
				}
			}
		}
		int[][] body = BuildingMask.bodyCells(room);
		assertTrue("a normal room's interior air is protected", contains(body, 2, 2, 2));
		assertTrue(contains(body, 0, 0, 0));

		// An air cell at the grid edge only sees four axes blocked when two of
		// its walls are missing: it is NOT interior.
		boolean[][][] notch = solidCube(3);
		notch[0][1][1] = false;
		notch[1][1][1] = false;
		notch[0][0][1] = false;
		body = BuildingMask.bodyCells(notch);
		assertFalse(contains(body, 0, 1, 1));
	}

	@Test
	public void anUnreadableTemplateFallsBackToItsWholeBoxWithoutMargin() {
		BuildingMask mask = new BuildingMask();
		mask.addBox(0, 2, 64, 70, 0, 2);
		assertTrue(mask.protects(0, 64, 0));
		assertTrue(mask.protects(2, 70, 2));
		assertFalse("the fallback box has no extra Y margin either",
				mask.protects(1, 63, 1));
		assertFalse(mask.protects(1, 71, 1));
		assertFalse(mask.protects(3, 64, 1));
	}

	@Test
	public void anEmptyColumnInsideTheOldBoundingBoxStaysWritable() {
		// The user-visible case: a house template's bbox has an empty corner
		// column (no building block, not enclosed). Plan A must not skip it.
		BuildingMask mask = new BuildingMask();
		mask.addCell(0, 64, 0);
		mask.addCell(1, 64, 0);
		mask.addCell(1, 64, 1);
		assertFalse(mask.protects(0, 64, 1));
	}

	private static boolean[][][] solidCube(int size) {
		boolean[][][] solid = new boolean[size][size][size];
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				for (int z = 0; z < size; z++) {
					solid[x][y][z] = true;
				}
			}
		}
		return solid;
	}

	private static boolean contains(int[][] cells, int x, int y, int z) {
		for (int[] cell : cells) {
			if (cell[0] == x && cell[1] == y && cell[2] == z) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void nonTerrainDecorationsWinTheirBlock() {
		// User decision 2026-10-17: road/fill/clear/kerb/ceiling/railing skip a
		// protected decoration and leave it in place.
		assertFalse(RoadWritePolicy.mayOverwrite(true));
		assertTrue(RoadWritePolicy.mayOverwrite(false));

		assertTrue(FeatureProtection.isProtectedDecoration(false, false, false));
		assertFalse(FeatureProtection.isProtectedDecoration(false, false, true));
		assertFalse(FeatureProtection.isProtectedDecoration(true, false, false));
		assertFalse(FeatureProtection.isProtectedDecoration(false, true, false));
	}

	@Test
	public void kerbEndIsOpenOnlyAboveTheCurrentWallTop() {
		// wallTop 64: a cap-cell terrain block at 64 is level with the highest
		// kerb block, so there is no terrain above it; 65 is above the wall top.
		assertTrue(RoadWritePolicy.kerbEndOpen(64, 64));
		assertTrue(RoadWritePolicy.kerbEndOpen(60, 64));
		assertTrue(RoadWritePolicy.kerbEndOpen(Integer.MIN_VALUE, 64));
		assertFalse(RoadWritePolicy.kerbEndOpen(65, 64));
	}

	@Test
	public void aKerbRunCutAtTheCeilingGetsNoCutoffTopRailing() {
		assertTrue(RoadWritePolicy.kerbRailingAllowed(64, 66));
		assertFalse(RoadWritePolicy.kerbRailingAllowed(65, 66));
		assertTrue(RoadWritePolicy.kerbRailingAllowed(65, RoadWritePolicy.NO_CEILING));
	}

	@Test
	public void aThreeByThreeLampLeavesItsEightCellRingWritable() {
		// plains_lamp_1 is a 3x3 bbox with only the centre 1x1 real base. The
		// exact body mask contains the base and leaves all eight ring cells
		// writable (the 2026-10-20 plan-A exclusion plus the 2026-10-23 exact
		// body rule).
		int[][] ring = {
				{0, 0}, {1, 0}, {2, 0},
				{0, 1},         {2, 1},
				{0, 2}, {1, 2}, {2, 2}
		};
		BuildingMask mask = new BuildingMask();
		mask.addCell(1, 64, 1);
		assertTrue(RoadWritePolicy.insideBuilding(mask, 1, 1, 130));
		for (int[] cell : ring) {
			assertFalse("the lamp's empty ring is not a plan-A obstacle",
					RoadWritePolicy.insideBuilding(mask, cell[0], cell[1], 130));
		}
	}

	@Test
	public void aRealBuildingBodyStaysProtectedNextToAnEmptyColumn() {
		BuildingMask mask = new BuildingMask();
		mask.addCell(10, 64, 0); // a wall block of the house at x=10
		assertTrue(RoadWritePolicy.insideBuilding(mask, 10, 0, 130));
		// the empty corner of the same piece's old bbox is free
		assertFalse(RoadWritePolicy.insideBuilding(mask, 11, 0, 130));
	}

	@Test
	public void aSingleLayerKerbWallIsSuppressedOnlyWhenBothProbedCellsAreVacant() {
		// 2026-10-24: the second layer (roadTopBlockY + 2) decides whether a
		// first kerb layer is built at all. The vacancy test follows the kerb
		// probe's own semantics: the far cell's protected decoration counts as
		// "not found" (the wrap loop ignores it the same way), while any block
		// with a collision/fluid - a building block included - is never vacant
		// (that case belongs to the probe-stage building interrupt).
		assertTrue(RoadWritePolicy.singleLayerKerbSuppressed(false, false, false));
		// a tree in the far second-layer cell is "not found" by the probe
		assertTrue(RoadWritePolicy.singleLayerKerbSuppressed(false, true, true));
		// a tree trunk / building block in the near cell has a collision: no
		assertFalse(RoadWritePolicy.singleLayerKerbSuppressed(true, false, false));
		// a building block in the far cell has a collision and is no decoration
		assertFalse(RoadWritePolicy.singleLayerKerbSuppressed(false, true, false));
		// a solid near cell always keeps the wall regardless of the far cell
		assertFalse(RoadWritePolicy.singleLayerKerbSuppressed(true, true, true));
	}
}
