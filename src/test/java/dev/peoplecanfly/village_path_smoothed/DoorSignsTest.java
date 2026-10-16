/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.peoplecanfly.village_path_smoothed.pipeline.DoorSigns;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;

/**
 * The debug door signs' pure selection rule (user request 2026-10-16): every
 * template door of a movable building gets one sign entry naming the road cell
 * nearest to the door in the WHOLE network (no window - visibility beats
 * fidelity), the door front cell winning ties, and the component's base walk
 * level as line 1's Y.
 */
public class DoorSignsTest {

	@Test
	public void eachDoorGetsItsNearestRoadCellAndTheFrontCellWinsTies() {
		// road cells (0,0) 130, (1,0) 132, (2,0) 130; door at (1,1) whose front
		// cell (front = door + outward (0,-1)) is (1,0). All three cells sit one
		// Chebyshev step away, so the front cell must win the tie.
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(0, 0, "plains", 130, false));
		cells.add(new RoadCell(1, 0, "plains", 132, false));
		cells.add(new RoadCell(2, 0, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{1, 1, 0, -1}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals("one sign for the one door", 1, signs.length);
		// {doorX, doorWalkY, doorZ, outDX, outDZ, roadX, roadSolvedP, roadZ}
		assertArrayEquals(new int[]{1, 65, 1, 0, -1, 1, 132, 0}, signs[0]);
	}

	/**
	 * A door facing away from the road still prints its nearest road cell, and
	 * an equal (distance, rank) pair keeps the lowest x/z - the documented
	 * coordinate tie-break.
	 */
	@Test
	public void theCoordinateTieBreakPicksTheLowestXZ() {
		// door at (5,5) with no front cell among the road cells; both (4,4) and
		// (4,5) sit one Chebyshev step away (front (5,4) is not a road cell), so
		// the lower x wins, and among equal x the lower z
		List<RoadCell> cells = new ArrayList<>();
		cells.add(new RoadCell(4, 5, "plains", 130, false));
		cells.add(new RoadCell(4, 4, "plains", 130, false));
		cells.add(new RoadCell(6, 5, "plains", 130, false));
		RoadNetwork net = RoadNetwork.build(cells, 4);
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{5, 5, 0, -1}});
		int[] baseWalkY = {70};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals(1, signs.length);
		assertEquals("lowest x wins the tie", 4, signs[0][5]);
		assertEquals("then the lowest z", 4, signs[0][7]);
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
		doorsPerComponent.add(new int[][]{{0, 0, 1, 0}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);

		assertEquals("no window any more: the far cell is named", 1, signs.length);
		assertEquals(30, signs[0][5]);
	}

	@Test
	public void doorsOfAVillageWithoutAnyRoadCellGetNoSign() {
		List<int[][]> doorsPerComponent = new ArrayList<>();
		doorsPerComponent.add(new int[][]{{0, 0, 1, 0}, {0, 3, 1, 0}});
		int[] baseWalkY = {65};

		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, RoadNetwork.build(new ArrayList<>(), 4));

		assertEquals("no road cell anywhere: no sign", 0, signs.length);
	}
}
