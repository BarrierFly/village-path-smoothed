/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsWorldSettings;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Village Path Smoothed: enabled" toggle to the Create World screen.
 *
 * The toggle value is bound to the world being created: pressing "Create"
 * arms the pending choice with the (finalized) world name from the UI state,
 * and the world's own {@code <world>/village_path_smoothed.json} is seeded
 * with it at that world's first chunk generation. Cancelling arms nothing, so
 * existing worlds without their own file fall back to the global default.
 * Client-side only; lives in the mixin config's "client" section.
 */
@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen extends Screen {
	protected MixinCreateWorldScreen(Component title) {
		super(title);
	}

	@Shadow
	private WorldCreationUiState uiState;

	@Inject(method = "init", at = @At("RETURN"))
	private void village_path_smoothed$addToggle(CallbackInfo ci) {
		Boolean picked = VpsWorldSettings.getPendingEnabled();
		boolean initial = picked != null ? picked : VillagePathSmoothedConfig.get().enabled;
		CycleButton<Boolean> toggle = CycleButton.onOffBuilder(initial)
				.create(8, this.height - 58, 170, 20, Component.translatable("vps.createWorld.enabled"),
						(button, value) -> VpsWorldSettings.setPendingEnabled(value));
		toggle.setTooltip(Tooltip.create(Component.translatable("vps.createWorld.enabled.tooltip")));
		this.addRenderableWidget(toggle);
	}

	@Inject(method = "onCreate", at = @At("HEAD"))
	private void village_path_smoothed$onCreate(CallbackInfo ci) {
		VpsWorldSettings.armForCreation(this.uiState.getName());
	}
}
