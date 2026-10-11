/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPipeline;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * T1.5 hook (B1): runs the joint forward decision at the tail of
 * {@code Structure#generate}, i.e. after the pieces are assembled but before
 * the StructureStart's bounding box is first evaluated (F4).
 *
 * Mixin only accepts two handler shapes: every parameter of the target method
 * plus the CallbackInfo(Returnable), or the CallbackInfo(Returnable) alone.
 * Declaring a prefix and omitting the trailing parameters is NOT a valid
 * shape - the descriptor check rejects it during APPLY and, because this
 * config is "required", that is a fatal launch error. The signature drifted
 * three times across the supported versions (1.21.10 added Holder and
 * ResourceKey, 26.3 added Climate.Sampler), so each shape gets its own
 * preprocessor branch and every branch declares the full parameter list.
 * {@code validBiome} is unused; it is declared only to match the descriptor.
 *
 * require = 1 on purpose: this hook is load-bearing for the T1.5 pipeline, so
 * a missing injection point must fail fast rather than silently degrade.
 * require never protected against descriptor mismatches anyway (that failure
 * happens at APPLY time, where require does not apply).
 */
@Mixin(net.minecraft.world.level.levelgen.structure.Structure.class)
public abstract class MixinStructureGenerate {
	//#if MC >= 260000
	//$$ @Inject(method = "generate", at = @At("RETURN"), require = 1)
	//$$ private void village_path_smoothed$t15(
	//$$ 		Holder<net.minecraft.world.level.levelgen.structure.Structure> structure,
	//$$ 		net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
	//$$ 		RegistryAccess registryAccess,
	//$$ 		ChunkGenerator chunkGenerator,
	//$$ 		BiomeSource biomeSource,
	//$$ 		net.minecraft.world.level.biome.Climate.Sampler climateSampler,
	//$$ 		RandomState randomState,
	//$$ 		StructureTemplateManager structureTemplateManager,
	//$$ 		long seed,
	//$$ 		ChunkPos chunkPos,
	//$$ 		int references,
	//$$ 		LevelHeightAccessor heightAccessor,
	//$$ 		Predicate<Holder<Biome>> validBiome,
	//$$ 		CallbackInfoReturnable<StructureStart> cir
	//$$ ) {
	//$$ 	runT15(cir, chunkGenerator, randomState, structureTemplateManager, chunkPos, heightAccessor);
	//$$ }
	//#elseif MC >= 12110
	//$$ @Inject(method = "generate", at = @At("RETURN"), require = 1)
	//$$ private void village_path_smoothed$t15(
	//$$ 		Holder<net.minecraft.world.level.levelgen.structure.Structure> structure,
	//$$ 		net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
	//$$ 		RegistryAccess registryAccess,
	//$$ 		ChunkGenerator chunkGenerator,
	//$$ 		BiomeSource biomeSource,
	//$$ 		RandomState randomState,
	//$$ 		StructureTemplateManager structureTemplateManager,
	//$$ 		long seed,
	//$$ 		ChunkPos chunkPos,
	//$$ 		int references,
	//$$ 		LevelHeightAccessor heightAccessor,
	//$$ 		Predicate<Holder<Biome>> validBiome,
	//$$ 		CallbackInfoReturnable<StructureStart> cir
	//$$ ) {
	//$$ 	runT15(cir, chunkGenerator, randomState, structureTemplateManager, chunkPos, heightAccessor);
	//$$ }
	//#else
	@Inject(method = "generate", at = @At("RETURN"), require = 1)
	private void village_path_smoothed$t15(
			RegistryAccess registryAccess,
			ChunkGenerator chunkGenerator,
			BiomeSource biomeSource,
			RandomState randomState,
			StructureTemplateManager structureTemplateManager,
			long seed,
			ChunkPos chunkPos,
			int references,
			LevelHeightAccessor heightAccessor,
			Predicate<Holder<Biome>> validBiome,
			CallbackInfoReturnable<StructureStart> cir
	) {
		runT15(cir, chunkGenerator, randomState, structureTemplateManager, chunkPos, heightAccessor);
	}
	//#endif

	private static void runT15(
			CallbackInfoReturnable<StructureStart> cir,
			ChunkGenerator chunkGenerator,
			RandomState randomState,
			StructureTemplateManager structureTemplateManager,
			ChunkPos chunkPos,
			LevelHeightAccessor heightAccessor
	) {
		try {
			VpsPipeline.onStructureGenerated(cir.getReturnValue(), chunkGenerator, randomState, structureTemplateManager, chunkPos, heightAccessor);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.error("[VPS] T1.5 forward decision failed for chunk {}", chunkPos, t);
		}
	}
}
