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
	/**
	 * Vanilla walk surface P0 = 2 * surfaceTopY, probed at collect time. The
	 * deviation objectives measure against this value. Not final only because
	 * the probe-boundary alignment pass (real-heightmap columns pulling their
	 * noise-probed neighbours onto the real value) rewrites it before the
	 * network is solved.
	 */
	public int baseP;
	/**
	 * Water-crossing cell: the water surface is its PREFERRED walk surface, not
	 * a fixed anchor. The solver treats it like any other cell (the water
	 * surface is its baseP, so deviating from it costs deviation), and a
	 * solution that leaves it for a smoother profile is counted in
	 * {@code SolveStats#waterAnchorDeviations()}.
	 */
	public final boolean anchor;
	/**
	 * True when {@link #baseP} came from the real heightmap (the column's chunk
	 * is inside the reliably readable +/-1 chunk window); false for noise-probed
	 * columns. Set by {@link RoadCollector} and consumed by the probe-boundary
	 * alignment pass; purely diagnostic otherwise.
	 */
	public boolean realHeight;
	/** Solved walk surface P = 2 * S; equals baseP until the solver runs. */
	public int solvedP;

	public RoadCell(int x, int z, String style, int baseP, boolean anchor) {
		this.index = -1;
		this.x = x;
		this.z = z;
		this.style = style;
		this.baseP = baseP;
		this.anchor = anchor;
		this.solvedP = baseP;
	}

	public static long packedXZ(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}
}
