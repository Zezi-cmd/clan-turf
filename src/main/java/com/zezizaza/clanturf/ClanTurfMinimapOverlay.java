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
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints the Grand Exchange onto the minimap as a single shaded blob in the current owning
 * clan's color, so who holds the GE reads at a glance from where players actually look. The
 * GE boundary polygon is projected point-by-point with {@link Perspective#localToMinimap},
 * which returns {@code null} off the minimap, so the shape self-clips to the minimap face.
 */
class ClanTurfMinimapOverlay extends Overlay
{
	private static final int FILL_ALPHA = 60;
	private static final int LINE_ALPHA = 180;
	private static final BasicStroke LINE_STROKE = new BasicStroke(1f);

	// Large distance cap so localToMinimap never drops a boundary point for being off the minimap
	// (its default cap shrinks as you zoom in). We instead clip the fill to the minimap face, so
	// the shaded area always runs right to the minimap edge. Kept well under sqrt(Integer.MAX) to
	// avoid overflow in the engine's distance*distance check.
	private static final int PROJECT_DISTANCE = 30000;

	private final Client client;
	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;

	@Inject
	ClanTurfMinimapOverlay(Client client, ClanTurfPlugin plugin, ClanTurfConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.minimapTint() || config.minimapOpacity() <= 0)
		{
			return null;
		}

		String leader = plugin.getBoundaryLeader();
		if (leader == null)
		{
			return null; // no owner yet -> nothing to tint
		}

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
		WorldPoint here = local.getWorldLocation();
		if (!GrandExchangeArea.near(here, 30))
		{
			return null; // matches the scene overlay: only near the GE
		}

		Widget minimap = minimapDrawWidget();
		if (minimap == null)
		{
			return null;
		}
		Rectangle bounds = minimap.getBounds();

		// Clip the GE outline to the loaded scene rectangle (in world coords) before projecting. The
		// result is always a complete polygon of whatever part of the GE is loaded, so the fill can
		// never tear into a strip when the GE is partly outside the streamed-in scene - it just fills
		// in as more of the scene loads. The large projection cap plus the ellipse clip below still
		// let the shape run to the minimap edge when zoomed in.
		int baseX = client.getBaseX();
		int baseY = client.getBaseY();
		int plane = here.getPlane();
		List<double[]> ring = new ArrayList<>();
		for (WorldPoint bp : GrandExchangeArea.boundary())
		{
			ring.add(new double[]{bp.getX(), bp.getY()});
		}
		ring = clip(ring, 0, baseX, true);                             // keep x >= scene left edge
		ring = clip(ring, 0, baseX + Constants.SCENE_SIZE - 1, false); // keep x <= scene right edge
		ring = clip(ring, 1, baseY, true);                             // keep y >= scene bottom edge
		ring = clip(ring, 1, baseY + Constants.SCENE_SIZE - 1, false); // keep y <= scene top edge
		if (ring.size() < 3)
		{
			return null; // the GE isn't in the loaded scene yet -> nothing to draw this frame
		}

		Polygon poly = new Polygon();
		for (double[] p : ring)
		{
			LocalPoint lp = LocalPoint.fromWorld(wv, new WorldPoint(
					(int) Math.round(p[0]), (int) Math.round(p[1]), plane));
			if (lp == null)
			{
				continue;
			}
			Point mp = Perspective.localToMinimap(client, lp, PROJECT_DISTANCE);
			if (mp != null)
			{
				poly.addPoint(mp.getX(), mp.getY());
			}
		}

		if (poly.npoints < 3)
		{
			return null;
		}

		// Resting color is the current owner; during a takeover, follow the exact same color
		// blend the boundary wall is showing, so the minimap fades old -> new in lockstep with it.
		Color base = ClanTurfColors.forClan(leader);
		long animStart = plugin.getAnimStartMs();
		Color from = plugin.getAnimFrom();
		Color to = plugin.getAnimTo();
		if (animStart > 0 && from != null && to != null)
		{
			long elapsed = System.currentTimeMillis() - animStart;
			if (elapsed >= 0 && elapsed < ClanTurfAnim.totalMs(config))
			{
				base = ClanTurfAnim.lerp(from, to, ClanTurfAnim.minimapColorMix(config, elapsed));
			}
		}

		// Scale both alphas by the user's opacity setting (100 = the tuned default look).
		int op = Math.max(0, Math.min(100, config.minimapOpacity()));
		int fillAlpha = FILL_ALPHA * op / 100;
		int lineAlpha = LINE_ALPHA * op / 100;

		Shape oldClip = graphics.getClip();
		// Clip to the (circular) minimap face so the fill never spills onto the surrounding UI.
		graphics.setClip(new Ellipse2D.Float(bounds.x, bounds.y, bounds.width, bounds.height));
		graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), fillAlpha));
		graphics.fillPolygon(poly);
		graphics.setStroke(LINE_STROKE);
		graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), lineAlpha));
		graphics.drawPolygon(poly);
		graphics.setClip(oldClip);
		return null;
	}

	/**
	 * Sutherland-Hodgman: clips the polygon (a list of {@code {x, y}}) against one axis-aligned
	 * half-plane. {@code axis} 0 = x, 1 = y; {@code keepGreater} true keeps points on the &gt;= side
	 * of {@code bound}, false the &lt;= side. The crossing point is inserted wherever an edge leaves
	 * the kept side, so the output stays a closed polygon.
	 */
	private static List<double[]> clip(List<double[]> poly, int axis, double bound, boolean keepGreater)
	{
		List<double[]> out = new ArrayList<>();
		int n = poly.size();
		for (int i = 0; i < n; i++)
		{
			double[] cur = poly.get(i);
			double[] prev = poly.get((i + n - 1) % n);
			boolean curIn = keepGreater ? cur[axis] >= bound : cur[axis] <= bound;
			boolean prevIn = keepGreater ? prev[axis] >= bound : prev[axis] <= bound;
			if (curIn)
			{
				if (!prevIn)
				{
					out.add(intersect(prev, cur, axis, bound));
				}
				out.add(cur);
			}
			else if (prevIn)
			{
				out.add(intersect(prev, cur, axis, bound));
			}
		}
		return out;
	}

	/** The point where segment {@code prev -> cur} crosses the line {@code axis == bound}. */
	private static double[] intersect(double[] prev, double[] cur, int axis, double bound)
	{
		int other = 1 - axis;
		double t = (bound - prev[axis]) / (cur[axis] - prev[axis]);
		double[] p = new double[2];
		p[axis] = bound;
		p[other] = prev[other] + t * (cur[other] - prev[other]);
		return p;
	}

	/** The active minimap draw-area widget (fixed or either resizable layout), or null if hidden. */
	private Widget minimapDrawWidget()
	{
		if (client.isResized())
		{
			Widget w = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
			if (w == null || w.isHidden())
			{
				w = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA);
			}
			return w != null && !w.isHidden() ? w : null;
		}
		Widget w = client.getWidget(ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
		return w != null && !w.isHidden() ? w : null;
	}
}
