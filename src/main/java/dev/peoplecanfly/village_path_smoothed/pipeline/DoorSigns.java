/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Pure door helpers shared by the building alignment, the debug door signs and
 * the railing exemption. No Minecraft types, so the rules stay unit testable.
 *
 * <p>Door/basis fix plan 2026-10-17 (user decisions D1-D5):
 *
 * <ul>
 *   <li>only the MAIN door participates in the height alignment and gets a
 *       sign: the real door nearest the template's {@code building_entrance}
 *       jigsaw, with a double door keeping both leaves ({@link #mainDoors});</li>
 *   <li>the door's target is the nearest road cell OUTSIDE the building
 *       footprints and on the outward side of the door wall
 *       ({@link #selectDoorRoadCell}); seats and columns the writer would skip
 *       are excluded. Since 2026-11-04 candidates at or beside the component's
 *       street-junction anchors (the vanilla street connection cells) win over
 *       a strictly nearer cell, so a road passing beneath an elevated door
 *       front is not mistaken for the front road; the pick stays XZ-only, no
 *       Y restriction (the house's height follows the selected road);</li>
 *   <li>the same target feeds the sign, the alignment and the railing
 *       exemption ({@link DoorTarget});</li>
 *   <li>no outward road cell means {@code NO FRONT}: the door still gets a sign
 *       naming the nearest whole-network cell, but the building is not moved
 *       for it (user decision D4).</li>
 * </ul>
 */
public final class DoorSigns {

	private DoorSigns() {
	}

	/**
	 * One main door and its selected target. {@code road == null} means the door
	 * had no eligible outward road cell ({@code NO FRONT}); the sign may still
	 * name an unfiltered fallback cell, but the alignment must not use one.
	 *
	 * <p>{@code normalX/normalZ} are the wall's outward normal (the half-plane
	 * the selector may choose from); {@code outX/outZ} is the one-step direction
	 * the sign and the railing exemption use.
	 */
	public record DoorTarget(int doorX, int doorZ, int floorY,
			int normalX, int normalZ, int outX, int outZ, RoadCell road) {
	}

	/** Result of one filtered search: the selected cell plus the outward filter's rejection count. */
	public record Selection(RoadCell cell, int behindExcluded, boolean anchorPreferred) {
	}

	/**
	 * The main-door indices: for each real door take the horizontal Chebyshev
	 * distance ({@code max(|dx|, |dz|)}) to every {@code building_entrance},
	 * keep the global minimum and return EVERY door at that distance, so a
	 * double door keeps both leaves. Doors tied on the XZ distance are decided
	 * by the smaller {@code |door floor Y - entrance Y|} (2026-11-03, door audit
	 * 2026-11-01 §5.1): the upper balcony doors of desert_tannery_1,
	 * desert_tool_smith_1 and plains_library_2 lose to their ground doors.
	 * Without any entrance every real door is a main door (the caller counts
	 * {@code MAIN_DOOR_NO_ENTRANCE}).
	 *
	 * @param doors {x, z, floorY} real lower door halves
	 * @param entrances {x, z, floorY} {@code building_entrance} jigsaw positions
	 */
	public static int[] mainDoorIndices(int[][] doors, int[][] entrances) {
		if (doors == null || doors.length == 0) {
			return new int[0];
		}
		if (entrances == null || entrances.length == 0) {
			int[] all = new int[doors.length];
			for (int i = 0; i < all.length; i++) {
				all[i] = i;
			}
			return all;
		}
		int[] nearestXz = new int[doors.length];
		int[] nearestDy = new int[doors.length];
		for (int i = 0; i < doors.length; i++) {
			int xz = Integer.MAX_VALUE;
			int dy = Integer.MAX_VALUE;
			for (int[] entrance : entrances) {
				int d = Math.max(Math.abs(doors[i][0] - entrance[0]), Math.abs(doors[i][1] - entrance[1]));
				int vertical = Math.abs(doors[i][2] - entrance[2]);
				if (d < xz) {
					xz = d;
					dy = vertical;
				} else if (d == xz && vertical < dy) {
					dy = vertical;
				}
			}
			nearestXz[i] = xz;
			nearestDy[i] = dy;
		}
		int bestXz = Integer.MAX_VALUE;
		int bestDy = Integer.MAX_VALUE;
		for (int i = 0; i < doors.length; i++) {
			if (nearestXz[i] < bestXz || (nearestXz[i] == bestXz && nearestDy[i] < bestDy)) {
				bestXz = nearestXz[i];
				bestDy = nearestDy[i];
			}
		}
		int count = 0;
		for (int i = 0; i < doors.length; i++) {
			if (nearestXz[i] == bestXz && nearestDy[i] == bestDy) {
				count++;
			}
		}
		int[] out = new int[count];
		int n = 0;
		for (int i = 0; i < doors.length; i++) {
			if (nearestXz[i] == bestXz && nearestDy[i] == bestDy) {
				out[n++] = i;
			}
		}
		return out;
	}

	/** Convenience view of {@link #mainDoorIndices}: the main door entries themselves. */
	public static int[][] mainDoors(int[][] doors, int[][] entrances) {
		int[] indices = mainDoorIndices(doors, entrances);
		int[][] out = new int[indices.length][];
		for (int i = 0; i < indices.length; i++) {
			out[i] = doors[indices[i]];
		}
		return out;
	}

	/**
	 * The nearest road cell on the outward side of the door wall, filtered by
	 * the caller's {@code eligibleByIndex} (seats and building footprints are
	 * excluded in the Minecraft layer). A candidate must satisfy
	 * {@code (cell.x - doorX) * outDX + (cell.z - doorZ) * outDZ > 0}: cells on
	 * the door column or behind the wall are never a front.
	 *
	 * <p>Tie-break order (plan 2026-10-17 section 3.3): horizontal Manhattan
	 * distance, then an on-axis (door column) cell, then the larger outward
	 * projection, then the lower {@code packedXZ} - deterministic.
	 */
	public static Selection selectDoorRoadCell(RoadNetwork net, int doorX, int doorZ,
			int outDX, int outDZ, IntPredicate eligibleByIndex) {
		return selectDoorRoadCell(net, doorX, doorZ, outDX, outDZ, null, eligibleByIndex);
	}

	/**
	 * 2026-11-04: the same outward search with the component's street-junction
	 * anchors. Vanilla records the street connection cell on every building
	 * piece's {@code building_entrance} junction - the road the walkway actually
	 * lands on (the street at the foot of long front stairs). Candidates at or
	 * 4-adjacent to an anchor are preferred OVER a strictly nearer cell, so a
	 * road passing beneath an elevated door front cannot win the XZ pick; the
	 * house's height still follows whichever cell is selected (no Y restriction -
	 * user decision 2026-11-04). Without any anchored candidate (or with no
	 * anchors at all) the plain nearest-cell rule above decides unchanged.
	 */
	public static Selection selectDoorRoadCell(RoadNetwork net, int doorX, int doorZ,
			int outDX, int outDZ, java.util.Set<Long> anchorColumns, IntPredicate eligibleByIndex) {
		if (anchorColumns != null && !anchorColumns.isEmpty()) {
			Selection anchored = search(net, doorX, doorZ, outDX, outDZ, anchorColumns, eligibleByIndex, true);
			if (anchored.cell() != null) {
				return anchored;
			}
		}
		return search(net, doorX, doorZ, outDX, outDZ, null, eligibleByIndex, false);
	}

	private static Selection search(RoadNetwork net, int doorX, int doorZ,
			int outDX, int outDZ, java.util.Set<Long> anchorColumns, IntPredicate eligibleByIndex,
			boolean anchoredOnly) {
		RoadCell best = null;
		int bestDistance = Integer.MAX_VALUE;
		boolean bestOnAxis = false;
		int bestDot = Integer.MIN_VALUE;
		long bestPacked = Long.MAX_VALUE;
		int behind = 0;
		for (RoadCell cell : net.cells) {
			if (eligibleByIndex != null && !eligibleByIndex.test(cell.index)) {
				continue;
			}
			int dx = cell.x - doorX;
			int dz = cell.z - doorZ;
			int dot = dx * outDX + dz * outDZ;
			if (dot <= 0) {
				behind++;
				continue;
			}
			if (anchoredOnly && !nearAnchor(cell.x, cell.z, anchorColumns)) {
				continue;
			}
			int distance = Math.abs(dx) + Math.abs(dz);
			boolean onAxis = dx == 0 || dz == 0;
			long packed = RoadCell.packedXZ(cell.x, cell.z);
			if (best == null
					|| distance < bestDistance
					|| (distance == bestDistance && onAxis && !bestOnAxis)
					|| (distance == bestDistance && onAxis == bestOnAxis && dot > bestDot)
					|| (distance == bestDistance && onAxis == bestOnAxis && dot == bestDot && packed < bestPacked)) {
				best = cell;
				bestDistance = distance;
				bestOnAxis = onAxis;
				bestDot = dot;
				bestPacked = packed;
			}
		}
		return new Selection(best, behind, anchoredOnly && best != null);
	}

	/** True when the cell IS an anchor column or one of its 4 neighbours. */
	private static boolean nearAnchor(int x, int z, java.util.Set<Long> anchorColumns) {
		for (long anchor : anchorColumns) {
			int ax = (int) (anchor >> 32);
			int az = (int) anchor;
			if (Math.abs(x - ax) + Math.abs(z - az) <= 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Builds a {@link DoorTarget} for one main door from the shared selector.
	 * The one-step sign/railing direction is the dominant-axis step toward the
	 * selected cell; without a selected cell it falls back to the wall normal
	 * (so the doorway still has a defined front).
	 */
	public static DoorTarget selectDoorTarget(RoadNetwork net, int doorX, int doorZ, int floorY,
			int normalDX, int normalDZ, IntPredicate eligibleByIndex) {
		Selection selection = selectDoorRoadCell(net, doorX, doorZ, normalDX, normalDZ, eligibleByIndex);
		RoadCell road = selection.cell();
		int[] out = road != null ? stepToward(road, doorX, doorZ) : null;
		if (out == null) {
			out = new int[]{normalDX, normalDZ};
		}
		return new DoorTarget(doorX, doorZ, floorY, normalDX, normalDZ, out[0], out[1], road);
	}

	/**
	 * Debug sign entries, one per main door that has ANY road cell in the
	 * network. A door without an eligible outward cell still gets a sign: line
	 * 2 names the nearest unfiltered cell and the last field flags
	 * {@code NO FRONT} (the writer prints it on line 4).
	 *
	 * @return {doorX, doorWalkY, doorZ, outDX, outDZ, roadX, roadSolvedP,
	 *         roadZ, doorFloorY, noFront(1/0)}
	 */
	public static int[][] compute(List<List<DoorTarget>> targetsPerComponent, int[] baseWalkY, RoadNetwork net) {
		List<int[]> out = new ArrayList<>();
		for (int c = 0; c < targetsPerComponent.size(); c++) {
			List<DoorTarget> targets = targetsPerComponent.get(c);
			if (targets == null) {
				continue;
			}
			int walkY = baseWalkY[c];
			for (DoorTarget target : targets) {
				boolean noFront = target.road() == null;
				RoadCell road = target.road();
				if (road == null) {
					road = nearestRoadCell(net, target.doorX(), target.doorZ());
				}
				if (road == null) {
					continue; // counted by the caller: a door with no road cell anywhere
				}
				out.add(new int[]{target.doorX(), walkY, target.doorZ(), target.outX(), target.outZ(),
						road.x, road.solvedP, road.z, target.floorY(), noFront ? 1 : 0});
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
	 * Kept for the junction fallback (farms/pens without real doors) and for the
	 * sign's unfiltered {@code NO FRONT} fallback.
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
	 * Still used by the junction fallback for components without real doors.
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
