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

	/** Ground floor only. */
	private static final int PLANE = 0;

	/** Spacing (tiles) between points along the drawn boundary, so long runs degrade gracefully. */
	private static final int BOUNDARY_STEP = 3;

	private static final int TILE_COUNT;
	private static final Set<Integer> REGION_IDS;
	private static final WorldPoint[] BOUNDARY;
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

	/** Tiles inside the area, used as the denominator for a clan's percentage. */
	static int totalTiles()
	{
		return TILE_COUNT;
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
