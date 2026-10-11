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
 * The two hooks of the cure route (review 4 section 9).
 *
 * <p>HEAD solves the village and opens the placement context, so the gravity
 * processor can drop the vanilla street layer on every column the mod's road
 * mask claims. RETURN closes the context and writes the road.
 *
 * <p>The solve used to happen at RETURN, which is now too late: by then every
 * block of the piece is already in the world. Moving it to HEAD is safe because
 * nothing of <em>this</em> start has been placed yet and the probe reads the
 * same level, generator and chunk either way (risk R8); the heightmap state a
 * chunk exposes to the generator does not depend on the piece that is about to
 * be placed into it.
 *
 * <p>Both handlers declare every target parameter (the descriptor rule in
 * AGENTS.md is about exactly this pair - the two shapes have to match the
 * target in both cases) and both run with {@code require = 1}.
 */
@Mixin(StructureStart.class)
public abstract class MixinStructureStart implements StructureStartVpsAccess {
	// volatile: the double-checked-lock read below runs outside any lock and
	// another worker thread must see a fully published RoadNetwork (report 4.2 #4)
	@Unique
	private volatile VpsVillageSolution village_path_smoothed$solution;

	@Inject(method = "placeInChunk", at = @At("HEAD"))
	private void village_path_smoothed$onPlaceInChunkHead(
			WorldGenLevel level,
			net.minecraft.world.level.StructureManager structureManager,
			ChunkGenerator generator,
			RandomSource random,
			BoundingBox chunkBB,
			ChunkPos chunkPos,
			CallbackInfo ci
	) {
		try {
			VpsPipeline.onStructurePlacementHead((StructureStart) (Object) this, level, generator, chunkPos);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.error("[VPS] Failed to prepare village road suppression for chunk {}", chunkPos, t);
		}
	}

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