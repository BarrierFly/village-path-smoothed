/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.pipeline.BuildingShift;
import dev.peoplecanfly.village_path_smoothed.pipeline.BuildingShift.Reason;
import dev.peoplecanfly.village_path_smoothed.pipeline.BuildingShift.ShiftDecision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T5 (review 3 section 7): the building-shift decision, extracted as the pure
 * {@link BuildingShift} helper and pinned here. Heights are half-block walk
 * surfaces (P = 2 * walkSurfaceY), the shift is whole blocks.
 *
 * <p>Plan D (user decision 2026-10-15): the alignment target is the door
 * INTERIOR walk surface ({@code baseY + 1}, measured on 6 vanilla templates -
 * the y=0 layer is the floor block, the door opens at y=1), and the single
 * median is replaced by the legal interval: the door floor must stay within
 * half a block of every door road cell, the shift inside the interval closest
 * to zero wins, the median is only the fallback.
 */
public class BuildingShiftTest {
	private static ShiftDecision shift(int[] road, int baseY) {
		return BuildingShift.desiredShift(road, baseY);
	}

	@Test
	public void noAdjacentRoadMeansDoNotMove() {
		ShiftDecision d = shift(new int[0], 64);
		assertEquals(Reason.NO_ROAD, d.reason());
		assertEquals(BuildingShift.DO_NOT_MOVE, d.shift());
		assertFalse(d.doorParityOdd());
		assertFalse(d.viaInterval());
		ShiftDecision nullRoad = shift(null, 64);
		assertEquals(Reason.NO_ROAD, nullRoad.reason());
		assertEquals(BuildingShift.DO_NOT_MOVE, nullRoad.shift());
	}

	@Test
	public void wideSpanFallsBackToTheMedian() {
		// user decision 2026-10-14: the old SPAN_TOO_WIDE refusal is gone - a
		// span too wide for the half-block tolerance falls back to the median
		// scheme instead of leaving the building unserved. {128, 136} = walks
		// {64, 68}: no single door floor is within half a block of both, so the
		// median (136 -> walk 68) decides: shift = 68 - (64 + 1) = 3.
		ShiftDecision wide = shift(new int[]{128, 136}, 64);
		assertEquals(Reason.SERVED, wide.reason());
		assertFalse(wide.viaInterval());
		assertEquals(3, wide.shift());
		assertEquals("the two-wing clamp anchors on this", 3, wide.medianShift());
	}

	@Test
	public void evenRoadAlignsTheDoorInteriorFloorExactly() {
		// baseY=64 -> door interior floor at 65; a flat road at walk 64 aligns
		// by moving the component DOWN one (the old code aligned baseY itself
		// and left the door one whole block above the road - the structural
		// part of the measured +1.5 door offsets)
		ShiftDecision d = shift(new int[]{128, 128, 128}, 64);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.viaInterval());
		assertEquals("door floor 65 -> 64: down one", -1, d.shift());
		// a road two blocks above the old base: the floor follows to 66
		assertEquals(1, shift(new int[]{132, 132, 132}, 64).shift());
		assertEquals("road two below: floor to 62", -3, shift(new int[]{124, 124, 124}, 64).shift());
	}

	@Test
	public void slabRoadKeepsTheBuildingWhereItIsWhenLegal() {
		// median 129 = walk surface 64.5: the door floor at 65 is half a block
		// above it - legal (a half-slab step), and the shift closest to zero is
		// 0. The old code moved the building a whole block and flagged parity;
		// the interval accepts the half step and moves nothing.
		ShiftDecision d = shift(new int[]{129, 129}, 64);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.viaInterval());
		assertEquals(0, d.shift());
		assertFalse("an interval pick never flags parity", d.doorParityOdd());
	}

	@Test
	public void oddMedianParityIsFlaggedOnlyOnTheFallback() {
		// {129, 133} spans 2 whole blocks: the interval is empty, the median
		// (133 -> walk 66.5) decides: shift = round(66.5 - 65) = 2, odd parity
		ShiftDecision d = shift(new int[]{129, 133}, 64);
		assertEquals(Reason.SERVED, d.reason());
		assertFalse(d.viaInterval());
		assertTrue(d.doorParityOdd());
		assertEquals(2, d.shift());
	}

	@Test
	public void intervalPicksTheShiftClosestToZero() {
		// {133, 133} = walk 66.5: legal shifts are {1, 2} (door floor 66 or 67),
		// closest to zero is 1
		ShiftDecision d = shift(new int[]{133, 133}, 64);
		assertTrue(d.viaInterval());
		assertEquals(1, d.shift());
		// a single legal value stays exactly there
		assertEquals(2, shift(new int[]{134, 134}, 64).shift());
		// two slab cells one whole block apart share the legal door floor 65
		assertEquals(0, shift(new int[]{129, 131}, 64).shift());
		// a full block and a slab one apart: only the slab-matched floor fits
		assertEquals(-1, shift(new int[]{128, 129}, 64).shift());
	}

	@Test
	public void oneBlockSpreadOverTheToleranceFallsBack() {
		// {130, 132} = walks {65, 66}: door floor 66 is 1.0 from walk 65 - past
		// the half-block tolerance - so the median (132 -> 66) decides
		ShiftDecision d = shift(new int[]{130, 132}, 64);
		assertFalse(d.viaInterval());
		assertEquals(1, d.shift());
		assertFalse(d.doorParityOdd());
	}

	@Test
	public void medianTakesTheUpperMiddleValue() {
		// the legacy median picks copy[n / 2]: for two values that is the higher
		// one (130 -> walk 65) - pinned so the fallback keeps it verbatim; with
		// the door-interior reference the shift is 65 - 65 = 0
		ShiftDecision d = shift(new int[]{128, 130}, 64);
		assertEquals(Reason.SERVED, d.reason());
		assertFalse(d.viaInterval());
		assertEquals(0, d.shift());
	}

	@Test
	public void negativeHeightsAndShiftsStayExact() {
		// base -31 -> door interior floor at -30; a road at walk -30 is already
		// level with it: no shift
		ShiftDecision d = shift(new int[]{-60, -60, -60}, -31);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.viaInterval());
		assertEquals(0, d.shift());
		// a road one block above: shift up one
		assertEquals(1, shift(new int[]{-58, -58, -58}, -31).shift());
	}

	@Test
	public void twoWingLimitClampsAroundTheMedianShift() {
		// the clamp itself lives in T15Planner; here we pin that medianShift is
		// the legacy anchor it clamps around, for both interval and fallback
		ShiftDecision interval = shift(new int[]{132, 132, 132}, 64);
		assertEquals("median scheme: round(66 - 65) = 1", 1, interval.medianShift());
		assertEquals(1, interval.shift());
		ShiftDecision fallback = shift(new int[]{128, 136}, 64);
		assertEquals(3, fallback.medianShift());
		assertEquals(fallback.medianShift(), fallback.shift());
	}
}
