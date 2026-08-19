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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the takeover "barks" above the Grand Exchange NPCs in the new owner's clan color, with a
 * gentle per-character wave and a fade-out near the end. We render the text ourselves (rather than
 * using the game's plain white overhead text) so it can carry the clan color and the wave effect.
 */
class ClanTurfBarkOverlay extends Overlay
{
	/** How many pixels each character rides up and down in the wave. */
	private static final double WAVE_AMPLITUDE = 2.5;
	/** Wave speed, radians per millisecond. */
	private static final double WAVE_SPEED = 0.006;
	/** Phase offset between adjacent characters, so the wave travels along the word. */
	private static final double WAVE_SPACING = 0.6;
	/** Vertical gap left between stacked lines so rows don't touch/clip. */
	private static final int LINE_GAP = 6;
	/** Locked font size for the takeover text. */
	private static final float TEXT_SIZE = 16f;
	/** Locked height the text floats above each NPC's model top, in local units. */
	private static final int TEXT_Z_OFFSET = 15;
	/** The game's overhead NPC chat color, so barks read like native NPC chatter. */
	private static final Color YELLOW = Color.YELLOW;

	private final ClanTurfPlugin plugin;
	private final Client client;

	@Inject
	ClanTurfBarkOverlay(ClanTurfPlugin plugin, Client client)
	{
		this.plugin = plugin;
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		// Always on top: barks must sit over NPC models and every other scene element, never behind them.
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		List<ClanTurfPlugin.Bark> barks = plugin.getBarks();
		if (barks.isEmpty())
		{
			return null;
		}

		long now = System.currentTimeMillis();
		long fade = plugin.getBarkFadeMs();
		g.setFont(FontManager.getRunescapeBoldFont().deriveFont(TEXT_SIZE));
		FontMetrics fm = g.getFontMetrics();
		int lineH = fm.getHeight();
		int ascent = fm.getAscent();

		Player local = client.getLocalPlayer();
		LocalPoint me = local == null ? null : local.getLocalLocation();

		// Phase 1: collect visible barks with a stable depth key (distance to the player). Sorting by
		// depth - instead of an arbitrary order - keeps the stacking from reshuffling every frame.
		List<Item> items = new ArrayList<>();
		for (ClanTurfPlugin.Bark bark : barks)
		{
			NPC npc = bark.npc;
			if (npc == null || now < bark.revealAt || now >= bark.expireAt)
			{
				continue; // not yet revealed, or fully expired
			}
			// Fade in over the first `fade` ms and out over the last `fade` ms, so a superseded chorus
			// fades away while the new one fades in.
			long life = now - bark.revealAt;
			long remaining = bark.expireAt - now;
			float aIn = life < fade ? (float) life / fade : 1f;
			float aOut = remaining < fade ? (float) remaining / fade : 1f;
			float alpha = Math.max(0f, Math.min(aIn, aOut));
			String text = bark.text;
			Point anchor = npc.getCanvasTextLocation(g, text, npc.getLogicalHeight() + TEXT_Z_OFFSET);
			if (anchor == null)
			{
				continue;
			}
			LocalPoint lp = npc.getLocalLocation();
			int dist = (me != null && lp != null) ? lp.distanceTo(me) : Integer.MAX_VALUE;
			items.add(new Item(text, anchor.getX(), anchor.getY(), alpha, dist, bark.color,
					clanMask(text, bark.clan)));
		}
		// Nearest first: the front NPC keeps its natural height, farther ones get lifted above it.
		items.sort(Comparator.comparingInt(it -> it.dist));

		// Phase 2: lift each line above any nearer one it would overlap, so clustered NPCs stack.
		List<Rectangle> boxes = new ArrayList<>();
		for (Item it : items)
		{
			int width = fm.stringWidth(it.text);
			int cell = lineH + LINE_GAP;
			Rectangle box = new Rectangle(it.x - 2, it.y - ascent, width + 4, cell);
			int guard = 0;
			boolean moved = true;
			while (moved && guard++ < 16)
			{
				moved = false;
				for (Rectangle b : boxes)
				{
					if (box.intersects(b))
					{
						box.y -= cell;
						it.y -= cell;
						moved = true;
						break;
					}
				}
			}
			boxes.add(box);
		}

		// Phase 3: draw far-to-near so the nearest NPC's line lands on top. Yellow with the clan name in
		// the clan color, plus the wave and a shadow.
		for (int idx = items.size() - 1; idx >= 0; idx--)
		{
			Item it = items.get(idx);
			int a = Math.round(255 * it.alpha);
			Color plain = new Color(YELLOW.getRed(), YELLOW.getGreen(), YELLOW.getBlue(), a);
			Color clanColor = new Color(it.color.getRed(), it.color.getGreen(), it.color.getBlue(), a);
			Color shadow = new Color(0, 0, 0, Math.round(180 * it.alpha));
			int x = it.x;
			for (int i = 0; i < it.text.length(); i++)
			{
				String ch = String.valueOf(it.text.charAt(i));
				int dy = (int) Math.round(
						Math.sin(now * WAVE_SPEED + i * WAVE_SPACING) * WAVE_AMPLITUDE);
				g.setColor(shadow);
				g.drawString(ch, x + 1, it.y + dy + 1);
				g.setColor(it.mask[i] ? clanColor : plain);
				g.drawString(ch, x, it.y + dy);
				x += fm.stringWidth(ch);
			}
		}
		return null;
	}

	/** A bark resolved to a screen position for this frame; {@code y} is adjusted by overlap-stacking. */
	private static final class Item
	{
		private final String text;
		private final int x;
		private int y;
		private final float alpha;
		private final int dist;
		private final Color color;
		private final boolean[] mask;

		private Item(String text, int x, int y, float alpha, int dist, Color color, boolean[] mask)
		{
			this.text = text;
			this.x = x;
			this.y = y;
			this.alpha = alpha;
			this.dist = dist;
			this.color = color;
			this.mask = mask;
		}
	}

	/** Marks which characters of {@code text} fall inside an occurrence of the clan name, so those are
	 * drawn in the clan color and the rest stay white. */
	private static boolean[] clanMask(String text, String clan)
	{
		boolean[] mask = new boolean[text.length()];
		if (clan == null || clan.isEmpty())
		{
			return mask;
		}
		int from = 0;
		int idx;
		while ((idx = text.indexOf(clan, from)) >= 0)
		{
			for (int i = idx; i < idx + clan.length(); i++)
			{
				mask[i] = true;
			}
			from = idx + clan.length();
		}
		return mask;
	}
}
