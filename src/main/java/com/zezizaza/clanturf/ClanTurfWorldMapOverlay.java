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
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;

/**
 * Draws the Grand Exchange on the world map (the globe-icon map): the GE outline, filled and outlined
 * in the current owner clan's color, with the clan's name at its center. Unlike the in-scene and
 * minimap overlays this is NOT gated to being at the GE - anyone running the plugin can open the world
 * map anywhere and see who holds the GE on their world, fed from the always-on battles data.
 */
class ClanTurfWorldMapOverlay extends Overlay
{
	private static final int FILL_ALPHA = 60;
	private static final int LINE_ALPHA = 210;

	private final Client client;
	private final WorldMapOverlay worldMapOverlay;
	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;

	@Inject
	ClanTurfWorldMapOverlay(Client client, WorldMapOverlay worldMapOverlay, ClanTurfPlugin plugin,
			ClanTurfConfig config)
	{
		this.client = client;
		this.worldMapOverlay = worldMapOverlay;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		// ABOVE_WIDGETS renders after the whole world map interface is drawn - including the World Map
		// plugin's teleport-destination points - so our fill/outline/name land on top of them. render()
		// gates on the map being open and clips to its viewport so we don't paint over the rest of the UI.
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.worldMap())
		{
			return null;
		}
		Widget map = client.getWidget(ComponentID.WORLD_MAP_MAPVIEW);
		if (map == null)
		{
			return null; // world map not open
		}

		// Project the GE outline onto the map. If any corner is off this map (e.g. a dungeon map is
		// shown), skip entirely - the GE only exists on the surface.
		WorldPoint[] corners = GrandExchangeArea.boundary();
		Polygon poly = new Polygon();
		for (WorldPoint wp : corners)
		{
			Point p = worldMapOverlay.mapWorldPointToGraphicsPoint(wp);
			if (p == null)
			{
				return null;
			}
			poly.addPoint(p.getX(), p.getY());
		}

		String owner = plugin.getWorldMapOwner();
		Color base = owner == null ? Color.WHITE : ClanTurfColors.forClan(owner);

		// Clip to the map viewport so nothing spills onto the map's border or legend.
		Shape origClip = graphics.getClip();
		graphics.setClip(map.getBounds());

		// Scale the tint by the Worldmap opacity slider (0-100), same as the minimap tint.
		int op = Math.max(0, Math.min(100, config.worldMapOpacity()));
		int fillAlpha = FILL_ALPHA * op / 100;
		int lineAlpha = LINE_ALPHA * op / 100;
		graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), fillAlpha));
		graphics.fill(poly);
		graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), lineAlpha));
		graphics.setStroke(new BasicStroke(2f));
		graphics.draw(poly);

		if (owner != null)
		{
			Point c = worldMapOverlay.mapWorldPointToGraphicsPoint(GrandExchangeArea.center());
			if (c != null)
			{
				int geWidthPx = poly.getBounds().width;

				// Font scales up with zoom (from the GE's on-screen width), floored so it stays readable
				// zoomed out and capped so it plateaus at the size that looks right and doesn't keep
				// ballooning as you zoom further in. Raise/lower the 24 to taste.
				int size = Math.min(24, Math.max(16, Math.round(geWidthPx / 12f)));
				Font font = FontManager.getRunescapeBoldFont().deriveFont((float) size);
				graphics.setFont(font);
				FontMetrics fm = graphics.getFontMetrics();

				// Multi-word clan names stack one word per line, each line centered. The whole block is
				// centered on the GE, plus a westward shift that scales with the GE's on-screen size
				// (~-3px zoomed out to ~-25px zoomed in), so it sits over the GE's visual middle rather
				// than its bounding-box center.
				String[] words = owner.trim().split("\\s+");
				int westShift = Math.round(geWidthPx / 20f);
				int lineH = fm.getHeight();
				int firstBaseline = c.getY() - words.length * lineH / 2 + fm.getAscent();

				// Accumulate every word's glyph outlines into one shape (body) with a matching offset
				// shadow, so the fill and the gleam apply to the whole name at once.
				Path2D body = new Path2D.Float();
				Path2D shadow = new Path2D.Float();
				for (int i = 0; i < words.length; i++)
				{
					int tx = c.getX() - fm.stringWidth(words[i]) / 2 - westShift;
					int ty = firstBaseline + i * lineH;
					GlyphVector gv = font.createGlyphVector(graphics.getFontRenderContext(), words[i]);
					body.append(gv.getOutline(tx, ty), false);
					shadow.append(gv.getOutline(tx + 1, ty + 1), false);
				}

				graphics.setColor(Color.BLACK); // shadow so the name reads over any map color
				graphics.fill(shadow);
				graphics.setColor(Color.WHITE); // body sits steady in white; the clan color is the gleam
				graphics.fill(body);

				// A single clan-color gleam sweeps across the name, then rests. Speed (time to cross),
				// pause (dwell between sweeps), width, feather, and opacity are all set in Appearance.
				int gleamAlpha = config.gleamOpacity();
				long speed = Math.max(1, config.gleamSpeedMs());
				long pause = Math.max(0, config.gleamPauseMs());
				long elapsed = System.currentTimeMillis() % (speed + pause);
				if (gleamAlpha > 0 && elapsed < speed)
				{
					Rectangle tb = body.getBounds();
					float band = Math.max(1f, size * config.gleamWidthPct() / 100f); // half-width of streak
					float travel = elapsed / (float) speed;
					float cx = tb.x - band + travel * (tb.width + 2 * band);
					// Trapezoid profile: transparent edges ramp to a solid core over 'feather', so a small
					// feather reads as a hard square band and a large one as a soft taper. Clamped so the
					// gradient's fractions stay strictly increasing at the extremes.
					float feather = Math.min(0.49f, Math.max(0.001f, config.gleamFeatherPct() / 100f));
					Color edge = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
					Color core = new Color(base.getRed(), base.getGreen(), base.getBlue(), gleamAlpha);
					Paint origPaint = graphics.getPaint();
					graphics.setPaint(new LinearGradientPaint(
							new Point2D.Float(cx - band, 0), new Point2D.Float(cx + band, 0),
							new float[]{0f, feather, 1f - feather, 1f},
							new Color[]{edge, core, core, edge}));
					graphics.fill(body);
					graphics.setPaint(origPaint);
				}
			}
		}

		graphics.setClip(origClip);
		return null;
	}
}
