/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCollector;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Fix plan v2 R5: the probe-boundary alignment pass. The +/-1 chunk real
 * heightmap window cannot be widened (vanilla's WorldGenRegion pins reads to
 * the generating step's direct dependencies), so noise-probed columns directly
 * adjacent to real columns inherit the real value before the network is solved
 * - the hard caliber switch must not bake a step into the network that the
 * solver then has to explain away.
 */
public class ProbeBoundaryAlignmentTest {
	private static RoadCell cell(int x, int z, int topY, boolean realHeight) {
		RoadCell c = new RoadCell(x, z, "plains", 2 * topY, false);
		c.realHeight = realHeight;
		return c;
	}

	/** The plan's acceptance shape: a 2-half-unit mismatch across the boundary is pulled level. */
	@Test
	public void boundaryMismatchIsPulledOntoTheRealValue() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 64, true));
		cells.add(cell(1, 0, 65, false)); // 2 half units above the real neighbour
		RoadCollector.alignProbeBoundaries(cells);
		assertEquals("real columns never move", 2 * 64, cells.get(0).baseP);
		assertEquals("the noise column inherits the real value", cells.get(0).baseP, cells.get(1).baseP);
	}

	/** Only columns DIRECTLY adjacent to a real column are aligned (the plan's literal rule). */
	@Test
	public void onlyDirectRealNeighboursAreAligned() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 64, true));
		cells.add(cell(1, 0, 64, true));
		cells.add(cell(2, 0, 65, false)); // adjacent to a real column that disagrees: aligned
		cells.add(cell(3, 0, 65, false)); // deep noise column: keeps its own probe
		RoadCollector.alignProbeBoundaries(cells);
		assertEquals(2 * 64, cells.get(2).baseP);
		assertEquals(2 * 65, cells.get(3).baseP);
	}

	@Test
	public void agreeingColumnsAndRealOnlyNetworksAreUntouched() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 64, true));
		cells.add(cell(1, 0, 64, false)); // agrees with the real column: untouched
		cells.add(cell(5, 5, 70, false)); // no real neighbour at all: untouched
		cells.add(cell(6, 5, 70, true));  // real column: never moves
		RoadCollector.alignProbeBoundaries(cells);
		assertEquals(2 * 64, cells.get(1).baseP);
		assertEquals(2 * 70, cells.get(2).baseP);
		assertEquals(2 * 70, cells.get(3).baseP);
	}

	@Test
	public void solvedPStaysInSyncWithBasePUntilTheSolverRuns() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 64, true));
		cells.add(cell(1, 0, 66, false));
		RoadCollector.alignProbeBoundaries(cells);
		assertEquals("a smoothing-disabled run writes from solvedP, so it must track baseP",
				cells.get(1).baseP, cells.get(1).solvedP);
	}
}
