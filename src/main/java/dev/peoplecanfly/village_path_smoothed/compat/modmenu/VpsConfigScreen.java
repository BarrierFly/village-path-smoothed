/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat.modmenu;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.ConfigValueCodec;
import dev.peoplecanfly.village_path_smoothed.pipeline.VpsPipeline;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Config screen built from vanilla widgets only (no Cloth Config).
 *
 * <p>Every option of {@link VillagePathSmoothedConfig} a player can reasonably
 * want is editable here, grouped into labelled sections and paginated so the
 * layout fits any GUI scale. Numeric options are text fields with live
 * validation plus {@code -}/{@code +} step buttons, and every single option has
 * its own reset-to-default button next to its control.
 *
 * <p>The screen edits a DRAFT copy: nothing reaches the live config until
 * {@code Save & Close}, and ESC throws the draft away. Saving writes the file,
 * installs the draft and clears the anchor-keyed solution cache, because a
 * solution is a function of the config and a stale cached network would keep
 * serving the pre-save settings (the same reason {@code /vps config reload}
 * clears it). The mod only rewrites roads while a chunk is generated, so a
 * saved change affects worlds and chunks generated afterwards - the footer says
 * exactly that instead of implying that existing roads change.
 *
 * <p>An unparsable or out-of-range entry stays in its field, turns red, and
 * blocks saving; the page holding it is reopened instead of silently keeping
 * the previous value.
 *
 * <p>Two config fields are deliberately left out, because exposing them would
 * be a lie: {@code kerb.replaceableBlocks} is a legacy compatibility list the
 * writer no longer reads (the kerb uses the collision/fluid rule), and
 * {@code diagnostics.logLevel} is not consumed by any code path. Everything
 * else in the config class has a row here.
 */
public class VpsConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 22;
	private static final int MAX_CONTROL_WIDTH = 140;
	private static final int MIN_CONTROL_WIDTH = 92;
	private static final int STEP_WIDTH = 16;
	private static final int RESET_WIDTH = 48;
	private static final int LABEL_MIN_WIDTH = 24;
	private static final int MIN_LAYOUT_WIDTH = 240;
	private static final int MAX_LAYOUT_WIDTH = 420;
	private static final int TEXT_COLOR = 0xE0E0E0;
	private static final int ERROR_COLOR = 0xFF5555;
	private static final String[] STYLE_NAMES = {"plains", "desert", "savanna", "snowy", "taiga"};

	private final Screen parent;
	/** A pristine instance: every per-option "Default" button reads its value from here, so defaults never drift from the config class. */
	private final VillagePathSmoothedConfig defaults = new VillagePathSmoothedConfig();
	/** Option key -> the raw text that failed validation. Keeping the text makes a red field survive a rebuild. */
	private final Map<String, String> invalidText = new LinkedHashMap<>();
	private final List<List<Object>> pages = new ArrayList<>();
	private VillagePathSmoothedConfig draft;
	private int page;
	private MutableComponent status;

	//#if MC >= 260000
	//$$ public VpsConfigScreen(Screen parent) {
	//$$ 	super(net.minecraft.client.Minecraft.getInstance(), net.minecraft.client.Minecraft.getInstance().font, Component.translatable("vps.config.title"));
	//$$ 	this.parent = parent;
	//$$ 	this.draft = VillagePathSmoothedConfig.copyOf(VillagePathSmoothedConfig.get());
	//$$ }
	//#else
	public VpsConfigScreen(Screen parent) {
		super(Component.translatable("vps.config.title"));
		this.parent = parent;
		this.draft = VillagePathSmoothedConfig.copyOf(VillagePathSmoothedConfig.get());
	}
	//#endif

	@Override
	protected void init() {
		int layoutWidth = this.layoutWidth();
		int left = (this.width - layoutWidth) / 2;

		this.addRenderableOnly(text(left, 6, layoutWidth,
				Component.translatable("vps.config.title").withStyle(ChatFormatting.BOLD)));

		int rowsTop = 20;
		int rowsBottom = this.height - 92;
		int visibleRows = Math.max(1, (rowsBottom - rowsTop) / ROW_HEIGHT);

		this.pages.clear();
		this.collectPages(generalSection(), visibleRows);
		this.collectPages(solverSection(), visibleRows);
		this.collectPages(advancedSection(), visibleRows);
		if (this.pages.isEmpty()) {
			this.pages.add(new ArrayList<>());
		}
		if (this.page >= this.pages.size()) {
			this.page = this.pages.size() - 1;
		}
		if (this.page < 0) {
			this.page = 0;
		}

		int controlWidth = this.controlWidth(layoutWidth);
		int y = rowsTop;
		for (Object row : this.pages.get(this.page)) {
			if (row instanceof Component header) {
				this.addRenderableOnly(text(left, y + 6, layoutWidth, header));
			} else if (row instanceof Option option) {
				this.addOption(option, left, y, layoutWidth, controlWidth);
			}
			y += ROW_HEIGHT;
		}

		this.addFooter(left, layoutWidth);
	}

	/**
	 * Left-aligned, width-fitted text widget. 1.21.10 dropped
	 * {@code StringWidget#alignLeft} (it draws left-aligned now), and the older
	 * nodes draw the full string without clipping, so the text is trimmed here:
	 * a long translation must never run off the panel.
	 */
	private StringWidget text(int x, int y, int width, Component message) {
		String full = message.getString();
		String fitted = this.font.plainSubstrByWidth(full, Math.max(8, width - 4));
		Component shown = fitted.length() < full.length()
				? Component.literal(fitted + "...").withStyle(message.getStyle())
				: message;
		StringWidget widget = new StringWidget(x, y, width, 9, shown, this.font);
		//#if MC >= 12110
		//$$ return widget;
		//#else
		return widget.alignLeft();
		//#endif
	}

	/** One option row: label on the left, then its control, then its own reset button. */
	private void addOption(Option option, int left, int y, int layoutWidth, int controlWidth) {
		int resetX = left + layoutWidth - RESET_WIDTH;
		int controlX = resetX - 4 - controlWidth;
		int labelWidth = Math.max(LABEL_MIN_WIDTH, controlX - left - 6);
		this.addRenderableOnly(text(left, y + 6, labelWidth, option.label()));
		option.createControl(controlX, y, controlWidth, 20);
		this.addRenderableWidget(Button.builder(Component.translatable("vps.config.reset"), button -> {
			option.reset();
			this.invalidText.remove(option.key);
			this.status = null;
			this.rebuildWidgets();
		}).bounds(resetX, y, RESET_WIDTH, 20).build());
	}

	private void addFooter(int left, int layoutWidth) {
		int hintY = this.height - 86;
		Component hint = this.invalidText.isEmpty()
				? Component.translatable("vps.config.scopeHint").withStyle(ChatFormatting.GRAY)
				: Component.translatable("vps.config.invalidHint").withStyle(ChatFormatting.RED);
		this.addRenderableOnly(text(left, hintY, layoutWidth, hint));
		if (this.status != null) {
			this.addRenderableOnly(text(left, hintY + 11, layoutWidth, this.status));
		}

		int navY = this.height - 64;
		Button previous = Button.builder(Component.literal("<"), button -> this.turnPage(-1))
				.bounds(left, navY, 20, 20).build();
		previous.active = this.page > 0;
		this.addRenderableWidget(previous);

		Button next = Button.builder(Component.literal(">"), button -> this.turnPage(1))
				.bounds(left + 24, navY, 20, 20).build();
		next.active = this.page + 1 < this.pages.size();
		this.addRenderableWidget(next);

		this.addRenderableOnly(text(left + 50, navY + 6, Math.max(60, layoutWidth - 158),
				Component.translatable("vps.config.page", this.page + 1, this.pages.size())));

		this.addRenderableWidget(Button.builder(Component.translatable("vps.config.resetAll"), button -> {
			this.draft = new VillagePathSmoothedConfig();
			this.invalidText.clear();
			this.status = Component.translatable("vps.config.resetAllDone").withStyle(ChatFormatting.YELLOW);
			this.rebuildWidgets();
		}).bounds(left + layoutWidth - 100, navY, 100, 20).build());

		int actionY = this.height - 40;
		this.addRenderableWidget(Button.builder(Component.translatable("vps.config.cancel"), button -> this.close())
				.bounds(left + layoutWidth - 226, actionY, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("vps.config.done"), button -> this.save())
				.bounds(left + layoutWidth - 118, actionY, 118, 20).build());
	}

	private void turnPage(int delta) {
		int target = this.page + delta;
		if (target < 0 || target >= this.pages.size()) {
			return;
		}
		this.page = target;
		this.status = null;
		this.rebuildWidgets();
	}

	private int layoutWidth() {
		return Math.max(MIN_LAYOUT_WIDTH, Math.min(MAX_LAYOUT_WIDTH, this.width - 24));
	}

	/** Control width shrinks on a narrow screen so the label and the reset button always fit. */
	private int controlWidth(int layoutWidth) {
		int room = layoutWidth - RESET_WIDTH - 4 - LABEL_MIN_WIDTH - 6;
		return Math.max(MIN_CONTROL_WIDTH, Math.min(MAX_CONTROL_WIDTH, room));
	}

	/**
	 * Splits rows into pages of at most {@code visible} rows. A page never ends on
	 * a section header, so a title is never orphaned at the bottom of a page.
	 */
	private void collectPages(List<Object> rows, int visible) {
		int index = 0;
		while (index < rows.size()) {
			int end = Math.min(rows.size(), index + visible);
			if (end < rows.size() && rows.get(end - 1) instanceof Component) {
				end--;
			}
			if (end <= index) {
				end = Math.min(rows.size(), index + visible);
			}
			this.pages.add(new ArrayList<>(rows.subList(index, end)));
			index = end;
		}
	}

	/** Index of the first page holding an invalid field, or -1. */
	private int pageWithInvalid() {
		for (int index = 0; index < this.pages.size(); index++) {
			for (Object row : this.pages.get(index)) {
				if (row instanceof Option option && this.invalidText.containsKey(option.key)) {
					return index;
				}
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// sections
	// ------------------------------------------------------------------

	private List<Object> generalSection() {
		List<Object> rows = new ArrayList<>();
		rows.add(sectionHeader("vps.config.section.general"));
		rows.add(new BoolOption("enabled",
				() -> this.draft.enabled, value -> this.draft.enabled = value,
				() -> this.defaults.enabled));
		rows.add(new BoolOption("roadSmoothing",
				() -> this.draft.roadSmoothing.enabled, value -> this.draft.roadSmoothing.enabled = value,
				() -> this.defaults.roadSmoothing.enabled));
		rows.add(new EnumOption<>("objective", VillagePathSmoothedConfig.Objective.values(),
				() -> this.draft.roadSmoothing.objective, value -> this.draft.roadSmoothing.objective = value,
				() -> this.defaults.roadSmoothing.objective));
		rows.add(new EnumOption<>("roadShapeStage", VillagePathSmoothedConfig.RoadShapeStage.values(),
				() -> this.draft.roadSmoothing.roadShapeStage, value -> this.draft.roadSmoothing.roadShapeStage = value,
				() -> this.defaults.roadSmoothing.roadShapeStage));
		rows.add(new EnumOption<>("probeCaliber", VillagePathSmoothedConfig.ProbeCaliber.values(),
				() -> this.draft.roadSmoothing.probeCaliber, value -> this.draft.roadSmoothing.probeCaliber = value,
				() -> this.defaults.roadSmoothing.probeCaliber));
		rows.add(new BoolOption("allowCut",
				() -> this.draft.roadSmoothing.allowCutIntoTerrain,
				value -> this.draft.roadSmoothing.allowCutIntoTerrain = value,
				() -> this.defaults.roadSmoothing.allowCutIntoTerrain));
		rows.add(new BoolOption("protectBuildings",
				() -> this.draft.roadSmoothing.protectBuildings,
				value -> this.draft.roadSmoothing.protectBuildings = value,
				() -> this.defaults.roadSmoothing.protectBuildings));
		rows.add(new EnumOption<>("roadSurfaceDetection", VillagePathSmoothedConfig.RoadSurfaceDetection.values(),
				() -> this.draft.roadSmoothing.roadSurfaceDetection,
				value -> this.draft.roadSmoothing.roadSurfaceDetection = value,
				() -> this.defaults.roadSmoothing.roadSurfaceDetection));
		rows.add(new BoolOption("includeTownCenters",
				() -> this.draft.roadSmoothing.includeTownCentersRoadMaterials,
				value -> this.draft.roadSmoothing.includeTownCentersRoadMaterials = value,
				() -> this.defaults.roadSmoothing.includeTownCentersRoadMaterials));

		rows.add(sectionHeader("vps.config.section.decoration"));
		rows.add(new BoolOption("kerb",
				() -> this.draft.kerb.enabled, value -> this.draft.kerb.enabled = value,
				() -> this.defaults.kerb.enabled));
		rows.add(new IntOption("kerb.minDropHeight",
				() -> this.draft.kerb.minDropHeight, value -> this.draft.kerb.minDropHeight = value,
				0, 64, () -> this.defaults.kerb.minDropHeight));
		rows.add(new BoolOption("railing",
				() -> this.draft.railing.enabled, value -> this.draft.railing.enabled = value,
				() -> this.defaults.railing.enabled));
		rows.add(new IntOption("railing.minDropHeight",
				() -> this.draft.railing.minDropHeight, value -> this.draft.railing.minDropHeight = value,
				0, 64, () -> this.defaults.railing.minDropHeight));
		rows.add(new BoolOption("railing.skipAtDoors",
				() -> this.draft.railing.skipAtBuildingDoors,
				value -> this.draft.railing.skipAtBuildingDoors = value,
				() -> this.defaults.railing.skipAtBuildingDoors));
		rows.add(new BoolOption("waterCrossing",
				() -> this.draft.waterCrossing.enabled, value -> this.draft.waterCrossing.enabled = value,
				() -> this.defaults.waterCrossing.enabled));
		rows.add(new IntOption("waterCrossing.distanceWhenSupported",
				() -> this.draft.waterCrossing.distanceWhenSupported,
				value -> this.draft.waterCrossing.distanceWhenSupported = value,
				0, 8, () -> this.defaults.waterCrossing.distanceWhenSupported));
		rows.add(new IntOption("waterCrossing.distanceWhenUnsupported",
				() -> this.draft.waterCrossing.distanceWhenUnsupported,
				value -> this.draft.waterCrossing.distanceWhenUnsupported = value,
				0, 8, () -> this.defaults.waterCrossing.distanceWhenUnsupported));
		return rows;
	}

	private List<Object> solverSection() {
		List<Object> rows = new ArrayList<>();
		rows.add(sectionHeader("vps.config.section.solver"));
		rows.add(new IntOption("maxFillLayers",
				() -> this.draft.roadSmoothing.maxFillLayers,
				value -> this.draft.roadSmoothing.maxFillLayers = value,
				-1, 64, () -> this.defaults.roadSmoothing.maxFillLayers));
		rows.add(new IntOption("maxClearLayers",
				() -> this.draft.roadSmoothing.maxClearLayers,
				value -> this.draft.roadSmoothing.maxClearLayers = value,
				-1, 64, () -> this.defaults.roadSmoothing.maxClearLayers));
		rows.add(new IntOption("stepViolationPenalty",
				() -> this.draft.roadSmoothing.stepViolationPenalty,
				value -> this.draft.roadSmoothing.stepViolationPenalty = value,
				0, 4096, () -> this.defaults.roadSmoothing.stepViolationPenalty));
		rows.add(new IntOption("dpWindowHalfUnits",
				() -> this.draft.roadSmoothing.dpWindowHalfUnits,
				value -> this.draft.roadSmoothing.dpWindowHalfUnits = value,
				1, 64, () -> this.defaults.roadSmoothing.dpWindowHalfUnits));
		rows.add(new IntOption("maxExactSectionWidth",
				() -> this.draft.roadSmoothing.maxExactSectionWidth,
				value -> this.draft.roadSmoothing.maxExactSectionWidth = value,
				1, 64, () -> this.defaults.roadSmoothing.maxExactSectionWidth));
		rows.add(new IntOption("icmRounds",
				() -> this.draft.roadSmoothing.icmRounds, value -> this.draft.roadSmoothing.icmRounds = value,
				0, 64, () -> this.defaults.roadSmoothing.icmRounds));
		rows.add(new DoubleOption("hybridWeightDeviation",
				() -> this.draft.roadSmoothing.hybridWeightDeviation,
				value -> this.draft.roadSmoothing.hybridWeightDeviation = value,
				0.0D, 1024.0D, () -> this.defaults.roadSmoothing.hybridWeightDeviation));
		rows.add(new IntOption("maxBuildingShift",
				() -> this.draft.roadSmoothing.maxBuildingShift,
				value -> this.draft.roadSmoothing.maxBuildingShift = value,
				0, 384, () -> this.defaults.roadSmoothing.maxBuildingShift));
		rows.add(new IntOption("buildingShiftMedianWingBlocks",
				() -> this.draft.roadSmoothing.buildingShiftMedianWingBlocks,
				value -> this.draft.roadSmoothing.buildingShiftMedianWingBlocks = value,
				0, 64, () -> this.defaults.roadSmoothing.buildingShiftMedianWingBlocks));
		rows.add(new IntOption("surfaceDeviationWarnBlocks",
				() -> this.draft.roadSmoothing.surfaceDeviationWarnBlocks,
				value -> this.draft.roadSmoothing.surfaceDeviationWarnBlocks = value,
				0, 64, () -> this.defaults.roadSmoothing.surfaceDeviationWarnBlocks));
		rows.add(new BoolOption("buildingGuard",
				() -> this.draft.roadSmoothing.buildingGuard,
				value -> this.draft.roadSmoothing.buildingGuard = value,
				() -> this.defaults.roadSmoothing.buildingGuard));
		return rows;
	}

	private List<Object> advancedSection() {
		List<Object> rows = new ArrayList<>();
		rows.add(sectionHeader("vps.config.section.advanced"));
		rows.add(new TextOption("roadPiecesRegex",
				() -> this.draft.roadSmoothing.roadPiecesRegex,
				value -> this.draft.roadSmoothing.roadPiecesRegex = value,
				() -> this.defaults.roadSmoothing.roadPiecesRegex, ConfigValueCodec::isValidRegex));
		rows.add(new TextOption("fillMaterial",
				() -> this.draft.roadSmoothing.fillMaterial,
				value -> this.draft.roadSmoothing.fillMaterial = value,
				() -> this.defaults.roadSmoothing.fillMaterial, value -> !value.trim().isEmpty()));
		rows.add(new IntOption("beardCompensation",
				() -> this.draft.roadSmoothing.beardCompensation,
				value -> this.draft.roadSmoothing.beardCompensation = value,
				0, 64, () -> this.defaults.roadSmoothing.beardCompensation));
		rows.add(new IntOption("beardRadius",
				() -> this.draft.roadSmoothing.beardRadius, value -> this.draft.roadSmoothing.beardRadius = value,
				0, 16, () -> this.defaults.roadSmoothing.beardRadius));
		rows.add(new TextOption("beardKernel",
				() -> this.draft.roadSmoothing.beardKernel,
				value -> this.draft.roadSmoothing.beardKernel = value,
				() -> this.defaults.roadSmoothing.beardKernel,
				value -> "step".equals(value.trim()) || "beard_thin".equals(value.trim())));
		rows.add(new DoubleOption("beardKernelBlocksPerDensity",
				() -> this.draft.roadSmoothing.beardKernelBlocksPerDensity,
				value -> this.draft.roadSmoothing.beardKernelBlocksPerDensity = value,
				0.0D, 64.0D, () -> this.defaults.roadSmoothing.beardKernelBlocksPerDensity));

		rows.add(sectionHeader("vps.config.section.materials"));
		addStyleBlocks(rows, "kerb.blockByStyle", false);
		addStyleBlocks(rows, "railing.leavesByStyle", true);

		rows.add(sectionHeader("vps.config.section.diagnostics"));
		rows.add(new BoolOption("diagnostics.dumpRoadNetwork",
				() -> this.draft.diagnostics.dumpRoadNetwork,
				value -> this.draft.diagnostics.dumpRoadNetwork = value,
				() -> this.defaults.diagnostics.dumpRoadNetwork));
		rows.add(new IntOption("diagnostics.maxRoadCellsPerVillage",
				() -> this.draft.diagnostics.maxRoadCellsPerVillage,
				value -> this.draft.diagnostics.maxRoadCellsPerVillage = value,
				0, 1000000, () -> this.defaults.diagnostics.maxRoadCellsPerVillage));

		rows.add(sectionHeader("vps.config.section.debug"));
		rows.add(new BoolOption("debug.disableVillagerBrains",
				() -> this.draft.debug.disableVillagerBrains,
				value -> this.draft.debug.disableVillagerBrains = value,
				() -> this.defaults.debug.disableVillagerBrains));
		rows.add(new BoolOption("debug.seamSigns",
				() -> this.draft.debug.seamSigns, value -> this.draft.debug.seamSigns = value,
				() -> this.defaults.debug.seamSigns));
		rows.add(new BoolOption("debug.doorSigns",
				() -> this.draft.debug.doorSigns, value -> this.draft.debug.doorSigns = value,
				() -> this.defaults.debug.doorSigns));
		return rows;
	}

	private static Component sectionHeader(String key) {
		return Component.translatable(key).withStyle(ChatFormatting.BOLD);
	}

	/**
	 * The per-style block ids of one {@link VillagePathSmoothedConfig.BlockByStyle}.
	 * They are advanced rows: each style's own default is what that village
	 * biome is tuned for, but a data-pack author needs to swap one without
	 * hand-editing the JSON. All five rows of a group share one tooltip.
	 */
	private void addStyleBlocks(List<Object> rows, String field, boolean leaves) {
		String shared = "vps.config." + field + ".tooltip";
		for (String style : STYLE_NAMES) {
			TextOption option = new TextOption(field + "." + style,
					() -> blockOf(style, leaves),
					value -> setBlockOf(style, leaves, value),
					() -> blockOf(style, leaves, this.defaults),
					value -> !value.trim().isEmpty());
			option.withTooltipKey(shared);
			rows.add(option);
		}
	}

	private String blockOf(String style, boolean leaves) {
		return blockOf(style, leaves, this.draft);
	}

	private static String blockOf(String style, boolean leaves, VillagePathSmoothedConfig config) {
		VillagePathSmoothedConfig.BlockByStyle table = leaves
				? config.railing.leavesByStyle
				: config.kerb.blockByStyle;
		return table == null ? "" : table.forStyle(style);
	}

	private void setBlockOf(String style, boolean leaves, String value) {
		if (leaves) {
			if (this.draft.railing.leavesByStyle == null) {
				this.draft.railing.leavesByStyle = new VillagePathSmoothedConfig.BlockByStyle();
			}
			assignStyle(this.draft.railing.leavesByStyle, style, value);
		} else {
			if (this.draft.kerb.blockByStyle == null) {
				this.draft.kerb.blockByStyle = new VillagePathSmoothedConfig.BlockByStyle();
			}
			assignStyle(this.draft.kerb.blockByStyle, style, value);
		}
	}

	private static void assignStyle(VillagePathSmoothedConfig.BlockByStyle table, String style, String value) {
		switch (style) {
			case "desert":
				table.desert = value;
				break;
			case "savanna":
				table.savanna = value;
				break;
			case "snowy":
				table.snowy = value;
				break;
			case "taiga":
				table.taiga = value;
				break;
			default:
				table.plains = value;
				break;
		}
	}

	// ------------------------------------------------------------------
	// save / close
	// ------------------------------------------------------------------

	private void save() {
		if (!this.invalidText.isEmpty()) {
			int target = this.pageWithInvalid();
			if (target >= 0) {
				this.page = target;
			}
			this.status = Component.translatable("vps.config.invalidSave").withStyle(ChatFormatting.RED);
			this.rebuildWidgets();
			return;
		}
		VillagePathSmoothedConfig.replace(this.draft);
		VillagePathSmoothedConfig.save();
		// A solution is a function of the config, so the anchor-keyed cache must not
		// keep serving networks solved with the pre-save settings (the same reason
		// /vps config reload clears it).
		VpsPipeline.clearSolutionCache();
		VillagePathSmoothed.LOGGER.info("[VPS] config saved from the ModMenu screen; solution cache cleared");
		this.close();
	}

	private void close() {
		//#if MC >= 260000
		//$$ // 26.3 moved the current screen onto Gui; Minecraft#setScreen is gone.
		//$$ this.minecraft.gui.setScreen(this.parent);
		//#else
		this.minecraft.setScreen(this.parent);
		//#endif
	}

	@Override
	public void onClose() {
		// ESC discards the draft: nothing is written and the live config is untouched.
		this.close();
	}

	// ------------------------------------------------------------------
	// option types
	// ------------------------------------------------------------------

	/** One editable config value, with its own reset-to-default button. */
	private abstract class Option {
		final String key;
		/** Overrides the tooltip key when several rows share one explanation (the per-style materials). */
		private String tooltipKey;

		Option(String key) {
			this.key = key;
		}

		Component label() {
			// Materials read "Kerb block, plains" rather than "Kerb.block by style.plains".
			return Component.translatable("vps.config." + this.key);
		}

		/** Builds the control(s) and registers every widget it owns with the screen. */
		abstract void createControl(int x, int y, int width, int height);

		abstract void reset();

		void withTooltipKey(String tooltipKey) {
			this.tooltipKey = tooltipKey;
		}

		void applyTooltip(AbstractWidget widget) {
			String key = this.tooltipKey != null ? this.tooltipKey : "vps.config." + this.key + ".tooltip";
			widget.setTooltip(Tooltip.create(Component.translatable(key)));
		}

		Font font() {
			return VpsConfigScreen.this.font;
		}

		void clearStatus() {
			VpsConfigScreen.this.status = null;
		}
	}

	/** A control that reports its own validation failures back to the screen. */
	private abstract class ValidatedOption extends Option {
		ValidatedOption(String key) {
			super(key);
		}

		/** The text a rebuilt field shows: the rejected entry when there is one, else the draft value. */
		String initialText(String applied) {
			return VpsConfigScreen.this.invalidText.getOrDefault(this.key, applied);
		}

		void setValidity(EditBox box, boolean valid) {
			if (valid) {
				VpsConfigScreen.this.invalidText.remove(this.key);
			}
			box.setTextColor(valid ? TEXT_COLOR : ERROR_COLOR);
		}

		void flagInvalid(String text) {
			VpsConfigScreen.this.invalidText.put(this.key, text);
		}
	}

	private final class BoolOption extends Option {
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private final BooleanSupplier defaultValue;

		BoolOption(String key, BooleanSupplier getter, Consumer<Boolean> setter, BooleanSupplier defaultValue) {
			super(key);
			this.getter = getter;
			this.setter = setter;
			this.defaultValue = defaultValue;
		}

		@Override
		void createControl(int x, int y, int width, int height) {
			CycleButton<Boolean> button = CycleButton.onOffBuilder(this.getter.getAsBoolean())
					.displayOnlyValue()
					.create(x, y, width, height, Component.empty(), (cycle, value) -> {
						this.setter.accept(value);
						this.clearStatus();
					});
			this.applyTooltip(button);
			VpsConfigScreen.this.addRenderableWidget(button);
		}

		@Override
		void reset() {
			this.setter.accept(this.defaultValue.getAsBoolean());
		}
	}

	private final class EnumOption<T extends Enum<T>> extends Option {
		private final T[] values;
		private final Supplier<T> getter;
		private final Consumer<T> setter;
		private final Supplier<T> defaultValue;

		EnumOption(String key, T[] values, Supplier<T> getter, Consumer<T> setter, Supplier<T> defaultValue) {
			super(key);
			this.values = values;
			this.getter = getter;
			this.setter = setter;
			this.defaultValue = defaultValue;
		}

		@Override
		void createControl(int x, int y, int width, int height) {
			Function<T, Component> stringifier = value -> Component.literal(pretty(value.name()));
			CycleButton<T> button = builder(stringifier, this.getter.get())
					.withValues(this.values)
					.displayOnlyValue()
					.create(x, y, width, height, Component.empty(), (cycle, value) -> {
						this.setter.accept(value);
						this.clearStatus();
					});
			this.applyTooltip(button);
			VpsConfigScreen.this.addRenderableWidget(button);
		}

		/**
		 * 1.21.11 folded {@code withInitialValue} into the builder factory, so the
		 * pre-1.21.11 shape needs its own branch. The previous screen used the
		 * single-argument factory on every version and never passed the current
		 * value, which made the button show the FIRST enum constant while the
		 * config held something else.
		 */
		private CycleButton.Builder<T> builder(Function<T, Component> stringifier, T initial) {
			//#if MC >= 12111
			//$$ return CycleButton.builder(stringifier, initial);
			//#else
			return CycleButton.<T>builder(stringifier).withInitialValue(initial);
			//#endif
		}

		@Override
		void reset() {
			this.setter.accept(this.defaultValue.get());
		}
	}

	/** Text field plus -/+ step buttons; unparsable or out-of-range input turns red and blocks saving. */
	private abstract class NumberOption extends ValidatedOption {
		private final int min;
		private final Supplier<String> defaultText;

		NumberOption(String key, int min, Supplier<String> defaultText) {
			super(key);
			this.min = min;
			this.defaultText = defaultText;
		}

		abstract String currentText();

		/** Applies the raw text to the draft; false when it does not parse or is out of range. */
		abstract boolean apply(String text);

		abstract String stepped(int delta);

		@Override
		void createControl(int x, int y, int width, int height) {
			int boxWidth = Math.max(40, width - 2 * (STEP_WIDTH + 2));
			// 26.3 dropped EditBox#setFilter, so validation lives in the responder:
			// the typed text stays as typed, and an invalid entry is only flagged
			// (red) and kept out of the draft.
			EditBox box = new EditBox(this.font(), x + STEP_WIDTH + 2, y, boxWidth, height, Component.empty());
			box.setMaxLength(12);
			box.setResponder(text -> {
				boolean ok = this.apply(text);
				if (!ok) {
					this.flagInvalid(text);
				}
				this.setValidity(box, ok);
			});
			box.setValue(this.initialText(this.currentText()));
			box.setTextColor(VpsConfigScreen.this.invalidText.containsKey(this.key) ? ERROR_COLOR : TEXT_COLOR);
			this.applyTooltip(box);
			VpsConfigScreen.this.addRenderableWidget(box);

			VpsConfigScreen.this.addRenderableWidget(Button.builder(Component.literal("-"), button -> this.step(-1))
					.bounds(x, y, STEP_WIDTH, height).build());
			VpsConfigScreen.this.addRenderableWidget(Button.builder(Component.literal("+"), button -> this.step(1))
					.bounds(x + STEP_WIDTH + 2 + boxWidth + 2, y, STEP_WIDTH, height).build());
		}

		private void step(int delta) {
			this.apply(this.stepped(delta));
			VpsConfigScreen.this.invalidText.remove(this.key);
			this.clearStatus();
			VpsConfigScreen.this.rebuildWidgets();
		}

		@Override
		void reset() {
			this.apply(this.defaultText.get());
		}

		int min() {
			return this.min;
		}
	}

	private final class IntOption extends NumberOption {
		private final IntSupplier getter;
		private final IntConsumer setter;
		private final int max;

		IntOption(String key, IntSupplier getter, IntConsumer setter, int min, int max, IntSupplier defaultValue) {
			super(key, min, () -> Integer.toString(defaultValue.getAsInt()));
			this.getter = getter;
			this.setter = setter;
			this.max = max;
		}

		@Override
		String currentText() {
			return Integer.toString(this.getter.getAsInt());
		}

		@Override
		boolean apply(String text) {
			Integer value = ConfigValueCodec.parseInt(text, this.min(), this.max);
			if (value == null) {
				return false;
			}
			this.setter.accept(value.intValue());
			return true;
		}

		@Override
		String stepped(int delta) {
			int value = this.getter.getAsInt() + delta;
			return Integer.toString(Math.max(this.min(), Math.min(this.max, value)));
		}
	}

	private final class DoubleOption extends NumberOption {
		private final DoubleSupplier getter;
		private final DoubleConsumer setter;
		private final double minValue;
		private final double max;

		DoubleOption(String key, DoubleSupplier getter, DoubleConsumer setter, double min, double max,
				DoubleSupplier defaultValue) {
			super(key, (int) Math.floor(min), () -> formatDouble(defaultValue.getAsDouble()));
			this.getter = getter;
			this.setter = setter;
			this.minValue = min;
			this.max = max;
		}

		@Override
		String currentText() {
			return formatDouble(this.getter.getAsDouble());
		}

		@Override
		boolean apply(String text) {
			Double value = ConfigValueCodec.parseDouble(text, this.minValue, this.max);
			if (value == null) {
				return false;
			}
			this.setter.accept(value.doubleValue());
			return true;
		}

		@Override
		String stepped(int delta) {
			return formatDouble(Math.max(this.minValue, Math.min(this.max, this.getter.getAsDouble() + delta)));
		}
	}

	private final class TextOption extends ValidatedOption {
		private final Supplier<String> getter;
		private final Consumer<String> setter;
		private final Supplier<String> defaultValue;
		private final Predicate<String> validator;

		TextOption(String key, Supplier<String> getter, Consumer<String> setter, Supplier<String> defaultValue,
				Predicate<String> validator) {
			super(key);
			this.getter = getter;
			this.setter = setter;
			this.defaultValue = defaultValue;
			this.validator = validator;
		}

		@Override
		void createControl(int x, int y, int width, int height) {
			EditBox box = new EditBox(this.font(), x, y, width, height, Component.empty());
			box.setMaxLength(255);
			box.setResponder(text -> this.setValidity(box, this.apply(text)));
			box.setValue(this.initialText(this.getter.get()));
			box.setTextColor(VpsConfigScreen.this.invalidText.containsKey(this.key) ? ERROR_COLOR : TEXT_COLOR);
			this.applyTooltip(box);
			VpsConfigScreen.this.addRenderableWidget(box);
		}

		private boolean apply(String text) {
			if (!this.validator.test(text)) {
				this.flagInvalid(text);
				return false;
			}
			this.setter.accept(text.trim());
			return true;
		}

		@Override
		void reset() {
			this.apply(this.defaultValue.get());
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static String formatDouble(double value) {
		return ConfigValueCodec.formatDouble(value);
	}

	private static String pretty(String enumName) {
		return ConfigValueCodec.prettyEnumName(enumName);
	}
}
