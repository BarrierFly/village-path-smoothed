/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.terrain.ColumnScan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The two directional column probes (user decision 2026-10-14). They replace the
 * single heightmap read the writer used for the fill/cut and the kerb/railing
 * decisions, so a canopy above an open road no longer fakes a fill and an
 * overhang above a solid one no longer fakes a cut.
 */
public class ColumnScanTest {
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 319;

	/** solid(y) = true inside [lo, hi] inclusive. */
	private static java.util.function.IntPredicate band(int lo, int hi) {
		return y -> y >= lo && y <= hi;
	}

	@Test
	public void downwardProbeFindsTheGroundUnderTheRoad() {
		// open air from 70 down to 64, ground block at 63 -> "first air" is 64
		assertEquals(64, ColumnScan.downwardSurfaceTopY(band(MIN_Y, 63), 70, MIN_Y));
		// the road block itself is standable (a cut into terrain): no fill expected
		assertEquals(64, ColumnScan.downwardSurfaceTopY(band(MIN_Y, 63), 63, MIN_Y));
		// a bridge: air at the road block, ground two blocks down -> surface 66
		assertEquals(66, ColumnScan.downwardSurfaceTopY(band(MIN_Y, 65), 68, MIN_Y));
	}

	@Test
	public void downwardProbeSkipsACanopy() {
		// leaves at 70..72, real ground at 63: starting at the canopy top, the
		// first standable block scanning down is the leaf at 72
		assertEquals(73, ColumnScan.downwardSurfaceTopY(band(MIN_Y, 72), 72, MIN_Y));
		// starting below the canopy the leaves are not seen at all
		assertEquals(64, ColumnScan.downwardSurfaceTopY(band(MIN_Y, 63), 69, MIN_Y));
	}

	@Test
	public void downwardProbeReturnsTheWorldFloorWhenNothingIsStandable() {
		assertEquals(MIN_Y, ColumnScan.downwardSurfaceTopY(y -> false, 70, MIN_Y));
	}

	@Test
	public void upwardProbeFindsTheCeilingOfTheSolidRun() {
		// solid 64..70, air above: the ceiling surface (first air) is 71
		assertEquals(71, ColumnScan.upwardSurfaceTopY(band(64, 70), 64, MAX_Y));
		// already air above the road: nothing to clear, the surface is the road top
		assertEquals(64, ColumnScan.upwardSurfaceTopY(band(MIN_Y, 63), 64, MAX_Y));
		// a cave: solid 64..66, air 67..69, solid 70..75 - the contiguous run stops
		// at 66, so the probe does not reach the roof above the gap
		assertEquals(67, ColumnScan.upwardSurfaceTopY(y -> (y >= 64 && y <= 66) || (y >= 70 && y <= 75), 64, MAX_Y));
	}

	@Test
	public void upwardSolidTopIsTheInclusiveTopOfTheRun() {
		assertEquals(70, ColumnScan.upwardSolidTopY(band(64, 70), 64, MAX_Y));
		assertEquals(63, ColumnScan.upwardSolidTopY(band(MIN_Y, 63), 64, MAX_Y));
	}
}
