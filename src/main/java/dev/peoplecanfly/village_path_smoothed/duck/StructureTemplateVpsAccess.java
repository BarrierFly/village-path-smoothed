/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.duck;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;

/**
 * Duck interface mixed into StructureTemplate; the palette list is private and
 * vanilla offers no public way to enumerate all blocks of a template.
 */
public interface StructureTemplateVpsAccess {
	List<StructureTemplate.Palette> village_path_smoothed$getPalettes();
}
