/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * One road column. Pure data, no Minecraft imports, so the solver is unit testable.
 *
 * Heights are tracked in half-block units: P = 2 * S where S is the walkable
 * surface height (the top face of the top road block). The vanilla walk surface
 * of a column is P0 = 2 * surfaceTopY, where surfaceTopY is the first air block
 * above the terrain (so the vanilla road block sits at surfaceTopY - 1).
 */
public final class RoadCell {
	public int index;
	public final int x;
	public final int z;
	public final String style;
	/** Vanilla walk surface P0 = 2 * surfaceTopY, probed at collect time. */
	public final int baseP;
	/** Water-crossing cell: its walk surface is fixed to the water surface. */
	public final boolean anchor;
	/**
	 * Reference profile P_ref, in the same half-block units (review 4 section
	 * 5.3). This is the profile the road is supposed to follow - the terrain
	 * wherever a legal slope plus the fill/cut budget can afford it, and a level
	 * span anchored on the piece ends wherever it cannot (canyon, terrace). It
	 * is what the solver measures deviation against; without it the objective
	 * has no way to tell "follow a 1-block bump" (review 4 section 3.1) from
	 * "plunge into a 12-block canyon" (review 4 section 5.2).
	 *
	 * <p>Defaults to {@link #baseP} until {@link RoadReference} or the region
	 * solver fills it in, so a solve that never runs stays correct.
	 */
	public int refP;
	/** Solved walk surface P = 2 * S; equals baseP until the solver runs. */
	public int solvedP;

	public RoadCell(int x, int z, String style, int baseP, boolean anchor) {
		this.index = -1;
		this.x = x;
		this.z = z;
		this.style = style;
		this.baseP = baseP;
		this.anchor = anchor;
		this.refP = baseP;
		this.solvedP = baseP;
	}

	public static long packedXZ(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}
}
