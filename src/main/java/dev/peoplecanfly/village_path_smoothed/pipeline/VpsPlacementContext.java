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
	/**
	 * Sink for the blocks a village pile feature really places during this
	 * placement. It is the solution's set, so blocks recorded while one chunk
	 * places stay visible to the writers of the other chunks (audit 2026-10-20,
	 * decision 4).
	 */
	private static final ThreadLocal<java.util.Set<Long>> PILE_BLOCKS = new ThreadLocal<>();
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	/**
	 * 2026-10-22: the Y vanilla's gravity processor moved each y=0 block to
	 * before suppression, keyed by packed XZ. This is the actual structure
	 * ground height at placement time; the writer uses it to move a scenery
	 * surface's above-ground blocks with it. Last write per column wins, which
	 * is exactly the vanilla placement order.
	 */
	private static final ThreadLocal<java.util.Map<Long, Integer>> VANILLA_GROUND_Y =
			ThreadLocal.withInitial(java.util.HashMap::new);

	private VpsPlacementContext() {
	}

	public static void push(RoadNetwork network, java.util.Set<Long> pileBlocks) {
		int[] depth = DEPTH.get();
		if (depth[0] == 0) {
			VANILLA_GROUND_Y.get().clear();
		}
		depth[0]++;
		NETWORK.set(network);
		PILE_BLOCKS.set(pileBlocks);
	}

	public static void pop() {
		int[] depth = DEPTH.get();
		depth[0]--;
		if (depth[0] <= 0) {
			DEPTH.remove();
			NETWORK.remove();
			PILE_BLOCKS.remove();
			VANILLA_GROUND_Y.remove();
		}
	}

	/** Records the vanilla-adjusted y=0 block Y of the piece currently placing. */
	public static void recordVanillaGround(int templateY, int worldX, int worldY, int worldZ) {
		if (templateY != 0 || NETWORK.get() == null) {
			return;
		}
		VANILLA_GROUND_Y.get().put(RoadCell.packedXZ(worldX, worldZ), worldY);
	}

	/** The current chunk's vanilla y=0 ground Y per packed XZ column. */
	public static java.util.Map<Long, Integer> vanillaGround() {
		return new java.util.HashMap<>(VANILLA_GROUND_Y.get());
	}

	/** The road mask of the village currently being placed on this thread, or null. */
	public static RoadNetwork current() {
		return NETWORK.get();
	}

	/** Records one block the current village's pile feature really placed. */
	public static void recordPileBlock(int x, int y, int z) {
		java.util.Set<Long> sink = PILE_BLOCKS.get();
		if (sink != null) {
			sink.add(RoadCell.packedXYZ(x, y, z));
		}
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
		boolean claimed = index >= 0;
		PlacementPolicy.Decision decision = PlacementPolicy.decide(true, templateY, claimed);
		if (decision == PlacementPolicy.Decision.SUPPRESS) {
			VpsPipeline.SUPPRESSED_VANILLA_ROAD_BLOCKS.incrementAndGet();
		} else {
			VpsPipeline.SUPPRESSION_PASSED_THROUGH.incrementAndGet();
		}
		return decision;
	}
}