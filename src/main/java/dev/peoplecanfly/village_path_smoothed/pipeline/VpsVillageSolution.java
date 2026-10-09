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
 * building footprints ({minX,maxX,minZ,maxZ,baseY}) for the write-time
 * diagnostic guard.
 */
public final class VpsVillageSolution {
	public final RoadNetwork network;
	public boolean solved;
	/** True when the T1.5 forward decision ran (buildings may have been shifted). */
	public boolean t15Applied;
	/** Diagnostic guard runs once per structure, at the first feature-placement write. */
	public boolean guardChecked;
	/** Moved RIGID component footprints {minX,maxX,minZ,maxZ,baseY(shifted)}; empty when nothing moved. */
	public int[][] shiftedComponents = new int[0][];

	public VpsVillageSolution(RoadNetwork network) {
		this.network = network;
	}
}
