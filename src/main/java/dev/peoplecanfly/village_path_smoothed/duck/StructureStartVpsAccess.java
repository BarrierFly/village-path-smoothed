/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.duck;

import dev.peoplecanfly.village_path_smoothed.pipeline.VpsVillageSolution;

/**
 * Duck interface mixed into StructureStart; carries the cached, solved road
 * profile so each village is only solved once per structure-start instance.
 */
public interface StructureStartVpsAccess {
	VpsVillageSolution village_path_smoothed$getSolution();

	void village_path_smoothed$setSolution(VpsVillageSolution solution);
}
