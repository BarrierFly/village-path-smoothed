/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact write-time protection of the real building body (2026-10-23).
 *
 * <p>Plan A used to carry one 3D bounding box per movable building, so a road
 * column inside the box was skipped even when that part of the box was empty
 * air (a courtyard, the empty corner of a template, the air under an eave).
 * The user decision narrows the protection to the building body and its
 * interior: a cell is protected when
 *
 * <ul>
 *   <li>the template really places a non-air building block there, or</li>
 *   <li>it is air and, looking along at least five of its six axes, the first
 *       block met before leaving the template is a building block (the chosen
 *       interior rule).</li>
 * </ul>
 *
 * <p>Everything else inside the old bounding box is free: the road block, the
 * support fill and the upward clear may use it. The class is pure (no Minecraft
 * imports) so the body rule and the mask lookups are unit tested; only the
 * template scan and the local-to-world transform live in
 * {@link BuildingComponents}.
 */
public final class BuildingMask {
	private final Set<Long> cells = new HashSet<>();
	/** Columns with at least one protected cell: the 2D shape for door-target exclusion. */
	private final Set<Long> columns = new HashSet<>();

	public void addCell(int x, int y, int z) {
		this.cells.add(RoadCell.packedXYZ(x, y, z));
		this.columns.add(RoadCell.packedXZ(x, z));
	}

	/** Adds every cell of a box, used only as the unreadable-template fallback. */
	public void addBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					addCell(x, y, z);
				}
			}
		}
	}

	/**
	 * Adds only the 2D columns of per-column boxes (decor's actual-block
	 * footprint) to the door-target exclusion. These stay out of the plan-A
	 * cell test: a decor is protected block by block by the decor set.
	 */
	public void addColumns(int[][] columnBoxes) {
		if (columnBoxes == null) {
			return;
		}
		for (int[] box : columnBoxes) {
			for (int x = box[0]; x <= box[1]; x++) {
				for (int z = box[4]; z <= box[5]; z++) {
					this.columns.add(RoadCell.packedXZ(x, z));
				}
			}
		}
	}

	/** A copy with the decor columns merged in, for the door-target exclusion. */
	public BuildingMask copyWithColumns(int[][] columnBoxes) {
		BuildingMask copy = new BuildingMask();
		copy.cells.addAll(this.cells);
		copy.columns.addAll(this.columns);
		copy.addColumns(columnBoxes);
		return copy;
	}

	public boolean protects(int x, int y, int z) {
		return this.cells.contains(RoadCell.packedXYZ(x, y, z));
	}

	/** True when the cell's road block lands on a protected building cell. */
	public boolean protectsRoadBlock(int x, int z, int solvedP) {
		return protects(x, RoadWritePolicy.roadTopBlockY(solvedP), z);
	}

	/** True when the column carries any protected building cell (2D footprint). */
	public boolean hasColumn(int x, int z) {
		return this.columns.contains(RoadCell.packedXZ(x, z));
	}

	public boolean isEmpty() {
		return this.cells.isEmpty();
	}

	public int cellCount() {
		return this.cells.size();
	}

	public int columnCount() {
		return this.columns.size();
	}

	/**
	 * The user's interior rule (2026-10-23, clarified the same day): every real
	 * building block, plus each air cell where, looking along at least five of
	 * the six axes, the first block met before leaving the template is a
	 * building block. A normal room's centre therefore counts (walls, floor and
	 * roof block five axes), while an open edge does not. The grid is
	 * template-local and unrotated; rotation preserves the six axes, so the
	 * caller can rotate the resulting cells.
	 *
	 * @param solid {@code solid[x][y][z]} = the template places a building block there
	 * @return protected cells as {x, y, z} triples
	 */
	public static int[][] bodyCells(boolean[][][] solid) {
		if (solid == null || solid.length == 0) {
			return new int[0][];
		}
		int sizeX = solid.length;
		int sizeY = solid[0].length;
		int sizeZ = sizeY == 0 ? 0 : solid[0][0].length;
		List<int[]> out = new ArrayList<>();
		for (int x = 0; x < sizeX; x++) {
			for (int y = 0; y < sizeY; y++) {
				for (int z = 0; z < sizeZ; z++) {
					if (solid[x][y][z] || enclosedAir(solid, x, y, z)) {
						out.add(new int[]{x, y, z});
					}
				}
			}
		}
		return out.toArray(new int[0][]);
	}

	private static boolean enclosedAir(boolean[][][] solid, int x, int y, int z) {
		int blocked = 0;
		if (rayMeetsSolid(solid, x, y, z, -1, 0, 0)) {
			blocked++;
		}
		if (rayMeetsSolid(solid, x, y, z, 1, 0, 0)) {
			blocked++;
		}
		if (rayMeetsSolid(solid, x, y, z, 0, -1, 0)) {
			blocked++;
		}
		if (rayMeetsSolid(solid, x, y, z, 0, 1, 0)) {
			blocked++;
		}
		if (rayMeetsSolid(solid, x, y, z, 0, 0, -1)) {
			blocked++;
		}
		if (rayMeetsSolid(solid, x, y, z, 0, 0, 1)) {
			blocked++;
		}
		return blocked >= 5;
	}

	/** True when the first block met along the axis, still inside the template, is building material. */
	private static boolean rayMeetsSolid(boolean[][][] solid, int x, int y, int z, int dx, int dy, int dz) {
		int cx = x + dx;
		int cy = y + dy;
		int cz = z + dz;
		while (inBounds(solid, cx, cy, cz)) {
			if (solid[cx][cy][cz]) {
				return true;
			}
			cx += dx;
			cy += dy;
			cz += dz;
		}
		return false;
	}

	private static boolean inBounds(boolean[][][] solid, int x, int y, int z) {
		return x >= 0 && x < solid.length
				&& y >= 0 && y < solid[x].length
				&& z >= 0 && z < solid[x][y].length;
	}
}
