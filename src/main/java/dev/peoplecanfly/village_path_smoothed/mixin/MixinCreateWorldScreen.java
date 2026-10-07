/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.pipeline.VpsWorldSettings;
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
 * The toggle sets the *pending* per-world enabled choice: a world created
 * afterwards starts with this value written into its own
 * {@code <world>/village_path_smoothed.json} on first chunk generation, so the
 * switch belongs to the world being created, not to the global config.
 * Client-side only; lives in the mixin config's "client" section.
 */
@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen extends Screen {
	protected MixinCreateWorldScreen(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void village_path_smoothed$addToggle(CallbackInfo ci) {
		Boolean pending = VpsWorldSettings.getPendingEnabled();
		boolean initial = pending != null ? pending : VpsWorldSettings.defaultForNewWorld();
		CycleButton<Boolean> toggle = CycleButton.onOffBuilder(initial)
				.create(8, this.height - 58, 170, 20, Component.translatable("vps.createWorld.enabled"),
						(button, value) -> VpsWorldSettings.setPendingEnabled(value));
		toggle.setTooltip(Tooltip.create(Component.translatable("vps.createWorld.enabled.tooltip")));
		this.addRenderableWidget(toggle);
	}
}
