/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

import dev.peoplecanfly.village_path_smoothed.pipeline.RoadWritePolicy;
import dev.peoplecanfly.village_path_smoothed.terrain.FeatureProtection;

/**
 * Door/basis fix plan 2026-10-17 section 3.3: the writer's building test is
 * shared with the door target selector; a building still wins its column, and a
 * vanilla side street inside its 2D footprint is never a door front.
 */
public class RoadWritePolicyTest {
	// {minX, maxX, minY, maxY, minZ, maxZ}
	private static final int[][] BOXES = {{0, 2, 64, 70, 0, 2}};

	@Test
	public void insideBuildingMatchesTheWriterBoxAndYRange() {
		// solvedP 130 = walk 65 -> road block Y 64, inside the box
		assertTrue(RoadWritePolicy.insideBuilding(BOXES, 1, 1, 130));
		// one block below the box bottom still counts (the writer expands by 1)
		assertTrue(RoadWritePolicy.insideBuilding(BOXES, 1, 1, 128));
		// far below the basement: a road passing underneath is not skipped
		assertFalse(RoadWritePolicy.insideBuilding(BOXES, 1, 1, 100));
		// outside the footprint
		assertFalse(RoadWritePolicy.insideBuilding(BOXES, 3, 1, 130));
		assertFalse(RoadWritePolicy.insideBuilding(null, 1, 1, 130));
	}

	@Test
	public void insideFootprintIgnoresY() {
		assertTrue(RoadWritePolicy.insideFootprint(BOXES, 0, 2));
		assertTrue(RoadWritePolicy.insideFootprint(BOXES, 2, 0));
		assertFalse(RoadWritePolicy.insideFootprint(BOXES, 3, 2));
		assertFalse(RoadWritePolicy.insideFootprint(null, 1, 1));
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
	public void aThreeByThreeLampIsRemovedFromPlanASoItsEightCellRingIsWritten() {
		// plains_lamp_1 is a 3x3 bbox with only the centre 1x1 real base. Keeping
		// its bbox in plan A skips all eight ring columns (the reported hole).
		int[][] lampBox = {{0, 2, 64, 67, 0, 2}};
		int[][] ring = {
				{0, 0}, {1, 0}, {2, 0},
				{0, 1},         {2, 1},
				{0, 2}, {1, 2}, {2, 2}
		};
		for (int[] cell : ring) {
			assertTrue("the lamp bbox would cover a ring cell",
					RoadWritePolicy.insideBuilding(lampBox, cell[0], cell[1], 130));
		}
		int[][] withoutDecor = RoadWritePolicy.selectPlanABoxes(lampBox, new int[]{7}, Set.of(7));
		assertEquals(0, withoutDecor.length);
		for (int[] cell : ring) {
			assertFalse("a recognised decor piece must not be a plan-A obstacle",
					RoadWritePolicy.insideBuilding(withoutDecor, cell[0], cell[1], 130));
		}
	}

	@Test
	public void planASelectionKeepsRealBuildingsNextToTheDecor() {
		int[][] boxes = {
				{0, 2, 64, 67, 0, 2},   // lamp, owned by piece 7
				{10, 12, 64, 70, 0, 2}  // house, owned by piece 8
		};
		int[][] kept = RoadWritePolicy.selectPlanABoxes(boxes, new int[]{7, 8}, Set.of(7));
		assertEquals(1, kept.length);
		assertTrue(RoadWritePolicy.insideBuilding(kept, 10, 0, 130));
		assertFalse(RoadWritePolicy.insideBuilding(kept, 1, 1, 130));
	}
}
