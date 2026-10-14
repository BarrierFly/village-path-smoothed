/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.pipeline.ProbeHeights;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadSmoother;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Fix plan v4 D1: mask integrity - a failed probe must not cost a column, and a
 * split mask is solved per component instead of being declined whole (D1-e,
 * reworked 2026-10-14).
 */
public class RoadMaskIntegrityTest {
	private static final int MIN_Y = -64;
	private static final int UNAVAILABLE = Integer.MIN_VALUE;

	/** A usable primary probe is taken as-is; the noise caliber is not even asked for. */
	@Test
	public void aGoodProbeWins() {
		assertEquals(ProbeHeights.Outcome.USE_PRIMARY, ProbeHeights.resolve(70, 71, MIN_Y, UNAVAILABLE));
		assertEquals(ProbeHeights.Outcome.USE_PRIMARY, ProbeHeights.resolve(70, UNAVAILABLE, MIN_Y, UNAVAILABLE));
	}

	/**
	 * The D1-c rule: an unavailable, thrown or floor-level primary probe falls
	 * back to the noise caliber. Dropping the column here is what used to split
	 * the mask.
	 */
	@Test
	public void aFailedProbeFallsBackToNoiseBeforeDropping() {
		assertEquals("probe threw / no random state",
				ProbeHeights.Outcome.USE_NOISE, ProbeHeights.resolve(UNAVAILABLE, 70, MIN_Y, UNAVAILABLE));
		assertEquals("lazily-primed empty chunk",
				ProbeHeights.Outcome.USE_NOISE, ProbeHeights.resolve(MIN_Y, 70, MIN_Y, UNAVAILABLE));
		assertEquals("below the floor is just as suspect",
				ProbeHeights.Outcome.USE_NOISE, ProbeHeights.resolve(MIN_Y - 3, 70, MIN_Y, UNAVAILABLE));
	}

	/** Only when the noise caliber is unusable too does the column actually go. */
	@Test
	public void theColumnIsOnlyDroppedWhenNothingIsLeft() {
		assertEquals(ProbeHeights.Outcome.DROP, ProbeHeights.resolve(UNAVAILABLE, UNAVAILABLE, MIN_Y, UNAVAILABLE));
		assertEquals("a noise height at the floor is no height",
				ProbeHeights.Outcome.DROP, ProbeHeights.resolve(UNAVAILABLE, MIN_Y, MIN_Y, UNAVAILABLE));
	}

	/** A superflat world sits a few blocks above the floor and must keep its real heights. */
	@Test
	public void aLowButLegitimateSurfaceIsNotDistrusted() {
		assertEquals(ProbeHeights.Outcome.USE_PRIMARY, ProbeHeights.resolve(MIN_Y + 2, MIN_Y + 9, MIN_Y, UNAVAILABLE));
	}

	// ------------------------------------------------------------------
	// connectivity (D1-e)
	// ------------------------------------------------------------------

	private static List<RoadCell> row(int from, int to) {
		List<RoadCell> cells = new ArrayList<>();
		for (int x = from; x <= to; x++) {
			cells.add(new RoadCell(x, 0, "plains", 128, false));
		}
		return cells;
	}

	@Test
	public void aContiguousMaskIsOneComponent() {
		RoadNetwork net = RoadNetwork.build(row(0, 20), 4);
		assertTrue(net.isConnected());
		assertEquals(1, net.componentCount());
		assertEquals(1.0, net.largestComponentRatio(), 0.0);
		assertEquals("no break to report", 0, net.widestBreak());
	}

	@Test
	public void aGapSplitsTheMaskAndIsMeasured() {
		// 0..9 then 13..22: three missing columns
		List<RoadCell> cells = new ArrayList<>(row(0, 9));
		cells.addAll(row(13, 22));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals(2, net.componentCount());
		assertEquals("half the mask", 0.5, net.largestComponentRatio(), 1e-9);
		assertEquals("the three missing columns between the halves", 3, net.widestBreak());
	}

	/** Two gaps of different width: the reported one is the widest. */
	@Test
	public void theWidestBreakWins() {
		List<RoadCell> cells = new ArrayList<>(row(0, 9));
		cells.addAll(row(11, 19)); // one missing column
		cells.addAll(row(24, 30)); // four missing columns
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals(3, net.componentCount());
		assertEquals(4, net.widestBreak());
	}

	/**
	 * An empty space at the edge of the mask is where the road legitimately
	 * stops; counting it as a break would drown the real signal.
	 */
	@Test
	public void aMaskEdgeIsNotABreak() {
		RoadNetwork net = RoadNetwork.build(row(0, 5), 4);
		assertEquals(0, net.widestBreak());
		assertTrue(net.isConnected());
	}

	/**
	 * A split mask is solved per component (reworked D1-e, 2026-10-14). The two
	 * halves have no 4-adjacency edge between them, so each one being legal on
	 * its own is exactly what is needed; declining the whole village here is what
	 * made real - routinely split - village masks do nothing at all.
	 */
	@Test
	public void aSplitMaskIsSolvedPerComponent() {
		List<RoadCell> cells = new ArrayList<>(row(0, 9));
		cells.addAll(row(13, 22));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		assertEquals(2, net.componentCount());
		int west = net.cellAt(9, 0);
		int east = net.cellAt(13, 0);
		assertTrue("both halves are in the mask", west >= 0 && east >= 0);
		assertEquals("no edge from the west half across the hole",
				-1, net.neighbors[west][RoadNetwork.DIR_EAST]);
		assertEquals("no edge into the east half from the hole",
				-1, net.neighbors[east][RoadNetwork.DIR_WEST]);

		RoadSmoother.solve(net, new RoadSmoother.SolveParams(
				VillagePathSmoothedConfig.Objective.SMOOTH_FIRST, 8 * 16, 32, 16, 4, true));

		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j < 0) {
					continue;
				}
				assertTrue("every edge inside a component is legal",
						Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP) <= 1);
			}
		}
	}
}