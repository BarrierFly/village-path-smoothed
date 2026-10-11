/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference profile P_ref (review 4 section 5.3). Pure Java, no Minecraft
 * imports, so it is unit tested directly.
 *
 * <p>The review closed the case on why the mod needed more than a re-weighted
 * objective. Two terrains a village road meets ask for opposite answers:
 *
 * <ul>
 *   <li>a 1-block mound (probe P1): following it costs half-block slab steps,
 *       levelling it costs up to 1 block of deviation per column;</li>
 *   <li>a 12-block canyon (probe P3): following it means diving to the floor
 *       and climbing back out, while the legal flat span between the two rims
 *       is a bridge deck.</li>
 * </ul>
 *
 * No single (steps, deviation) ordering can tell them apart - review 4 section
 * 3.3 proved that by contradiction, because "follow" and "bridge" are each the
 * cheaper option in the opposite direction. What separates them is feature
 * <em>scale</em>, so the distinction has to be structural: it belongs in the
 * reference profile, not in the weights.
 *
 * <p>Construction, per 1-D piece (a slice is one swept cross-section):
 *
 * <ol>
 *   <li><b>baseline</b> - a legal staircase interpolating the anchors. The
 *       anchors are the hard values (water crossings, and the seam heights in
 *       phase 3) plus both ends of the piece, valued at their own terrain. For
 *       a canyon both rims sit at the same height, so the baseline runs flat
 *       across it; for a terrace between two plazas it is the legal ramp
 *       between them.</li>
 *   <li><b>target</b> - the terrain where the terrain sits inside the fill/clear
 *       envelope around the baseline, the baseline where it does not. A
 *       negative envelope (unlimited fill or clear) always follows.</li>
 *   <li><b>reference</b> - the forward legal chase of the target, so the result
 *       satisfies |dP| &lt;= 1 by construction and is deterministic.</li>
 * </ol>
 *
 * <p>Consequence, and the reason the solver can now be deviation-first: a mound
 * is inside the envelope and is followed, a canyon leaves it and becomes a span
 * whose level is decided by geometry rather than by cost, and the writer turns
 * that level into a bridge deck or a lined tunnel because the fill/clear caps
 * say so.
 */
public final class RoadReference {
	/** Spans whose two ends could not be joined legally; surfaced through /vps stats. */
	private static final AtomicLong ILLEGAL_DROPS = new AtomicLong();

	private RoadReference() {
	}

	/**
	 * One piece's reference profile, one entry per slice.
	 *
	 * @param ref per-slice reference walk surface, half-block units
	 * @param target per-slice value the reference chases: terrain, baseline (span) or the pin
	 * @param span per-slice flag: true where the terrain left the envelope and the reference is a level span
	 * @param spanSlices how many slices ended up on a level span
	 * @param illegalDrops spans whose two ends could not be joined legally, so the drop had to be kept
	 */
	public record Profile(int[] ref, int[] target, boolean[] span, int spanSlices, int illegalDrops) {
	}

	/**
	 * Builds the reference for one 1-D piece.
	 *
	 * @param terrain per-slice terrain walk surface (median of the slice's baseP), half units
	 * @param pinned per-slice flag: this slice carries a hard value
	 * @param pinValue per-slice hard value; only read where {@code pinned} is true
	 * @param fillBudget2 how far above the baseline the road stays affordable, half units (negative = unlimited)
	 * @param clearBudget2 same, downwards (negative = unlimited)
	 */
	public static Profile forArm(int[] terrain, boolean[] pinned, int[] pinValue, int fillBudget2, int clearBudget2) {
		int n = terrain.length;
		int[] ref = new int[n];
		int[] target = new int[n];
		boolean[] span = new boolean[n];
		if (n == 0) {
			return new Profile(ref, target, span, 0, 0);
		}

		Baseline baseline = buildBaseline(terrain, pinned, pinValue);
		boolean anyPin = false;
		for (boolean isPin : pinned) {
			anyPin |= isPin;
		}
		int spanSlices = 0;
		for (int s = 0; s < n; s++) {
			if (pinned[s]) {
				target[s] = pinValue[s];
				ref[s] = pinValue[s];
				continue;
			}
			int deviation = terrain[s] - baseline.value[s];
			int budget = deviation >= 0 ? fillBudget2 : clearBudget2;
			if ((budget < 0 || Math.abs(deviation) <= budget) && reachableFromPin(terrain, pinned, pinValue, s, anyPin)) {
				// inside the fill/cut envelope and legally reachable from the
				// hard values: the terrain IS the reference
				target[s] = terrain[s];
				ref[s] = terrain[s];
			} else {
				// outside it (canyon floor, terrace wall), or unreachable from a
				// pinned end (a street clamped to two junctions): a level span
				// whose level is decided by geometry, not by the cost model
				target[s] = baseline.value[s];
				ref[s] = baseline.value[s];
				span[s] = true;
				spanSlices++;
			}
		}
		// forward legal chase: one half unit per slice, so the reference is
		// always a legal staircase without consulting the cost model
		ref[0] = target[0];
		for (int s = 1; s < n; s++) {
			int previous = ref[s - 1];
			ref[s] = Math.max(previous - 1, Math.min(previous + 1, target[s]));
		}
		// a hard value outranks the chase: the DP keeps it at the cost of a
		// counted violation, which is exactly what an anchor is for
		for (int s = 0; s < n; s++) {
			if (pinned[s]) {
				ref[s] = pinValue[s];
			}
		}
		if (baseline.illegalDrops > 0) {
			ILLEGAL_DROPS.addAndGet(baseline.illegalDrops);
		}
		return new Profile(ref, target, span, spanSlices, baseline.illegalDrops);
	}

	/**
	 * Can the terrain value at slice {@code s} be reached from every hard value
	 * at a legal one half unit per slice?
	 *
	 * <p>This is the half of the follow test that the fill/cut envelope alone
	 * cannot express. Review 4 probe M2 is the case that needs it: a street
	 * clamped between two junctions sits flat on terrain for 17 slices and then
	 * steps up 8 blocks in one, and the far junction is 8 blocks up. The terrain
	 * is well inside a 6-block clear envelope relative to the baseline, so the
	 * envelope says "follow" - and so does any single pin, because the near
	 * junction is at exactly the terrain height. But one profile has to satisfy
	 * <em>both</em> junctions, and from the far one the terrain is 16 half units
	 * away over 7 slices. So the test is an AND over the pins: reachable from
	 * every one of them, or it is a span and the span is the legal ramp.
	 *
	 * <p>With no hard value at all there is nothing to be unreachable from and
	 * the test is vacuous, which is the plain street / canyon case: those are
	 * decided by the envelope alone.
	 */
	private static boolean reachableFromPin(int[] terrain, boolean[] pinned, int[] pinValue, int s, boolean anyPin) {
		if (!anyPin) {
			return true;
		}
		for (int p = 0; p < terrain.length; p++) {
			if (pinned[p] && Math.abs(terrain[s] - pinValue[p]) > Math.abs(s - p)) {
				return false;
			}
		}
		return true;
	}

	private static final class Baseline {
		final int[] value;
		final int illegalDrops;

		Baseline(int[] value, int illegalDrops) {
			this.value = value;
			this.illegalDrops = illegalDrops;
		}
	}

	/**
	 * Legal staircase through the anchors: for every pair of consecutive
	 * anchors the straight interpolation is clamped into the range both ends can
	 * actually reach (one half unit per slice). A pair that cannot be joined
	 * legally at all stays reachable from the near side and is counted.
	 */
	private static Baseline buildBaseline(int[] terrain, boolean[] pinned, int[] pinValue) {
		int n = terrain.length;
		List<int[]> anchors = new ArrayList<>();
		for (int s = 0; s < n; s++) {
			if (pinned[s]) {
				anchors.add(new int[]{s, pinValue[s]});
			}
		}
		if (anchors.isEmpty() || anchors.get(0)[0] != 0) {
			anchors.add(0, new int[]{0, terrain[0]});
		}
		if (anchors.get(anchors.size() - 1)[0] != n - 1) {
			anchors.add(new int[]{n - 1, terrain[n - 1]});
		}
		anchors.sort((a, b) -> Integer.compare(a[0], b[0]));

		int[] value = new int[n];
		int illegal = 0;
		for (int a = 0; a + 1 < anchors.size(); a++) {
			int fromSlice = anchors.get(a)[0];
			int toSlice = anchors.get(a + 1)[0];
			int fromValue = anchors.get(a)[1];
			int toValue = anchors.get(a + 1)[1];
			int gap = Math.max(1, toSlice - fromSlice);
			for (int s = fromSlice; s <= toSlice; s++) {
				if (s == fromSlice) {
					value[s] = fromValue;
					continue;
				}
				if (s == toSlice) {
					value[s] = toValue;
					continue;
				}
				int back = s - fromSlice;
				int forward = toSlice - s;
				long linear = fromValue + Math.round((double) (toValue - fromValue) * back / gap);
				long lo = Math.max((long) fromValue - back, (long) toValue - forward);
				long hi = Math.min((long) fromValue + back, (long) toValue + forward);
				if (lo > hi) {
					lo = (long) fromValue - back;
					hi = (long) fromValue + back;
					illegal++;
				}
				value[s] = (int) Math.max(lo, Math.min(hi, linear));
			}
		}
		return new Baseline(value, illegal);
	}

	public static long illegalDrops() {
		return ILLEGAL_DROPS.get();
	}

	/** Clears the counter; tests use it to keep assertions independent. */
	public static void resetCounters() {
		ILLEGAL_DROPS.set(0);
	}
}