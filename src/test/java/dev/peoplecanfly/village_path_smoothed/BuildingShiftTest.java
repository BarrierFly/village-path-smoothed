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
		ShiftDecision nullRoad = shift(null, 64);
		assertEquals(Reason.NO_ROAD, nullRoad.reason());
		assertEquals(BuildingShift.DO_NOT_MOVE, nullRoad.shift());
	}

	@Test
	public void doorParityIsFlaggedButNeverRefusedForSpan() {
		// User decision 2026-10-14: the old SPAN_TOO_WIDE refusal ("adjacent road
		// cells span more than 3 blocks, so the building is unservable") is gone -
		// it left the building at the vanilla terrain height beside a smoothed
		// road, which is the symptom it was supposed to avoid. The caller now
		// selects the road cells at the door, so a wide span across the whole
		// footprint can no longer reach this decision.
		ShiftDecision wide = shift(new int[]{128, 136}, 64);
		assertEquals(Reason.SERVED, wide.reason());
		assertEquals("the median of {64, 68} is 68", 4, wide.shift());
	}

	@Test
	public void evenMedianShiftsInWholeBlocks() {
		assertEquals(Reason.SERVED, shift(new int[]{128, 128, 128}, 64).reason());
		assertEquals("flat road at walk 64, base at 64: no shift", 0, shift(new int[]{128, 128, 128}, 64).shift());
		assertEquals("road 2 blocks higher: follow it", 2, shift(new int[]{132, 132, 132}, 64).shift());
		assertEquals("road 2 blocks lower: follow it down", -2, shift(new int[]{124, 124, 124}, 64).shift());
		assertFalse(shift(new int[]{128, 128, 128}, 64).doorParityOdd());
	}

	@Test
	public void slabMedianCountsAsAParityFallback() {
		// median 129 = walk surface 64.5 (bottom slab): the building shifts a
		// whole block onto it and the odd parity is flagged for B3-4
		ShiftDecision d = shift(new int[]{129, 129}, 64);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.doorParityOdd());
		assertEquals("round(64.5 - 64) rounds half up", 1, d.shift());
		// even medians never flag
		assertFalse(shift(new int[]{128, 130}, 64).doorParityOdd());
	}

	@Test
	public void medianTakesTheUpperMiddleValue() {
		// the legacy median picks copy[n / 2]: for two values that is the higher
		// one (130), not the average - pinned so the extraction keeps it verbatim
		ShiftDecision d = shift(new int[]{128, 130}, 64);
		assertEquals(Reason.SERVED, d.reason());
		assertEquals(1, d.shift());
		assertFalse(d.doorParityOdd());
	}

	@Test
	public void negativeHeightsAndShiftsStayExact() {
		ShiftDecision d = shift(new int[]{-60, -60, -60}, -31);
		assertEquals(Reason.SERVED, d.reason());
		assertEquals("walk -30, base -31: up one block", 1, d.shift());
	}
}
