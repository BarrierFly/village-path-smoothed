/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug door signs (user request 2026-10-16): one standing sign at every
 * template door of a movable building, always on - no config gate. The sign
 * documents the door-to-road alignment so it can be verified on foot:
 *
 * <ul>
 *   <li>line 1: the door's X/Z with the base walk level the alignment used
 *       (the component's Y0 layer top, {@code baseY + dy + 1});</li>
 *   <li>line 2: the corresponding road cell's X/Z and its solved walk surface
 *       ({@code solvedP / 2}, so x.0 or x.5).</li>
 * </ul>
 *
 * <p>Numbers only, space separated. The corresponding road cell is the cell
 * nearest to the door in Chebyshev distance in the WHOLE network (no window -
 * the user decision of 2026-10-16 is visibility over fidelity: every door
 * must get its sign); ties prefer the door-front cell the railing rule uses,
 * then the lower x/z. A door whose network has no road cell at all gets no
 * second line - the caller counts the skips ({@code doors - signs}).
 *
 * <p>Pure data: the writer places the blocks and may replace whatever
 * occupies the target cell (debug feature). The water-seat base rules are
 * deliberately out of scope for this feature (user request 2026-10-16).
 */
public final class DoorSigns {

	private DoorSigns() {
	}

	/**
	 * @param doorsPerComponent one entry per movable component, its template
	 *        doors as {doorX, doorZ, outDX, outDZ} ({0, 0} when no outward
	 *        direction could be derived: the sign stands in the door column);
	 *        a component without doors contributes an empty array
	 * @param baseWalkY the component's door walk level (baseY + dy +
	 *        {@code BuildingShift#DOOR_WALK_OFFSET}), one entry per component
	 * @return one sign entry {doorX, doorWalkY, doorZ, outDX, outDZ, roadX,
	 *         roadSolvedP, roadZ} per door with a road cell in the network
	 */
	public static int[][] compute(List<int[][]> doorsPerComponent, int[] baseWalkY, RoadNetwork net) {
		List<int[]> out = new ArrayList<>();
		for (int c = 0; c < doorsPerComponent.size(); c++) {
			int[][] doors = doorsPerComponent.get(c);
			if (doors == null) {
				continue;
			}
			int walkY = baseWalkY[c];
			for (int[] door : doors) {
				RoadCell road = nearestRoadCell(net, door);
				if (road == null) {
					continue; // counted by the caller: doors without any road cell
				}
				out.add(new int[]{door[0], walkY, door[1], door[2], door[3], road.x, road.solvedP, road.z});
			}
		}
		return out.toArray(new int[0][]);
	}

	/** Nearest road cell to one door anywhere in the network, or null when the network is empty. */
	private static RoadCell nearestRoadCell(RoadNetwork net, int[] door) {
		RoadCell best = null;
		int bestChebyshev = Integer.MAX_VALUE;
		int bestFrontRank = Integer.MAX_VALUE;
		for (RoadCell cell : net.cells) {
			int chebyshev = Math.max(Math.abs(cell.x - door[0]), Math.abs(cell.z - door[1]));
			int frontRank = cell.x == door[0] + door[2] && cell.z == door[1] + door[3] ? 0 : 1;
			// strict beats: cells arrive in packedXZ order, so an equal (rank,
			// distance) pair keeps the lowest x/z - the documented tie-break
			if (chebyshev > bestChebyshev
					|| (chebyshev == bestChebyshev && frontRank >= bestFrontRank)) {
				continue;
			}
			best = cell;
			bestChebyshev = chebyshev;
			bestFrontRank = frontRank;
		}
		return best;
	}
}
