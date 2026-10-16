/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCollector;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadSmoother;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadWriter;
import dev.peoplecanfly.village_path_smoothed.pipeline.T15Planner;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPipeline;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsWorldSettings;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /vps config show|reload, /vps stats, /vps dump [radius] (Q17).
 * Registered through MixinCommands, so no fabric-api dependency is needed.
 */
public final class VpsCommands {
	private VpsCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
		return Commands.literal("vps")
				//#if MC >= 12111
				//$$ .requires(Commands.hasPermission(new net.minecraft.server.permissions.PermissionCheck.Require(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)))
				//#else
				.requires(source -> source.hasPermission(2))
				//#endif
				.then(Commands.literal("config")
						.then(Commands.literal("show").executes(context -> {
							context.getSource().sendSystemMessage(Component.literal(configSummary(context.getSource().getServer())));
							return 1;
						}))
						.then(Commands.literal("reload").executes(context -> {
							VillagePathSmoothedConfig.load();
							// solutions are a function of the config: the anchor-keyed cache
							// must not serve pre-reload networks (F10)
							VpsPipeline.clearSolutionCache();
							context.getSource().sendSystemMessage(Component.literal("[VPS] config reloaded from " + VillagePathSmoothedConfig.getConfigPath()));
							return 1;
						})))
				.then(Commands.literal("stats").executes(context -> {
					context.getSource().sendSystemMessage(Component.literal(statsSummary()));
					return 1;
				}))
				.then(Commands.literal("world")
						.then(Commands.literal("show").executes(context -> {
							context.getSource().sendSystemMessage(Component.literal(worldSummary(context.getSource().getServer())));
							return 1;
						}))
						.then(Commands.literal("on").executes(context -> setWorldEnabled(context.getSource(), true)))
						.then(Commands.literal("off").executes(context -> setWorldEnabled(context.getSource(), false))))
				.then(Commands.literal("dump")
						.executes(context -> dump(context.getSource(), 2))
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 8)).executes(context ->
								dump(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
				.then(Commands.literal("debug")
						.then(Commands.literal("towncenters").executes(context -> townCentreReport(context.getSource())))
						.then(Commands.literal("villagerbrain")
								.then(Commands.literal("freeze")
										.then(Commands.literal("on").executes(context -> setVillagerBrainFreeze(context.getSource(), true, false)))
										.then(Commands.literal("off").executes(context -> setVillagerBrainFreeze(context.getSource(), false, false))))
								// G1: the legacy bare on/off are kept for one version and point at the new verb
								.then(Commands.literal("on").executes(context -> setVillagerBrainFreeze(context.getSource(), true, true)))
								.then(Commands.literal("off").executes(context -> setVillagerBrainFreeze(context.getSource(), false, true)))
								.executes(context -> {
									boolean active = VillagePathSmoothedConfig.get().debug.disableVillagerBrains;
									context.getSource().sendSystemMessage(Component.literal("[VPS] villager freeze: " + (active ? "ACTIVE" : "INACTIVE")
											+ " - " + (active ? "villagers stand still (brain tick skipped)" : "villagers behave normally")
											+ " (brain ticks skipped so far: " + VpsPipeline.DEBUG_VILLAGER_BRAIN_SKIPS.get() + ")"));
									return 1;
								})));
	}

	/**
	 * Which y=0 blocks the town-centre road-material rule rejected, by id (fix plan
 * v4 F5). The white list was written from experience, so this is the evidence
 * needed to widen it - a plaza material in here means a village centre that stays
 * outside the road network; a fountain block missing from here means the opposite.
 */
	private static int townCentreReport(CommandSourceStack source) {
		List<String> report = RoadCollector.townCenterSkipReport();
		if (report.isEmpty()) {
			source.sendSystemMessage(Component.literal("[VPS] no town-centre cells skipped yet (generate a village first)"));
			return 0;
		}
		source.sendSystemMessage(Component.literal("[VPS] town-centre y=0 blocks NOT taken as road ("
				+ RoadCollector.TOWN_CENTER_NON_ROAD_SKIPPED.get() + " cells total, most frequent first):\n  "
				+ String.join("\n  ", report)));
		return report.size();
	}

	/** G1: unambiguous verbs - the flag freezes villagers, it does not "disable their brains" in either direction. */
	private static int setVillagerBrainFreeze(CommandSourceStack source, boolean freeze, boolean legacySyntax) {
		VillagePathSmoothedConfig.get().debug.disableVillagerBrains = freeze;
		VillagePathSmoothedConfig.save();
		String state = freeze ? "villagers stand still (brain tick skipped)" : "villagers behave normally";
		String legacyHint = legacySyntax ? " [legacy syntax; use /vps debug villagerbrain freeze " + (freeze ? "on" : "off") + "]" : "";
		source.sendSystemMessage(Component.literal("[VPS] " + state + " from now on" + legacyHint));
		return 1;
	}

	private static int dump(CommandSourceStack source, int radius) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		ServerLevel level = source.getLevel();
		Player player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal("[VPS] /vps dump must be run by a player"));
			return 0;
		}
		BlockPos origin = player.blockPosition();
		ChunkPos centerChunk = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
		//#if MC >= 260000
		//$$ int baseX = centerChunk.x();
		//$$ int baseZ = centerChunk.z();
		//#else
		int baseX = centerChunk.x;
		int baseZ = centerChunk.z;
		//#endif

		VpsGenContext ctx = VpsGenContext.capture(level, level.getChunkSource().getGenerator(), centerChunk);
		int villages = 0;
		StringBuilder report = new StringBuilder();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				LevelChunk chunk = (LevelChunk) level.getChunk(baseX + dx, baseZ + dz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
					StructureStart start = entry.getValue();
					if (start == null || !start.isValid()) {
						continue;
					}
					if (!VpsCompat.isVanillaVillageStructure(level, entry.getKey()) && !RoadCollector.hasCustomRegex(config)) {
						continue;
					}
					villages++;
					RoadNetwork network = RoadCollector.collect(start, ctx, config);
					RoadSmoother.solve(network, VpsPipeline.paramsFor(config));
					Map<String, Integer> perStyle = new HashMap<>();
					for (var cell : network.cells) {
						perStyle.merge(cell.style, 1, Integer::sum);
					}
					report.append(String.format("village @ %s: %d street cells, %d arms, %d regions, styles=%s%n",
							start.getChunkPos(), network.cells.size(), network.arms.size(), network.regions.size(), perStyle));
				}
			}
		}
		if (villages == 0) {
			source.sendSystemMessage(Component.literal("[VPS] no village structure starts found within " + radius + " chunks of " + centerChunk));
		} else {
			source.sendSystemMessage(Component.literal("[VPS] dump of " + villages + " village(s):\n" + report));
		}
		return villages;
	}

	private static int setWorldEnabled(CommandSourceStack source, boolean enabled) {
		MinecraftServer server = source.getServer();
		VpsWorldSettings settings = VpsWorldSettings.get(server);
		settings.enabled = enabled;
		VpsWorldSettings.save(server, settings);
		source.sendSystemMessage(Component.literal("[VPS] village path smoothing " + (enabled ? "enabled" : "disabled")
				+ " for this world (" + VpsWorldSettings.fileOf(server) + "); applies to chunks generated from now on"));
		return 1;
	}

	private static String worldSummary(MinecraftServer server) {
		return "[VPS] this world: enabled=" + VpsWorldSettings.isEnabled(server)
				+ " (" + VpsWorldSettings.fileOf(server) + ")";
	}

	private static String configSummary(MinecraftServer server) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		// note: the global "enabled" is only the seed default for worlds without
		// their own switch; the per-world switch is authoritative (/vps world)
		return "[VPS] global default enabled=" + config.enabled
				+ ", this world enabled=" + (server == null ? "?" : VpsWorldSettings.isEnabled(server))
				+ ", roadSmoothing=" + config.roadSmoothing.enabled + " (" + config.roadSmoothing.objective + ", window=" + config.roadSmoothing.dpWindowHalfUnits
				+ ", penalty=" + config.roadSmoothing.stepViolationPenalty + ", cut=" + config.roadSmoothing.allowCutIntoTerrain
				+ ", stage=" + config.roadSmoothing.roadShapeStage
				+ ", probeCaliber=" + config.roadSmoothing.probeCaliber
				+ ", deviationWarn=" + config.roadSmoothing.surfaceDeviationWarnBlocks + " blocks"
				+ ", roadPiecesRegex=" + config.roadSmoothing.roadPiecesRegex
				+ " (town_centers " + (config.roadSmoothing.roadPiecesRegex.contains("town_centers") ? "included" : "NOT included")
				+ ", centre plazas " + (config.roadSmoothing.includeTownCentersRoadMaterials ? "on" : "off") + ")"
				+ ", kerb=" + config.kerb.enabled + " (> " + config.kerb.minDropHeight + " blocks, savanna="
				+ config.kerb.blockByStyle.savanna + ")"
				+ ", railing=" + config.railing.enabled + " (< -" + config.railing.minDropHeight + " blocks)"
				+ ", waterCrossing=" + config.waterCrossing.enabled;
	}

	/**
	 * The counters are grouped by the plan v4 metric table so a reader can find
	 * the number a defect column is named after without scrolling one enormous
	 * line: integrity, defects, writer/edges, solver, buildings, misc.
	 */
	private static String statsSummary() {
		long solved = VpsPipeline.VILLAGES_SOLVED.get();
		long nanos = VpsPipeline.SOLVE_NANOS.get();
		long maskCells = RoadCollector.MASK_CELLS_TOTAL.get();
		long largestCells = RoadCollector.MASK_CELLS_LARGEST_COMPONENT.get();
		long gapSum = RoadCollector.GAP_WIDTH_SUM.get();
		long splitVillages = RoadCollector.NETWORKS_SPLIT.get();
		return String.format(
				"[VPS] villages solved: %d (%.2f ms avg)%n"
						+ "  roads      : cells written %d, kerbs %d, roof lining %d, railings %d (skipped: water %d / door %d), waterlogged slabs/leaves %d, scaffolds %d (water-self %d / water-below %d / dry %d), water bridges %d%n"
						+ "  integrity  : dropped cells %d (noise-reprobed %d), split masks %d (solved per component), largest component %s%%, widest break avg %s / max %d%n"
						+ "  defects    : writer overrides %d, nudged to the surface %d, refused %d (out of world %d), surface fallbacks %d, over-budget %d, edge drops > 1 block %d (worst %d half units)%n"
						+ "  solve      : contradictions %d, step violations %d (worst %d), seam re-solves refused %d, polish moved %d (max delta %d half units, p90 %d, midpoint conflicts %d), water anchor deviations %d, villages without arms %d, solution cache hits %d%n"
						+ "  suppression: vanilla road blocks dropped %d (passed through %d)%n"
						+ "  buildings  : T1.5 applied %d (no-server %d), components %d, shifted %d, no-road %d, overlap guards %d, floating %d, buried %d, re-solved after shift %d (%d cells changed), road cells yielded %d, door anchor offsets 0/1/2/3/4+ %d/%d/%d/%d/%d%n"
						+ "  centres    : town-centre plaza cells %d (non-road cells skipped %d), vanilla-rendered %d, groups %d (shifted %d, pieces %d), decor seats %d (written %d, decor pieces moved %d)%n"
						+ "  model gap  : written surface vs real terrain: mean %s half units, worst %d, over threshold %d, cut below terrain %d, fill above %d%n"
						+ "  misc       : floor-garbage probes %d, probe boundary switches %d, probe mismatch columns %d, villager freeze: %s (brain ticks skipped: %d)",
				solved,
				solved > 0 ? nanos / 1_000_000.0 / solved : 0.0,
				VpsPipeline.ROAD_CELLS_WRITTEN.get(),
				VpsPipeline.KERBS_PLACED.get(),
				RoadWriter.ROOF_LINED_BLOCKS.get(),
				VpsPipeline.RAILINGS_PLACED.get(),
				RoadWriter.RAILINGS_SKIPPED_AT_WATER.get(),
				RoadWriter.RAILINGS_SKIPPED_AT_DOOR.get(),
				VpsPipeline.WATERLOGGED_PLACEMENTS.get(),
				VpsPipeline.SCAFFOLDS_PLACED.get(),
				VpsPipeline.SCAFFOLD_WATER_SELF.get(),
				VpsPipeline.SCAFFOLD_WATER_BELOW.get(),
				VpsPipeline.SCAFFOLD_DRY.get(),
				RoadWriter.WATER_BRIDGE_CELLS.get(),
				RoadCollector.PROBE_SKIPPED_CELLS.get(),
				RoadCollector.NOISE_FALLBACK_CELLS.get(),
				splitVillages,
				maskCells > 0 ? String.format("%.1f", 100.0 * largestCells / maskCells) : "-",
				splitVillages > 0 ? String.format("%.1f", (double) gapSum / splitVillages) : "-",
				RoadCollector.GAP_WIDTH_MAX.get(),
				RoadWriter.OVERRIDDEN_WRITES.get(),
				RoadWriter.NUDGED_WRITES.get(),
				RoadWriter.REFUSED_WRITES.get(),
				RoadWriter.CLAMPED_WRITES.get(),
				VpsPipeline.SURFACE_FALLBACK_WRITES.get(),
				RoadWriter.OVER_BUDGET_CELLS.get(),
				RoadWriter.EDGE_DROP_VIOLATIONS.get(),
				RoadWriter.EDGE_WORST_DROP.get(),
				VpsPipeline.SOLVE_CANDIDATE_CONTRADICTIONS.get(),
				VpsPipeline.SOLVE_STEP_VIOLATIONS.get(),
				VpsPipeline.SOLVE_WORST_STEP.get(),
				VpsPipeline.SOLVE_PHASE3_REFUSALS.get(),
				VpsPipeline.ROAD_POLISH_MOVED.get(),
				VpsPipeline.ROAD_POLISH_MAX_DELTA.get(),
				VpsPipeline.ROAD_POLISH_DELTA_P90.get(),
				VpsPipeline.ROAD_POLISH_CONFLICTS.get(),
				VpsPipeline.WATER_ANCHOR_DEVIATIONS.get(),
				VpsPipeline.VILLAGES_WITHOUT_ARMS.get(),
				VpsPipeline.SOLUTION_CACHE_HITS.get(),
				VpsPipeline.SUPPRESSED_VANILLA_ROAD_BLOCKS.get(),
				VpsPipeline.SUPPRESSION_PASSED_THROUGH.get(),
				VpsPipeline.T15_APPLIED.get(),
				VpsPipeline.T15_NO_SERVER.get(),
				VpsPipeline.BUILDING_COMPONENTS.get(),
				VpsPipeline.BUILDINGS_SHIFTED.get(),
				VpsPipeline.BUILDINGS_NO_ROAD_ACCESS.get(),
				VpsPipeline.OVERLAP_GUARDS.get(),
				VpsPipeline.BUILDING_FLOATING.get(),
				VpsPipeline.BUILDING_BURIED.get(),
				T15Planner.T15_RESOLVED_AFTER_SHIFT.get(),
				T15Planner.T15_RESOLVE_DELTA_CELLS.get(),
				RoadWriter.BUILDING_CELLS_SKIPPED.get(),
				VpsPipeline.DOOR_ANCHOR_OFFSET_0.get(),
				VpsPipeline.DOOR_ANCHOR_OFFSET_1.get(),
				VpsPipeline.DOOR_ANCHOR_OFFSET_2.get(),
				VpsPipeline.DOOR_ANCHOR_OFFSET_3.get(),
				VpsPipeline.DOOR_ANCHOR_OFFSET_4_PLUS.get(),
				RoadCollector.TOWN_CENTER_CELLS.get(),
				RoadCollector.TOWN_CENTER_NON_ROAD_SKIPPED.get(),
				RoadWriter.PLAZA_CELLS_VANILLA.get(),
				VpsPipeline.PLAZA_GROUPS.get(),
				VpsPipeline.PLAZAS_SHIFTED.get(),
				VpsPipeline.PLAZA_PIECES_SHIFTED.get(),
				RoadCollector.SEAT_CELLS.get(),
				RoadWriter.SEATS_WRITTEN.get(),
				T15Planner.SEAT_DECOR_PIECES_MOVED.get(),
				RoadWriter.DEVIATION_CELLS.get() > 0
					? String.format("%.1f", (double) RoadWriter.DEVIATION_SUM.get() / RoadWriter.DEVIATION_CELLS.get())
					: "-",
				RoadWriter.DEVIATION_WORST.get(),
				RoadWriter.DEVIATION_OVER_THRESHOLD_CELLS.get(),
				RoadWriter.CUT_BELOW_TERRAIN_CELLS.get(),
				RoadWriter.FILL_ABOVE_TERRAIN_CELLS.get(),
				VpsGenContext.FLOOR_GARBAGE_HITS.get(),
				RoadCollector.PROBE_BOUNDARY_SWITCHES.get(),
				RoadCollector.PROBE_MISMATCH_COLUMNS.get(),
				VillagePathSmoothedConfig.get().debug.disableVillagerBrains ? "ACTIVE" : "INACTIVE",
				VpsPipeline.DEBUG_VILLAGER_BRAIN_SKIPS.get());
	}
}
