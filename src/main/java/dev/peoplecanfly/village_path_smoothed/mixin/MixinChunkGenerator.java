/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.duck.StructureManagerVpsAccess;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsGenThread;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the MinecraftServer for the STRUCTURE_STARTS step (F12). The
 * StructureManager exposes the owning level, which during worldgen is the
 * ServerLevel.
 *
 * Mixin only accepts two handler shapes: every parameter of the target method
 * plus the CallbackInfo, or the CallbackInfo alone. Declaring a prefix and
 * omitting the trailing parameters is NOT a valid shape - the descriptor check
 * rejects it at APPLY time and, with "required": true in the mixin config,
 * that is a fatal launch error. {@code createStructures} gained a trailing
 * {@code ResourceKey<Level>} in 1.21.10, so 1.21.10+ and the older nodes get
 * their own preprocessor branch. The chunk and template manager parameters are
 * unused; they are declared only to match the descriptor.
 *
 * require = 1 on purpose: see MixinStructureGenerate's comment - this hook is
 * load-bearing, so a missing injection point must fail fast.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator {
	//#if MC >= 12110
	//$$ @Inject(method = "createStructures", at = @At("HEAD"), require = 1)
	//$$ private void village_path_smoothed$captureServerHead(
	//$$ 		RegistryAccess registryAccess,
	//$$ 		ChunkGeneratorStructureState structureState,
	//$$ 		StructureManager structureManager,
	//$$ 		ChunkAccess chunk,
	//$$ 		StructureTemplateManager structureTemplateManager,
	//$$ 		ResourceKey<Level> level,
	//$$ 		CallbackInfo ci
	//$$ ) {
	//$$ 	pushContext(structureManager);
	//$$ }
	//
	//$$ @Inject(method = "createStructures", at = @At("RETURN"), require = 1)
	//$$ private void village_path_smoothed$captureServerReturn(
	//$$ 		RegistryAccess registryAccess,
	//$$ 		ChunkGeneratorStructureState structureState,
	//$$ 		StructureManager structureManager,
	//$$ 		ChunkAccess chunk,
	//$$ 		StructureTemplateManager structureTemplateManager,
	//$$ 		ResourceKey<Level> level,
	//$$ 		CallbackInfo ci
	//$$ ) {
	//$$ 	VpsGenThread.pop();
	//$$ }
	//#else
	@Inject(method = "createStructures", at = @At("HEAD"), require = 1)
	private void village_path_smoothed$captureServerHead(
			RegistryAccess registryAccess,
			ChunkGeneratorStructureState structureState,
			StructureManager structureManager,
			ChunkAccess chunk,
			StructureTemplateManager structureTemplateManager,
			CallbackInfo ci
	) {
		pushContext(structureManager);
	}

	@Inject(method = "createStructures", at = @At("RETURN"), require = 1)
	private void village_path_smoothed$captureServerReturn(
			RegistryAccess registryAccess,
			ChunkGeneratorStructureState structureState,
			StructureManager structureManager,
			ChunkAccess chunk,
			StructureTemplateManager structureTemplateManager,
			CallbackInfo ci
	) {
		VpsGenThread.pop();
	}
	//#endif

	/**
	 * Captures the owning server AND the dimension id for the STRUCTURE_STARTS
	 * step. The dimension is needed by the T1.5 solution cache (F10, anchor-keyed
	 * across StructureStart instances): {@code Structure#generate} has no level
	 * parameter, so the only chance to learn it is here, where the
	 * StructureManager's level is reachable.
	 */
	private static void pushContext(StructureManager structureManager) {
		try {
			LevelAccessor level = ((StructureManagerVpsAccess) (Object) structureManager).village_path_smoothed$getLevel();
			if (level instanceof ServerLevel serverLevel) {
				VpsGenThread.push(serverLevel.getServer(), VpsCompat.registryKeyId(serverLevel.dimension()));
				return;
			}
			if (level instanceof WorldGenRegion region) {
				// 1.21.11 has no dimension() on WorldGenRegion; the owning ServerLevel does
				VpsGenThread.push(region.getLevel().getServer(), VpsCompat.registryKeyId(region.getLevel().dimension()));
				return;
			}
		} catch (Throwable ignored) {
			// fall through: the T1.5 pass degrades to the legacy T5 solve
		}
		VpsGenThread.push(null, null);
	}
}
