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
	 * 3D bounding boxes {minX,maxX,minY,maxY,minZ,maxZ} of every PROTECTED piece
	 * (movable buildings + the immovable hay piles, user decision 2026-10-16).
	 * The writer skips a claimed road column whose road block lands inside one,
	 * so a smoothed road can never overwrite a building or a hay pile (plan A,
	 * user decision 2026-10-15).
	 */
	public int[][] buildingBoxes = new int[0][];
	/** Door-front cells {frontX,frontZ,outwardDX,outwardDZ}; the writer suppresses the railing there. */
	public int[][] doorFronts = new int[0][];
	/**
	 * Debug door signs (user request 2026-10-16, always on): one entry per
	 * template door of a movable building - {doorX, doorWalkY, doorZ, outDX,
	 * outDZ, roadX, roadSolvedP, roadZ}. The writer places a standing sign at
	 * the door front printing line 1 "doorX doorWalkY doorZ" and line 2
	 * "roadX roadSolvedP/2 roadZ" (numbers only); computed on the FINAL
	 * network so line 2 names the surface the writer really renders.
	 */
	public int[][] doorSigns = new int[0][];
	/**
	 * Debug seam signs (user request 2026-10-16, always on): one entry per seam
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
