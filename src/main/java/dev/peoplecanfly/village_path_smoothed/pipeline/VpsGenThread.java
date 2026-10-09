/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import net.minecraft.server.MinecraftServer;

/**
 * Captures the owning {@link MinecraftServer} for the duration of the
 * STRUCTURE_STARTS chunk step, so the T1.5 forward decision (which runs inside
 * {@code Structure#generate}, before any {@code WorldGenLevel} is available)
 * can still read the authoritative per-world switch.
 *
 * The value is set/cleared by MixinChunkGenerator around
 * {@code ChunkGenerator#createStructures}; that method is the single entry to
 * structure generation in every supported version and holds the
 * {@code StructureManager}, whose level is the ServerLevel. When the server
 * cannot be resolved the T1.5 pass degrades to "compute nothing, move nothing"
 * and the legacy T5 solve at feature placement still runs, so a disabled world
 * can never get its buildings moved (R-B19).
 */
public final class VpsGenThread {
	private static final ThreadLocal<MinecraftServer> SERVER = new ThreadLocal<>();
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private VpsGenThread() {
	}

	public static void push(MinecraftServer server) {
		DEPTH.get()[0]++;
		// always overwrite (even with null): a stale server from a previous step
		// on a reused worker thread must never leak into an unresolved step
		SERVER.set(server);
	}

	public static void pop() {
		int[] depth = DEPTH.get();
		depth[0]--;
		if (depth[0] <= 0) {
			DEPTH.remove();
			SERVER.remove();
		}
	}

	/** The server captured for the current structure-generation step, or null. */
	public static MinecraftServer server() {
		return SERVER.get();
	}
}
