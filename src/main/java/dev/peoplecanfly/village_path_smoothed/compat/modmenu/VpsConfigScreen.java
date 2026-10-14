/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat.modmenu;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal config screen built from vanilla widgets only (no Cloth Config):
 * feature toggles and the solver objective. Numeric knobs are edited through
 * the config file or /vps config; the file is written when the screen closes.
 */
public class VpsConfigScreen extends Screen {
	private final Screen parent;
	private final List<AbstractWidget> optionWidgets = new ArrayList<>();

	//#if MC >= 260000
	//$$ public VpsConfigScreen(Screen parent) {
	//$$ 	super(net.minecraft.client.Minecraft.getInstance(), net.minecraft.client.Minecraft.getInstance().font, Component.translatable("vps.config.title"));
	//$$ 	this.parent = parent;
	//$$ }
	//#else
	public VpsConfigScreen(Screen parent) {
		super(Component.translatable("vps.config.title"));
		this.parent = parent;
	}
	//#endif

	@Override
	protected void init() {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		this.optionWidgets.clear();

		int centerX = this.width / 2;
		int y = 30;
		int width = 260;

		this.addOption(CycleButton.onOffBuilder(config.enabled)
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.enabled"),
						(button, value) -> config.enabled = value));
		y += 24;
		this.addOption(CycleButton.onOffBuilder(config.roadSmoothing.enabled)
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.roadSmoothing"),
						(button, value) -> config.roadSmoothing.enabled = value));
		y += 24;
		//#if MC >= 12111
		//$$ this.addOption(CycleButton.<VillagePathSmoothedConfig.Objective>builder(
		//$$ 				objective -> Component.literal(pretty(objective.name())),
		//$$ 				config.roadSmoothing.objective)
		//$$ 				.withValues(java.util.Arrays.asList(VillagePathSmoothedConfig.Objective.values()))
		//$$ 				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.objective"),
		//$$ 						(button, value) -> config.roadSmoothing.objective = value));
		//#else
		this.addOption(CycleButton.<VillagePathSmoothedConfig.Objective>builder(objective -> Component.literal(pretty(objective.name())))
				.withValues(java.util.Arrays.asList(VillagePathSmoothedConfig.Objective.values()))
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.objective"),
						(button, value) -> config.roadSmoothing.objective = value));
		//#endif
		y += 24;
		this.addOption(CycleButton.onOffBuilder(config.roadSmoothing.allowCutIntoTerrain)
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.allowCut"),
						(button, value) -> config.roadSmoothing.allowCutIntoTerrain = value));
		y += 24;
		//#if MC >= 12111
		//$$ this.addOption(CycleButton.<VillagePathSmoothedConfig.ProbeCaliber>builder(
		//$$ 				caliber -> Component.literal(pretty(caliber.name())),
		//$$ 				config.roadSmoothing.probeCaliber)
		//$$ 				.withValues(java.util.Arrays.asList(VillagePathSmoothedConfig.ProbeCaliber.values()))
		//$$ 				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.probeCaliber"),
		//$$ 						(button, value) -> config.roadSmoothing.probeCaliber = value));
		//#else
		this.addOption(CycleButton.<VillagePathSmoothedConfig.ProbeCaliber>builder(caliber -> Component.literal(pretty(caliber.name())))
				.withValues(java.util.Arrays.asList(VillagePathSmoothedConfig.ProbeCaliber.values()))
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.probeCaliber"),
						(button, value) -> config.roadSmoothing.probeCaliber = value));
		//#endif
		y += 24;
		this.addOption(CycleButton.onOffBuilder(config.kerb.enabled)
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.kerb"),
						(button, value) -> config.kerb.enabled = value));
		y += 24;
		this.addOption(CycleButton.onOffBuilder(config.railing.enabled)
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.railing"),
						(button, value) -> config.railing.enabled = value));
		y += 24;
		this.addOption(CycleButton.onOffBuilder(config.waterCrossing.enabled)
				.create(centerX - width / 2, y, width, 20, Component.translatable("vps.config.waterCrossing"),
						(button, value) -> config.waterCrossing.enabled = value));

		this.addRenderableWidget(Button.builder(Component.translatable("vps.config.done"), button -> this.onClose())
				.bounds(centerX - width / 2, y + 12, width, 20)
				.build());
	}

	private void addOption(AbstractWidget widget) {
		this.optionWidgets.add(widget);
		this.addRenderableWidget(widget);
	}

	@Override
	public void onClose() {
		VillagePathSmoothedConfig.save();
		VillagePathSmoothed.LOGGER.info("[VPS] config saved from the ModMenu screen");
		super.onClose();
	}

	private static String pretty(String enumName) {
		String[] parts = enumName.split("_");
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(part.charAt(0)).append(part.substring(1).toLowerCase());
		}
		return builder.toString();
	}
}
