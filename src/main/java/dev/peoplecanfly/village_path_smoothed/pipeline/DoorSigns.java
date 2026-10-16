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
 *       ({@code solvedP / 2}, so x.0 or x.5);</li>
 *   <li>line 3: the actual door floor Y of the template's lower door half after
 *       the shift (R4-2, user decision 2026-10-16), so "assumption vs reality"
 *       can be read at a glance.</li>
 * </ul>
 *
 * <p>Numbers only, space separated. The corresponding road cell is the cell
 * nearest to the door by the <b>horizontal Manhattan distance</b>
 * ({@code |dx| + |dz|}) in the WHOLE network (no window - the user decision of
 * 2026-10-16 is visibility over fidelity: every door must get its sign); ties
 * keep the lowest x/z. R4-1 (user decision 2026-10-16): this is the SAME cell
 * the building alignment and the railing exemption use, so the sign, the
 * building and the doorway all describe one location. A door whose network has
 * no road cell at all gets no sign - the caller counts the skips
 * ({@code doors - signs}).
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
	 *        doors as {doorX, doorZ, outDX, outDZ, doorFloorY} ({0, 0} outward
	 *        when no direction could be derived); a component without doors
	 *        contributes an empty array
	 * @param baseWalkY the component's door walk level (baseY + dy +
	 *        {@code BuildingShift#DOOR_WALK_OFFSET}), one entry per component
	 * @return one sign entry {doorX, doorWalkY, doorZ, outDX, outDZ, roadX,
	 *         roadSolvedP, roadZ, doorFloorY} per door with a road cell in the
	 *         network
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
				RoadCell road = nearestRoadCell(net, door[0], door[1]);
				if (road == null) {
					continue; // counted by the caller: doors without any road cell
				}
				out.add(new int[]{door[0], walkY, door[1], door[2], door[3], road.x, road.solvedP, road.z, door[4]});
			}
		}
		return out.toArray(new int[0][]);
	}

	/** Nearest road cell to one door anywhere in the network, or null when the network is empty. */
	public static RoadCell nearestRoadCell(RoadNetwork net, int doorX, int doorZ) {
		return nearestRoadCell(net, doorX, doorZ, null);
	}

	/**
	 * Nearest road cell to one door by horizontal Manhattan distance, restricted
	 * to the inclusive {@code {minX, maxX, minZ, maxZ}} window when it is not
	 * null. Ties keep the first cell in packedXZ order (lowest x, then lowest z).
	 * R4-1: the alignment searches inside the footprint window; the sign and the
	 * railing direction search the whole network - the two agree whenever a road
	 * cell lies in the window.
	 */
	public static RoadCell nearestRoadCell(RoadNetwork net, int doorX, int doorZ, int[] window) {
		RoadCell best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (RoadCell cell : net.cells) {
			if (window != null && (cell.x < window[0] || cell.x > window[1]
					|| cell.z < window[2] || cell.z > window[3])) {
				continue;
			}
			int distance = Math.abs(cell.x - doorX) + Math.abs(cell.z - doorZ);
			// cells arrive in packedXZ order, so a strict beat keeps the lowest
			// x/z on a tie - the documented tie-break
			if (distance < bestDistance) {
				best = cell;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Unit step from a door toward the nearest road cell in the whole network
	 * (R3-1/R4-1, user decision 2026-10-16): one step along the dominant axis,
	 * ties picking x. Null when the network is empty or the nearest cell is the
	 * door column itself - the caller then falls back to the footprint axis.
	 */
	public static int[] stepTowardRoad(RoadNetwork net, int doorX, int doorZ) {
		RoadCell road = nearestRoadCell(net, doorX, doorZ);
		return stepToward(road, doorX, doorZ);
	}

	/** The dominant-axis unit step from ({@code doorX}, {@code doorZ}) to {@code road}, or null. */
	public static int[] stepToward(RoadCell road, int doorX, int doorZ) {
		if (road == null) {
			return null;
		}
		int dx = Integer.compare(road.x, doorX);
		int dz = Integer.compare(road.z, doorZ);
		if (dx == 0 && dz == 0) {
			return null;
		}
		if (Math.abs(road.x - doorX) < Math.abs(road.z - doorZ)) {
			dx = 0; // z is the dominant axis
		} else {
			dz = 0; // x is the dominant axis (ties pick x)
		}
		return new int[]{dx, dz};
	}
}
