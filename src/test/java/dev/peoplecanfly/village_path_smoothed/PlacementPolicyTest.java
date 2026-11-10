/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.pipeline.PlacementPolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The suppression decision matrix of {@link PlacementPolicy} (fix plan v4
 * section 9). AGENTS.md claims this class is unit tested; until 2026-11-10 that
 * claim was false, so a regression in the hot per-block path was invisible.
 *
 * <p>Audit A-1 adds the row that matters most: a world with the master switch
 * off has no live placement context, and every block must pass through
 * untouched (that is what "off = exactly like vanilla" means).
 */
public class PlacementPolicyTest {

	@Test
	public void onlyY0IsEverARoadLayer() {
		assertFalse(PlacementPolicy.isRoadLayer(1));
		assertFalse(PlacementPolicy.isRoadLayer(-1));
		assertTrue(PlacementPolicy.isRoadLayer(0));
	}

	@Test
	public void anInactiveContextAlwaysPassesThroughEvenOnAClaimedY0Cell() {
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH, PlacementPolicy.decide(false, 0, true));
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH, PlacementPolicy.decide(false, 0, false));
	}

	@Test
	public void aClaimedY0CellInALiveContextIsSuppressed() {
		assertEquals(PlacementPolicy.Decision.SUPPRESS, PlacementPolicy.decide(true, 0, true));
	}

	@Test
	public void anUnclaimedCellKeepsItsVanillaRoad() {
		// a dropped probe column keeps the vanilla block on purpose
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH, PlacementPolicy.decide(true, 0, false));
	}

	@Test
	public void aNonRoadTemplateLayerIsNeverSuppressed() {
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH, PlacementPolicy.decide(true, 1, true));
		assertEquals(PlacementPolicy.Decision.PASS_THROUGH, PlacementPolicy.decide(true, -2, true));
	}
}
