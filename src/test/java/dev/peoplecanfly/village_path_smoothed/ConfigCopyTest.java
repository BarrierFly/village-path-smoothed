/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The ModMenu screen edits a draft made by {@link VillagePathSmoothedConfig#copyOf}
 * and installs it with {@link VillagePathSmoothedConfig#replace}. That round trip
 * goes through Gson, so a field the serializer drops would silently reset itself
 * the moment the screen is opened - the kind of defect nobody notices until a
 * setting "does not stick".
 */
public class ConfigCopyTest {
	/** A config whose every field differs from the default, so a dropped field shows up. */
	private static VillagePathSmoothedConfig nonDefault() {
		VillagePathSmoothedConfig config = new VillagePathSmoothedConfig();
		config.enabled = false;
		config.roadSmoothing.enabled = false;
		config.roadSmoothing.maxFillLayers = 7;
		config.roadSmoothing.maxClearLayers = -1;
		config.roadSmoothing.objective = VillagePathSmoothedConfig.Objective.HYBRID;
		config.roadSmoothing.roadShapeStage = VillagePathSmoothedConfig.RoadShapeStage.T5;
		config.roadSmoothing.maxBuildingShift = 5;
		config.roadSmoothing.buildingShiftMedianWingBlocks = 2;
		config.roadSmoothing.probeCaliber = VillagePathSmoothedConfig.ProbeCaliber.ALL_NOISE;
		config.roadSmoothing.surfaceDeviationWarnBlocks = 9;
		config.roadSmoothing.buildingGuard = false;
		config.roadSmoothing.protectBuildings = false;
		config.roadSmoothing.hybridWeightDeviation = 2.5D;
		config.roadSmoothing.stepViolationPenalty = 11;
		config.roadSmoothing.dpWindowHalfUnits = 6;
		config.roadSmoothing.maxExactSectionWidth = 9;
		config.roadSmoothing.icmRounds = 1;
		config.roadSmoothing.allowCutIntoTerrain = false;
		config.roadSmoothing.fillMaterial = "minecraft:dirt";
		config.roadSmoothing.beardCompensation = 3;
		config.roadSmoothing.beardRadius = 7;
		config.roadSmoothing.beardKernel = "beard_thin";
		config.roadSmoothing.beardKernelBlocksPerDensity = 0.5D;
		config.roadSmoothing.roadPiecesRegex = "^minecraft:village/plains/streets/$";
		config.roadSmoothing.includeTownCentersRoadMaterials = false;
		config.roadSmoothing.roadSurfaceDetection = VillagePathSmoothedConfig.RoadSurfaceDetection.TEMPLATE_Y0_ROAD_MATERIAL;
		config.kerb.enabled = false;
		config.kerb.minDropHeight = 3;
		config.kerb.replaceableBlocks = java.util.Arrays.asList("#minecraft:dirt", "#minecraft:sand");
		config.kerb.blockByStyle = new VillagePathSmoothedConfig.BlockByStyle(
				"minecraft:stone", "minecraft:sandstone", "minecraft:granite",
				"minecraft:packed_ice", "minecraft:deepslate");
		config.railing.enabled = false;
		config.railing.minDropHeight = 4;
		config.railing.skipAtBuildingDoors = false;
		config.railing.leavesByStyle = new VillagePathSmoothedConfig.BlockByStyle(
				"minecraft:birch_leaves", "minecraft:jungle_leaves", "minecraft:mangrove_leaves",
				"minecraft:dark_oak_leaves", "minecraft:azalea_leaves");
		config.waterCrossing.enabled = false;
		config.waterCrossing.distanceWhenSupported = 4;
		config.waterCrossing.distanceWhenUnsupported = 6;
		config.housePathGround.mudBricks = true;
		config.diagnostics.logLevel = "DEBUG";
		config.diagnostics.dumpRoadNetwork = true;
		config.diagnostics.maxRoadCellsPerVillage = 1234;
		config.debug.disableVillagerBrains = true;
		config.debug.seamSigns = true;
		config.debug.doorSigns = true;
		return config;
	}

	@Test
	public void copyOfKeepsEveryFieldAndDoesNotAliasTheSource() {
		VillagePathSmoothedConfig source = nonDefault();
		VillagePathSmoothedConfig copy = VillagePathSmoothedConfig.copyOf(source);

		assertNotNull(copy);
		assertNotSame(source, copy);
		// Nested objects must be fresh: the screen mutates the draft, and an alias
		// would edit the live config before Save is pressed.
		assertNotSame(source.roadSmoothing, copy.roadSmoothing);
		assertNotSame(source.kerb, copy.kerb);
		assertNotSame(source.railing, copy.railing);
		assertNotSame(source.waterCrossing, copy.waterCrossing);
		assertNotSame(source.housePathGround, copy.housePathGround);
		assertNotSame(source.diagnostics, copy.diagnostics);
		assertNotSame(source.debug, copy.debug);

		assertFalse(copy.enabled);
		assertFalse(copy.roadSmoothing.enabled);
		assertEquals(7, copy.roadSmoothing.maxFillLayers);
		assertEquals(-1, copy.roadSmoothing.maxClearLayers);
		assertEquals(VillagePathSmoothedConfig.Objective.HYBRID, copy.roadSmoothing.objective);
		assertEquals(VillagePathSmoothedConfig.RoadShapeStage.T5, copy.roadSmoothing.roadShapeStage);
		assertEquals(5, copy.roadSmoothing.maxBuildingShift);
		assertEquals(2, copy.roadSmoothing.buildingShiftMedianWingBlocks);
		assertEquals(VillagePathSmoothedConfig.ProbeCaliber.ALL_NOISE, copy.roadSmoothing.probeCaliber);
		assertEquals(9, copy.roadSmoothing.surfaceDeviationWarnBlocks);
		assertFalse(copy.roadSmoothing.buildingGuard);
		assertFalse(copy.roadSmoothing.protectBuildings);
		assertEquals(2.5D, copy.roadSmoothing.hybridWeightDeviation, 0.0D);
		assertEquals(11, copy.roadSmoothing.stepViolationPenalty);
		assertEquals(6, copy.roadSmoothing.dpWindowHalfUnits);
		assertEquals(9, copy.roadSmoothing.maxExactSectionWidth);
		assertEquals(1, copy.roadSmoothing.icmRounds);
		assertFalse(copy.roadSmoothing.allowCutIntoTerrain);
		assertEquals("minecraft:dirt", copy.roadSmoothing.fillMaterial);
		assertEquals(3, copy.roadSmoothing.beardCompensation);
		assertEquals(7, copy.roadSmoothing.beardRadius);
		assertEquals("beard_thin", copy.roadSmoothing.beardKernel);
		assertEquals(0.5D, copy.roadSmoothing.beardKernelBlocksPerDensity, 0.0D);
		assertEquals("^minecraft:village/plains/streets/$", copy.roadSmoothing.roadPiecesRegex);
		assertFalse(copy.roadSmoothing.includeTownCentersRoadMaterials);
		assertEquals(VillagePathSmoothedConfig.RoadSurfaceDetection.TEMPLATE_Y0_ROAD_MATERIAL,
				copy.roadSmoothing.roadSurfaceDetection);

		assertFalse(copy.kerb.enabled);
		assertEquals(3, copy.kerb.minDropHeight);
		assertEquals(2, copy.kerb.replaceableBlocks.size());
		assertEquals("minecraft:sandstone", copy.kerb.blockByStyle.forStyle("desert"));
		assertEquals("minecraft:deepslate", copy.kerb.blockByStyle.forStyle("taiga"));

		assertFalse(copy.railing.enabled);
		assertEquals(4, copy.railing.minDropHeight);
		assertFalse(copy.railing.skipAtBuildingDoors);
		assertEquals("minecraft:jungle_leaves", copy.railing.leavesByStyle.forStyle("desert"));

		assertFalse(copy.waterCrossing.enabled);
		assertEquals(4, copy.waterCrossing.distanceWhenSupported);
		assertEquals(6, copy.waterCrossing.distanceWhenUnsupported);

		assertTrue(copy.housePathGround.mudBricks);

		assertEquals("DEBUG", copy.diagnostics.logLevel);
		assertTrue(copy.diagnostics.dumpRoadNetwork);
		assertEquals(1234, copy.diagnostics.maxRoadCellsPerVillage);

		assertTrue(copy.debug.disableVillagerBrains);
		assertTrue(copy.debug.seamSigns);
		assertTrue(copy.debug.doorSigns);

		// Editing the copy must leave the source alone.
		copy.roadSmoothing.maxFillLayers = 1;
		copy.kerb.blockByStyle.desert = "minecraft:glass";
		assertEquals(7, source.roadSmoothing.maxFillLayers);
		assertEquals("minecraft:sandstone", source.kerb.blockByStyle.desert);
	}

	@Test
	public void copyOfNullFallsBackToDefaults() {
		VillagePathSmoothedConfig copy = VillagePathSmoothedConfig.copyOf(null);
		assertNotNull(copy);
		assertEquals(new VillagePathSmoothedConfig().roadSmoothing.stepViolationPenalty,
				copy.roadSmoothing.stepViolationPenalty);
	}

	@Test
	public void replaceInstallsTheDraftAsTheLiveInstance() {
		VillagePathSmoothedConfig original = VillagePathSmoothedConfig.get();
		try {
			VillagePathSmoothedConfig draft = nonDefault();
			VillagePathSmoothedConfig.replace(draft);
			assertEquals(VillagePathSmoothedConfig.Objective.HYBRID,
					VillagePathSmoothedConfig.get().roadSmoothing.objective);
			assertEquals(1234, VillagePathSmoothedConfig.get().diagnostics.maxRoadCellsPerVillage);
		} finally {
			VillagePathSmoothedConfig.replace(original);
		}
	}
}
