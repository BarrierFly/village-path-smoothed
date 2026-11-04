/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import dev.peoplecanfly.village_path_smoothed.compat.ConfigValueCodec;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The ModMenu config screen is client-only and cannot be unit tested, so the
 * parts that can actually be wrong live in {@link ConfigValueCodec}: does a
 * typed value land inside the option's range, does it round trip through the
 * field, and is the road-piece regex usable. A regression here would silently
 * write a wrong config (e.g. clamping {@code -1} "unlimited" to 0).
 */
public class ConfigValueCodecTest {
	@Test
	public void parseIntAcceptsTheInclusiveRangeAndTrimmedText() {
		assertEquals(Integer.valueOf(-1), ConfigValueCodec.parseInt("-1", -1, 64));
		assertEquals(Integer.valueOf(64), ConfigValueCodec.parseInt("64", -1, 64));
		assertEquals(Integer.valueOf(4), ConfigValueCodec.parseInt("  4  ", -1, 64));
		assertEquals(Integer.valueOf(0), ConfigValueCodec.parseInt("0", 0, 64));
	}

	@Test
	public void parseIntRejectsOutOfRangeInsteadOfClamping() {
		// -1 means "unlimited" for the fill/clear caps: clamping it to 0 would
		// silently turn "unlimited" into "no fill at all".
		assertNull(ConfigValueCodec.parseInt("-1", 0, 64));
		assertNull(ConfigValueCodec.parseInt("65", -1, 64));
		assertNull(ConfigValueCodec.parseInt("999999999999", -1, 64));
	}

	@Test
	public void parseIntRejectsNonNumbers() {
		assertNull(ConfigValueCodec.parseInt("", -1, 64));
		assertNull(ConfigValueCodec.parseInt("four", -1, 64));
		assertNull(ConfigValueCodec.parseInt("4.5", -1, 64));
		assertNull(ConfigValueCodec.parseInt(null, -1, 64));
	}

	@Test
	public void parseDoubleAcceptsFractionsAndRejectsGarbage() {
		assertEquals(Double.valueOf(8.5D), ConfigValueCodec.parseDouble("8.5", 0.0D, 1024.0D));
		assertEquals(Double.valueOf(0.0D), ConfigValueCodec.parseDouble("0", 0.0D, 1024.0D));
		assertNull(ConfigValueCodec.parseDouble("NaN", 0.0D, 1024.0D));
		assertNull(ConfigValueCodec.parseDouble("Infinity", 0.0D, 1024.0D));
		assertNull(ConfigValueCodec.parseDouble("1025", 0.0D, 1024.0D));
		assertNull(ConfigValueCodec.parseDouble("-0.5", 0.0D, 1024.0D));
		assertNull(ConfigValueCodec.parseDouble("8,5", 0.0D, 1024.0D));
	}

	@Test
	public void formatDoubleIsLocaleFreeAndDropsThePointZeroTail() {
		assertEquals("8", ConfigValueCodec.formatDouble(8.0D));
		assertEquals("1", ConfigValueCodec.formatDouble(1.0D));
		assertEquals("8.5", ConfigValueCodec.formatDouble(8.5D));
		// No locale may turn the separator into a comma: the field has to parse
		// its own output on the next open.
		assertFalse(ConfigValueCodec.formatDouble(0.25D).contains(","));
		assertNotNull(ConfigValueCodec.parseInt(ConfigValueCodec.formatDouble(-1.0D), -1, 64));
	}

	@Test
	public void isValidRegexSeparatesAPatternFromPlainText() {
		assertTrue(ConfigValueCodec.isValidRegex(VillagePathSmoothedConfig.RoadSmoothing.DEFAULT_ROAD_PIECES_REGEX));
		assertTrue(ConfigValueCodec.isValidRegex("^minecraft:village/.*$"));
		assertFalse(ConfigValueCodec.isValidRegex("[unclosed"));
		assertFalse(ConfigValueCodec.isValidRegex(null));
	}

	@Test
	public void prettyEnumNameMatchesTheExistingCycleButtonLabels() {
		// The screen's labels pre-date this helper; keep their exact shape so the
		// refactor cannot rename a value the player already knows.
		assertEquals("Smooth First", ConfigValueCodec.prettyEnumName("SMOOTH_FIRST"));
		assertEquals("T1 5", ConfigValueCodec.prettyEnumName("T1_5"));
		assertEquals("Center Window", ConfigValueCodec.prettyEnumName("CENTER_WINDOW"));
		assertEquals("", ConfigValueCodec.prettyEnumName(""));
	}
}
