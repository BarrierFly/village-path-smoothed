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
	/**
	 * Index of the {@code StructurePiece} this cell was claimed from (into the
	 * start's piece list), or -1. Plan C (2026-10-15) needs it to move the
	 * plaza piece that owns a pinned plaza group.
	 */
	public int pieceIndex = -1;
	/**
	 * True when this cell was claimed from a {@code town_centers} piece (the
	 * plaza around a meeting point or fountain). Plaza cells are solved flat at
	 * a pinned level and rendered by their own rigid piece - the writer never
	 * writes a road block on them (plan C, user decision 2026-10-15).
	 */
	public boolean plaza;
	/**
	 * Walk surface the owning plaza piece's rigid platform actually renders at:
	 * {@code 2 * (piece bounding-box minY + 1)}. Read live off the piece, so it
	 * is re-collected after any T1.5 move and is what the post-shift pin uses.
	 */
	public int plazaVanillaP;
	/** Set by {@link RoadNetwork#applyPlazaPins}: this cell's solved height is hard-pinned. */
	public boolean plazaPinned;
	/** The pinned walk surface (half units) for a {@link #plazaPinned} cell. */
	public int plazaPinP;
	/**
	 * Decor seat (原版多向道路结构调查 2026-10-15, reworked 2026-10-16): a street
	 * piece's up-facing y=0 jigsaw whose final_state is one of the base
	 * materials. The cell is a NORMAL solver cell - it rides the solved profile
	 * (|dP| <= 1 to its neighbours, whole-block walk surfaces only so the decor
	 * piece on top can shift by whole blocks) - and the writer renders the seat
	 * block at the solved height; the RIGID decor piece standing on it (lamp,
	 * tree, hay pile) is shifted to match at T1.5, shape unchanged.
	 */
	public boolean seat;
	/** The seat's final_state block id (e.g. "grass_block"); rendered by the writer. */
	public String seatBlock;
	/**
	 * 2026-10-21: a non-road fixture base column (a house/street lamp's floor
	 * cell). It is included in the solve with a fixed pin so its 4-adjacent
	 * roads are constrained to the base's real walk surface; the writer never
	 * writes a road block, fill, clear or seat on it.
	 */
	public boolean fixtureBase;
	/**
	 * 2026-10-22: the road cell overlaps a structure ground column (a street
	 * fixture base or a street-template scenery block such as farmland). The
	 * cell is solved as one flat, vertically movable unit with the structure and
	 * the writer renders the structure ground instead of a road: the vanilla
	 * road layer is suppressed for the claimed column, so without this the
	 * surface would be a hole and the roads on both sides would not meet it.
	 */
	public boolean groundOverlap;
	/** Block id of the structure ground rendered at the solved level. */
	public String groundBlock;
	/** Ground block Y the structure rendered at, or {@link #GROUND_Y_UNKNOWN}. */
	public int groundOldBlockY;
	/** Source template block offsets above the ground block (0 = the ground). */
	public int[] groundAboveLocalYs;
	/** Groups cells of one source structure; {@link RoadNetwork} builds the unit. */
	public long groundGroupKey = Long.MIN_VALUE;
	/** True when the overlap unit may move as a whole (T1.5 / scenery). */
	public boolean groundGroupMovable;
	/** Sentinel for a scenery ground whose vanilla Y is recorded at write time. */
	public static final int GROUND_Y_UNKNOWN = Integer.MIN_VALUE;
	/**
	 * R1-4 / seam reason: set when {@code settleSeats} had to clamp a seat's
	 * solved level at the world edge, so the decor piece could not reach the pin.
	 * Diagnostic only; the seam signs name it as a known smoothing blocker.
	 */
	public boolean edgeClamped;

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

	/**
	 * World position key with the same layout {@code BlockPos.asLong} uses, kept
	 * here so the pure pipeline (context + writer) can key protected decoration
	 * blocks without importing Minecraft.
	 */
	public static long packedXYZ(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	public static int unpackX(long key) {
		int x = (int) (key >> 38);
		return x >= 0x2000000 ? x - 0x4000000 : x;
	}

	public static int unpackY(long key) {
		int y = (int) (key & 0xFFF);
		return y >= 0x800 ? y - 0x1000 : y;
	}

	public static int unpackZ(long key) {
		int z = (int) ((key >> 12) & 0x3FFFFFF);
		return z >= 0x2000000 ? z - 0x4000000 : z;
	}
}
