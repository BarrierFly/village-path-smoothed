/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.duck.StructureStartVpsAccess;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPipeline;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsVillageSolution;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Single hook of the whole mod: after a structure start has placed its pieces
 * into a chunk, rebuild the road mask from the pieces' templates, solve the
 * smooth profile (cached per StructureStart) and rewrite the road blocks that
 * fall into this chunk's writable area.
 */
@Mixin(StructureStart.class)
public abstract class MixinStructureStart implements StructureStartVpsAccess {
	@Unique
	private VpsVillageSolution village_path_smoothed$solution;

	@Inject(method = "placeInChunk", at = @At("RETURN"))
	private void village_path_smoothed$onPlaceInChunkTail(
			WorldGenLevel level,
			net.minecraft.world.level.StructureManager structureManager,
			ChunkGenerator generator,
			RandomSource random,
			BoundingBox chunkBB,
			ChunkPos chunkPos,
			CallbackInfo ci
	) {
		try {
			VpsPipeline.onStructurePlacedInChunk((StructureStart) (Object) this, level, generator, chunkBB, chunkPos);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.error("[VPS] Failed to smooth village roads for chunk {}", chunkPos, t);
		}
	}

	@Override
	public VpsVillageSolution village_path_smoothed$getSolution() {
		return this.village_path_smoothed$solution;
	}

	@Override
	public void village_path_smoothed$setSolution(VpsVillageSolution solution) {
		this.village_path_smoothed$solution = solution;
	}
}
