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
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
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

		// Project every boundary point with a large distance cap so none are dropped, then build
		// the full ring. Points that fall outside the minimap get clipped below, so the shape runs
		// to the minimap edge instead of cutting across when the GE is only partly in view.
		Polygon poly = new Polygon();
		for (WorldPoint bp : GrandExchangeArea.boundary())
		{
			for (WorldPoint wp : WorldPoint.toLocalInstance(wv, bp))
			{
				LocalPoint lp = LocalPoint.fromWorld(wv, wp);
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

	/** The active minimap draw-area widget (fixed or either resizable layout), or null if hidden. */
	private Widget minimapDrawWidget()
	{
		if (client.isResized())
		{
			Widget w = client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
			if (w == null || w.isHidden())
			{
				w = client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_STONES_DRAW_AREA);
			}
			return w != null && !w.isHidden() ? w : null;
		}
		Widget w = client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
		return w != null && !w.isHidden() ? w : null;
	}
}
