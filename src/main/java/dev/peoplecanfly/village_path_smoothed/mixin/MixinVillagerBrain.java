/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPipeline;
//#if MC >= 12111
//$$ import net.minecraft.world.entity.npc.villager.Villager;
//#else
import net.minecraft.world.entity.npc.Villager;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Debug hook: cancels the villager brain tick while
 * {@code debug.disableVillagerBrains} is on, so villagers stand still and do
 * not wander over freshly smoothed roads while the mod is being observed.
 * Purely observational, togglable at runtime via /vps debug villagerbrain;
 * no behaviour changes while the switch is off.
 *
 * The handler deliberately takes only the CallbackInfo: the target signature
 * changed from customServerAiStep() to customServerAiStep(ServerLevel) in
 * 1.21.10, and a no-arg handler matches both. The Villager import moved from
 * npc to npc.villager in 1.21.11 (preprocessor branch above).
 */
@Mixin(Villager.class)
public abstract class MixinVillagerBrain {

	@Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
	private void village_path_smoothed$cancelBrainTick(CallbackInfo ci) {
		if (VillagePathSmoothedConfig.get().debug.disableVillagerBrains) {
			VpsPipeline.DEBUG_VILLAGER_BRAIN_SKIPS.incrementAndGet();
			ci.cancel();
		}
	}
}
