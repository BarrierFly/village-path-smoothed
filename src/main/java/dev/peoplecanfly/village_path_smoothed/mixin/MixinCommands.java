/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.command.VpsCommands;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandBuildContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the /vps command on every server-side dispatcher (dedicated and
 * integrated servers). This keeps the mod free of any fabric-api dependency.
 */
@Mixin(Commands.class)
public abstract class MixinCommands {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void village_path_smoothed$registerCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
		try {
			((Commands) (Object) this).getDispatcher().register(VpsCommands.buildRoot());
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.error("[VPS] Failed to register the /vps command", t);
		}
	}
}
