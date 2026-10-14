/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * Where the T5 probe window is anchored (fix plan v4 D2-c / M3, option R2-1
 * {@code probeCaliber = CENTER_WINDOW}).
 *
 * <p>Before this class the anchor was "the chunk that happened to trigger the
 * placeInChunk solve", which made the whole village's {@code baseP} field - and
 * therefore the solved profile - depend on generation order: the same seed could
 * produce two different roads. The anchor is now a pure function of the village
 * bounding box, and deliberately so: <b>no method here takes a trigger
 * position</b>, so trigger-dependence is not representable rather than merely
 * avoided. {@code VpsPipeline} resolves the box, calls {@link #centerChunk} once
 * and hands the result to {@code VpsGenContext}.
 *
 * <p>Pure Java on purpose (no Minecraft imports) so the bench and the unit tests
 * can assert the anchoring rule itself rather than a mirror of it.
 *
 * <p>Scope note (fix plan v4 F14/F16): this window is a <b>write-stage</b>
 * property. The solve runs during {@code STRUCTURE_STARTS}, where no chunk of
 * the village has terrain yet, so "real" heights only exist inside this window
 * at FEATURES time. The default T1.5 path never uses it at all (pure noise).
 */
public final class ProbeWindow {
	private ProbeWindow() {
	}

	/**
	 * Chunk holding the centre of a village bounding box, in chunk coordinates.
	 * Ties round towards the low side so the result is stable.
	 *
	 * @param minX/maxX/minZ/maxZ inclusive block bounds of the village
	 * @return {@code {chunkX, chunkZ}}
	 */
	public static int[] centerChunk(int minX, int maxX, int minZ, int maxZ) {
		return new int[]{centerBlock(minX, maxX) >> 4, centerBlock(minZ, maxZ) >> 4};
	}

	/** The same anchor in block coordinates, for callers that address blocks. */
	public static int centerBlock(int min, int max) {
		return min + (max - min) / 2;
	}

	/**
	 * The vanilla reliability window of the real heightmap: the chunk being
	 * written plus one chunk of margin in each direction
	 * ({@code ChunkGenerator} blockStateWriteRadius = 1 for FEATURES).
	 */
	public static boolean inWindow(int x, int z, int centerChunkX, int centerChunkZ) {
		return Math.abs((x >> 4) - centerChunkX) <= 1 && Math.abs((z >> 4) - centerChunkZ) <= 1;
	}
}