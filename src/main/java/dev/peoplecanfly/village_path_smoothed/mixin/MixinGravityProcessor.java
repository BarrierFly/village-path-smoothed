/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.mixin;

import dev.peoplecanfly.village_path_smoothed.pipeline.PlacementPolicy;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPlacementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Review 4 section 9: the cure route. Vanilla streets are placed <em>before</em>
 * this mod runs - {@code StructureStart#placeInChunk} walks the template
 * through the processor chain, {@code GravityProcessor} drops the road layer
 * onto the real terrain, and only then do we get to rewrite it. The mod could
 * only ever overwrite what vanilla had already written, and the cleanup range
 * had two windows where the vanilla block survived (the profile 9-10 blocks
 * above the surface, or 7-8 below it): exactly the "two roads stacked on the
 * same spot" report.
 *
 * <p>So we stop placing it. Returning null from the gravity processor drops the
 * block: {@code StructureTemplate#processBlockInfos} stops the chain for that
 * block and never adds it to the placement list, so nothing is written and no
 * later processor sees it. {@link RoadWriter} then writes the road block as the
 * single source, and the two residue windows are gone as a mechanism rather
 * than as a tuning question.
 *
 * <p>The hook point and its shape are stable across every supported version
 * (checked on the workspace's vanilla sources): the parameter list is
 * {@code (LevelReader, BlockPos, BlockPos, StructureBlockInfo,
 * StructureBlockInfo, StructurePlaceSettings)} in 1.19.4, 1.20.1, 1.21.1,
 * 1.21.10, 1.21.11 and 26.3 alike. Only the accessor spelling of
 * {@code StructureBlockInfo} drifts, and that is the {@code //#if} branch
 * below - never the descriptor.
 */
@Mixin(GravityProcessor.class)
public abstract class MixinGravityProcessor {
	@Inject(method = "processBlock", at = @At("RETURN"))
	private static void village_path_smoothed$suppressClaimedRoadLayer(
			LevelReader level,
			BlockPos targetPosition,
			BlockPos referencePosition,
			StructureTemplate.StructureBlockInfo originalBlockInfo,
			StructureTemplate.StructureBlockInfo processedBlockInfo,
			StructurePlaceSettings settings,
			CallbackInfoReturnable<StructureTemplate.StructureBlockInfo> cir
	) {
		if (VpsPlacementContext.current() == null) {
			return;
		}
		//#if MC >= 12001
		//$$ int templateY = originalBlockInfo.pos().getY();
		//$$ BlockPos worldPos = processedBlockInfo.pos();
		//#else
		int templateY = originalBlockInfo.pos.getY();
		BlockPos worldPos = processedBlockInfo.pos;
		//#endif
		if (VpsPlacementContext.decide(templateY, worldPos.getX(), worldPos.getZ()) == PlacementPolicy.Decision.SUPPRESS) {
			cir.setReturnValue(null);
		}
	}
}