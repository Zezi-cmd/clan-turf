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
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.NPC;
import net.runelite.api.Point;
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
	/** The final slice of a bark's life spent fading out, in milliseconds. */
	private static final long FADE_MS = 700;
	/** Locked font size for the takeover text. */
	private static final float TEXT_SIZE = 16f;
	/** Locked height the text floats above each NPC's model top, in local units. */
	private static final int TEXT_Z_OFFSET = 15;
	/** The game's overhead NPC chat color, so barks read like native NPC chatter. */
	private static final Color YELLOW = Color.YELLOW;

	private final ClanTurfPlugin plugin;

	@Inject
	ClanTurfBarkOverlay(ClanTurfPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Map<NPC, ClanTurfPlugin.Bark> barks = plugin.getBarks();
		if (barks.isEmpty())
		{
			return null;
		}

		long now = System.currentTimeMillis();
		long duration = plugin.getBarkDurationMs();
		Color base = plugin.getBarkColor();
		String clan = plugin.getBarkClan();
		int zOffset = TEXT_Z_OFFSET;
		g.setFont(FontManager.getRunescapeBoldFont().deriveFont(TEXT_SIZE));
		FontMetrics fm = g.getFontMetrics();

		for (Map.Entry<NPC, ClanTurfPlugin.Bark> e : barks.entrySet())
		{
			NPC npc = e.getKey();
			ClanTurfPlugin.Bark bark = e.getValue();
			if (npc == null || bark == null)
			{
				continue;
			}
			// Staggered reveal: a bark is invisible until its own reveal time, then lives for duration.
			long life = now - bark.revealAt;
			if (life < 0 || life > duration)
			{
				continue;
			}
			long remaining = duration - life;
			float alpha = remaining >= FADE_MS ? 1f : Math.max(0f, (float) remaining / FADE_MS);
			int a = Math.round(255 * alpha);
			Color plain = new Color(YELLOW.getRed(), YELLOW.getGreen(), YELLOW.getBlue(), a);
			Color clanColor = new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
			Color shadow = new Color(0, 0, 0, Math.round(180 * alpha));

			String text = bark.text;
			boolean[] clanMask = clanMask(text, clan);
			Point anchor = npc.getCanvasTextLocation(g, text, npc.getLogicalHeight() + zOffset);
			if (anchor == null)
			{
				continue;
			}
			// getCanvasTextLocation centers the string on the NPC, so anchor.x is the left edge.
			int x = anchor.getX();
			int y = anchor.getY();
			for (int i = 0; i < text.length(); i++)
			{
				String ch = String.valueOf(text.charAt(i));
				int dy = (int) Math.round(
						Math.sin(now * WAVE_SPEED + i * WAVE_SPACING) * WAVE_AMPLITUDE);
				g.setColor(shadow);
				g.drawString(ch, x + 1, y + dy + 1);
				g.setColor(clanMask[i] ? clanColor : plain);
				g.drawString(ch, x, y + dy);
				x += fm.stringWidth(ch);
			}
		}
		return null;
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
