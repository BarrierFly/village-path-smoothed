/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.terrain.BeardCompensation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T4: the real vanilla structure beard next to the mod's step approximation.
 *
 * <p>Villages declare {@code terrain_adaptation: beard_thin}
 * ({@code data/minecraft/worldgen/structure/village_plains.json} and the four
 * other styles), so the branch that matters is
 * {@code Beardifier#compute -> case BEARD_THIN -> getBeardContribution(m, q, n, p) * 0.8}
 * on guardian {@code origin/parchment/vineflower} (1.21.11).
 *
 * <p>Substituting the kernel table collapses to a closed form. With
 * {@code m} = outward distance from the piece footprint in x, {@code n} the
 * same in z, {@code p} = y - (box.minY + groundLevelDelta) and
 * {@code e = m^2 + n^2 + (p + 0.5)^2}:
 *
 * <pre>
 *   beard = -0.8 * (p + 0.5) / sqrt(2 * e) * exp(-e / 16)
 * </pre>
 *
 * <p>Zero outside the 24^3 kernel: {@code |m| >= 12}, {@code |n| >= 12} or
 * {@code p} outside {@code [-12, 11]}. The shape is therefore a
 * Gaussian-times-inverse-distance bump: a 1/d field inside a Gaussian ball of
 * radius ~8, mirrored with opposite sign about the plane {@code p = -0.5}.
 *
 * <p>The values are vanilla DENSITY units. How many blocks one density unit is
 * worth is NOT established by this test, so only shapes, signs, ratios and
 * comparisons against the mod's block-valued approximation are asserted - never
 * an absolute height.
 */
public class BeardReferenceTest {
	/** House footprint used by the reference profiles: 11x11, base at y=60. */
	private static final int[] HOUSE = {100, 110, 200, 210, 60, 71};
	private static final int[] HOUSE_XZ = {100, 110, 200, 210};
	private static final int BASE = 60;
	/** Row used for the horizontal profiles: just outside the wall, on the axis. */
	private static final int PROBE_X = 100;
	private static final int PROBE_Z = 205;

	/** The closed form derived from {@code Beardifier}. */
	private static double beardThin(int[] box, int x, int y, int z) {
		int m = Math.max(0, Math.max(box[0] - x, x - box[1]));
		int n = Math.max(0, Math.max(box[2] - z, z - box[3]));
		int p = y - box[4]; // groundLevelDelta 0
		if (m >= 12 || n >= 12 || p < -12 || p > 11) {
			return 0.0;
		}
		double d = p + 0.5;
		double e = (double) (m * m) + (double) (n * n) + d * d;
		return -0.8 * d / Math.sqrt(2.0 * e) * Math.exp(-e / 16.0);
	}

	private static double atRoadLevel(int metresOut, int heightAboveBase) {
		return beardThin(HOUSE, PROBE_X - metresOut, BASE + heightAboveBase, PROBE_Z);
	}

	private static int modCompensation(int x, int z) {
		return BeardCompensation.compensate(64, BeardCompensation.isNearBuilding(new int[][]{HOUSE_XZ}, x, z, 4), 1) - 64;
	}

	// ------------------------------------------------------------------
	// the real field
	// ------------------------------------------------------------------

	@Test
	public void beardIsNegativeAtRoadLevelAndPositiveBelowTheBase() {
		assertTrue("at the piece's ground level the terrain is pulled DOWN", atRoadLevel(0, 0) < 0.0);
		assertTrue("one block above the base it is still negative", atRoadLevel(0, 1) < 0.0);
		assertTrue("six blocks above the base it is still negative", atRoadLevel(0, 6) < 0.0);
		assertTrue("below the base the beard fills the terrain up", atRoadLevel(0, -1) > 0.0);
		assertTrue("far below the base it is still filling", atRoadLevel(0, -6) > 0.0);
	}

	/** The sign flip is exactly at p = -0.5, not at the base plane. */
	@Test
	public void beardIsMirrorSymmetricAboutHalfABlockBelowTheBase() {
		for (int p = 0; p <= 6; p++) {
			double above = beardThin(HOUSE, 103, BASE + p, PROBE_Z);
			double below = beardThin(HOUSE, 103, BASE - 1 - p, PROBE_Z);
			assertEquals("p=" + p + " mirrors p=" + (-1 - p), 0.0, above + below, 1e-12);
		}
	}

	@Test
	public void beardHasAHardSupportOfTwelveBlocks() {
		assertTrue("11 blocks out is still inside the kernel", beardThin(HOUSE, 89, BASE, PROBE_Z) != 0.0);
		assertEquals("12 blocks out is outside the kernel", 0.0, beardThin(HOUSE, 88, BASE, PROBE_Z), 0.0);
		assertEquals("the kernel only spans 12 blocks above the base", 0.0, beardThin(HOUSE, 105, BASE + 12, PROBE_Z), 0.0);
	}

	@Test
	public void horizontalReachAtTheBasePlaneIsVeryShort() {
		// the mod pays a full +1 block out to 4 blocks; vanilla is already at
		// ~5% of its peak there and ~1% at 6
		assertTrue("r=1 keeps about 42%", Math.abs(atRoadLevel(1, 0) / atRoadLevel(0, 0) - 0.420) < 0.01);
		assertTrue("r=2 keeps about 19%", Math.abs(atRoadLevel(2, 0) / atRoadLevel(0, 0) - 0.189) < 0.01);
		assertTrue("r=3 keeps about 9%", Math.abs(atRoadLevel(3, 0) / atRoadLevel(0, 0) - 0.094) < 0.01);
		assertTrue("r=4 keeps about 5%", Math.abs(atRoadLevel(4, 0) / atRoadLevel(0, 0) - 0.046) < 0.01);
		assertTrue("r=6 is under 1%", Math.abs(atRoadLevel(6, 0) / atRoadLevel(0, 0)) < 0.01);
	}

	@Test
	public void horizontalReachGrowsWithTheHeightAboveTheBase() {
		// the same column measured a few blocks higher sees a much wider bump,
		// which is why a purely horizontal radius cannot model it
		assertTrue("at +3 blocks the r=2 share rises above 60%",
				Math.abs(atRoadLevel(2, 3) / atRoadLevel(0, 3)) > 0.65);
		assertTrue("at +3 blocks the r=4 share rises above 20%",
				Math.abs(atRoadLevel(4, 3) / atRoadLevel(0, 3)) > 0.20);
		assertTrue("and the same distance carries more weight there",
				Math.abs(atRoadLevel(2, 3)) > Math.abs(atRoadLevel(2, 0)));
		assertTrue("the peak of |beard| along the vertical sits a few blocks above the base",
				Math.abs(atRoadLevel(0, 3)) < Math.abs(atRoadLevel(0, 0))
						&& Math.abs(atRoadLevel(0, 6)) < Math.abs(atRoadLevel(0, 0)));
	}

	@Test
	public void beardIsRoundNotSquare() {
		// 4 blocks out on the diagonal against 4 blocks out on the axis
		double axis = beardThin(HOUSE, 96, BASE, PROBE_Z);
		double corner = beardThin(HOUSE, 96, BASE, 196);
		assertTrue("the corner is far weaker than the axis", Math.abs(corner) < Math.abs(axis) * 0.35);
		assertEquals("the mod pays the same +1 in both places", modCompensation(96, PROBE_Z), modCompensation(96, 196));
	}

	@Test
	public void beardSumsOverPieces() {
		int[] second = {118, 128, 200, 210, 60, 71};
		double alone = beardThin(HOUSE, 114, BASE, PROBE_Z);
		double both = alone + beardThin(second, 114, BASE, PROBE_Z);
		assertTrue("two symmetric houses fill the gap between them twice as hard", both < alone);
		assertEquals("vanilla adds the contributions", 2 * alone, both, 1e-12);
	}

	// ------------------------------------------------------------------
	// the mod's approximation against it
	// ------------------------------------------------------------------

	@Test
	public void modCompensationIsAFlatCeilingNotAShape() {
		for (int r = 0; r <= 4; r++) {
			assertTrue("r " + r + " is compensated", BeardCompensation.isNearBuilding(new int[][]{HOUSE_XZ}, PROBE_X - r, PROBE_Z, 4));
			assertEquals("r " + r + " gets exactly +1 block", 1, modCompensation(PROBE_X - r, PROBE_Z));
		}
		assertFalse("r 5 is not compensated", BeardCompensation.isNearBuilding(new int[][]{HOUSE_XZ}, PROBE_X - 5, PROBE_Z, 4));
	}

	@Test
	public void modRingIsAStepWhereVanillaIsSmooth() {
		double inside = atRoadLevel(4, 0);
		double outside = atRoadLevel(5, 0);
		assertTrue("vanilla still has a tail just outside the mod's radius", outside < 0.0);
		assertTrue("vanilla drops by about half across the mod's ring",
				Math.abs((inside - outside) / inside - 0.54) < 0.03);
		assertEquals("the mod drops 100% of its compensation there", 1, modCompensation(96, PROBE_Z));
		assertEquals(0, modCompensation(95, PROBE_Z));
	}

	@Test
	public void modCompensationCannotSeeTheVerticalSide() {
		// the same column is +1 no matter which side of the base plane it sits
		// on, while vanilla fills below the plane and cuts above it
		assertEquals(1, modCompensation(PROBE_X, PROBE_Z));
		assertTrue("vanilla disagrees between the two heights",
				atRoadLevel(0, -1) * atRoadLevel(0, 3) < 0.0);
	}

	@Test
	public void modCompensationDoesNotAddUpOverHouses() {
		int[][] two = {HOUSE_XZ, {118, 128, 200, 210}};
		int mod = BeardCompensation.compensate(64, BeardCompensation.isNearBuilding(two, 114, PROBE_Z, 4), 1) - 64;
		double vanilla = beardThin(HOUSE, 114, BASE, PROBE_Z) + beardThin(new int[]{118, 128, 200, 210, 60, 71}, 114, BASE, PROBE_Z);
		assertEquals("one block for any number of houses", 1, mod);
		assertTrue("vanilla adds them up", Math.abs(vanilla) > Math.abs(beardThin(HOUSE, 114, BASE, PROBE_Z)));
	}
}