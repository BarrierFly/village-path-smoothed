/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-world settings, stored as {@code <world>/village_path_smoothed.json}
 * next to level.dat. The file is created lazily on the world's first chunk
 * generation and seeded from the world chosen on the create-world screen
 * (singleplayer) or the global default; from then on the world keeps its own
 * switch, independent of the global config and of other worlds.
 */
public final class VpsWorldSettings {
	private static final String FILE_NAME = VillagePathSmoothed.MOD_ID + ".json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** One MinecraftServer instance per running world; weak keys drop closed servers. */
	private static final Map<MinecraftServer, VpsWorldSettings> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	/** The world the create-world screen is about to create, armed when "Create" is pressed. */
	private record PendingChoice(String worldName, boolean enabled) {
	}

	/** Client side: the last value picked on the create-world-screen toggle (for the button's own state). */
	private static volatile Boolean lastToggleEnabled;
	/** The armed choice for the world currently being created; consumed once at its first chunk generation. */
	private static volatile PendingChoice pendingForCreation;

	public boolean enabled = true;

	public VpsWorldSettings() {
	}

	/** Client side: the create-world-screen toggle changed its value. */
	public static void setPendingEnabled(Boolean value) {
		lastToggleEnabled = value;
	}

	/** Client side: the last value picked on the create-world-screen toggle, or null when untouched. */
	public static Boolean getPendingEnabled() {
		return lastToggleEnabled;
	}

	/**
	 * Client side: the "Create" button was pressed on the create-world screen,
	 * so {@code worldName} is the world being created. If the user picked a
	 * toggle value, it is armed for exactly this world and consumed at its
	 * first chunk generation; untouched toggles arm nothing.
	 */
	public static void armForCreation(String worldName) {
		Boolean enabled = lastToggleEnabled;
		if (enabled != null && worldName != null) {
			pendingForCreation = new PendingChoice(worldName, enabled);
		}
	}

	/** The enabled state a freshly created world starts with. */
	private static boolean defaultForNewWorld(MinecraftServer server) {
		PendingChoice choice = pendingForCreation;
		if (choice != null && choice.worldName.equals(levelName(server))) {
			pendingForCreation = null; // one-shot: applies to the world being created only
			// report 4.1 #1: consume the remembered toggle together with the
			// pending choice, otherwise the next world created without touching
			// the toggle inherits this world's choice via the button state
			lastToggleEnabled = null;
			return choice.enabled;
		}
		return VillagePathSmoothedConfig.get().enabled;
	}

	private static String levelName(MinecraftServer server) {
		try {
			return server.overworld().getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData data
					? data.getLevelName()
					: "";
		} catch (Throwable t) {
			return "";
		}
	}

	/** Cached per-server read used by the worldgen hook. */
	public static boolean isEnabled(MinecraftServer server) {
		return get(server).enabled;
	}

	public static VpsWorldSettings get(MinecraftServer server) {
		synchronized (CACHE) {
			VpsWorldSettings settings = CACHE.get(server);
			if (settings == null) {
				settings = load(server);
				CACHE.put(server, settings);
			}
			return settings;
		}
	}

	public static void save(MinecraftServer server, VpsWorldSettings settings) {
		CACHE.put(server, settings);
		write(file(server), settings);
	}

	private static VpsWorldSettings load(MinecraftServer server) {
		Path path = file(server);
		if (Files.exists(path)) {
			try {
				VpsWorldSettings settings = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), VpsWorldSettings.class);
				if (settings != null) {
					return settings;
				}
			} catch (Exception e) {
				VillagePathSmoothed.LOGGER.error("[VPS] failed to read {}, using defaults", path, e);
			}
		}
		VpsWorldSettings settings = new VpsWorldSettings();
		settings.enabled = defaultForNewWorld(server);
		write(path, settings);
		return settings;
	}

	private static void write(Path path, VpsWorldSettings settings) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(settings), StandardCharsets.UTF_8);
		} catch (IOException e) {
			VillagePathSmoothed.LOGGER.error("[VPS] failed to write {}", path, e);
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
	}

	/** The per-world settings file path, for diagnostics and commands. */
	public static Path fileOf(MinecraftServer server) {
		return file(server);
	}
}
