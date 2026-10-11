/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.pipeline.PlacementPolicy;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadReference;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the two pure classes review 4 added:
 * {@link RoadReference} (the mechanism that makes "follow the mound" and
 * "bridge the canyon" the same code path) and {@link PlacementPolicy} (the
 * one decision of the cure route, review 4 section 9).
 */
public class RoadReferenceTest {
	private static final int FILL2 = 2 * (8 + 2);  // maxFillLayers 8, plus the re-anchor slack
	private static final int CLEAR2 = 2 * (6 + 2); // maxClearLayers 6, plus the re-anchor slack

	@Before
	public void resetCounters() {
		RoadReference.resetCounters();
	}

	private static RoadReference.Profile reference(int[] terrain) {
		return reference(terrain, null);
	}

	private static RoadReference.Profile reference(int[] terrain, Integer[] pins) {
		boolean[] pinned = new boolean[terrain.length];
		int[] pinValue = new int[terrain.length];
		if (pins != null) {
			for (int s = 0; s < terrain.length; s++) {
				if (pins[s] != null) {
					pinned[s] = true;
					pinValue[s] = pins[s];
				}
			}
		}
		return RoadReference.forArm(terrain, pinned, pinValue, FILL2, CLEAR2);
	}

	private static boolean legalStaircase(int[] values) {
		for (int i = 1; i < values.length; i++) {
			if (Math.abs(values[i] - values[i - 1]) > 1) {
				return false;
			}
		}
		return true;
	}

	@Test
	public void flatTerrainIsFollowedExactly() {
		int[] terrain = new int[20];
		java.util.Arrays.fill(terrain, 128);
		RoadReference.Profile profile = reference(terrain);
		assertEquals(0, profile.spanSlices());
		for (int value : profile.ref()) {
			assertEquals(128, value);
		}
	}

	@Test
	public void oneBlockMoundIsFollowedNotBridged() {
		// 1 block = 2 half units, well inside the envelope. The reference is a
		// legal staircase, so it enters the mound at one half unit per slice and
		// only reaches the top in the middle of it - that lag is the point, a
		// reference that jumped would not be a profile the DP could ever place.
		int[] terrain = {128, 128, 128, 130, 130, 130, 128, 128, 128};
		RoadReference.Profile profile = reference(terrain);
		assertEquals("a 1-block mound must stay a follow", 0, profile.spanSlices());
		assertEquals(129, profile.ref()[3]);  // stepping up
		assertEquals(130, profile.ref()[4]);  // on top
		assertEquals(130, profile.ref()[5]);
		assertEquals(129, profile.ref()[6]);  // stepping down
		assertTrue("the reference must be a legal staircase", legalStaircase(profile.ref()));
	}

	@Test
	public void canyonTwelveBlocksDeepBecomesALevelSpanAtTheRim() {
		// 0.5 block per cell down, an 8-wide floor, 0.5 block per cell up; the
		// rims are both at 128 so the baseline is flat there
		int[] terrain = new int[64];
		for (int z = 0; z < 64; z++) {
			int offset;
			if (z < 24) {
				offset = z / 2;
			} else if (z < 32) {
				offset = 12;
			} else {
				offset = 12 - (z - 32) / 2;
			}
			terrain[z] = 2 * (64 - offset);
		}
		RoadReference.Profile profile = reference(terrain);
		assertTrue("the canyon must be a span", profile.spanSlices() > 0);
		for (int[] ref : new int[][]{profile.ref()}) {
			for (int value : ref) {
				assertTrue("every reference value stays at or above the rim minus the clear cap",
						value >= 2 * 52 - CLEAR2);
			}
		}
		assertTrue("the reference must be a legal staircase", legalStaircase(profile.ref()));
		assertEquals("a fully bridgeable span never has to keep a drop", 0, profile.illegalDrops());
	}

	@Test
	public void terraceBetweenTwoPlazasBecomesALegalRamp() {
		// review 4 probe M2: flat at 64 for 17 slices, then 72 - the arm is
		// pinned at both ends by the two junction regions
		int[] terrain = new int[18];
		java.util.Arrays.fill(terrain, 128);
		for (int z = 17; z < 18; z++) {
			terrain[z] = 144;
		}
		Integer[] pins = new Integer[18];
		pins[0] = 128;
		pins[17] = 144;
		RoadReference.Profile profile = reference(terrain, pins);
		assertTrue("the reference must be a legal staircase", legalStaircase(profile.ref()));
		assertEquals("the pinned ends must be honoured exactly", 128, profile.ref()[0]);
		assertEquals(144, profile.ref()[17]);
		// and it must actually climb: the old default held the whole street at
		// a low level and dropped the far junction 7.5 blocks below its own plaza
		assertTrue("the reference must ramp, not flatten", profile.ref()[9] > 128);
	}

	@Test
	public void hardValuesWinOverTheTerrain() {
		// both ends pinned 6 blocks above the terrain: the terrain is inside the
		// clear envelope, so the reference dips towards it, but it can never
		// reach the low value and come back in five slices
		int[] terrain = {128, 128, 128, 128, 128};
		Integer[] pins = new Integer[5];
		pins[0] = 140;
		pins[4] = 140;
		RoadReference.Profile profile = reference(terrain, pins);
		assertEquals("a hard value is not negotiable", 140, profile.ref()[0]);
		assertEquals(140, profile.ref()[4]);
		assertTrue("and the interior never dips below both of them", profile.ref()[2] >= 128);
	}

	@Test
	public void anUnjoinablePairOfPinsKeepsTheDropAndCountsIt() {
		// 40 half units apart over 3 slices: geometrically impossible, so both
		// interior slices fall back to the near side and both are counted
		int[] terrain = {128, 128, 128, 128};
		Integer[] pins = new Integer[4];
		pins[0] = 128;
		pins[3] = 168;
		RoadReference.Profile profile = reference(terrain, pins);
		assertEquals(2, profile.illegalDrops());
		assertTrue(RoadReference.illegalDrops() >= 1);
	}

	@Test
	public void unlimitedEnvelopeNeverFormsASpan() {
		int[] terrain = new int[32];
		for (int z = 0; z < 32; z++) {
			terrain[z] = 2 * (64 + (z < 16 ? -(16 - z) : (z - 16)));
		}
		RoadReference.Profile profile = RoadReference.forArm(terrain, new boolean[32], new int[32], -1, -1);
		assertEquals(0, profile.spanSlices());
	}

	@Test
	public void emptyInputIsHandled() {
		RoadReference.Profile profile = reference(new int[0]);
		assertEquals(0, profile.ref().length);
		assertEquals(0, profile.spanSlices());
	}

	// ------------------------------------------------------------------
	// PlacementPolicy (review 4 section 9)
	// ------------------------------------------------------------------

	@Test
	public void claimedRoadLayerIsSuppressed() {
		assertEquals(PlacementPolicy.Decision.SUPPRESS,
				PlacementPolicy.decide(true, 0, true));
	}

	@Test
	public void unclaimedCellsKeepTheirVanillaRoad() {
		// decision 2 of the review: cells the probe dropped stay vanilla, which
		// is the only thing keeping a failed probe from becoming a hole
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH,
				PlacementPolicy.decide(true, 0, false));
	}

	@Test
	public void noContextMeansNoBehaviourChange() {
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH,
				PlacementPolicy.decide(false, 0, true));
	}

	@Test
	public void onlyTheRoadLayerIsSuppressed() {
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH,
				PlacementPolicy.decide(true, 1, true));
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH,
				PlacementPolicy.decide(true, -1, true));
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH,
				PlacementPolicy.decide(true, 7, true));
	}

	@Test
	public void theDecisionNeverSuppressesAnythingWithoutBothConditions() {
		// exhaustive matrix: suppression needs the context AND y==0 AND a claim
		for (boolean context : new boolean[]{false, true}) {
			for (int y : new int[]{-2, -1, 0, 1, 2}) {
				for (boolean claimed : new boolean[]{false, true}) {
					PlacementPolicy.Decision actual = PlacementPolicy.decide(context, y, claimed);
					PlacementPolicy.Decision expected = context && y == 0 && claimed
							? PlacementPolicy.Decision.SUPPRESS
							: PlacementPolicy.Decision.PASS_THROUGH;
					assertEquals("context=" + context + " y=" + y + " claimed=" + claimed, expected, actual);
				}
			}
		}
		assertFalse(RoadReference.illegalDrops() > 0);
	}
}