/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.peoplecanfly.village_path_smoothed.pipeline.DoorSigns;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;

/**
 * The debug door signs' pure selection rule (user request 2026-10-16, reworked
 * by the R4-1 decision 2026-10-16): the corresponding road cell is the one
 * nearest the door by horizontal MANHATTAN distance ({@code |dx| + |dz|}) in the
 * WHOLE network - the same cell the building alignment and the railing exemption
 * use - with the lowest x/z breaking a tie, and line 3 carries the actual door
 * floor Y.
 */
public class DoorSignsTest {

	@Test
	public void eachDoorGetsItsManhattanNearestRoadCell() {
		// door at (1,1): (1,0) is one Manhattan step away, (0,0) and (2,0) are two
		// (a Chebyshev tie under the old rule). Manhattan picks the orthogonal
		// cell, the very fix R4-1 asks for.
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(0, 0, "plains", 130, false));
		cells.add(new RoadCell(1, 0, "plains", 132, false));
		cells.add(new RoadCell(2, 0, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{1, 1, 0, -1, 64}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals("one sign for the one door", 1, signs.length);
		// {doorX, doorWalkY, doorZ, outDX, outDZ, roadX, roadSolvedP, roadZ, floorY}
		assertArrayEquals(new int[]{1, 65, 1, 0, -1, 1, 132, 0, 64}, signs[0]);
	}

	@Test
	public void theCoordinateTieBreakPicksTheLowestXZ() {
		// door at (5,5): both (4,5) and (6,5) are one Manhattan step away, so the
		// lower x wins; among equal x the lower z
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(4, 5, "plains", 130, false));
		cells.add(new RoadCell(4, 4, "plains", 130, false));
		cells.add(new RoadCell(6, 5, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{5, 5, 0, -1, 70}});
		int[] baseWalkY = {70};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals(1, signs.length);
		assertEquals("lowest x wins the tie", 4, signs[0][5]);
		assertEquals("then the lowest z", 5, signs[0][7]);
	}

	/**
	 * R4-1: the Manhattan rule keeps the orthogonal doorway cell when a diagonal
	 * cell is one Chebyshev step away but two Manhattan steps away.
	 */
	@Test
	public void theOrthogonalCellBeatsTheDiagonalOne() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(1, 1, "plains", 130, false));
		cells.add(new RoadCell(1, 0, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{0, 0, 1, 0, 60}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals(1, signs.length);
		assertEquals("the orthogonal (1,0) wins", 1, signs[0][5]);
		assertEquals(0, signs[0][7]);
	}

	/**
	 * Visibility beats fidelity (user decision 2026-10-16): the road cell is
	 * searched in the WHOLE network - a door far from any street still gets its
	 * sign naming the nearest road cell, however many blocks past the old
	 * footprint+4 alignment window it sits.
	 */
	@Test
	public void theNearestRoadCellIsChosenEvenBeyondTheOldAlignmentWindow() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(30, 0, "plains", 130, false));
		cells.add(new RoadCell(31, 0, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{0, 0, 1, 0, 65}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals("no window any more: the far cell is named", 1, signs.length);
		assertEquals(30, signs[0][5]);
	}

	@Test
	public void doorsOfAVillageWithoutAnyRoadCellGetNoSign() {
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{0, 0, 1, 0, 65}, {0, 3, 1, 0, 65}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, RoadNetwork.build(new ArrayList<>(), 4));

		assertEquals("no road cell anywhere: no sign", 0, signs.length);
	}

	/**
	 * The alignment's windowed search (R4-1) shares the Manhattan metric and the
	 * tie-break with the sign's whole-network search; outside the window the
	 * door's cell must be ignored.
	 */
	@Test
	public void theWindowedSearchIgnoresCellsOutsideTheWindow() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(10, 0, "plains", 130, false));
		cells.add(new RoadCell(0, 0, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);

		// window around (0,0): only the (0,0) cell counts, even though (10,0)
		// exists in the network
		RoadCell windowed = DoorSigns.nearestRoadCell(net, 1, 0, new int[]{-2, 2, -2, 2});
		assertEquals(0, windowed.x);

		// the whole network sees (0,0) as nearest to (1,0) too (distance 1 vs 9)
		assertSame(windowed, DoorSigns.nearestRoadCell(net, 1, 0));

		// no cell in the window -> null (the alignment must then not move)
		assertNull(DoorSigns.nearestRoadCell(net, 1, 0, new int[]{20, 30, 20, 30}));
	}
}
