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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints claimed tiles as clan territory: each tile is filled in its clan's color, but the
 * outline is drawn only on edges that border empty ground or a rival. Shared edges between
 * same-clan tiles are skipped, so a solid block reads as one region with one border instead
 * of a thousand little squares.
 *
 * <p>Against open ground a clan's border runs along the tile edge (so whole perimeters join
 * cleanly); where two clans touch, each pulls its border a couple of pixels inside its own
 * tiles, so the shared edge shows two near-touching colored lines - a clear seam even
 * between similar colors. Cutting a path through a rival's block outlines the slice.
 */
class ClanTurfOverlay extends Overlay
{
	/** Don't paint tiles further than this many tiles from the player (perf + clutter). */
	private static final int MAX_DRAW_DISTANCE = 32;
	/** Within this distance tiles are full strength; from here out to MAX they fade to nothing. */
	private static final int FADE_FULL_DIST = 12;
	/** Show the boundary only within this many tiles of the GE (hidden beyond, like the tiles). */
	private static final int BOUNDARY_MARGIN = 30;
	/** How long a newly claimed (or taken-over) tile takes to fade in, in milliseconds. */
	private static final long FADE_IN_MS = 500;
	/** How long the single-tile "taken from a rival" wall pop lasts, in milliseconds. */
	private static final long STEAL_WALL_MS = 600;
	/** Peak height of the steal-pop wall (independent of the boundary tile-wall settings). */
	private static final int STEAL_WALL_HEIGHT = 60;
	/** How long each tile's fade-out lasts on a reset/clear dissolve, in milliseconds. Kept short so
	 * the random start delay below dominates and the tiles clearly pop out at different times. */
	private static final long DISSOLVE_MS = 300;
	/** Max random per-tile start delay for the dissolve, so tiles fade out in a staggered ripple. */
	private static final long DISSOLVE_STAGGER_MS = 900;

	/**
	 * Pixels a shared (clan-vs-clan) border is pulled toward its own tile, so two touching clans
	 * render two near-touching lines. Larger = further apart; ~1 makes them sit edge-to-edge.
	 */
	private static final double RIVAL_INSET_PX = 1.5;

	// Butt caps + miter joins: no cap overhang, so collinear tile edges meet exactly instead of
	// stacking a bright dot at every shared corner. Each tile's edges are chained into one path.
	private static final Stroke EDGE_STROKE =
			new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);

	// Edge classification from edgeType().
	private static final int EDGE_SKIP = 0; // neighbour is same clan -> internal, don't draw
	private static final int EDGE_FULL = 1; // neighbour is empty -> border on the true tile edge
	private static final int EDGE_RIVAL = 2; // neighbour is a rival -> border inset a few pixels

	/**
	 * The boundary rests as a flat ground line and only rises into a wall briefly when the lead
	 * changes hands, then settles back down - so the tall-wall overdraw of foreground
	 * objects lasts ~1s per takeover instead of all the time.
	 */
	// Takeover animation timing is live-tunable from config (Boundary animation section).
	private static final Stroke BARRIER_TOP_STROKE =
			new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	/** Barrier tint before any clan owns tiles. */
	private static final Color BOUNDARY_DEFAULT = Color.WHITE;
	private static final int SPARKLE_BRIGHT = 150;   // max brightness added at a tile's peak

	private final Client client;
	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;

	/** Per-tile fade-in: when each tile was first seen (or last changed clan). */
	private final Map<Long, Appear> appearing = new HashMap<>();
	/** Last non-empty tile-&gt;clan snapshot, so a reset/clear can dissolve what was on screen. */
	private final Map<Long, String> lastOwners = new HashMap<>();
	/** Tiles currently fading out (reset/clear dissolve): tile key -&gt; {clan, start time}. */
	private final Map<Long, Dissolve> dissolving = new HashMap<>();
	private long seenDissolveFlash;
	private int lastWorld = -1; // wipe per-tile memory on a world hop so nothing bleeds across worlds

	private static final class Appear
	{
		final String clan;
		final long since;
		final boolean steal; // taken from a different clan (drives the single-tile wall-on-steal pop)

		Appear(String clan, long since, boolean steal)
		{
			this.clan = clan;
			this.since = since;
			this.steal = steal;
		}
	}

	/** One fading-out tile during a reset/clear dissolve: its clan color and when its fade starts. */
	private static final class Dissolve
	{
		final String clan;
		final long start;

		Dissolve(String clan, long start)
		{
			this.clan = clan;
			this.start = start;
		}
	}

	@Inject
	ClanTurfOverlay(Client client, ClanTurfPlugin plugin, ClanTurfConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		WorldView wv = local.getWorldView();
		if (wv == null)
		{
			return null;
		}

		int world = client.getWorld();
		if (world != lastWorld)
		{
			// New world: the old world's tiles and animations don't belong here. Forget them so the
			// new world's claims fade in fresh, instead of every changed tile popping a wall-on-steal.
			lastWorld = world;
			appearing.clear();
			dissolving.clear();
			lastOwners.clear();
		}

		Collection<ClanTurfPoint> claims = plugin.getVisibleClaims();
		final WorldPoint playerLocation = local.getWorldLocation();

		// Boundary shows only near the GE (matches the tiles, and avoids the broken fragments
		// you get trying to draw it from far away).
		if (config.showBoundary() && GrandExchangeArea.near(playerLocation, BOUNDARY_MARGIN))
		{
			drawBoundary(graphics, wv);
		}

		// Reset/clear dissolve: fade the last on-screen tiles out (staggered) instead of a hard cut.
		// Runs even when claims are already empty (post-wipe), so it sits before the early return.
		long dnow = System.currentTimeMillis();
		long flash = plugin.getDissolveFlashMs();
		if (flash != seenDissolveFlash)
		{
			seenDissolveFlash = flash;
			startDissolve(dnow);
		}
		if (!dissolving.isEmpty())
		{
			drawDissolving(graphics, wv, playerLocation, dnow);
		}

		if (claims.isEmpty())
		{
			return null;
		}

		final int plane = wv.getPlane();
		final int alpha = config.fillOpacity();
		final boolean outline = config.drawOutline();

		final long now = System.currentTimeMillis();

		// Takeover window, shared by the sparkle and the near-player tile walls.
		long animStart = plugin.getAnimStartMs();
		long animTotal = animTotalMs();
		long animEl = now - animStart;
		boolean inAnim = animStart > 0 && animEl >= 0 && animEl < animTotal;
		String animClan = inAnim ? plugin.getBoundaryLeader() : null;

		// Overall window envelope: fades the whole effect out over the last 300ms. Each tile's own
		// pulse (computed per tile below) is multiplied by this, so the shimmer brightness and the
		// tile-wall height both breathe in and out on that same per-tile pulse.
		double winEnv = 0.0;
		if (inAnim)
		{
			long fadeOut = 300;
			winEnv = animEl > animTotal - fadeOut
					? Math.max(0.0, (animTotal - animEl) / (double) fadeOut) : 1.0;
		}
		boolean doSparkle = inAnim && config.tileEffects() && config.sparkleIntensity() > 0;
		// Near-player tile walls: a small banded wall on the conquering clan's tiles within a radius
		// of the player, rising/falling with that tile's own shimmer pulse. Radius-bounded so it
		// stays a local flourish instead of hundreds of walls across the whole GE.
		boolean doTileWalls = inAnim && config.tileEffects() && config.tileWallHeight() > 0;

		// Who owns each tile, in canonical world coords, so we can test neighbours cheaply. Also
		// stamp when each tile first appeared (or changed clan) so it can fade in.
		Map<Long, String> owner = new HashMap<>(claims.size() * 2);
		for (ClanTurfPoint p : claims)
		{
			if (p.getZ() != plane)
			{
				continue;
			}
			WorldPoint w = WorldPoint.fromRegion(p.getRegionId(), p.getRegionX(), p.getRegionY(), p.getZ());
			owner.put(key(w.getX(), w.getY()), p.getClanName());
		}
		if (!owner.isEmpty())
		{
			lastOwners.clear();
			lastOwners.putAll(owner); // remember the latest tiles so a wipe can dissolve them
		}
		appearing.keySet().retainAll(owner.keySet()); // forget tiles that are gone

		for (ClanTurfPoint point : claims)
		{
			if (point.getZ() != plane)
			{
				continue;
			}

			WorldPoint stored = WorldPoint.fromRegion(
					point.getRegionId(), point.getRegionX(), point.getRegionY(), point.getZ());
			String clan = point.getClanName();
			Color base = ClanTurfColors.forClan(clan);
			int sx = stored.getX();
			int sy = stored.getY();

			for (WorldPoint wp : WorldPoint.toLocalInstance(wv, stored))
			{
				if (wp.getPlane() != plane)
				{
					continue;
				}
				double dist = playerLocation == null ? 0 : wp.distanceTo(playerLocation);
				if (dist >= MAX_DRAW_DISTANCE)
				{
					continue;
				}
				// Start the fade the first time the tile is actually on screen (not when it entered the
				// claim set, which for a rival's tile could be far off-screen -> it would 'pop' in).
				Appear a = appearing.get(key(sx, sy));
				if (a == null || !a.clan.equals(clan))
				{
					// a != null here means the clan changed - the tile was taken from a rival.
					a = new Appear(clan, now, a != null);
					appearing.put(key(sx, sy), a);
				}
				double fade = fadeFactor(dist) * appearFactor(a, now); // distance ghosting + fade-in

				LocalPoint lp = LocalPoint.fromWorld(wv, wp);
				if (lp == null)
				{
					continue;
				}

				Polygon poly = Perspective.getCanvasTilePoly(client, lp);
				if (poly == null || poly.npoints < 4)
				{
					continue;
				}

				// One per-tile pulse (0..1) drives BOTH the shimmer brightness and the tile-wall
				// height, so a tile's wall rises and falls in lockstep with its own shimmer fade.
				double tw = 0.0;
				if ((doSparkle || doTileWalls) && clan.equals(animClan))
				{
					// 2D hash -> decorrelated phase + a per-tile speed, so neighbours twinkle
					// independently instead of marching in waves.
					int hsh = (sx * 73856093) ^ (sy * 19349663);
					hsh ^= (hsh >>> 13);
					hsh *= 0x85ebca6b;
					hsh ^= (hsh >>> 16);
					double phase = ((hsh >>> 8) & 0xFFFF) / 65535.0;
					double freqVar = 0.6 + ((hsh & 0xFF) / 255.0) * 0.9;
					double speed = config.sparkleSpeed() / 10.0;
					double s = Math.sin(2 * Math.PI * (now / 1000.0 * speed * freqVar + phase));
					tw = Math.pow(Math.max(0.0, s), 3) * winEnv;
				}

				if (alpha > 0)
				{
					int r = base.getRed();
					int g = base.getGreen();
					int b = base.getBlue();
					if (doSparkle && tw > 0)
					{
						int add = (int) Math.round(tw * (config.sparkleIntensity() / 100.0) * SPARKLE_BRIGHT);
						r = Math.min(255, r + add);
						g = Math.min(255, g + add);
						b = Math.min(255, b + add);
					}
					graphics.setColor(new Color(r, g, b, (int) Math.round(alpha * fade)));
					graphics.fill(poly);
				}

				// Tile wall on the same shimmering tiles within the radius: its height tracks the
				// same per-tile pulse, so it rises and falls exactly as that square fades in/out.
				if (doTileWalls && tw > 0 && dist <= config.tileWallRadius())
				{
					int wallH = (int) Math.round(tw * config.tileWallHeight());
					if (wallH > 0)
					{
						drawTileWall(graphics, wv, lp, plane, wallH, base, fade);
					}
				}

				// Wall-on-steal: a single short wall pops on a tile taken from a rival, rising then
				// falling over STEAL_WALL_MS, synced to the fade - no shimmer, no radius gate.
				if (config.wallOnSteal() && a.steal)
				{
					long stealEl = now - a.since;
					if (stealEl >= 0 && stealEl < STEAL_WALL_MS)
					{
						double st = stealEl / (double) STEAL_WALL_MS;
						int wallH = (int) Math.round(Math.sin(Math.PI * st) * STEAL_WALL_HEIGHT);
						if (wallH > 0)
						{
							drawTileWall(graphics, wv, lp, plane, wallH, base, fade);
						}
					}
				}

				if (outline)
				{
					// Reuse the fill's own corner points so the outline stays locked to the ground
					// (corner order SW, SE, NE, NW). Empty-facing edges draw on the true tile edge
					// so perimeters join; only clan-vs-clan edges inset. Edge alpha fades with
					// distance too, so the whole tile ghosts in as you approach.
					// Brightened a touch; opacity is live-tunable and fades with distance.
					Color edge = brighten(base, 60, (int) Math.round(config.outlineOpacity() * fade));
					int[] xp = poly.xpoints;
					int[] yp = poly.ypoints;
					double ccx = (xp[0] + xp[1] + xp[2] + xp[3]) / 4.0;
					double ccy = (yp[0] + yp[1] + yp[2] + yp[3]) / 4.0;

					drawTileOutline(graphics, edge,
							edgeType(owner.get(key(sx, sy - 1)), clan),   // south
							edgeType(owner.get(key(sx + 1, sy)), clan),   // east
							edgeType(owner.get(key(sx, sy + 1)), clan),   // north
							edgeType(owner.get(key(sx - 1, sy)), clan),   // west
							xp, yp, ccx, ccy);
				}
			}
		}

		return null;
	}

	/** Empty neighbour -> border on the tile edge; same clan -> skip; rival -> inset border. */
	private static int edgeType(String neighbour, String clan)
	{
		if (neighbour == null)
		{
			return EDGE_FULL;
		}
		return neighbour.equals(clan) ? EDGE_SKIP : EDGE_RIVAL;
	}

	/**
	 * Draws one border edge in the clan color. A full edge sits on the true tile corners so
	 * perimeters join; a rival edge is nudged a couple of pixels toward the tile centre so two
	 * touching clans render two near-touching lines. Both endpoints shift equally, so straight
	 * multi-tile borders stay continuous.
	 */
	/**
	 * Draws a tile's visible border edges as ONE stroked path. Chaining the edges (rather than
	 * four separate {@code drawLine} calls with square caps) means shared corners join cleanly
	 * instead of stacking a brighter dot where translucent caps overlapped. Full edges sit on the
	 * true corners so neighbouring tiles' borders meet; rival edges are nudged inward so two
	 * touching clans render two near-touching lines.
	 */
	private void drawTileOutline(Graphics2D graphics, Color color,
			int tSouth, int tEast, int tNorth, int tWest,
			int[] xp, int[] yp, double cx, double cy)
	{
		List<int[]> segs = new ArrayList<>(4);
		addSeg(segs, tSouth, xp[0], yp[0], xp[1], yp[1], cx, cy);
		addSeg(segs, tEast, xp[1], yp[1], xp[2], yp[2], cx, cy);
		addSeg(segs, tNorth, xp[2], yp[2], xp[3], yp[3], cx, cy);
		addSeg(segs, tWest, xp[3], yp[3], xp[0], yp[0], cx, cy);
		if (segs.isEmpty())
		{
			return;
		}

		// Greedily chain segments that share an endpoint into continuous polylines.
		Path2D.Double path = new Path2D.Double();
		boolean[] used = new boolean[segs.size()];
		for (int i = 0; i < segs.size(); i++)
		{
			if (used[i])
			{
				continue;
			}
			int[] s = segs.get(i);
			used[i] = true;
			path.moveTo(s[0], s[1]);
			path.lineTo(s[2], s[3]);
			int ex = s[2];
			int ey = s[3];
			boolean extended = true;
			while (extended)
			{
				extended = false;
				for (int j = 0; j < segs.size(); j++)
				{
					if (used[j])
					{
						continue;
					}
					int[] t = segs.get(j);
					if (t[0] == ex && t[1] == ey)
					{
						path.lineTo(t[2], t[3]);
						ex = t[2];
						ey = t[3];
						used[j] = true;
						extended = true;
						break;
					}
					if (t[2] == ex && t[3] == ey)
					{
						path.lineTo(t[0], t[1]);
						ex = t[0];
						ey = t[1];
						used[j] = true;
						extended = true;
						break;
					}
				}
			}
		}

		graphics.setColor(color);
		graphics.setStroke(EDGE_STROKE);
		graphics.draw(path);
	}

	/** Adds one border edge to the list, applying the rival inset; skips internal edges. */
	private static void addSeg(List<int[]> segs, int type,
			int x1, int y1, int x2, int y2, double cx, double cy)
	{
		if (type == EDGE_SKIP)
		{
			return;
		}
		if (type == EDGE_RIVAL)
		{
			double mx = (x1 + x2) / 2.0;
			double my = (y1 + y2) / 2.0;
			double len = Math.hypot(cx - mx, cy - my);
			if (len > 1e-3)
			{
				int ox = (int) Math.round((cx - mx) / len * RIVAL_INSET_PX);
				int oy = (int) Math.round((cy - my) / len * RIVAL_INSET_PX);
				x1 += ox;
				y1 += oy;
				x2 += ox;
				y2 += oy;
			}
		}
		segs.add(new int[]{x1, y1, x2, y2});
	}

	private static long key(int x, int y)
	{
		return ((long) x << 20) | (y & 0xFFFFF);
	}

	/** 0 -> 1 over FADE_IN_MS from when the tile first appeared, so new claims ghost in. */
	private static double appearFactor(Appear a, long now)
	{
		if (a == null)
		{
			return 1.0;
		}
		long elapsed = now - a.since;
		if (elapsed >= FADE_IN_MS)
		{
			return 1.0;
		}
		return Math.max(0.0, elapsed / (double) FADE_IN_MS);
	}

	/** 1.0 within FADE_FULL_DIST, ramping down to 0 at MAX_DRAW_DISTANCE. */
	private static double fadeFactor(double dist)
	{
		if (dist <= FADE_FULL_DIST)
		{
			return 1.0;
		}
		if (dist >= MAX_DRAW_DISTANCE)
		{
			return 0.0;
		}
		return (MAX_DRAW_DISTANCE - dist) / (double) (MAX_DRAW_DISTANCE - FADE_FULL_DIST);
	}

	/**
	 * Seeds the dissolve from the last on-screen tiles, each with a random start delay so they fade
	 * out in a staggered ripple rather than all at once.
	 */
	private void startDissolve(long now)
	{
		dissolving.clear();
		for (Map.Entry<Long, String> e : lastOwners.entrySet())
		{
			long stagger = (long) (Math.random() * DISSOLVE_STAGGER_MS);
			dissolving.put(e.getKey(), new Dissolve(e.getValue(), now + stagger));
		}
	}

	/** Paints the fading-out tiles of a reset/clear dissolve, dropping each when its fade finishes. */
	private void drawDissolving(Graphics2D graphics, WorldView wv, WorldPoint playerLocation, long now)
	{
		int baseAlpha = config.fillOpacity();
		int plane = wv.getPlane();
		Iterator<Map.Entry<Long, Dissolve>> it = dissolving.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Long, Dissolve> e = it.next();
			Dissolve d = e.getValue();
			long el = now - d.start;
			if (el >= DISSOLVE_MS)
			{
				it.remove();
				continue;
			}
			double f = el <= 0 ? 1.0 : 1.0 - (el / (double) DISSOLVE_MS); // 1 -> 0 over DISSOLVE_MS
			int sx = (int) (e.getKey() >> 20);
			int sy = (int) (e.getKey() & 0xFFFFF);
			Color base = ClanTurfColors.forClan(d.clan);
			WorldPoint stored = new WorldPoint(sx, sy, plane);
			for (WorldPoint wp : WorldPoint.toLocalInstance(wv, stored))
			{
				if (wp.getPlane() != plane)
				{
					continue;
				}
				double dist = playerLocation == null ? 0 : wp.distanceTo(playerLocation);
				if (dist >= MAX_DRAW_DISTANCE)
				{
					continue;
				}
				LocalPoint lp = LocalPoint.fromWorld(wv, wp);
				if (lp == null)
				{
					continue;
				}
				Polygon poly = Perspective.getCanvasTilePoly(client, lp);
				if (poly == null || poly.npoints < 4)
				{
					continue;
				}
				if (baseAlpha > 0)
				{
					int a = (int) Math.round(baseAlpha * fadeFactor(dist) * f);
					graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), a));
					graphics.fill(poly);
				}
			}
		}
	}

	/**
	 * Draws the boundary. It's a flat leader-colored line at rest; when the lead changes hands
	 * it rises into a wall in the old leader's color, swaps to the new color at the peak, and
	 * drops back to flat - a brief takeover flourish so the tall-wall overdraw isn't
	 * permanent.
	 */
	private void drawBoundary(Graphics2D graphics, WorldView wv)
	{
		// The committed leader and the takeover animation are driven by the plugin (debounced on
		// the game thread); the overlay just renders whatever state it exposes.
		String leader = plugin.getBoundaryLeader();
		long animStartMs = plugin.getAnimStartMs();
		Color animFrom = plugin.getAnimFrom();
		Color animTo = plugin.getAnimTo();

		Color rest = leader == null ? BOUNDARY_DEFAULT : ClanTurfColors.forClan(leader);

		final int barrierAlpha = config.barrierOpacity();
		final int maxHeight = config.barrierHeight();

		// Choreography: small rise -> fall -> full rise -> hold -> final fall. Color fades
		// old->new on the small rise, new->old on the fall, old->new again on the full rise,
		// then holds new. The rise-to-top (d1+d2+d3) is meant to match the sound length.
		final long d1 = Math.max(1, config.smallRiseMs());   // small rise up
		final long d2 = Math.max(1, config.smallFallMs());   // small rise back down
		final long d3 = Math.max(1, config.fullRiseMs());    // full rise to the top
		final long hold = config.holdMs();                   // pause at the top
		final long d5 = Math.max(1, config.fallMs());        // final descent
		final double h1 = clamp01(config.smallRiseHeightPct() / 100.0);

		final long tFallStart = d1;
		final long tFullStart = d1 + d2;
		final long tTop = d1 + d2 + d3;
		final long tHoldEnd = tTop + hold;
		final long totalMs = tHoldEnd + d5;

		long elapsed = System.currentTimeMillis() - animStartMs;

		if (animStartMs <= 0 || elapsed >= totalMs || animFrom == null || animTo == null)
		{
			// Resting: a flat leader-colored line on the ground.
			drawBarrier(graphics, wv, brighten(rest, 60, config.outlineOpacity()),
					new Color(rest.getRed(), rest.getGreen(), rest.getBlue(), barrierAlpha), 0);
			return;
		}

		double h;
		if (elapsed < tFallStart)
		{
			double t = elapsed / (double) d1;                // small rise
			h = easeInOut(t) * h1;
		}
		else if (elapsed < tFullStart)
		{
			double t = (elapsed - tFallStart) / (double) d2; // fall back down
			h = h1 * (1.0 - easeInOut(t));
		}
		else if (elapsed < tTop)
		{
			double t = (elapsed - tFullStart) / (double) d3; // full rise
			h = easeInOut(t);
		}
		else if (elapsed < tHoldEnd)
		{
			h = 1.0;                                         // hold at the top
		}
		else
		{
			double t = (elapsed - tHoldEnd) / (double) d5;   // final descent
			h = 1.0 - easeInOut(t);
		}

		// Color blend is shared with the minimap tint so the two never drift apart.
		double mix = ClanTurfAnim.colorMix(config, elapsed);

		int height = (int) Math.round(maxHeight * clamp01(h));

		// Vibrato: ramps up over the final rise (the "lead"), peaks as the takeover lands at the
		// top, then decays to still during the hold. A tremor on the wall height, not position.
		int amp = config.vibratoAmplitude();
		if (amp > 0)
		{
			long leadMs = config.vibratoStartMs();
			long decayMs = config.vibratoDecayMs();
			long vibStart = tTop - leadMs;
			if (elapsed >= vibStart && elapsed < tHoldEnd)
			{
				double env;
				if (elapsed < tTop)
				{
					env = leadMs <= 0 ? 1.0 : (elapsed - vibStart) / (double) leadMs; // ramp up into the top
				}
				else
				{
					env = decayMs <= 0 ? 0.0 : Math.max(0.0, 1.0 - (elapsed - tTop) / (double) decayMs); // decay
				}
				double phase = elapsed - vibStart; // continuous oscillation across the whole window
				double vib = amp * env * Math.sin(2 * Math.PI * config.vibratoFrequencyHz() * phase / 1000.0);
				height += (int) Math.round(vib);
			}
		}

		Color base = lerp(animFrom, animTo, clamp01(mix));

		// Border animation off: keep the color fade (old -> new) but skip the wall, so the takeover
		// just recolors the flat ground boundary line.
		if (!config.boundaryAnimation())
		{
			height = 0;
		}

		Color lineCol = brighten(base, 60, config.outlineOpacity());
		Color fillCol = new Color(base.getRed(), base.getGreen(), base.getBlue(), barrierAlpha);
		drawBarrier(graphics, wv, lineCol, fillCol, height);
	}

	/**
	 * Draws the boundary: always a line along the ground; when {@code height > 0} also a
	 * translucent wall rising to that height, with a bright crest line along its top.
	 */
	private void drawBarrier(Graphics2D graphics, WorldView wv, Color lineColor, Color fillColor, int height)
	{
		WorldPoint[] corners = GrandExchangeArea.boundary();
		int n = corners.length;
		if (n < 2)
		{
			return;
		}

		Point[] ground = new Point[n];
		Point[] top = new Point[n];
		for (int i = 0; i < n; i++)
		{
			LocalPoint lp = LocalPoint.fromWorld(wv, corners[i]);
			if (lp == null)
			{
				continue;
			}
			int plane = corners[i].getPlane();
			ground[i] = Perspective.localToCanvas(client, lp, plane);
			top[i] = height <= 0 ? ground[i] : Perspective.localToCanvas(client, lp, plane, height);
		}

		// Wall fill: each segment split into horizontal bands, tiling edge-to-edge (no overlap, so
		// no alpha stacking). Bands share vertices at each fraction, so there are no seams; the
		// per-band alpha fades toward the top for a dissolving-barrier look.
		if (height > 0)
		{
			int strips = Math.max(1, config.bandStrips());
			for (int i = 0; i < n; i++)
			{
				int j = (i + 1) % n;
				if (ground[i] == null || ground[j] == null || top[i] == null || top[j] == null)
				{
					continue;
				}
				for (int s = 0; s < strips; s++)
				{
					double f0 = s / (double) strips;
					double f1 = (s + 1) / (double) strips;
					Polygon quad = new Polygon();
					quad.addPoint(bandX(ground[i], top[i], f0), bandY(ground[i], top[i], f0));
					quad.addPoint(bandX(ground[j], top[j], f0), bandY(ground[j], top[j], f0));
					quad.addPoint(bandX(ground[j], top[j], f1), bandY(ground[j], top[j], f1));
					quad.addPoint(bandX(ground[i], top[i], f1), bandY(ground[i], top[i], f1));
					int a = (int) Math.round(fillColor.getAlpha() * bandFactor(s, strips));
					graphics.setColor(new Color(fillColor.getRed(), fillColor.getGreen(),
							fillColor.getBlue(), Math.max(0, Math.min(255, a))));
					graphics.fill(quad);
				}
			}
		}

		// Ground line only: a single continuous path (round joins, no stacked caps). The wall's
		// top is left open during the animation - no crest line - so there's just the one border.
		graphics.setColor(lineColor);
		graphics.setStroke(BARRIER_TOP_STROKE);
		strokeLoop(graphics, ground);
	}

	/**
	 * Draws a small banded wall around one tile: the four edges rise to {@code height}, filled in
	 * the clan color with the same bottom-opaque, top-transparent banding as the GE boundary wall.
	 * A tile-sized echo of the boundary, used as a near-player takeover flourish.
	 */
	private void drawTileWall(Graphics2D graphics, WorldView wv, LocalPoint lp, int plane,
			int height, Color color, double fade)
	{
		int half = Perspective.LOCAL_TILE_SIZE / 2;
		int cx = lp.getX();
		int cy = lp.getY();
		int[][] offs = {{-half, -half}, {half, -half}, {half, half}, {-half, half}}; // SW,SE,NE,NW

		Point[] g = new Point[4];
		Point[] t = new Point[4];
		for (int k = 0; k < 4; k++)
		{
			LocalPoint c = new LocalPoint(cx + offs[k][0], cy + offs[k][1], wv);
			g[k] = Perspective.localToCanvas(client, c, plane);
			t[k] = Perspective.localToCanvas(client, c, plane, height);
		}

		int strips = Math.max(1, config.bandStrips());
		int refAlpha = config.barrierOpacity();
		for (int k = 0; k < 4; k++)
		{
			int m = (k + 1) % 4;
			if (g[k] == null || g[m] == null || t[k] == null || t[m] == null)
			{
				continue;
			}
			for (int s = 0; s < strips; s++)
			{
				double f0 = s / (double) strips;
				double f1 = (s + 1) / (double) strips;
				Polygon quad = new Polygon();
				quad.addPoint(bandX(g[k], t[k], f0), bandY(g[k], t[k], f0));
				quad.addPoint(bandX(g[m], t[m], f0), bandY(g[m], t[m], f0));
				quad.addPoint(bandX(g[m], t[m], f1), bandY(g[m], t[m], f1));
				quad.addPoint(bandX(g[k], t[k], f1), bandY(g[k], t[k], f1));
				int a = (int) Math.round(refAlpha * bandFactor(s, strips) * fade);
				graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
						Math.max(0, Math.min(255, a))));
				graphics.fill(quad);
			}
		}
	}

	/**
	 * Fill-alpha multiplier for band {@code s} of {@code strips} (0 = bottom). Interpolates from
	 * the bottom-band percent to the top-band percent, shaped by the fade curve: exponent 1 is a
	 * straight line, &gt;1 holds opaque near the ground then drops, &lt;1 drops off quickly.
	 */
	private double bandFactor(int s, int strips)
	{
		double bottom = clamp01(config.bandBottomPct() / 100.0);
		double top = clamp01(config.bandTopPct() / 100.0);
		double frac = strips <= 1 ? 0.0 : s / (double) (strips - 1);
		double curve = Math.max(0.05, config.bandCurve() / 100.0);
		double shaped = Math.pow(frac, curve);
		return bottom + (top - bottom) * shaped;
	}

	/** Canvas X interpolated up the wall face from ground (f=0) to top (f=1). */
	private static int bandX(Point ground, Point top, double f)
	{
		return (int) Math.round(ground.getX() + (top.getX() - ground.getX()) * f);
	}

	/** Canvas Y interpolated up the wall face from ground (f=0) to top (f=1). */
	private static int bandY(Point ground, Point top, double f)
	{
		return (int) Math.round(ground.getY() + (top.getY() - ground.getY()) * f);
	}

	/** Strokes a closed loop through the canvas points as one path, breaking at null (offscreen) gaps. */
	private static void strokeLoop(Graphics2D graphics, Point[] pts)
	{
		Path2D path = new Path2D.Double();
		boolean started = false;
		int n = pts.length;
		for (int i = 0; i <= n; i++)
		{
			Point p = pts[i % n];
			if (p == null)
			{
				started = false;
				continue;
			}
			if (!started)
			{
				path.moveTo(p.getX(), p.getY());
				started = true;
			}
			else
			{
				path.lineTo(p.getX(), p.getY());
			}
		}
		graphics.draw(path);
	}

	/** Total takeover animation length (all phases), used to bound the sparkle window. */
	private long animTotalMs()
	{
		return config.smallRiseMs() + config.smallFallMs() + config.fullRiseMs()
				+ config.holdMs() + config.fallMs();
	}

	private static double clamp01(double v)
	{
		return Math.max(0.0, Math.min(1.0, v));
	}

	/** Ease-out with a slight overshoot, so the wall pops up past its height and settles back. */
	private static double easeOutBack(double t)
	{
		double c1 = 1.70158;
		double c3 = c1 + 1;
		double x = clamp01(t) - 1;
		return 1 + c3 * x * x * x + c1 * x * x;
	}

	private static double easeInOut(double t)
	{
		t = clamp01(t);
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	/** Linear blend between two colors (t 0 = a, 1 = b). */
	private static Color lerp(Color a, Color b, double t)
	{
		t = clamp01(t);
		return new Color(
				(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	private static Color brighten(Color c, int add, int alpha)
	{
		return new Color(
				Math.min(255, c.getRed() + add),
				Math.min(255, c.getGreen() + add),
				Math.min(255, c.getBlue() + add),
				alpha);
	}

}
