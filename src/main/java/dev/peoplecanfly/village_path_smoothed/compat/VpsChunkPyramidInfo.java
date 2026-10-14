/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * One-time diagnostic: how far the chunk pyramid's accumulated dependencies
 * actually reach (fix plan v4 F20-assert, downgraded to a log).
 *
 * <p>The numbers matter because they bound what any future "solve where the
 * terrain already exists" design can rely on: a chunk at status TERRAIN only
 * guarantees terrain within a given radius, so a loadable-but-not-generated
 * chunk is the fallback condition such a design has to handle. Fix plan v4
 * derived 9 / 10 / 11 by reading the vanilla sources; this prints what the
 * running game says, once, so the two can be compared.
 *
 * <p><b>Reflection on purpose.</b> {@code ChunkPyramid} and {@code ChunkStatus}
 * moved package between the supported versions ({@code world.level.chunk.status}
 * in 1.21.x, {@code world.level.levelgen} before that). A preprocessor branch
 * would need two complete import lists and would break again on the next move;
 * a diagnostic that reports "unavailable" is worth more than one that crashes a
 * launch, and nothing here is load-bearing.
 */
public final class VpsChunkPyramidInfo {
	private static final String[] PACKAGES = {
			"net.minecraft.world.level.chunk.status",
			"net.minecraft.world.level.levelgen",
	};
	private static final String[] STATUSES = {"TERRAIN", "FEATURES", "FULL"};

	private VpsChunkPyramidInfo() {
	}

	/** Logs the radii once, or a single debug line when the classes cannot be found. */
	public static void logOnce() {
		try {
			StringBuilder line = new StringBuilder("[VPS] chunk pyramid accumulated dependency radius:");
			boolean any = false;
			for (String status : STATUSES) {
				Integer radius = radiusFor(status);
				if (radius != null) {
					line.append(' ').append(status).append('=').append(radius);
					any = true;
				}
			}
			if (any) {
				// fix plan v4 F20 reads 9 / 10 / 11 from the vanilla sources; if these
				// disagree, any future terrain-first design has to be re-derived
				VillagePathSmoothed.LOGGER.info(line.toString());
			} else {
				VillagePathSmoothed.LOGGER.debug("[VPS] chunk pyramid radii unavailable (no ChunkPyramid/ChunkStatus on the classpath)");
			}
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] chunk pyramid radii unavailable: {}", t.toString());
		}
	}

	private static Integer radiusFor(String status) {
		for (String pkg : PACKAGES) {
			try {
				Class<?> pyramidClass = Class.forName(pkg + ".ChunkPyramid");
				Class<?> statusClass = Class.forName(pkg + ".ChunkStatus");
				Field pyramid = pyramidClass.getField("GENERATION_PYRAMID");
				Object instance = pyramid.get(null);
				Field statusField = statusClass.getField(status);
				Object statusValue = statusField.get(null);
				Method getStepTo = pyramidClass.getMethod("getStepTo", statusClass);
				Object step = getStepTo.invoke(instance, statusValue);
				Object dependencies = step.getClass().getMethod("accumulatedDependencies").invoke(step);
				return (Integer) dependencies.getClass().getMethod("getRadius").invoke(dependencies);
			} catch (ClassNotFoundException ignored) {
				// try the next known package
			} catch (Throwable t) {
				return null;
			}
		}
		return null;
	}
}