/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.peoplecanfly.village_path_smoothed.pipeline.RoadCell;
import dev.peoplecanfly.village_path_smoothed.pipeline.RoadNetwork;
import dev.peoplecanfly.village_path_smoothed.pipeline.SeamSigns;

/**
 * The debug seam signs' pure rule (user request 2026-10-16): a seam is a
 * 4-adjacency edge whose two cells come from different sources (different
 * piece, plaza vs road, seat vs road). The sign is anchored on the more
 * structural cell, names the far cell with the largest |dP|, and carries the
 * known cause only when the edge breaks the half-block guarantee.
 */
public class SeamSignsTest {

	private static RoadCell cell(int x, int z, int piece, int solvedP, boolean plaza, boolean seat) {
		RoadCell cell = new RoadCell(x, z, "plains", solvedP, false);
		cell.pieceIndex = piece;
		cell.plaza = plaza;
		cell.seat = seat;
		return cell;
	}

	private static RoadNetwork net(List<RoadCell> cells) {
		return RoadNetwork.build(cells, 4);
	}

	@Test
	public void twoPiecesSeamGetsASignAndNamesTheLargerDrop() {
		// three cells in a row: (0,0) piece 0, (1,0) piece 0, (2,0) piece 1.
		// Only the piece 0 -> piece 1 edge is a seam. Anchor is the lower x/z
		// (piece 0 side), far cell is piece 1; |dP| = 2 -> the cause is a plain
		// polish residual.
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 0, 128, false, false));
		cells.add(cell(1, 0, 0, 128, false, false));
		cells.add(cell(2, 0, 1, 130, false, false));
		int[][] signs = SeamSigns.compute(net(cells), true);

		assertEquals("one seam cell", 1, signs.length);
		assertArrayEquals(new int[]{1, 128, 0, 2, 130, 0, SeamSigns.REASON_POLISH}, signs[0]);
		assertEquals(1, SeamSigns.violationCount(signs));
	}

	@Test
	public void aLegalSeamStillGetsItsSignButNoCause() {
		// |dP| = 1 (half a block) is legal: the sign exists, lines 3/4 stay empty
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 0, 128, false, false));
		cells.add(cell(1, 0, 1, 129, false, false));
		int[][] signs = SeamSigns.compute(net(cells), true);

		assertEquals(1, signs.length);
		assertEquals(SeamSigns.REASON_NONE, signs[0][6]);
		assertEquals(0, SeamSigns.violationCount(signs));
	}

	@Test
	public void adjacentCellsOfTheSamePieceAndKindAreNotASeam() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 0, 128, false, false));
		cells.add(cell(1, 0, 0, 130, false, false));
		int[][] signs = SeamSigns.compute(net(cells), true);

		assertEquals("same piece, same kind: no seam", 0, signs.length);
	}

	@Test
	public void plazaWinsTheAnchorAndTheCauseIsThePin() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 5, 128, true, false));   // plaza platform
		cells.add(cell(1, 0, 5, 132, false, false));  // street beside it
		int[][] signs = SeamSigns.compute(net(cells), true);

		assertEquals(1, signs.length);
		assertEquals("anchor is the plaza cell", 0, signs[0][0]);
		assertEquals("far cell is the street", 1, signs[0][3]);
		assertEquals(SeamSigns.REASON_PLAZA, signs[0][6]);
	}

	@Test
	public void seatSeamNamesTheParityConstraint() {
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 0, 128, false, true));   // decor seat (even surface)
		cells.add(cell(1, 0, 0, 131, false, false));  // street beside it (odd slab)
		int[][] signs = SeamSigns.compute(net(cells), true);

		assertEquals(1, signs.length);
		assertEquals("anchor is the seat cell", 0, signs[0][0]);
		assertEquals(SeamSigns.REASON_SEAT, signs[0][6]);
	}

	@Test
	public void theWorstNeighbourOfASeamCellIsNamed() {
		// plaza cell (0,0); two street neighbours, (1,0) |dP|=2 and (0,1)
		// |dP|=6: the sign names the larger drop
		List<RoadCell> cells = new ArrayList<>();
		cells.add(cell(0, 0, 5, 128, true, false));
		cells.add(cell(1, 0, 5, 130, false, false));
		cells.add(cell(0, 1, 5, 134, false, false));
		int[][] signs = SeamSigns.compute(net(cells), true);

		assertEquals(1, signs.length);
		assertEquals(0, signs[0][0]);
		assertEquals("the worst drop (0,1) wins: farX", 0, signs[0][3]);
		assertEquals("the worst drop (0,1) wins: farZ", 1, signs[0][5]);
		assertEquals(134, signs[0][4]);
	}
}
