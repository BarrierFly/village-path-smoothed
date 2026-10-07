/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Village Path Smoothed: enabled" toggle to the Create World screen.
 * The toggle flips the mod's global {@code enabled} config entry (and saves
 * it), which decides whether road smoothing runs during world generation -
 * so it takes effect for the world being created without touching the mods
 * list. Client-side only; lives in the mixin config's "client" section.
 */
@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen extends Screen {
	protected MixinCreateWorldScreen(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void village_path_smoothed$addToggle(CallbackInfo ci) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		CycleButton<Boolean> toggle = CycleButton.onOffBuilder(config.enabled)
				.create(8, this.height - 58, 170, 20, Component.translatable("vps.createWorld.enabled"),
						(button, value) -> {
							VillagePathSmoothedConfig.get().enabled = value;
							VillagePathSmoothedConfig.save();
							VillagePathSmoothed.LOGGER.info("[VPS] mod {} from the create-world screen", value ? "enabled" : "disabled");
						});
		toggle.setTooltip(Tooltip.create(Component.translatable("vps.createWorld.enabled.tooltip")));
		this.addRenderableWidget(toggle);
	}
}
