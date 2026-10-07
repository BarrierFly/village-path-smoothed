/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * Per-StructureStart cache: the road network with its solved profile.
 * Attached through the StructureStartVpsAccess duck interface, so its lifetime
 * equals the structure start instance's lifetime (no global maps to leak).
 */
public final class VpsVillageSolution {
	public final RoadNetwork network;
	public boolean solved;

	public VpsVillageSolution(RoadNetwork network) {
		this.network = network;
	}
}
