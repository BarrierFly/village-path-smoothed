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
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
	/**
	 * One MinecraftServer instance per running world; weak keys drop closed
	 * servers. Guarded by its own monitor, which only the first load of a world
	 * ever takes - see {@link #get}.
	 */
	private static final Map<MinecraftServer, VpsWorldSettings> CACHE = new WeakHashMap<>();

	/**
	 * Lock-free fast path for the worldgen gate.
	 *
	 * <p>{@link #get} is called from {@code StructureStart#placeInChunk}, i.e.
	 * once per chunk per structure on every worldgen worker thread. Routing that
	 * through a monitor turns the lookup into a lock convoy between the workers
	 * even though there is normally exactly one server running and its answer
	 * never changes. This holds the most recent server/answer pair so the steady
	 * state is a reference compare.
	 *
	 * <p>The server is held through a {@link WeakReference} so the fast path
	 * cannot keep a closed server alive - that job still belongs to the
	 * WeakHashMap above, and this only avoids its monitor.
	 */
	private static volatile ServerAnswer lastAnswer;

	private record ServerAnswer(WeakReference<MinecraftServer> server, VpsWorldSettings settings) {
	}

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
		ServerAnswer fast = lastAnswer;
		if (fast != null && fast.server().get() == server) {
			return fast.settings();
		}
		return loadLocked(server);
	}

	/**
	 * Slow path, taken once per server. The file I/O stays inside the monitor:
	 * it only happens for a world whose settings have never been read, so no
	 * worldgen thread ever queues behind a disk read.
	 */
	private static VpsWorldSettings loadLocked(MinecraftServer server) {
		synchronized (CACHE) {
			VpsWorldSettings settings = CACHE.get(server);
			if (settings == null) {
				settings = load(server);
				CACHE.put(server, settings);
			}
			lastAnswer = new ServerAnswer(new WeakReference<>(server), settings);
			return settings;
		}
	}

	public static void save(MinecraftServer server, VpsWorldSettings settings) {
		synchronized (CACHE) {
			CACHE.put(server, settings);
		}
		// the fast path caches the instance, so it has to follow the replacement
		lastAnswer = new ServerAnswer(new WeakReference<>(server), settings);
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
