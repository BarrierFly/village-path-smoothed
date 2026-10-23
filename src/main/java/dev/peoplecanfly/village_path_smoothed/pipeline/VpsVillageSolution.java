/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * Per-StructureStart cache: the road network with its solved profile.
 * Attached through the StructureStartVpsAccess duck interface, so its lifetime
 * equals the structure start instance's lifetime (no global maps to leak).
 *
 * With roadShapeStage=T1_5 the solution is produced during structure assembly
 * ({@link T15Planner}); {@link #shiftedComponents} then carries the moved
 * building footprints ({minX,maxX,minZ,maxY,baseY}) for the write-time
 * diagnostic guard.
 *
 * <p>{@link #network} is not final: the T1.5 pass replaces it with the network
 * re-collected after the building shift, because the noise probe's beard boxes
 * are only correct once the pieces have moved (review 4 section 7, P1).
 */
public final class VpsVillageSolution {
	public RoadNetwork network;
	public boolean solved;
	/** True when the T1.5 forward decision ran (buildings may have been shifted). */
	public boolean t15Applied;
	/** Diagnostic guard runs once per structure, at the first feature-placement write. */
	public boolean guardChecked;
	/** Moved RIGID component footprints {minX,maxX,minZ,maxZ,baseY(shifted)}; empty when nothing moved. */
	public int[][] shiftedComponents = new int[0][];
	/**
	 * Exact cells of every movable BUILDING's body and interior (decor pieces
	 * excluded; audit 2026-10-20 decision 1, narrowed by the 2026-10-23
	 * decision). The writer skips a claimed road column only when its road block
	 * lands on one of those cells, so a smoothed road can never overwrite a
	 * building body while the empty part of the old bounding box stays
	 * writable. Decor is protected block-by-block through {@link #decorBlocks}.
	 */
	public BuildingMask buildingMask = new BuildingMask();
	/** Actual decor template blocks of the identified street decor ({@link RoadCell#packedXYZ}). */
	public java.util.Set<Long> decorBlocks = java.util.Set.of();
	/**
	 * Expected jigsaw-final base block id per packed XYZ of the identified
	 * street decor. Vanilla placement can leave the lowest decor block air at a
	 * street seat (the 2026-10-21 lamp-base gap); the writer restores exactly
	 * these cells when they are still air.
	 */
	public java.util.Map<Long, String> decorBaseBlocks = java.util.Map.of();
	/**
	 * Blocks the village pile features really placed during this start's
	 * placement ({@link RoadCell#packedXYZ}). Recorded by the BlockPileFeature
	 * hook; the writer protects exactly these instead of the 5x5/7x7 potential
	 * box. Mutable on purpose: chunks record while the village places.
	 */
	public final java.util.Set<Long> pileBlocks =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Door-front cells {frontX,frontZ,outwardDX,outwardDZ}; the writer suppresses the railing there. */
	public int[][] doorFronts = new int[0][];
	/**
	 * Debug door signs (user request 2026-10-16; main door and NO FRONT by the
	 * door/basis fix plan 2026-10-17): one entry per MAIN door -
	 * {doorX, doorWalkY, doorZ, outDX, outDZ, roadX, roadSolvedP, roadZ,
	 * doorFloorY, noFront}. The writer places a standing sign at the door front
	 * printing line 1 "doorX doorWalkY doorZ", line 2 the selected road cell,
	 * line 3 the actual door floor and line 4 "NO FRONT" when no eligible
	 * outward cell existed; computed on the FINAL network so line 2 names the
	 * surface the writer really renders.
	 */
	public int[][] doorSigns = new int[0][];
	/**
	 * Debug seam signs (user request 2026-10-16, opt-in since 2026-10-17): one entry per seam
	 * cell of the solved mask - {anchorX, anchorSolvedP, anchorZ, farX,
	 * farSolvedP, farZ, reason}. A seam is a 4-adjacency edge whose cells come
	 * from different sources (different piece, plaza vs road, seat vs road); the
	 * writer places a standing sign above the anchor road cell printing the two
	 * solved coordinates and, when the edge is not legal, the known cause.
	 * Computed on the FINAL network so the printed Y is what the writer renders.
	 */
	public int[][] seamSigns = new int[0][];

	public VpsVillageSolution(RoadNetwork network) {
		this.network = network;
	}
}
