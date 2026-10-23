/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * Pure decision helpers for the per-cell fill/clear caps. No Minecraft types,
 * so unit tests can load this class without the game on the classpath.
 */
public final class RoadWritePolicy {
	/** Sentinel walk surface for a column the writer refused to write (fix plan v4 D6-b). */
	public static final int UNWRITTEN = Integer.MIN_VALUE;
	/** Sentinel for "no ceiling above the road" in the kerb/ceiling helpers. */
	public static final int NO_CEILING = Integer.MIN_VALUE;

	private RoadWritePolicy() {
	}

	/**
	 * User decision 2026-10-17: a non-terrain decoration always wins its block.
	 * The road block, the support fill, the clear, the kerb, the ceiling lining
	 * and every railing skip it instead of replacing it. Natural terrain is
	 * still written normally (the classifier lives in
	 * {@code terrain.FeatureProtection}, this method keeps the decision pure
	 * and unit-testable).
	 */
	public static boolean mayOverwrite(boolean protectedDecoration) {
		return !protectedDecoration;
	}

	/**
	 * What the writer actually puts in one column, in the same half-block walk
	 * surface units the solver uses ({@code S = 2 * walkSurface}).
	 *
	 * <p>Extracted as a pure function (fix plan v4 M0) so the bench measures the
	 * writer's real height decisions instead of mirroring them: the road profile
	 * can be legal in the mask and still be overwritten at write time by the
	 * water-crossing branch, the dimension clamp or the exception fallback
	 * (fix plan v4 F7). The bench calls these two methods for every cell, so a
	 * change to the writer's geometry shows up in {@code writtenViolEdg} without
	 * the test duplicating a rule.
	 *
	 * @param walkSurface2 the walk surface actually written, {@link #UNWRITTEN}
	 *        when the writer refused the cell
	 * @param slab whether the road block is a bottom slab
	 * @param written false when the writer refused to write the column
	 * @param overrodeSolved true when the written walk surface differs from the
	 *        cell's solved surface - i.e. the writer broke the solution
	 */
	public record Column(int walkSurface2, boolean slab, boolean written, boolean overrodeSolved) {
	}

	/**
	 * True when the road sits more than {@code maxFillLayers} whole blocks
	 * above the ground and must become a bridge deck instead of stacking an
	 * unlimited landfill. A negative cap means unlimited (never bridge).
	 *
	 * @param fillLayers whole-block layers of fill between the ground surface and the road block
	 * @param maxFillLayers configured cap (roadSmoothing.maxFillLayers)
	 */
	public static boolean bridgeDeck(int fillLayers, int maxFillLayers) {
		return maxFillLayers >= 0 && fillLayers > maxFillLayers;
	}

	/**
	 * Inclusive top Y of the clear loop above the road block; the caller loops
	 * from {@code roadTopBlockY + 1} to this value, so a result below that
	 * start clears nothing. A negative cap means unlimited (clear up to the
	 * vanilla surface, the old behaviour).
	 *
	 * @param roadTopBlockY Y of the top road block
	 * @param surfaceTopY first air above the vanilla surface
	 * @param maxClearLayers configured cap (roadSmoothing.maxClearLayers)
	 */
	public static int clearTopY(int roadTopBlockY, int surfaceTopY, int maxClearLayers) {
		if (maxClearLayers < 0) {
			return surfaceTopY - 1;
		}
		return Math.min(surfaceTopY - 1, roadTopBlockY + Math.max(0, maxClearLayers));
	}

	/**
	 * True when the capped clear leaves terrain above the headroom: that
	 * terrain forms a tunnel roof and the writer lines it with the kerb
	 * material. Never true for the unlimited cap.
	 */
	public static boolean tunnelRoof(int roadTopBlockY, int surfaceTopY, int clearTop, int maxClearLayers) {
		return maxClearLayers >= 0
				&& clearTop < surfaceTopY - 1
				&& clearTop >= roadTopBlockY;
	}

	/**
	 * True when the ceiling at {@code ceilingBottomY} was formed by the clear cap
	 * itself (the block directly above the cleared space is terrain), rather than
	 * a natural cave roof further above. Only a cap-formed ceiling turns its
	 * single bottom layer into glowstone.
	 */
	public static boolean capFormedCeiling(int clearTop, int highestBlockY, int maxClearLayers, int ceilingBottomY) {
		return maxClearLayers >= 0 && clearTop < highestBlockY && ceilingBottomY == clearTop + 1;
	}

	/**
	 * Road walk surface in half-block units: a whole road block tops out 1.0
	 * above its block Y, a bottom slab 0.5. All drop criteria are computed in
	 * this unit (A1) - never from {@code roadTopBlockY + 1}.
	 */
	public static int roadWalkSurface2(int roadTopBlockY, boolean slab) {
		return 2 * roadTopBlockY + (slab ? 1 : 2);
	}

	/** Kerb: the side walk surface is more than {@code minDropHeight} whole blocks above the road. */
	public static boolean kerbApplies(int sideWalkSurface2, int roadWalkSurface2, int minDropHeight) {
		return sideWalkSurface2 - roadWalkSurface2 > 2 * minDropHeight;
	}

	/**
	 * Top Y the kerb wall may write. When the road has a ceiling (a cap-formed
	 * roof or a natural cave roof), the wall stops one block below it: the
	 * ceiling layer itself belongs to the road column only, and the kerb must not
	 * climb the hillside above the tunnel. Open sky ({@link #NO_CEILING}) keeps
	 * the old behaviour and wraps up to the probed side top.
	 *
	 * @param probeTopY highest collision/fluid block in the two probed side columns
	 * @param ceilingBottomY bottom block of the road's ceiling, or {@link #NO_CEILING}
	 */
	public static int kerbTopY(int probeTopY, int ceilingBottomY) {
		if (ceilingBottomY == NO_CEILING) {
			return probeTopY;
		}
		return Math.min(probeTopY, ceilingBottomY - 1);
	}

	/**
	 * End-cap trigger (user decision 2026-10-21): the cap cell alone decides
	 * whether the wall's along-road end is open. "Open" means there is no
	 * terrain above the current wall top: the cap cell's highest terrain block
	 * is at or below {@code wallTop}. The second probed cell is only used when
	 * placing the layers above the wall top (probe-2-wrap-1); it never vetoes
	 * the end.
	 *
	 * @param capTopmostY highest terrain block Y in the cap cell (from the road
	 *        block's level), or {@link Integer#MIN_VALUE} when the column is
	 *        empty up there
	 * @param wallTop highest kerb block Y written for the current wall
	 */
	public static boolean kerbEndOpen(int capTopmostY, int wallTop) {
		return capTopmostY <= wallTop;
	}

	/**
	 * True when a wrapped kerb run's top may take its leaf railing. A run
	 * artificially cut off at the road's ceiling gets no cutoff-top leaves; the
	 * world edge is handled by the writer's bounds check.
	 */
	public static boolean kerbRailingAllowed(int runTopY, int ceilingBottomY) {
		return ceilingBottomY == NO_CEILING || runTopY + 1 < ceilingBottomY;
	}

	/** Railing: the road walk surface is more than {@code minDropHeight} whole blocks above the side. */
	public static boolean railingApplies(int sideWalkSurface2, int roadWalkSurface2, int minDropHeight) {
		return roadWalkSurface2 - sideWalkSurface2 > 2 * minDropHeight;
	}

	/**
	 * Walk surface a water crossing is written at (user decision 2026-10-15).
	 *
	 * <p>A crossing is never below the water surface: a profile at or below it is
	 * raised to {@code waterWalkSurface2}, where the scaffold sits (the scaffold is
	 * the crossing's walk surface, user decision 2026-10-14). A profile above the
	 * water keeps its solved surface and becomes a bridge deck - the writer places
	 * no scaffolding and no support fill from the waterbed there, however shallow
	 * the water is.
	 *
	 * @param profileWalkSurface2 the cell's written walk surface before the water rule
	 * @param waterWalkSurface2 the water surface in walk units ({@code 2 * surfaceTopY})
	 * @return {@code max(profile, water)}: the scaffold top at or below water, the
	 *         solved (bridge) surface above it
	 */
	public static int crossingWrittenWalkSurface2(int profileWalkSurface2, int waterWalkSurface2) {
		return Math.max(profileWalkSurface2, waterWalkSurface2);
	}

	/**
	 * Height decision of the profile branch: the road block sits where the solver
	 * put the walk surface. A height outside the dimension bounds is <b>not</b>
	 * clamped any more (fix plan v4 D6-b) - clamping was the writer silently
	 * deciding a height - so the column comes back unwritten and the caller offers
	 * it the single-cell surface nudge below.
	 */
	public static Column profileColumn(int solvedP, int minY, int maxY) {
		int roadTopBlockY = roadTopBlockY(solvedP);
		boolean slab = (solvedP & 1) != 0;
		if (roadTopBlockY < minY || roadTopBlockY > maxY) {
			return new Column(UNWRITTEN, slab, false, true);
		}
		return new Column(roadWalkSurface2(roadTopBlockY, slab), slab, true, false);
	}

	/**
	 * 2026-10-23: true when the road block of a column lands on a protected
	 * building cell. The mask is the exact building body + interior
	 * ({@link BuildingMask}); there is no bounding-box or Y-margin expansion any
	 * more, so the empty part of a template is written normally.
	 */
	public static boolean insideBuilding(BuildingMask buildingMask, int x, int z, int solvedP) {
		return buildingMask != null && buildingMask.protectsRoadBlock(x, z, solvedP);
	}

	/**
	 * 2026-10-23: true when the exact block is a protected building cell. The
	 * kerb, the clear above the road and the ceiling lining use this test; the
	 * decoration classifier is checked separately by the writer.
	 */
	public static boolean insideBuildingAt(BuildingMask buildingMask, int x, int y, int z) {
		return buildingMask != null && buildingMask.protects(x, y, z);
	}

	/**
	 * 2026-10-23: true when the column carries any protected building cell. A
	 * vanilla side street routed through a house is still in the road network
	 * but the writer skips its body; it must never be chosen as a door front.
	 */
	public static boolean insideFootprint(BuildingMask buildingMask, int x, int z) {
		return buildingMask != null && buildingMask.hasColumn(x, z);
	}

	/**
	 * The single-cell surface nudge (fix plan v4 D6-b, rule from AGENTS.md
	 * 2026-10-14): when a cell cannot carry its solved height - out of the
	 * dimension bounds, or its profile write threw - it may be written at the
	 * real surface instead, but only if
	 *
	 * <ol>
	 *   <li>every neighbouring walk surface stays within one half unit, i.e. the
	 *       nudge cannot introduce a step bigger than the one the solver already
	 *       guarantees, and</li>
	 *   <li>it is not beside a building entrance, so a nudged cell cannot pull a
	 *       doorway away from the road.</li>
	 * </ol>
	 *
	 * <p>Otherwise the cell is refused: the writer writes nothing and counts it.
	 * This is still "the writer is a renderer, not a second solver" - the nudge
	 * moves ONE cell by at most the half-block step budget, never a run, a chunk
	 * or a profile.
	 *
	 * @param neighbourWalkSurfaces2 walk surfaces of the cell's road neighbours,
	 *        as they will be written (their solved surfaces); null or empty means
	 *        "no neighbours", which makes the nudge legal
	 */
	public static Column surfaceNudgeColumn(int solvedP, int realSurfaceTopY, int minY, int maxY,
			int[] neighbourWalkSurfaces2, boolean nearBuildingEntrance) {
		if (nearBuildingEntrance) {
			return new Column(UNWRITTEN, false, false, false);
		}
		int candidate = surfaceNudgeWalkSurface2(realSurfaceTopY);
		int blockY = (candidate - 2) / 2;
		if (blockY < minY || blockY > maxY) {
			return new Column(UNWRITTEN, false, false, false);
		}
		if (!nudgeKeepsNeighboursLegal(candidate, neighbourWalkSurfaces2)) {
			return new Column(UNWRITTEN, false, false, false);
		}
		return new Column(candidate, false, true, candidate != solvedP);
	}

	/** Walk surface of a plain full road block sitting on the real surface. */
	public static int surfaceNudgeWalkSurface2(int realSurfaceTopY) {
		return roadWalkSurface2(realSurfaceTopY - 1, false);
	}

	/** True when no neighbour walk surface is more than one half unit away from the candidate. */
	public static boolean nudgeKeepsNeighboursLegal(int candidateWalkSurface2, int[] neighbourWalkSurfaces2) {
		if (neighbourWalkSurfaces2 == null) {
			return true;
		}
		for (int neighbour : neighbourWalkSurfaces2) {
			if (Math.abs(candidateWalkSurface2 - neighbour) > 1) {
				return false;
			}
		}
		return true;
	}

	/** Inverse of {@link #roadWalkSurface2}: the block Y a written walk surface belongs to. */
	public static int roadBlockY(int walkSurface2, boolean slab) {
		return (walkSurface2 - (slab ? 1 : 2)) / 2;
	}

	/** Y of the top road block for a solved walk surface: whole block vs bottom slab. */
	public static int roadTopBlockY(int solvedP) {
		return (solvedP & 1) == 0 ? solvedP / 2 - 1 : (solvedP - 1) / 2;
	}

	private static int clamp(int minY, int maxY, int y) {
		return Math.max(minY, Math.min(maxY, y));
	}

	/**
	 * True when the solved walk surface {@code p} (half units) is beyond the
	 * affordable fill or cut cap. Since fix plan v2 R3 this is an observation flag
	 * only: the caller counts the cell and renders the solved height as-is
	 * (bridge deck / tunnel per the caps); it never snaps anything.
	 *
	 * <p>Directional since 2026-10-14: the two surfaces come from the per-cell
	 * probes (down for the ground, up for the ceiling), not from the heightmap
	 * top, so a canopy above the road cannot fake a fill and an overhang cannot
	 * fake a cut. A negative cap means unlimited in that direction.
	 *
	 * @param groundSurfaceTopY first air above the ground found scanning down
	 * @param ceilingSurfaceTopY first air above the ceiling found scanning up
	 */
	public static boolean overBudget(int p, int groundSurfaceTopY, int ceilingSurfaceTopY,
			int maxFillLayers, int maxClearLayers) {
		if (p >= 2 * groundSurfaceTopY) {
			return maxFillLayers >= 0 && p - 2 * groundSurfaceTopY > 2 * (maxFillLayers + 2);
		}
		return maxClearLayers >= 0 && 2 * ceilingSurfaceTopY - p > 2 * (maxClearLayers + 2);
	}
}
