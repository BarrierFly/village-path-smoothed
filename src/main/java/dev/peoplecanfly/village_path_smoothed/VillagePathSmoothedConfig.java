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
		/**
		 * Max consecutive fill layers when the road sits above the ground; beyond it the cell becomes a bridge deck (< 0 = unlimited).
		 * Writer-side only (fix plan v2 R2): fill/clear caps decide how a road whose height is already fixed is PUT OUT
		 * (landfill vs bridge deck, clearing vs tunnel lining) and never influence the solve.
		 */
		public int maxFillLayers = 3;
		/**
		 * Max layers cleared above the road when it sits below the ground; beyond it the cell tunnels and the kerb lines the roof (< 0 = unlimited).
		 * Writer-side only, see maxFillLayers (fix plan v2 R2).
		 */
		public int maxClearLayers = -1;
		public Objective objective = Objective.SMOOTH_FIRST;
		/** T1.5 forward decision: solve the road profile (and shift RIGID building components) during structure assembly; T5 = legacy solve at feature placement (no building move). */
		public RoadShapeStage roadShapeStage = RoadShapeStage.T1_5;
		/** Cap on the building component vertical shift in whole blocks (0 = unlimited, only the world height clamps; fix plan v2 R7/N6 default). */
		public int maxBuildingShift = 0;
		/**
		 * Fix plan v4 D2 / R2-1: which height caliber the T5 (feature placement)
		 * solve reads.
		 *
		 * <p>{@code CENTER_WINDOW} (D2-c, default) keeps the vanilla +/-1 chunk
		 * real-heightmap window but anchors it on the <b>village's bounding
		 * box</b> instead of the chunk that happened to trigger the solve, so the
		 * village's height field - and therefore its road - no longer depends on
		 * generation order.
		 *
		 * <p>{@code ALL_NOISE} (D2-b) drops the window entirely: every column is
		 * the pure-noise model, exactly like the default T1.5 path, and the real
		 * heightmap is only read at write time for the fill/cut budget. Spatially
		 * uniform, but no column of the T5 village is on real terrain any more.
		 *
		 * <p>Both calibers must satisfy "the solve does not depend on which chunk
		 * triggered it". The remaining trade-off is that CENTER_WINDOW keeps a
		 * spatially uneven slice of real terrain (real inside the box centre,
		 * noise outside), which is why the seam alignment pass still loads.
		 */
		public ProbeCaliber probeCaliber = ProbeCaliber.CENTER_WINDOW;
		/**
		 * R2-3: deviation from the REAL terrain beyond this many whole blocks is
		 * reported per village as a WARN (with coordinates, cell count and the
		 * worst deviation). It never corrects geometry and never falls back to
		 * vanilla roads - that decision belongs to the mask-integrity gate alone.
		 */
		public int surfaceDeviationWarnBlocks = 4;
		/** Diagnostic only: count floating/buried building components at write time; never repairs and never reverts a shift. */
		public boolean buildingGuard = true;
		/**
		 * Plan A (user decision 2026-10-15): the writer refuses to write the road
		 * block, the support fill or the upward clear for a claimed column whose
		 * road block would land inside a movable building's 3D bounding box. A
		 * smoothed road therefore never overwrites a building body; skipped columns
		 * are counted ({@code building cells skipped} in {@code /vps stats}).
		 */
		public boolean protectBuildings = true;
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
		/**
		 * Blocks added to the noise-probed height next to RIGID pieces (buildings). Default 0 since fix plan v2 R6 (N11):
		 * the +1 step compensated the beard but made doorways sit on a 1-block noise bump the real heightmap columns
		 * do not have. The config item is kept so the compensation can be re-enabled for comparisons (< 0 or 0 disables,
		 * in both kernel modes).
		 */
		public int beardCompensation = 0;
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
		/**
		 * Pieces whose y=0 cells may join the road mask. {@code town_centers}
		 * adds the meeting points and fountains (fix plan v4 D5-c): the plaza
		 * around a centre piece then shares one 1-Lipschitz solution with the
		 * streets instead of being snapped to the median of its neighbours, which
		 * is what made the fountain surroundings the worst-looking spot in the
		 * village. Only road-material cells are taken (see
		 * {@code includeTownCentersRoadMaterials}), so the fountain's basin,
		 * bell and logs keep their vanilla blocks and the piece's y=0 layer is
		 * never suppressed where it is not road.
		 */
		public String roadPiecesRegex = "^minecraft:village/([a-z_]+)/(streets|terminators|town_centers)/";
		/**
		 * Road-material test for {@code town_centers} cells. A centre piece's y=0
		 * layer holds the plaza (dirt path / smooth sandstone) next to the
		 * fountain's cobblestone, water and bell; only the plaza joins the mask.
		 */
		public boolean includeTownCentersRoadMaterials = true;
		public RoadSurfaceDetection roadSurfaceDetection = RoadSurfaceDetection.TEMPLATE_Y0_SOLID;
	}

	public enum RoadShapeStage {
		T1_5, T5
	}

	/** Fix plan v4 D2: how the T5 solve reads heights. See roadSmoothing.probeCaliber. */
	public enum ProbeCaliber {
		CENTER_WINDOW, ALL_NOISE
	}

	public static class Kerb {
		public boolean enabled = true;
		/** "Terrain more than N blocks above the road" triggers a kerb. */
		public int minDropHeight = 1;
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
		/**
		 * User decision 2026-10-15: no railing on the single block in front of a
		 * movable building's door, so a doorway can never be fenced in. Only the
		 * door-front cell is skipped; the rest of the edge is unaffected.
		 */
		public boolean skipAtBuildingDoors = true;
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
