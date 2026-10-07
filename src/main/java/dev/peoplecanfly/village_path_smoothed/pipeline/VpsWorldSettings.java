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
 * generation and seeded from the create-world-screen choice (singleplayer) or
 * the global default; from then on the world keeps its own switch, independent
 * of the global config and of other worlds.
 */
public final class VpsWorldSettings {
	private static final String FILE_NAME = VillagePathSmoothed.MOD_ID + ".json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** One MinecraftServer instance per running world; weak keys drop closed servers. */
	private static final Map<MinecraftServer, VpsWorldSettings> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
	/**
	 * Client side: the choice made on the create-world screen. It applies to
	 * worlds created afterwards (sticky for the session); null means untouched.
	 */
	private static volatile Boolean pendingEnabled;

	public boolean enabled = true;

	public VpsWorldSettings() {
	}

	public static void setPendingEnabled(Boolean value) {
		pendingEnabled = value;
	}

	public static Boolean getPendingEnabled() {
		return pendingEnabled;
	}

	/** The enabled state a freshly created world starts with. */
	public static boolean defaultForNewWorld() {
		Boolean pending = pendingEnabled;
		return pending != null ? pending : VillagePathSmoothedConfig.get().enabled;
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
		settings.enabled = defaultForNewWorld();
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
