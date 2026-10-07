/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration (Q16). The mod only compile-depends on the ModMenu API;
 * this entrypoint class is never loaded unless ModMenu itself is installed and
 * invokes the "modmenu" entrypoint.
 */
public class VpsModMenuApi implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return VpsConfigScreen::new;
	}
}
