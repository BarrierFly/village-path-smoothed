/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
