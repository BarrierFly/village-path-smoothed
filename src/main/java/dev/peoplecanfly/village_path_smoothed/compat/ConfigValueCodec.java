/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.compat;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure text/number helpers behind the ModMenu config screen.
 *
 * <p>The screen is client-only and cannot be unit tested, but the parts that can
 * actually be wrong are exactly the ones here: does a typed value land in the
 * option's range, does a value survive the round trip through the field, and is
 * the piece regex usable. No Minecraft types, so {@code ConfigValueCodecTest}
 * runs on every version node with the rest of the pure suite.
 */
public final class ConfigValueCodec {
	private ConfigValueCodec() {
	}

	/**
	 * Parses an integer option value.
	 *
	 * @return the value, or {@code null} when the text does not parse or falls
	 *         outside {@code [min, max]}. The screen shows the field red and
	 *         refuses to save on {@code null}; it never clamps, so a typo cannot
	 *         silently become a legal-but-wrong setting.
	 */
	public static Integer parseInt(String text, int min, int max) {
		if (text == null) {
			return null;
		}
		try {
			int value = Integer.parseInt(text.trim());
			return value < min || value > max ? null : Integer.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Parses a floating-point option value; {@code null} on a parse failure, NaN, infinity or an out-of-range value. */
	public static Double parseDouble(String text, double min, double max) {
		if (text == null) {
			return null;
		}
		try {
			double value = Double.parseDouble(text.trim());
			if (Double.isNaN(value) || Double.isInfinite(value)) {
				return null;
			}
			return value < min || value > max ? null : Double.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Locale-free rendering of an option value. Whole values lose the {@code .0}
	 * tail so the field stays narrow; a locale that writes {@code 8,0} or
	 * non-ASCII digits would make the field unparsable on the next open.
	 */
	public static String formatDouble(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1.0E9D) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}

	/** True when the candidate compiles as a regex; the road-piece option is a regex, not free text. */
	public static boolean isValidRegex(String candidate) {
		if (candidate == null) {
			return false;
		}
		try {
			Pattern.compile(candidate);
			return true;
		} catch (PatternSyntaxException e) {
			return false;
		}
	}

	/** {@code SMOOTH_FIRST} -> {@code Smooth first}, for the cycle-button labels. */
	public static String prettyEnumName(String enumName) {
		if (enumName == null || enumName.isEmpty()) {
			return "";
		}
		String[] parts = enumName.split("_");
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(part.charAt(0)).append(part.substring(1).toLowerCase());
		}
		return builder.toString();
	}
}
