/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.peoplecanfly.village_path_smoothed.pipeline.DoorSigns;
import dev.peoplecanfly.village_path_smoothed.pipeline.DoorSigns.DoorTarget;
import dev.peoplecanfly.village_path_smoothed.pipeline.DoorSigns.Selection;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;

/**
 * Door/basis fix plan 2026-10-17: the main-door selection and the filtered
 * door-road target are pure rules, pinned here.
 */
public class DoorSignsTest {

	private static RoadNetwork net(List<RoadCell> cells) {
		return RoadNetwork.build(new ArrayList<>(cells), 4);
	}

	@Test
	public void aSingleRealDoorIsTheMainDoor() {
		int[][] doors = {{1, 1, 64}};
		int[][] entrances = {{0, 0}};
		assertArrayEquals(new int[]{0}, DoorSigns.mainDoorIndices(doors, entrances));
	}

	@Test
	public void aDoubleDoorKeepsBothLeavesWhenTheyAreEquallyClose() {
		// both leaves are Chebyshev 1 from the entrance: a double door stays a pair
		int[][] doors = {{1, 1, 64}, {1, 2, 64}};
		int[][] entrances = {{0, 1}};
		assertArrayEquals(new int[]{0, 1}, DoorSigns.mainDoorIndices(doors, entrances));
	}

	@Test
	public void sideAndUpperDoorsAreExcludedWhenFartherFromTheEntrance() {
		// main door at (1,1), side door at (7,1), upper door at (5,5)
		int[][] doors = {{1, 1, 64}, {7, 1, 64}, {5, 5, 69}};
		int[][] entrances = {{0, 1}};
		assertArrayEquals(new int[]{0}, DoorSigns.mainDoorIndices(doors, entrances));
	}

	@Test
	public void noEntranceFallsBackToEveryRealDoor() {
		int[][] doors = {{1, 1, 64}, {7, 1, 64}};
		assertArrayEquals(new int[]{0, 1}, DoorSigns.mainDoorIndices(doors, new int[0][]));
		assertArrayEquals(doors, DoorSigns.mainDoors(doors, new int[0][]));
	}

	@Test
	public void theSelectorPicksTheNearestCellOnTheOutwardSide() {
		// door at (1,1), outward +x: (2,1) is one step out; (0,1) is behind
		RoadNetwork network = net(List.of(
				new RoadCell(0, 1, "plains", 130, false),
				new RoadCell(2, 1, "plains", 132, false),
				new RoadCell(5, 1, "plains", 130, false)));
		Selection selection = DoorSigns.selectDoorRoadCell(network, 1, 1, 1, 0, null);
		assertEquals(2, selection.cell().x);
		assertEquals(1, selection.cell().z);
		assertEquals("(0,1) is behind the wall plus (3,0)... only (0,1)", 1, selection.behindExcluded());
	}

	@Test
	public void theDoorColumnBeatsAnOffAxisCellAtTheSameDistance() {
		// door at (0,0), outward +x: (1,0) is on the door column, (1,1) is a
		// diagonal at the same Manhattan distance - the on-axis cell wins
		RoadNetwork network = net(List.of(
				new RoadCell(1, 1, "plains", 130, false),
				new RoadCell(1, 0, "plains", 130, false)));
		Selection selection = DoorSigns.selectDoorRoadCell(network, 0, 0, 1, 0, null);
		assertEquals(1, selection.cell().x);
		assertEquals(0, selection.cell().z);
	}

	@Test
	public void aCellInsideTheBuildingOrOnASeatIsExcludedByThePredicate() {
		RoadNetwork network = net(List.of(
				new RoadCell(2, 0, "plains", 130, false),
				new RoadCell(1, 0, "plains", 130, false)));
		// the seat/inside column (1,0) is skipped, only (2,0) remains
		Selection selection = DoorSigns.selectDoorRoadCell(network, 0, 0, 1, 0,
				index -> network.cells.get(index).x != 1);
		assertEquals(2, selection.cell().x);
	}

	@Test
	public void noOutwardCellMeansNull() {
		RoadNetwork network = net(List.of(
				new RoadCell(0, 0, "plains", 130, false),
				new RoadCell(-1, 0, "plains", 130, false)));
		Selection selection = DoorSigns.selectDoorRoadCell(network, 0, 0, 1, 0, null);
		assertNull(selection.cell());
		assertEquals(2, selection.behindExcluded());
	}

	@Test
	public void aNoFrontDoorStillGetsASignNamingTheNearestWholeNetworkCell() {
		RoadNetwork network = net(List.of(
				new RoadCell(-1, 0, "plains", 128, false),
				new RoadCell(-2, 0, "plains", 130, false)));
		// door has no selected target (road null) but the network is not empty
		DoorTarget target = new DoorTarget(0, 0, 64, 1, 0, 1, 0, null);
		List<List<DoorTarget>> targets = new ArrayList<>();
		targets.add(List.of(target));
		int[][] signs = DoorSigns.compute(targets, new int[]{65}, network);
		assertEquals(1, signs.length);
		// {doorX, doorWalkY, doorZ, outDX, outDZ, roadX, roadP, roadZ, floorY, noFront}
		assertArrayEquals(new int[]{0, 65, 0, 1, 0, -1, 128, 0, 64, 1}, signs[0]);
	}

	@Test
	public void aServedDoorSignsItsSelectedTargetWithNoFrontClear() {
		RoadCell road = new RoadCell(1, 0, "plains", 132, false);
		RoadNetwork network = net(List.of(road));
		DoorTarget target = new DoorTarget(0, 0, 64, 1, 0, 1, 0, road);
		List<List<DoorTarget>> targets = new ArrayList<>();
		targets.add(List.of(target));
		int[][] signs = DoorSigns.compute(targets, new int[]{65}, network);
		assertEquals(1, signs.length);
		assertArrayEquals(new int[]{0, 65, 0, 1, 0, 1, 132, 0, 64, 0}, signs[0]);
	}
}
