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
 *
 * <p>Plan E (user decision 2026-10-15, door two-symptom recheck): the anchor
 * is the template's real door block (not the junction), the reference is the
 * Y0 layer (vanilla designs the foundation to face the road), and every door
 * road cell presents its SOLID level - a slab counts as air and presents the
 * level below, so the house's Y0 layer sits half a block below a slab road.
 * All targets are whole-block levels: the interval is a single exact value or
 * empty, and the parity fallback flag cannot fire any more.
 */
public class BuildingShiftTest {
	private static ShiftDecision shift(int[] road, int baseWalkY) {
		return BuildingShift.desiredShift(road, baseWalkY);
	}

	@Test
	public void noAdjacentRoadMeansDoNotMove() {
		ShiftDecision d = shift(new int[0], 65);
		assertEquals(Reason.NO_ROAD, d.reason());
		assertEquals(BuildingShift.DO_NOT_MOVE, d.shift());
		assertFalse(d.doorParityOdd());
		assertFalse(d.viaInterval());
		ShiftDecision nullRoad = shift(null, 65);
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
		ShiftDecision wide = shift(new int[]{128, 136}, 65);
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
		ShiftDecision d = shift(new int[]{128, 128, 128}, 65);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.viaInterval());
		assertEquals("door floor 65 -> 64: down one", -1, d.shift());
		// a road two blocks above the old base: the floor follows to 66
		assertEquals(1, shift(new int[]{132, 132, 132}, 65).shift());
		assertEquals("road two below: floor to 62", -3, shift(new int[]{124, 124, 124}, 65).shift());
	}

	@Test
	public void slabRoadCountsAsAirAndSitsTheFloorHalfBelow() {
		// plan E: the slab cells (129 = walk 64.5) count as AIR and present the
		// level below (target 128 = walk 64) - the door floor aligns exactly onto
		// it, half a block below the slab's walk surface, moving the building
		// down one. The old plan D rule kept the building where it was (floor
		// half ABOVE the slab); the user decision flipped it ("让房屋底层比半砖路低半格").
		ShiftDecision d = shift(new int[]{129, 129}, 65);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.viaInterval());
		assertEquals("floor 65 -> 64: the slab is air", -1, d.shift());
		assertFalse("an interval pick never flags parity", d.doorParityOdd());
	}

	@Test
	public void slabSpanFallsBackToTheMedianOfWholeLevels() {
		// {129, 133} are slabs at walks 64.5/66.5; plan E converts them to the
		// solid levels {128, 132} (2 whole blocks apart): the interval is empty,
		// the median of the TARGETS (132 -> floor 66) decides: shift = 66 - 65 =
		// 1. The parity flag cannot fire any more - every target is a whole
		// block, so the floor always matches exactly on the fallback.
		ShiftDecision d = shift(new int[]{129, 133}, 65);
		assertEquals(Reason.SERVED, d.reason());
		assertFalse(d.viaInterval());
		assertFalse("whole-block targets never flag parity (plan E)", d.doorParityOdd());
		assertEquals(1, d.shift());
	}

	@Test
	public void intervalPicksTheShiftClosestToZero() {
		// {133, 133} = slabs at walk 66.5 -> solid targets 132: the floor goes to
		// 66 exactly, shift 1
		ShiftDecision d = shift(new int[]{133, 133}, 65);
		assertTrue(d.viaInterval());
		assertEquals(1, d.shift());
		// a single legal value stays exactly there
		assertEquals(2, shift(new int[]{134, 134}, 65).shift());
		// two slab cells one whole block apart (walks 64.5/65.5) convert to
		// targets {128, 130} - no single floor fits both, so the median of the
		// targets (130) decides: shift 0 via the fallback, not the interval
		ShiftDecision slabs = shift(new int[]{129, 131}, 65);
		assertEquals(0, slabs.shift());
		assertFalse(slabs.viaInterval());
		// a full block and a slab one apart: the slab converts to the full
		// block's level, the floor matches it exactly
		assertEquals(-1, shift(new int[]{128, 129}, 65).shift());
	}

	@Test
	public void oneBlockSpreadOverTheToleranceFallsBack() {
		// {130, 132} = walks {65, 66}: door floor 66 is 1.0 from walk 65 - past
		// the half-block tolerance - so the median (132 -> 66) decides
		ShiftDecision d = shift(new int[]{130, 132}, 65);
		assertFalse(d.viaInterval());
		assertEquals(1, d.shift());
		assertFalse(d.doorParityOdd());
	}

	@Test
	public void medianTakesTheUpperMiddleValue() {
		// the legacy median picks copy[n / 2]: for two values that is the higher
		// one (130 -> walk 65) - pinned so the fallback keeps it verbatim; with
		// the door-interior reference the shift is 65 - 65 = 0
		ShiftDecision d = shift(new int[]{128, 130}, 65);
		assertEquals(Reason.SERVED, d.reason());
		assertFalse(d.viaInterval());
		assertEquals(0, d.shift());
	}

	@Test
	public void negativeHeightsAndShiftsStayExact() {
		// base -31 -> door interior floor at -30; a road at walk -30 is already
		// level with it: no shift
		ShiftDecision d = shift(new int[]{-60, -60, -60}, -30);
		assertEquals(Reason.SERVED, d.reason());
		assertTrue(d.viaInterval());
		assertEquals(0, d.shift());
		// a road one block above: shift up one
		assertEquals(1, shift(new int[]{-58, -58, -58}, -30).shift());
	}

	@Test
	public void baseWalkYCarriesAnyGroundLevelDelta() {
		// door/basis fix plan 2026-10-17 D1: the caller passes
		// minY + getGroundLevelDelta() directly. A template whose delta is 2
		// (minY 70 -> walk 72) must align like any other base walk level; no
		// hidden +1 is added here.
		assertEquals(0, shift(new int[]{144, 144}, 72).shift());
		assertEquals(-2, shift(new int[]{140, 140}, 72).shift());
	}

	@Test
	public void secondAlignmentAddsOnlyTheDifference() {
		assertEquals(0, BuildingShift.additionalShift(BuildingShift.DO_NOT_MOVE, 3));
		assertEquals(0, BuildingShift.additionalShift(3, 3));
		assertEquals(2, BuildingShift.additionalShift(5, 3));
		assertEquals(-4, BuildingShift.additionalShift(-1, 3));
	}

	@Test
	public void solidTargetPresentsTheLevelBelowASlab() {
		// plan E: a full road block presents its own top walk surface; a bottom
		// slab counts as air and presents the walk surface of the block below
		assertEquals(128, BuildingShift.solidTargetP(128));
		assertEquals(128, BuildingShift.solidTargetP(129));
		assertEquals(130, BuildingShift.solidTargetP(131));
		assertEquals(0, BuildingShift.solidTargetP(0));
		assertEquals(0, BuildingShift.solidTargetP(1));
		assertEquals(-60, BuildingShift.solidTargetP(-60));
		assertEquals(-60, BuildingShift.solidTargetP(-59));
	}

	@Test
	public void componentBaseYExcludesTheOneByOneDecorPiece() {
		// R2-A (2026-10-16): a "house + light" 2-piece component used to take the
		// upper median, i.e. the light's minY one or two above the house. The
		// 1x1 piece is excluded: the house's minY is the base.
		assertEquals("house 10, 1x1 decor 11 -> 10",
				10, BuildingShift.componentBaseY(new int[]{10, 11}, new boolean[]{false, true}));
		assertEquals("house 10, 1x1 decor 12 -> 10",
				10, BuildingShift.componentBaseY(new int[]{10, 12}, new boolean[]{false, true}));
		// two real pieces and one decor: the median of the two real pieces
		// ({10, 12}, upper median) - the decor's 13 is ignored
		assertEquals(12, BuildingShift.componentBaseY(new int[]{10, 12, 13}, new boolean[]{false, false, true}));
		// no 1x1 piece: plain upper median (unchanged behaviour)
		assertEquals(12, BuildingShift.componentBaseY(new int[]{10, 12, 13}, new boolean[]{false, false, false}));
		// every piece 1x1 (vanilla has no such building): the whole set is used
		assertEquals(11, BuildingShift.componentBaseY(new int[]{10, 11}, new boolean[]{true, true}));
	}

	@Test
	public void twoWingLimitClampsAroundTheMedianShift() {
		// the clamp itself lives in T15Planner; here we pin that medianShift is
		// the legacy anchor it clamps around, for both interval and fallback
		ShiftDecision interval = shift(new int[]{132, 132, 132}, 65);
		assertEquals("median scheme: round(66 - 65) = 1", 1, interval.medianShift());
		assertEquals(1, interval.shift());
		ShiftDecision fallback = shift(new int[]{128, 136}, 65);
		assertEquals(3, fallback.medianShift());
		assertEquals(fallback.medianShift(), fallback.shift());
	}
}
