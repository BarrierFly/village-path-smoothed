/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.duck.StructureManagerVpsAccess;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsGenThread;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the MinecraftServer for the STRUCTURE_STARTS step (F12). The
 * handler only declares the leading parameters shared by every supported
 * version (trailing parameters are omitted): RegistryAccess, the structure
 * state and the StructureManager; the latter exposes the owning level, which
 * during worldgen is the ServerLevel.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator {
	@Inject(method = "createStructures", at = @At("HEAD"), require = 0)
	private void village_path_smoothed$captureServerHead(
			RegistryAccess registryAccess,
			ChunkGeneratorStructureState structureState,
			StructureManager structureManager,
			CallbackInfo ci
	) {
		VpsGenThread.push(serverOf(structureManager));
	}

	@Inject(method = "createStructures", at = @At("RETURN"), require = 0)
	private void village_path_smoothed$captureServerReturn(
			RegistryAccess registryAccess,
			ChunkGeneratorStructureState structureState,
			StructureManager structureManager,
			CallbackInfo ci
	) {
		VpsGenThread.pop();
	}

	private static MinecraftServer serverOf(StructureManager structureManager) {
		try {
			LevelAccessor level = ((StructureManagerVpsAccess) (Object) structureManager).village_path_smoothed$getLevel();
			if (level instanceof ServerLevel serverLevel) {
				return serverLevel.getServer();
			}
			if (level instanceof WorldGenRegion region) {
				return region.getLevel().getServer();
			}
		} catch (Throwable ignored) {
			// fall through: the T1.5 pass degrades to the legacy T5 solve
		}
		return null;
	}
}
