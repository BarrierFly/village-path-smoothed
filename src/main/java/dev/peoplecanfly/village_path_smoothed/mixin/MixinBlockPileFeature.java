/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPlacementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.BlockPileFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#if MC >= 260000
//$$ import net.minecraft.util.RandomSource;
//$$ import net.minecraft.world.level.chunk.ChunkGenerator;
//#else
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.BlockPileConfiguration;
//#endif

/**
 * Records the blocks a village pile feature really places (audit 2026-10-20,
 * decision 4: protect the actual blocks at write time, not the whole 5x5/7x7
 * potential box).
 *
 * <p>The pile placement is random and sparse, so the only exact source is the
 * placement itself. HEAD snapshots the potential box's air cells, RETURN reads
 * them back: a cell that was air and is now occupied was placed by this
 * feature (the pile only writes into empty cells). The positions go into the
 * village solution's pile set and the road writer protects exactly those.
 *
 * <p>Only village piles record: {@link VpsPlacementContext} is live only
 * during a village start's placement. 26.3 turned {@code BlockPileFeature}
 * into a record with {@code place(WorldGenLevel, ChunkGenerator, RandomSource,
 * BlockPos)}, so the handler shapes are separate preprocessor branches.
 */
@Mixin(BlockPileFeature.class)
public abstract class MixinBlockPileFeature {
	/** The pile feature writes at most a 7x7x2 box around its origin (radius 2-3, two layers). */
	@Unique
	private static final int village_path_smoothed$RADIUS = 3;

	@Unique
	private static final ThreadLocal<village_path_smoothed$Snapshot> village_path_smoothed$SNAPSHOT = new ThreadLocal<>();

	//#if MC >= 260000
	//$$ @Inject(method = "place", at = @At("HEAD"), require = 1)
	//$$ private void village_path_smoothed$snapshotBeforePile(
	//$$ 		WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin,
	//$$ 		CallbackInfoReturnable<Boolean> cir
	//$$ ) {
	//$$ 	village_path_smoothed$snapshot(level, origin);
	//$$ }
	//
	//$$ @Inject(method = "place", at = @At("RETURN"), require = 1)
	//$$ private void village_path_smoothed$recordPlacedPileBlocks(
	//$$ 		WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin,
	//$$ 		CallbackInfoReturnable<Boolean> cir
	//$$ ) {
	//$$ 	village_path_smoothed$record(level);
	//$$ }
	//#else
	@Inject(method = "place", at = @At("HEAD"), require = 1)
	private void village_path_smoothed$snapshotBeforePile(
			FeaturePlaceContext<BlockPileConfiguration> context,
			CallbackInfoReturnable<Boolean> cir
	) {
		village_path_smoothed$snapshot(context.level(), context.origin());
	}

	@Inject(method = "place", at = @At("RETURN"), require = 1)
	private void village_path_smoothed$recordPlacedPileBlocks(
			FeaturePlaceContext<BlockPileConfiguration> context,
			CallbackInfoReturnable<Boolean> cir
	) {
		village_path_smoothed$record(context.level());
	}
	//#endif

	@Unique
	private static void village_path_smoothed$snapshot(WorldGenLevel level, BlockPos origin) {
		if (VpsPlacementContext.current() == null) {
			return;
		}
		village_path_smoothed$SNAPSHOT.set(new village_path_smoothed$Snapshot(level, origin));
	}

	@Unique
	private static void village_path_smoothed$record(WorldGenLevel level) {
		village_path_smoothed$Snapshot snapshot = village_path_smoothed$SNAPSHOT.get();
		village_path_smoothed$SNAPSHOT.remove();
		if (snapshot != null) {
			snapshot.record(level);
		}
	}

	@Unique
	private static final class village_path_smoothed$Snapshot {
		private final int originX;
		private final int originY;
		private final int originZ;
		private final boolean[] wasAir;

		private village_path_smoothed$Snapshot(WorldGenLevel level, BlockPos origin) {
			this.originX = origin.getX();
			this.originY = origin.getY();
			this.originZ = origin.getZ();
			this.wasAir = new boolean[(village_path_smoothed$RADIUS * 2 + 1)
					* (village_path_smoothed$RADIUS * 2 + 1) * 2];
			int i = 0;
			for (int dy = 0; dy <= 1; dy++) {
				for (int dx = -village_path_smoothed$RADIUS; dx <= village_path_smoothed$RADIUS; dx++) {
					for (int dz = -village_path_smoothed$RADIUS; dz <= village_path_smoothed$RADIUS; dz++) {
						this.wasAir[i++] = level.getBlockState(
								new BlockPos(this.originX + dx, this.originY + dy, this.originZ + dz)).isAir();
					}
				}
			}
		}

		private void record(WorldGenLevel level) {
			int i = 0;
			for (int dy = 0; dy <= 1; dy++) {
				for (int dx = -village_path_smoothed$RADIUS; dx <= village_path_smoothed$RADIUS; dx++) {
					for (int dz = -village_path_smoothed$RADIUS; dz <= village_path_smoothed$RADIUS; dz++) {
						int x = this.originX + dx;
						int y = this.originY + dy;
						int z = this.originZ + dz;
						if (this.wasAir[i++] && !level.getBlockState(new BlockPos(x, y, z)).isAir()) {
							VpsPlacementContext.recordPileBlock(x, y, z);
						}
					}
				}
			}
		}
	}
}
