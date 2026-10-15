/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * The placement context of the cure route (review 4 section 9): which world
 * columns the mod's road mask owns while a village start is placing its pieces.
 *
 * <p>Vanilla places a structure piece by walking every block of its template
 * through a processor chain, and one of those processors is the gravity
 * processor that drops the street onto the real terrain. Returning null from
 * there drops the block and every later processor never sees it, which is why
 * suppressing there beats "place a road and then erase the vanilla one": the
 * two residue windows of review 4 section 6.2 (the profile 9-10 blocks above
 * the surface, or 7-8 below it, where the cleanup range never reached the
 * vanilla block) simply stop existing.
 *
 * <p>Scope discipline (risk R5): the context is pushed at
 * {@code StructureStart#placeInChunk} HEAD and popped at RETURN, so it is live
 * for exactly the pieces of the start being placed, on the thread doing the
 * placing. Other structures, other worlds and other threads see
 * {@link #current()} as null and get a pass-through.
 */
public final class VpsPlacementContext {
	private static final ThreadLocal<RoadNetwork> NETWORK = new ThreadLocal<>();
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private VpsPlacementContext() {
	}

	public static void push(RoadNetwork network) {
		DEPTH.get()[0]++;
		NETWORK.set(network);
	}

	public static void pop() {
		int[] depth = DEPTH.get();
		depth[0]--;
		if (depth[0] <= 0) {
			DEPTH.remove();
			NETWORK.remove();
		}
	}

	/** The road mask of the village currently being placed on this thread, or null. */
	public static RoadNetwork current() {
		return NETWORK.get();
	}

	/**
	 * The suppression decision for one block going through the processor chain.
	 * The mask lookup only happens while a context is live, so the pass-through
	 * path costs one thread-local read and nothing else.
	 *
	 * <p>The layer test runs before the mask lookup and before the counters.
	 * {@link PlacementPolicy#decide} short-circuits on it too, but by then this
	 * method has already paid a hash lookup on the road mask and a contended
	 * {@link java.util.concurrent.atomic.AtomicLong} increment - and this runs
	 * for every block of every piece the start places. The rule itself still
	 * lives in {@link PlacementPolicy#isRoadLayer} so there is exactly one copy.
	 */
	public static PlacementPolicy.Decision decide(int templateY, int worldX, int worldZ) {
		RoadNetwork network = NETWORK.get();
		if (network == null) {
			return PlacementPolicy.Decision.PASS_THROUGH;
		}
		if (!PlacementPolicy.isRoadLayer(templateY)) {
			return PlacementPolicy.Decision.PASS_THROUGH;
		}
		int index = network.cellAt(worldX, worldZ);
		// a fixed base seat is in the mask for the SOLVER only (its vanilla piece
		// renders the block) - the suppression must not claim it either, or the
		// seat block under the lamp/tree would be dropped with nothing written back
		boolean claimed = index >= 0 && !network.cells.get(index).fixedBase;
		PlacementPolicy.Decision decision = PlacementPolicy.decide(true, templateY, claimed);
		if (decision == PlacementPolicy.Decision.SUPPRESS) {
			VpsPipeline.SUPPRESSED_VANILLA_ROAD_BLOCKS.incrementAndGet();
		} else {
			VpsPipeline.SUPPRESSION_PASSED_THROUGH.incrementAndGet();
		}
		return decision;
	}
}