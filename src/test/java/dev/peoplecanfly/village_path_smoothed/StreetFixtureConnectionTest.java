/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * 2026-10-21 fixture-connection semantics: two structures in the road network
 * are connected only through horizontally adjacent (4-adjacency) solve cells.
 * Proximity and diagonal corners are not connections; a decor's own footprint
 * cells are not external connections.
 */
public class StreetFixtureConnectionTest {
	private static RoadNetwork network(int[][] cells) {
		List<RoadCell> list = new ArrayList<>();
		for (int[] cell : cells) {
			list.add(new RoadCell(cell[0], cell[1], "plains", 2 * cell[2], false));
		}
		return RoadNetwork.build(list, 4);
	}

	private static long column(int x, int z) {
		return RoadCell.packedXZ(x, z);
	}

	@Test
	public void connectionNeedsASharedSideNotADiagonalCorner() {
		RoadNetwork net = network(new int[][]{{0, 0, 64}, {1, 0, 64}, {1, 1, 64}});
		int[] external = net.externalNeighbours(Set.of(column(0, 0)));
		assertArrayEquals(new int[]{net.cellAt(1, 0)}, external);
	}

	@Test
	public void aRoadBesideTheFarFootprintEdgeIsFound() {
		// A 1x6 base footprint (z=0..5) with its seat at z=3, and a road at
		// z=-1. The old seat-origin search stopped at Chebyshev 3 and missed
		// this road (it is 4 cells from the seat); the footprint edge finds it.
		int[][] cells = new int[7][];
		for (int z = 0; z <= 5; z++) {
			cells[z] = new int[]{0, z, 64};
		}
		cells[6] = new int[]{0, -1, 64};
		RoadNetwork net = network(cells);
		Set<Long> own = Set.of(
				column(0, 0), column(0, 1), column(0, 2),
				column(0, 3), column(0, 4), column(0, 5));
		int[] external = net.externalNeighbours(own);
		assertArrayEquals(new int[]{net.cellAt(0, -1)}, external);
	}

	@Test
	public void ownCellsAreNotConnections() {
		RoadNetwork net = network(new int[][]{{0, 0, 64}, {1, 0, 64}});
		int[] external = net.externalNeighbours(Set.of(column(0, 0), column(1, 0)));
		assertEquals("internal footprint neighbours are not external connections", 0, external.length);
	}

	@Test
	public void aSeatWithOnlyADiagonalNeighbourIsIsolated() {
		RoadNetwork net = network(new int[][]{{5, 5, 64}, {6, 6, 64}});
		int[] external = net.externalNeighbours(Set.of(column(5, 5)));
		assertEquals("a diagonal corner is not a connection", 0, external.length);
	}

	@Test
	public void aDisconnectedBboxRingIsNotPartOfTheSeatStructure() {
		// The seat (0,0) is separated from a bbox ring at (2,0)/(2,1)/(3,0) by
		// non-road columns; the base group must stop at the seat component.
		RoadNetwork net = network(new int[][]{{0, 0, 64}});
		Set<Long> footprint = Set.of(column(0, 0), column(2, 0), column(2, 1), column(3, 0));
		assertEquals(Set.of(column(0, 0)),
				net.connectedColumns(footprint, Set.of(column(0, 0))));
		assertEquals(Set.of(column(2, 0), column(2, 1), column(3, 0)),
				net.connectedColumns(footprint, Set.of(column(3, 0))));
	}

	@Test
	public void packedBlockPositionsRoundTrip() {
		long key = RoadCell.packedXYZ(-2439, 168, -2127);
		assertEquals(-2439, RoadCell.unpackX(key));
		assertEquals(168, RoadCell.unpackY(key));
		assertEquals(-2127, RoadCell.unpackZ(key));
	}
}
