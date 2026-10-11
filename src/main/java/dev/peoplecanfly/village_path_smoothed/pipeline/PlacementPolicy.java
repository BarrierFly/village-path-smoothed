/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * The one decision of the cure route (review 4 section 9): should the vanilla
 * street block currently going through the placement processor chain be dropped?
 *
 * <p>Kept as a pure static predicate with no Minecraft imports so the whole
 * decision matrix is unit tested, including the parts that cannot be reached
 * in-game (a non-village structure, a template layer other than y=0, a disabled
 * world).
 *
 * <p>Why the decision can be this small: every one of the mod's regex-matched
 * pieces is a {@code legacy_single_pool_element} with {@code terrain_matching},
 * so the very same {@code GravityProcessor} instance is the hook point for all
 * of them, and y=0 is the only layer their templates carry blocks on. The
 * decision reads nothing but the template layer and whether the mod's road mask
 * already owns the column - no Y value, no palette, no seed - so it does not
 * depend on the processor ordering assumptions in risk R4.
 */
public final class PlacementPolicy {
	/** What to do with the block the processor chain is currently holding. */
	public enum Decision {
		/** Leave it to vanilla untouched. */
		PASS_THROUGH,
		/** Drop it: the writer will place the road block itself. */
		SUPPRESS
	}

	private PlacementPolicy() {
	}

	/**
	 * @param contextActive false when no village is being placed on this thread,
	 *        i.e. a non-village structure or a world with the switch off
	 * @param templateY the block's y inside the piece template, before any
	 *        processor has moved it
	 * @param claimed true when the mod's road mask owns this world column
	 */
	public static Decision decide(boolean contextActive, int templateY, boolean claimed) {
		if (!contextActive) {
			// not ours: zero behaviour change, not even a mask lookup
			return Decision.PASS_THROUGH;
		}
		// y != 0 is decoration or terrain the gravity processor also carries
		// (street lamps are separate pieces and never reach here); only the road
		// layer is suppressed, so a future template with more layers keeps its
		// content
		if (templateY != 0) {
			return Decision.PASS_THROUGH;
		}
		// an unclaimed cell means the probe failed and the cell was dropped from
		// the mask; it keeps its vanilla road (decision 2 of the review)
		return claimed ? Decision.SUPPRESS : Decision.PASS_THROUGH;
	}
}