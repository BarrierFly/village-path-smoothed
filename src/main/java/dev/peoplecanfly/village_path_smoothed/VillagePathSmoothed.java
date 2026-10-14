/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 *
 * You can copy, modify and distribute this project, even for commercial purposes,
 * under the terms of the WTFPL v2. See the LICENSE file for details.
 */

package dev.peoplecanfly.village_path_smoothed;

import com.mojang.logging.LogUtils;
import dev.peoplecanfly.village_path_smoothed.compat.VpsChunkPyramidInfo;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class VillagePathSmoothed implements ModInitializer {
	public static final String MOD_ID = "village_path_smoothed";
	public static final Logger LOGGER = LogUtils.getLogger();
	public static String MOD_VERSION = "unknown";

	@Override
	public void onInitialize() {
		MOD_VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");

		VillagePathSmoothedConfig.load();

		LOGGER.info("[VPS] Village Path Smoothed {} initialized; config loaded from {}", MOD_VERSION, VillagePathSmoothedConfig.getConfigPath());
		// fix plan v4 F20 (downgraded from an assertion to a log): the accumulated
		// dependency radii are the boundary condition of any future terrain-first
		// design, so print them once and let the source-derived 9 / 10 / 11 be
		// checked against the running game instead of trusted blindly
		VpsChunkPyramidInfo.logOnce();
	}
}
