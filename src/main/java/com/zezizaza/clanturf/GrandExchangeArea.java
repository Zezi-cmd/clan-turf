/*
 * Copyright (c) 2026, Q
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.zezizaza.clanturf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * The claimable area, defined as a polygon: you give the outer corner points (in order
 * around the perimeter) and everything inside is fair game. Any shape, any number of
 * corners.
 *
 * <p>To set the real Grand Exchange fortress: walk the wall, read the dev-tools
 * <b>Location &rarr; World</b> X,Y at each corner going around in one direction, and drop
 * the pairs into {@link #VERTICES} below. The code fills the interior automatically.
 *
 * <p>Containment tests a tile by its own (x, y) - the tile's south-west corner, which
 * is exactly what the dev-tools readout gives - and is <b>boundary-inclusive</b>, so
 * a tile sitting on the wall line counts as inside on every side (north/east included, not
 * just south/west). Everything else in the plugin calls {@link #contains}, so nothing else
 * changes when the vertex list changes.
 */
final class GrandExchangeArea
{
	/**
	 * Outer boundary corners as {@code {worldX, worldY}}, in order around the GE fortress
	 * wall. Traced in-game from the dev-tools Location readout, one corner per direction
	 * change. The fill covers everything inside; walkability of a given corner tile (trees,
	 * objects) is irrelevant to the geometry.
	 */
	private static final int[][] VERTICES = {
			{3144, 3468},
			{3139, 3473},
			{3139, 3481},
			{3142, 3484},
			{3142, 3492},
			{3139, 3495},
			{3139, 3512},
			{3143, 3516},
			{3157, 3516},
			{3160, 3513},
			{3168, 3513},
			{3171, 3516},
			{3188, 3516},
			{3197, 3507},
			{3197, 3506},
			{3195, 3504},
			{3195, 3503},
			{3189, 3497},
			{3189, 3479},
			{3186, 3476},
			{3186, 3468},
	};

	/**
	 * Tiles inside the boundary you can never stand on (the central building, stalls, staircases, plus
	 * two walkable-but-walled-off tiles), gathered with the dev unwalkable scan. They render as cosmetic
	 * "filler" - each one takes a neighboring clan's color so pockets fill in smoothly - but are never
	 * claimable, so they're excluded from the walkable total and never counted or synced.
	 */
	private static final int[][] FILLER_TILES = {
			{3139, 3481}, {3139, 3496}, {3139, 3501}, {3139, 3506}, {3139, 3511}, {3140, 3474},
			{3140, 3475}, {3140, 3476}, {3140, 3477}, {3140, 3478}, {3140, 3479}, {3140, 3504},
			{3141, 3475}, {3141, 3476}, {3141, 3477}, {3141, 3478}, {3141, 3498}, {3141, 3499},
			{3141, 3509}, {3141, 3510}, {3141, 3513}, {3142, 3474}, {3142, 3475}, {3142, 3476},
			{3142, 3477}, {3142, 3478}, {3142, 3479}, {3142, 3485}, {3142, 3490}, {3142, 3493},
			{3142, 3494}, {3142, 3498}, {3142, 3499}, {3142, 3506}, {3142, 3507}, {3142, 3509},
			{3142, 3510}, {3143, 3481}, {3143, 3482}, {3143, 3483}, {3143, 3493}, {3143, 3494},
			{3143, 3495}, {3143, 3496}, {3143, 3506}, {3143, 3507}, {3144, 3480}, {3144, 3482},
			{3144, 3483}, {3144, 3495}, {3144, 3496}, {3144, 3516}, {3145, 3469}, {3145, 3471},
			{3145, 3500}, {3145, 3501}, {3145, 3514}, {3145, 3515}, {3146, 3469}, {3146, 3470},
			{3146, 3471}, {3146, 3500}, {3146, 3501}, {3146, 3514}, {3146, 3515}, {3147, 3469},
			{3147, 3470}, {3147, 3471}, {3147, 3512}, {3147, 3513}, {3148, 3469}, {3148, 3470},
			{3148, 3471}, {3148, 3509}, {3148, 3510}, {3148, 3512}, {3148, 3513}, {3149, 3469},
			{3149, 3470}, {3149, 3471}, {3149, 3509}, {3149, 3510}, {3150, 3469}, {3150, 3471},
			{3150, 3516}, {3151, 3473}, {3152, 3468}, {3152, 3472}, {3152, 3513}, {3152, 3514},
			{3153, 3468}, {3153, 3470}, {3153, 3471}, {3153, 3513}, {3153, 3514}, {3154, 3470},
			{3154, 3471}, {3154, 3485}, {3154, 3486}, {3154, 3493}, {3154, 3494}, {3155, 3485},
			{3155, 3494}, {3156, 3484}, {3156, 3495}, {3156, 3516}, {3157, 3468}, {3159, 3481},
			{3159, 3498}, {3160, 3479}, {3160, 3480}, {3160, 3498}, {3160, 3499}, {3160, 3500},
			{3161, 3468}, {3161, 3479}, {3161, 3499}, {3161, 3500}, {3161, 3513}, {3163, 3488},
			{3163, 3489}, {3163, 3490}, {3163, 3491}, {3164, 3488}, {3164, 3489}, {3164, 3490},
			{3164, 3491}, {3164, 3510}, {3164, 3511}, {3165, 3488}, {3165, 3489}, {3165, 3490},
			{3165, 3491}, {3165, 3510}, {3165, 3511}, {3166, 3488}, {3166, 3489}, {3166, 3490},
			{3166, 3491}, {3166, 3513}, {3168, 3468}, {3168, 3479}, {3168, 3499}, {3168, 3500},
			{3169, 3479}, {3169, 3480}, {3169, 3499}, {3169, 3500}, {3170, 3481}, {3170, 3498},
			{3172, 3468}, {3172, 3469}, {3172, 3470}, {3172, 3516}, {3173, 3468}, {3173, 3469},
			{3173, 3470}, {3173, 3484}, {3173, 3495}, {3174, 3468}, {3174, 3471}, {3174, 3485},
			{3174, 3493}, {3174, 3494}, {3175, 3472}, {3175, 3485}, {3175, 3486}, {3175, 3493},
			{3175, 3494}, {3176, 3473}, {3177, 3469}, {3177, 3473}, {3177, 3513}, {3177, 3514},
			{3177, 3516}, {3178, 3468}, {3178, 3469}, {3178, 3472}, {3178, 3513}, {3178, 3514},
			{3179, 3472}, {3180, 3472}, {3181, 3477}, {3181, 3478}, {3182, 3468}, {3182, 3474},
			{3182, 3475}, {3182, 3476}, {3182, 3479}, {3182, 3509}, {3182, 3510}, {3182, 3516},
			{3183, 3468}, {3183, 3480}, {3183, 3503}, {3183, 3504}, {3184, 3503}, {3184, 3504},
			{3184, 3507}, {3184, 3509}, {3184, 3510}, {3184, 3512}, {3185, 3476}, {3185, 3477},
			{3185, 3507}, {3185, 3509}, {3185, 3510}, {3185, 3512}, {3186, 3471}, {3186, 3472},
			{3186, 3473}, {3186, 3485}, {3186, 3486}, {3187, 3478}, {3187, 3479}, {3187, 3485},
			{3187, 3486}, {3187, 3499}, {3187, 3500}, {3187, 3509}, {3187, 3510}, {3187, 3516},
			{3188, 3478}, {3188, 3479}, {3188, 3499}, {3188, 3500}, {3188, 3502}, {3188, 3503},
			{3189, 3479}, {3189, 3480}, {3189, 3481}, {3189, 3486}, {3189, 3493}, {3189, 3502},
			{3189, 3503}, {3191, 3509}, {3191, 3510}, {3192, 3504}, {3192, 3505}, {3192, 3509},
			{3192, 3510}, {3193, 3504}, {3193, 3505}, {3195, 3503}, {3197, 3507},
	};

	/**
	 * Hand-collected tiles that sit just outside a diagonal wall and poke one corner into the GE, leaving a
	 * triangular gap the axis-aligned fill can't cover. Each row is {@code {x, y, corner}} where corner is
	 * the tile corner that points INTO the GE: {@link #GAP_NE}/{@link #GAP_SE}/{@link #GAP_SW}/{@link
	 * #GAP_NW}. The overlay fills only that corner's triangle, so it reaches the wall and never bleeds out.
	 */
	private static final int GAP_NE = 0;
	private static final int GAP_SE = 1;
	private static final int GAP_SW = 2;
	private static final int GAP_NW = 3;
	private static final int[][] GAP_TILES = {
			// diagonal running NW from the south-west corner (poke corner: NE)
			{3143, 3468, GAP_NE}, {3142, 3469, GAP_NE}, {3141, 3470, GAP_NE}, {3140, 3471, GAP_NE},
			{3139, 3472, GAP_NE},
			// (poke corner: SE)
			{3139, 3482, GAP_SE}, {3140, 3483, GAP_SE}, {3141, 3484, GAP_SE},
			// (poke corner: NE) - third tile read 3139,4394 in-game, taken as 3139,3494 (typo)
			{3141, 3492, GAP_NE}, {3140, 3493, GAP_NE}, {3139, 3494, GAP_NE},
			// (poke corner: SE)
			{3139, 3513, GAP_SE}, {3140, 3514, GAP_SE}, {3141, 3515, GAP_SE}, {3142, 3516, GAP_SE},
			// (poke corner: SW)
			{3158, 3516, GAP_SW}, {3159, 3515, GAP_SW}, {3160, 3514, GAP_SW},
			// (poke corner: SE)
			{3168, 3514, GAP_SE}, {3169, 3515, GAP_SE}, {3170, 3516, GAP_SE},
			// long north-east diagonal (poke corner: SW)
			{3189, 3516, GAP_SW}, {3190, 3515, GAP_SW}, {3191, 3514, GAP_SW}, {3192, 3513, GAP_SW},
			{3193, 3512, GAP_SW}, {3194, 3511, GAP_SW}, {3195, 3510, GAP_SW}, {3196, 3509, GAP_SW},
			{3197, 3508, GAP_SW},
			// (poke corner: NW)
			{3197, 3505, GAP_NW}, {3196, 3504, GAP_NW},
			// (poke corner: NW)
			{3195, 3502, GAP_NW}, {3194, 3501, GAP_NW}, {3193, 3500, GAP_NW}, {3192, 3499, GAP_NW},
			{3191, 3498, GAP_NW}, {3190, 3497, GAP_NW},
			// (poke corner: NW)
			{3189, 3478, GAP_NW}, {3188, 3477, GAP_NW}, {3187, 3476, GAP_NW},
	};

	/** The four unreachable center tiles: no claim can ever border them, so under the claim-triggered mode
	 *  they are filled only once every tile in {@link #CENTER_RING} around them is filled. */
	private static final int[][] CENTER_TILES = {
			{3164, 3490}, {3165, 3490}, {3164, 3489}, {3165, 3489},
	};
	/** The ring of pre-claim tiles around {@link #CENTER_TILES}; when all are filled, the center fills. */
	private static final int[][] CENTER_RING = {
			{3163, 3491}, {3164, 3491}, {3165, 3491}, {3166, 3491},
			{3166, 3490}, {3166, 3489}, {3166, 3488},
			{3165, 3488}, {3164, 3488}, {3163, 3488},
			{3163, 3489}, {3163, 3490},
	};

	/** Ground floor only. */
	private static final int PLANE = 0;

	/** Spacing (tiles) between points along the drawn boundary, so long runs degrade gracefully. */
	private static final int BOUNDARY_STEP = 3;

	private static final int TILE_COUNT;
	private static final int WALKABLE_COUNT;
	private static final Set<Integer> REGION_IDS;
	private static final WorldPoint[] BOUNDARY;
	private static final List<WorldPoint> FILLER_LIST;
	/** Each gap tile's poke-in triangle plus the two interior tiles whose claim should reveal it. */
	private static final List<GapFill> GAP_FILLS;
	private static final int MIN_X;
	private static final int MIN_Y;
	private static final int MAX_X;
	private static final int MAX_Y;

	static
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int[] v : VERTICES)
		{
			minX = Math.min(minX, v[0]);
			minY = Math.min(minY, v[1]);
			maxX = Math.max(maxX, v[0]);
			maxY = Math.max(maxY, v[1]);
		}
		MIN_X = minX;
		MIN_Y = minY;
		MAX_X = maxX;
		MAX_Y = maxY;

		// Denominator for GE %: count every tile the area actually contains.
		int count = 0;
		for (int x = minX; x <= maxX; x++)
		{
			for (int y = minY; y <= maxY; y++)
			{
				if (inPolygon(x, y))
				{
					count++;
				}
			}
		}
		TILE_COUNT = count;

		// The unwalkable filler tiles, as an ordered list for rendering. Walkable = interior minus
		// filler; this is the denominator for a clan's GE share, so 100% means "all the ground you can
		// actually claim", not "every tile inside the wall".
		List<WorldPoint> fillerList = new ArrayList<>(FILLER_TILES.length);
		for (int[] t : FILLER_TILES)
		{
			fillerList.add(new WorldPoint(t[0], t[1], PLANE));
		}
		FILLER_LIST = fillerList;
		WALKABLE_COUNT = count - fillerList.size();

		// Precompute each gap tile's poke-in triangle: the poke corner (inside the wall) plus its two
		// edge-neighbour corners, which sit on the tile-corner boundary line. Those two are then pushed half
		// a tile OUTWARD (poke -> hypotenuse direction, i.e. the wall's outward normal for these 45-degree
		// walls) so the fill reaches the drawn wall line instead of stopping half a tile short of it.
		// Corners of tile (x, y): SW=(x,y), SE=(x+1,y), NW=(x,y+1), NE=(x+1,y+1).
		List<GapFill> gapFills = new ArrayList<>(GAP_TILES.length);
		for (int[] g : GAP_TILES)
		{
			int x = g[0], y = g[1];
			int[] poke;
			int[] h1;
			int[] h2;
			int[] na;
			int[] nb;
			switch (g[2])
			{
				case GAP_NE:
					poke = new int[]{x + 1, y + 1};
					h1 = new int[]{x + 1, y};
					h2 = new int[]{x, y + 1};
					na = new int[]{x + 1, y};
					nb = new int[]{x, y + 1};
					break;
				case GAP_SE:
					poke = new int[]{x + 1, y};
					h1 = new int[]{x, y};
					h2 = new int[]{x + 1, y + 1};
					na = new int[]{x + 1, y};
					nb = new int[]{x, y - 1};
					break;
				case GAP_SW:
					poke = new int[]{x, y};
					h1 = new int[]{x + 1, y};
					h2 = new int[]{x, y + 1};
					na = new int[]{x - 1, y};
					nb = new int[]{x, y - 1};
					break;
				default: // GAP_NW
					poke = new int[]{x, y + 1};
					h1 = new int[]{x, y};
					h2 = new int[]{x + 1, y + 1};
					na = new int[]{x - 1, y};
					nb = new int[]{x, y + 1};
					break;
			}
			double mx = (h1[0] + h2[0]) / 2.0;
			double my = (h1[1] + h2[1]) / 2.0;
			double dx = mx - poke[0];
			double dy = my - poke[1];
			double len = Math.hypot(dx, dy);
			double ux = len > 1e-9 ? dx / len : 0;
			double uy = len > 1e-9 ? dy / len : 0;
			// No outward push: extending the corners onto the raised ground at the wall base made the
			// overlay project them up the wall face. Keep them on the flat tile-corner line (push 0).
			double push = 0.0;
			double[][] tri = {
					{poke[0], poke[1]},
					{h1[0] + ux * push, h1[1] + uy * push},
					{h2[0] + ux * push, h2[1] + uy * push},
			};
			gapFills.add(new GapFill(new int[]{x, y}, tri, na, nb));
		}
		GAP_FILLS = gapFills;

		// Every map region the area's bounding box touches, so the store reads/writes all of
		// them (the fortress can straddle a region boundary). regionId = (x>>6)<<8 | (y>>6).
		Set<Integer> ids = new HashSet<>();
		for (int rx = minX >> 6; rx <= maxX >> 6; rx++)
		{
			for (int ry = minY >> 6; ry <= maxY >> 6; ry++)
			{
				ids.add((rx << 8) | ry);
			}
		}
		REGION_IDS = ids;

		// Densify the outline: a point every few tiles along each edge, not just at the corners.
		// A long straight run (the southern wall) would otherwise be a single segment that
		// vanishes entirely whenever either endpoint isn't projectable; broken into short
		// segments it only ever loses the handful of tiles that are actually off-screen.
		List<WorldPoint> pts = new ArrayList<>();
		for (int i = 0; i < VERTICES.length; i++)
		{
			int[] a = VERTICES[i];
			int[] b = VERTICES[(i + 1) % VERTICES.length];
			int dx = b[0] - a[0];
			int dy = b[1] - a[1];
			int span = Math.max(Math.abs(dx), Math.abs(dy));
			int steps = Math.max(1, span / BOUNDARY_STEP);
			for (int s = 0; s < steps; s++)
			{
				double t = s / (double) steps;
				int x = (int) Math.round(a[0] + dx * t);
				int y = (int) Math.round(a[1] + dy * t);
				pts.add(new WorldPoint(x, y, PLANE));
			}
		}
		BOUNDARY = pts.toArray(new WorldPoint[0]);
	}

	private GrandExchangeArea()
	{
	}

	/** Boundary corners as world points, in perimeter order; the overlay draws the outline. */
	static WorldPoint[] boundary()
	{
		return BOUNDARY;
	}

	/** Center of the area's bounding box, on the ground floor - used to place the world-map label. */
	static WorldPoint center()
	{
		return new WorldPoint((MIN_X + MAX_X) / 2, (MIN_Y + MAX_Y) / 2, PLANE);
	}

	/**
	 * @return true if the tile is within {@code margin} tiles of the area's bounding box on the
	 *         ground floor. Used to decide when the client should be connected to the server.
	 */
	static boolean near(WorldPoint wp, int margin)
	{
		return wp != null && wp.getPlane() == PLANE
				&& wp.getX() >= MIN_X - margin && wp.getX() <= MAX_X + margin
				&& wp.getY() >= MIN_Y - margin && wp.getY() <= MAX_Y + margin;
	}

	/**
	 * @return Chebyshev (tile) distance from the point to the area's bounding box on the ground
	 *         floor: 0 if inside it, {@link Integer#MAX_VALUE} if on another plane or null.
	 */
	static int distanceTo(WorldPoint wp)
	{
		if (wp == null || wp.getPlane() != PLANE)
		{
			return Integer.MAX_VALUE;
		}
		int dx = Math.max(0, Math.max(MIN_X - wp.getX(), wp.getX() - MAX_X));
		int dy = Math.max(0, Math.max(MIN_Y - wp.getY(), wp.getY() - MAX_Y));
		return Math.max(dx, dy);
	}

	/**
	 * @return true if the tile is inside (or on) the boundary on the ground floor.
	 */
	static boolean contains(WorldPoint wp)
	{
		return wp != null && wp.getPlane() == PLANE && inPolygon(wp.getX(), wp.getY());
	}

	/**
	 * @return true if the tile at (x, y) is inside (or on) the boundary. Same test as
	 *         {@link #contains(WorldPoint)} by raw tile coords, for callers already on the ground floor.
	 */
	static boolean contains(int x, int y)
	{
		return inPolygon(x, y);
	}

	/** Walkable (claimable) tiles - interior minus the unwalkable filler - the denominator for GE share. */
	static int totalTiles()
	{
		return WALKABLE_COUNT;
	}

	/** The unwalkable filler tiles, for the overlay to color from their claimed neighbors. */
	static List<WorldPoint> filler()
	{
		return FILLER_LIST;
	}

	/**
	 * Poke-in gap fills for the hand-listed diagonal-wall tiles: a triangle plus the two interior tiles
	 * whose claim reveals it. The overlay draws each only when a bordering tile is claimed. Render-only.
	 */
	static List<GapFill> gapFills()
	{
		return GAP_FILLS;
	}

	/** The four unreachable center tiles, filled only once their whole ring is filled. Render-only. */
	static int[][] centerTiles()
	{
		return CENTER_TILES;
	}

	/** The ring of tiles around the center; when all are filled, the center fills. */
	static int[][] centerRing()
	{
		return CENTER_RING;
	}

	/** A gap triangle (three world corner points), its source tile (for fade tracking), and the two
	 *  interior tiles whose claim reveals it. */
	static final class GapFill
	{
		final int[] tile;
		final double[][] triangle;
		final int[] neighbourA;
		final int[] neighbourB;

		GapFill(int[] tile, double[][] triangle, int[] neighbourA, int[] neighbourB)
		{
			this.tile = tile;
			this.triangle = triangle;
			this.neighbourA = neighbourA;
			this.neighbourB = neighbourB;
		}
	}

	/** Map regions the area covers; the store reads claims from each. */
	static Set<Integer> regionIds()
	{
		return REGION_IDS;
	}

	/**
	 * Boundary-inclusive point-in-polygon for integer tile coordinates. A point exactly on
	 * an edge counts as inside (so wall-line tiles claim on every side); otherwise a standard
	 * ray cast decides. {@code VERTICES} are the tiles' south-west corners, which is what the
	 * dev-tools Location readout reports, so we test the raw tile (x, y).
	 */
	private static boolean inPolygon(int px, int py)
	{
		int n = VERTICES.length;
		boolean inside = false;
		for (int i = 0, j = n - 1; i < n; j = i++)
		{
			int xi = VERTICES[i][0], yi = VERTICES[i][1];
			int xj = VERTICES[j][0], yj = VERTICES[j][1];

			if (onSegment(px, py, xi, yi, xj, yj))
			{
				return true;
			}

			boolean crosses = (yi > py) != (yj > py)
					&& px < (double) (xj - xi) * (py - yi) / (yj - yi) + xi;
			if (crosses)
			{
				inside = !inside;
			}
		}
		return inside;
	}

	/** True if (px,py) lies on the segment (x1,y1)-(x2,y2), endpoints included. */
	private static boolean onSegment(int px, int py, int x1, int y1, int x2, int y2)
	{
		long cross = (long) (x2 - x1) * (py - y1) - (long) (y2 - y1) * (px - x1);
		if (cross != 0)
		{
			return false;
		}
		return px >= Math.min(x1, x2) && px <= Math.max(x1, x2)
				&& py >= Math.min(y1, y2) && py <= Math.max(y1, y2);
	}
}
