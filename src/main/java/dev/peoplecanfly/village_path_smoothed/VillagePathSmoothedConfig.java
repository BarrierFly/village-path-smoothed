/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-POJO config backed by Gson, no third-party config library.
 * Loaded once on mod init; reloaded by /vps config reload and the ModMenu screen.
 */
public final class VillagePathSmoothedConfig {
	public static final List<String> VILLAGE_STYLES = Arrays.asList("plains", "desert", "savanna", "snowy", "taiga");

	public enum Objective {
		MIN_DEVIATION, SHORTEST_CATCHUP, HYBRID, SMOOTH_FIRST
	}

	public enum RoadSurfaceDetection {
		TEMPLATE_Y0_SOLID, TEMPLATE_Y0_ROAD_MATERIAL
	}

	public boolean enabled = true;

	public RoadSmoothing roadSmoothing = new RoadSmoothing();
	public Kerb kerb = new Kerb();
	public Railing railing = new Railing();
	public WaterCrossing waterCrossing = new WaterCrossing();
	public Diagnostics diagnostics = new Diagnostics();
	public Debug debug = new Debug();

	public static class RoadSmoothing {
		public boolean enabled = true;
		/** Max consecutive fill layers when the road sits above the ground; beyond it the cell becomes a bridge deck (< 0 = unlimited). */
		public int maxFillLayers = 8;
		/** Max layers cleared above the road when it sits below the ground; beyond it the cell tunnels and the kerb lines the roof (< 0 = unlimited). */
		public int maxClearLayers = 6;
		public Objective objective = Objective.SMOOTH_FIRST;
		/** T1.5 forward decision: solve the road profile (and shift RIGID building components) during structure assembly; T5 = legacy solve at feature placement (no building move). */
		public RoadShapeStage roadShapeStage = RoadShapeStage.T1_5;
		/** Cap on the building component vertical shift in whole blocks (0 = unlimited, only the world height clamps). */
		public int maxBuildingShift = 32;
		/** Diagnostic only: count floating/buried building components at write time; never repairs and never reverts a shift. */
		public boolean buildingGuard = true;
		/** Weight w1 of the HYBRID objective (w2 = 1). */
		public double hybridWeightDeviation = 8.0;
		/** Soft-constraint penalty per half-block of |dP| above 1 (cliff escape hatch). */
		public int stepViolationPenalty = 32;
		/**
		 * Per-cell candidate half-window in half-blocks; automatically reduced
		 * for wide slices. NOTE (review 3 section 3-4): this knob does NOT
		 * widen the seam between a crossing and its streets any more - the
		 * seam band extends toward the region on its own. Shrinking it below
		 * ~4 freezes the region ICM to its seed and can leave shallow interior
		 * steps, so "bigger is smoother" only holds down to the default.
		 */
		public int dpWindowHalfUnits = 16;
		/** Max slice width solved by the exact arm DP; wider slices/areas go through ICM. */
		public int maxExactSectionWidth = 4;
		/** Iterative coordinate minimization rounds for wide regions. */
		public int icmRounds = 4;
		public boolean allowCutIntoTerrain = true;
		public String fillMaterial = "STYLE_ROAD";
		/** Blocks added to the noise-probed height next to RIGID pieces (buildings): vanilla beards terrain toward the building base, the raw noise probe does not know that (< 0 disables; 0 disables too). */
		public int beardCompensation = 1;
		/** Horizontal radius (Chebyshev, blocks) of the beard zone around building footprints (step kernel mode only). */
		public int beardRadius = 4;
		/**
		 * Shape of the beard compensation: "step" = the legacy flat +1 inside
		 * beardRadius (default), "beard_thin" = the real vanilla closed form
		 * (review 3 P1-B), whose blocks-per-density scale is
		 * beardKernelBlocksPerDensity. Keep "step" until that factor has been
		 * calibrated in game (review 3 section 7 T6): the closed form cuts
		 * terrain above the building base plane, which changes the look.
		 */
		public String beardKernel = "step";
		/**
		 * Blocks of surface shift per unit of vanilla beard density
		 * (beard_thin kernel mode only). NOT calibrated yet: 1.0 is a guess
		 * consistent with the old +1 step at the field's peak; review 3
		 * section 2.4 leaves the true density-to-blocks conversion to T6.
		 */
		public double beardKernelBlocksPerDensity = 1.0;
		public String roadPiecesRegex = "^minecraft:village/([a-z_]+)/(streets|terminators)/";
		public RoadSurfaceDetection roadSurfaceDetection = RoadSurfaceDetection.TEMPLATE_Y0_SOLID;
	}

	public enum RoadShapeStage {
		T1_5, T5
	}

	public static class Kerb {
		public boolean enabled = true;
		/** "Terrain more than N blocks above the road" triggers a kerb. */
		public int minDropHeight = 1;
		/** Elevated-road wall: when the side terrain drops at least this many blocks below the road, a kerb-material wall is placed at the road block level on the side column (0 = always, < 0 = never). */
		public int wallMinDropHeight = 3;
		/** Extra block ids (besides #minecraft:dirt) treated as replaceable by kerbs. */
		public List<String> replaceableBlocks = Arrays.asList("#minecraft:dirt");
		public BlockByStyle blockByStyle = new BlockByStyle(
				"minecraft:cobblestone",
				"minecraft:smooth_sandstone",
				"minecraft:terracotta",
				"minecraft:cobblestone",
				"minecraft:cobblestone"
		);
	}

	public static class Railing {
		public boolean enabled = true;
		public int minDropHeight = 1;
		public BlockByStyle leavesByStyle = new BlockByStyle(
				"minecraft:oak_leaves",
				"minecraft:acacia_leaves",
				"minecraft:acacia_leaves",
				"minecraft:spruce_leaves",
				"minecraft:spruce_leaves"
		);
	}

	public static class WaterCrossing {
		public boolean enabled = true;
		public int distanceWhenSupported = 0;
		public int distanceWhenUnsupported = 1;
	}

	public static class Diagnostics {
		public String logLevel = "INFO";
		public boolean dumpRoadNetwork = false;
		public int maxRoadCellsPerVillage = 20000;
	}

	/** Runtime debug switches, all togglable in-game via /vps debug (no restart). */
	public static class Debug {
		/** Debug: villagers skip their brain tick (stand still, trading still works). */
		public boolean disableVillagerBrains = false;
	}

	public static class BlockByStyle {
		public String plains;
		public String desert;
		public String savanna;
		public String snowy;
		public String taiga;

		public BlockByStyle() {
		}

		public BlockByStyle(String plains, String desert, String savanna, String snowy, String taiga) {
			this.plains = plains;
			this.desert = desert;
			this.savanna = savanna;
			this.snowy = snowy;
			this.taiga = taiga;
		}

		public String forStyle(String style) {
			switch (style) {
				case "desert":
					return this.desert;
				case "savanna":
					return this.savanna;
				case "snowy":
					return this.snowy;
				case "taiga":
					return this.taiga;
				default:
					return this.plains;
			}
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static volatile VillagePathSmoothedConfig instance;

	public static VillagePathSmoothedConfig get() {
		VillagePathSmoothedConfig config = instance;
		return config != null ? config : (instance = new VillagePathSmoothedConfig());
	}

	public static Path getConfigPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(VillagePathSmoothed.MOD_ID + ".json");
	}

	public static synchronized void load() {
		Path path = getConfigPath();
		try {
			if (Files.exists(path)) {
				instance = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), VillagePathSmoothedConfig.class);
			}
		} catch (Exception e) {
			VillagePathSmoothed.LOGGER.error("[VPS] Failed to read config {}, using defaults", path, e);
		}
		if (instance == null) {
			instance = new VillagePathSmoothedConfig();
		}
		save();
	}

	public static synchronized void save() {
		try {
			Path path = getConfigPath();
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(get()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			VillagePathSmoothed.LOGGER.error("[VPS] Failed to write config", e);
		}
	}
}
