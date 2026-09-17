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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.clan.ClanChannel;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/**
 * Overhead alliance symbols. Floats the alliance's clan-motif symbol, tinted to the alliance's shared color,
 * above every alliance member near you - anywhere in the game - so allies and rival alliances read at a
 * glance in a crowded fight. It draws only a symbol, never a name, so it never collides with Player
 * Indicators or the game's own nameplates. Your own clan is tagged from local data (no opt-in needed);
 * players outside your clan are matched against the opt-in roster. Never sends anyone's location.
 */
class ClanTurfAllianceOverlay extends Overlay
{
	private static final int SYMBOL_SIZE = 22;
	private static final int NAME_Z_OFFSET = 40; // the name line; the symbol sits a fixed pixel gap above it
	private static final double FADE_MS = 400.0; // fade in/out duration, frame-rate independent

	private double fade; // 0..1, eases toward "should the symbols be showing at all"
	private long lastRenderMs; // for a time-based fade that doesn't depend on frame rate

	private final Client client;
	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;
	private final SpriteManager spriteManager;
	private final Map<Integer, BufferedImage> iconCache = new HashMap<>();
	private final Set<Integer> iconRequested = new HashSet<>();
	private final Map<String, BufferedImage> tintCache = new HashMap<>(); // "id:hex" -> tinted+scaled symbol

	@Inject
	ClanTurfAllianceOverlay(Client client, ClanTurfPlugin plugin, ClanTurfConfig config,
			SpriteManager spriteManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.spriteManager = spriteManager;
		// Default layer (UNDER_WIDGETS), same as RuneLite's Player Indicators, so the symbol sorts on top of
		// players like the name plates do - ABOVE_SCENE gets drawn into the scene and occluded by the GPU plugin.
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// One time-based fade covering both opting in/out and GE proximity, so nothing pops. Target is
		// visible when indicators are on and - if "Hide indicators outside GE" is set - you are near the GE.
		long nowMs = System.currentTimeMillis();
		double dt = lastRenderMs == 0 ? 16.0 : Math.min(200.0, nowMs - lastRenderMs);
		lastRenderMs = nowMs;
		boolean visible = config.showPlayerIndicators()
				&& (plugin.isNearGe() || !config.hideIndicatorsOutsideGe());
		fade = Math.max(0.0, Math.min(1.0, fade + (visible ? dt : -dt) / FADE_MS));
		if (fade <= 0.0)
		{
			return null;
		}
		// The opacity slider dims only the symbol; names ride the GE fade at full opacity.
		float symbolAlpha = (float) (fade * (config.indicatorOpacity() / 100.0));
		float nameAlpha = (float) fade;
		boolean drawNames = config.showAllianceNames();
		ClanChannel clanChannel = client.getClanChannel();
		// Your own clan shares your alliance, and you know that locally - no opt-in needed. Computed once.
		ClanTurfStore.AllianceTag myTag = plugin.myAllianceTag();
		Player self = client.getLocalPlayer();
		// Capture sparkle: during a takeover, the conquering alliance's symbols brighten (everyone sees it).
		double pulse = plugin.capturePulse();
		String flashAid = pulse > 0.01 ? plugin.capturedAllianceId() : null;
		java.awt.Composite origComposite = graphics.getComposite();
		for (Player p : client.getPlayers())
		{
			if (p == null || p.getName() == null)
			{
				continue;
			}
			boolean isSelf = p == self;
			boolean mine = isSelf
					|| (clanChannel != null && clanChannel.findMember(p.getName()) != null);
			ClanTurfStore.AllianceTag tag = mine
					? myTag
					: plugin.allianceTagForPlayer(Text.standardize(p.getName()));
			if (tag == null || tag.icon <= 0)
			{
				continue;
			}
			BufferedImage symbol = tintedSymbol(tag.icon, tag.colorHex);
			if (symbol == null)
			{
				continue; // sprite still loading or a malformed color
			}
			Color color = decodeColor(tag.colorHex);
			// Sparkle the conquering alliance's symbols during a takeover: brighten their own color (not white),
			// pulsing up then back. Fires on a gain and is seen by everyone.
			if (color != null && flashAid != null && flashAid.equals(tag.allianceId))
			{
				symbol = flashed(symbol, color, (float) pulse);
			}
			// Symbol only, no name - so it never collides with Player Indicators or the game's nameplates.
			// Float it a fixed pixel gap above where the name line would sit, so the gap holds at any zoom
			// (imgPt supplies the horizontal centering; its Y is replaced by the name-line Y).
			Point namePt = p.getCanvasTextLocation(graphics, p.getName(), p.getLogicalHeight() + NAME_Z_OFFSET);
			Point imgPt = p.getCanvasImageLocation(symbol, p.getLogicalHeight() + NAME_Z_OFFSET);
			if (namePt == null || imgPt == null)
			{
				continue;
			}
			int symY = namePt.getY() - graphics.getFontMetrics().getAscent() - SYMBOL_SIZE - 2;
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, symbolAlpha));
			graphics.drawImage(symbol, imgPt.getX(), symY, null);

			// Optionally the name too (at full opacity - the slider is symbol-only), but only for players
			// Player Indicators (or your own friend/clan/team relationships) isn't already naming.
			if (color != null && drawNames && !coveredByPlayerIndicators(p))
			{
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, nameAlpha));
				OverlayUtil.renderTextLocation(graphics, namePt, p.getName(), color);
			}
		}
		graphics.setComposite(origComposite);
		return null;
	}

	/**
	 * The alliance symbol scaled for overhead display with a translucent color wash over it - so the symbol's
	 * own detail stays visible underneath rather than being flattened to a solid silhouette. Cached per color.
	 */
	private BufferedImage tintedSymbol(int id, String hex)
	{
		Color color = decodeColor(hex);
		if (color == null)
		{
			return null;
		}
		String key = id + ":" + hex;
		BufferedImage cached = tintCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		BufferedImage raw = iconImage(id);
		if (raw == null)
		{
			return null; // sprite not loaded yet; the scene repaints so it lands next frame
		}
		BufferedImage out = ClanTurfColors.tintSymbol(raw, color, SYMBOL_SIZE, ClanTurfColors.SYMBOL_TINT_ALPHA);
		tintCache.put(key, out);
		return out;
	}

	/**
	 * A brightened copy of the symbol for the capture pulse: a lighter shade of the symbol's OWN color washed
	 * over it (not white), scaled by the pulse so it brightens up then settles back to normal.
	 */
	private static BufferedImage flashed(BufferedImage src, Color color, float amount)
	{
		Color bright = new Color(
				Math.min(255, color.getRed() + 90),
				Math.min(255, color.getGreen() + 90),
				Math.min(255, color.getBlue() + 90));
		float a = Math.max(0f, Math.min(0.45f, amount * 0.45f));
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, a));
		g.setColor(bright);
		g.fillRect(0, 0, src.getWidth(), src.getHeight());
		g.dispose();
		return out;
	}

	/**
	 * True when Player Indicators (or the game) already names this player, so Clan Turf shouldn't draw a
	 * second name over them. Keys off the local relationship - self, friend, friends-chat, clan, team - not
	 * Player Indicators' own settings, so a name only doubles if you have that plugin drawing the same player.
	 */
	private boolean coveredByPlayerIndicators(Player p)
	{
		Player self = client.getLocalPlayer();
		if (p == self || p.isFriend() || p.isFriendsChatMember() || p.isClanMember())
		{
			return true;
		}
		return self != null && self.getTeam() > 0 && p.getTeam() == self.getTeam();
	}

	/** Null on a malformed hex, else the alliance's shared color. */
	private static Color decodeColor(String hex)
	{
		if (hex == null || hex.length() != 6)
		{
			return null;
		}
		try
		{
			return new Color(Integer.parseInt(hex, 16));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Cached clan-symbol sprite, loaded asynchronously the first time an id is seen (mirrors the world-map
	 * overlay). Returns null until the load lands; the scene repaints every frame, so the symbol appears on
	 * the next one. The callback runs on the client thread, same as render(), so no extra synchronization.
	 */
	private BufferedImage iconImage(int id)
	{
		if (id <= 0)
		{
			return null;
		}
		BufferedImage cached = iconCache.get(id);
		if (cached != null)
		{
			return cached;
		}
		if (iconRequested.add(id))
		{
			spriteManager.getSpriteAsync(id, 0, img ->
			{
				if (img != null)
				{
					iconCache.put(id, img);
				}
			});
		}
		return null;
	}
}
