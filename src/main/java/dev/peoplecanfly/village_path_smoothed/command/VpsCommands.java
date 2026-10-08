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
						.then(Commands.literal("villagerbrain")
								.then(Commands.literal("on").executes(context -> setVillagerBrainDebug(context.getSource(), true)))
								.then(Commands.literal("off").executes(context -> setVillagerBrainDebug(context.getSource(), false)))
								.executes(context -> {
									boolean on = VillagePathSmoothedConfig.get().debug.disableVillagerBrains;
									context.getSource().sendSystemMessage(Component.literal("[VPS] villager brain disabled: " + on
											+ " (brain ticks skipped so far: " + VpsPipeline.DEBUG_VILLAGER_BRAIN_SKIPS.get() + ")"));
									return 1;
								})));
	}

	private static int setVillagerBrainDebug(CommandSourceStack source, boolean disabled) {
		VillagePathSmoothedConfig.get().debug.disableVillagerBrains = disabled;
		VillagePathSmoothedConfig.save();
		source.sendSystemMessage(Component.literal("[VPS] villager brain " + (disabled ? "DISABLED (debug)" : "enabled")
				+ " - applies to every villager tick from now on"));
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
				+ ", penalty=" + config.roadSmoothing.stepViolationPenalty + ", cut=" + config.roadSmoothing.allowCutIntoTerrain + ")"
				+ ", kerb=" + config.kerb.enabled + " (> " + config.kerb.minDropHeight + " blocks, savanna="
				+ config.kerb.blockByStyle.savanna + ")"
				+ ", railing=" + config.railing.enabled + " (< -" + config.railing.minDropHeight + " blocks)"
				+ ", waterCrossing=" + config.waterCrossing.enabled;
	}

	private static String statsSummary() {
		long solved = VpsPipeline.VILLAGES_SOLVED.get();
		long nanos = VpsPipeline.SOLVE_NANOS.get();
		return String.format(
				"[VPS] villages solved: %d (%.2f ms avg), road cells written: %d, kerbs: %d, railings: %d, scaffolds: %d"
						+ ", probe skips: %d, floor-garbage probes: %d, clamped writes: %d, re-anchored writes: %d",
				solved,
				solved > 0 ? nanos / 1_000_000.0 / solved : 0.0,
				VpsPipeline.ROAD_CELLS_WRITTEN.get(),
				VpsPipeline.KERBS_PLACED.get(),
				VpsPipeline.RAILINGS_PLACED.get(),
				VpsPipeline.SCAFFOLDS_PLACED.get(),
				RoadCollector.PROBE_SKIPPED_CELLS.get(),
				VpsGenContext.FLOOR_GARBAGE_HITS.get(),
				RoadWriter.CLAMPED_WRITES.get(),
				RoadWriter.REANCHORED_WRITES.get());
	}
}
