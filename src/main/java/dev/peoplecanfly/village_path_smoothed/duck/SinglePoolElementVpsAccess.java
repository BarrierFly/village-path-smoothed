/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.duck;

/**
 * Duck interface mixed into SinglePoolElement to read the template location.
 * SinglePoolElement#getTemplateLocation() does not exist on 1.19.4, so we read
 * the "template" field through an accessor instead; it has the same name in
 * every supported version.
 */
public interface SinglePoolElementVpsAccess {
	/** The template location as a string ("minecraft:village/plains/streets/..."), or null for runtime templates. */
	String village_path_smoothed$getTemplateLocation();
}
