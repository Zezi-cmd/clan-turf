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
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
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

	// Opt-in "evolve" flourish: when YOU tick "Share my symbol", your own overhead symbol does a short
	// grow/shake/pulse then pops into full color with a glow (local player only). See the v9 style guide.
	private static final long EVOLVE_MS = 1600;
	private boolean optInInit;   // seed prevOptIn from the current setting on the first render (no flourish on login)
	private boolean prevOptIn;   // last-seen "Share my symbol" value, to catch the off -> on transition
	private boolean hadTagInit;  // seed prevHadTag on the first render (no flourish for an alliance you already had)
	private boolean prevHadTag;  // whether you had an alliance symbol last render, to catch none -> present (created/joined)
	private long evolveStartMs;  // when the current evolve started (0 = none / finished)
	private boolean evolveWhite; // true = white->color (your own create); false = colored only (opt-in / already set up)
	private final Map<BufferedImage, BufferedImage> whiteCache = new HashMap<>(); // colored symbol -> white silhouette

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
		// One time-based fade covering GE proximity, so nothing pops. Anyone sees opted-in players' symbols
		// (viewing is not gated on your own opt-in); "Show player indicators" only controls whether YOU are
		// published. Target is visible unless "Hide indicators outside GE" is set and you are away from it.
		// Never draw overhead player info in a PVP area - the Wilderness, PVP/Deadman worlds, or the PVP Arena.
		// Jagex's rules forbid surfacing extra information about other players for the purpose of scouting PVP
		// targets, so we hard-cut the symbols (and names) there regardless of settings. Clan Turf is a GE tool;
		// this costs nothing in normal use and keeps us inside the rules.
		if (inPvpArea())
		{
			fade = 0.0;
			return null;
		}
		long nowMs = System.currentTimeMillis();
		double dt = lastRenderMs == 0 ? 16.0 : Math.min(200.0, nowMs - lastRenderMs);
		lastRenderMs = nowMs;

		// Detect the local player ticking "Share my symbol" (off -> on) and start the evolve flourish. Seed the
		// previous value on the first render so being opted-in at login doesn't fire it.
		boolean optIn = config.showPlayerIndicators();
		if (!optInInit)
		{
			prevOptIn = optIn;
			optInInit = true;
		}
		if (optIn && !prevOptIn)
		{
			evolveStartMs = nowMs;
			evolveWhite = false; // opting in (symbol may already exist) plays the COLORED animation
		}
		prevOptIn = optIn;

		// Also fire the flourish when your own alliance symbol first appears. Only YOUR OWN create plays the
		// white -> color evolve; a symbol your clan set up (or a join) just gets the colored animation.
		boolean hasTag = plugin.myAllianceTag() != null;
		if (!hadTagInit)
		{
			prevHadTag = hasTag;
			hadTagInit = true;
		}
		if (hasTag && !prevHadTag)
		{
			evolveStartMs = nowMs;
			evolveWhite = plugin.recentlyCreatedAlliance();
		}
		prevHadTag = hasTag;
		// Alliance symbols are an online-only feature: offline, fade them all out (including your own clan's).
		boolean visible = config.useServer()
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
			// flickering on the wall beats. Fires on a gain and is seen by everyone.
			boolean flashing = color != null && flashAid != null && flashAid.equals(tag.allianceId);
			if (flashing)
			{
				symbol = flashed(symbol, (float) pulse);
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
			long evolveElapsed = nowMs - evolveStartMs;
			if (isSelf && evolveStartMs > 0 && evolveElapsed < EVOLVE_MS)
			{
				// Your own symbol plays the opt-in evolve flourish instead of the plain draw.
				Color evColor = color != null ? color : Color.WHITE;
				renderEvolve(graphics, symbol, imgPt.getX() + symbol.getWidth() / 2,
						symY + symbol.getHeight() / 2, evolveElapsed, symbolAlpha, evColor);
			}
			else
			{
				if (flashing)
				{
					// A small white glow behind the symbol, flickering on the same wall beat as the brighten.
					double cx = imgPt.getX() + symbol.getWidth() / 2.0;
					double cy = symY + symbol.getHeight() / 2.0;
					// Takeover glow is the alliance's own color (white is only for the create/opt-in evolve).
					drawGlow(graphics, cx, cy, symbol.getWidth() * 0.9 + pulse * 8.0, pulse * 0.45, symbolAlpha, color);
				}
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, symbolAlpha));
				graphics.drawImage(symbol, imgPt.getX(), symY, null);
			}

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
		BufferedImage out = ClanTurfColors.recolorSymbol(raw, color, SYMBOL_SIZE);
		tintCache.put(key, out);
		return out;
	}

	/**
	 * The opt-in evolve flourish (local player only): 0-1.0s a white silhouette grows, shakes and pulses faster
	 * and faster over a soft glow; at 1.0s it pops (slight overshoot) into full color and the white overlay +
	 * glow fade out by 1.6s. Driven entirely by {@code elapsed}, no timers.
	 */
	private void renderEvolve(Graphics2D g, BufferedImage colored, int cx, int cy, long elapsed, float baseAlpha,
			Color allianceColor)
	{
		double t = elapsed / 1000.0; // seconds
		double scale;
		double shakeX;
		double whiteAlpha;
		double glowAlpha;
		double glowRadius;
		if (t < 1.0)
		{
			double grow = t < 0.18 ? easeOut(t / 0.18) : 1.0;
			double pulse = 1.0 + 0.12 * Math.sin(t * t * 40.0) * (0.4 + t); // speeds up and grows across the second
			scale = grow * pulse;
			shakeX = Math.sin(t * 45.0) * 2.0; // slower, slightly gentler wobble
			// Start pure white and tint toward the alliance color across the shake (stays white early via the
			// >1 power, colors up toward the pop); the pop finishes the last sliver of white.
			whiteAlpha = 1.0 - Math.pow(t, 1.6) * 0.8;
			glowAlpha = 0.55;
			glowRadius = 26.0 + 14.0 * t; // 26 -> 40
		}
		else
		{
			double tp = t - 1.0;
			double prog = tp < 0.30 ? backOut(tp / 0.30) : 1.0; // 1.45 settles to 1.0 with a slight overshoot
			scale = 1.45 - 0.45 * prog;
			whiteAlpha = tp < 0.35 ? 0.2 * (1.0 - easeOut(tp / 0.35)) : 0.0; // finish the remaining white on the pop
			glowAlpha = tp < 0.60 ? 0.55 * (1.0 - tp / 0.60) : 0.0;
			glowRadius = 40.0;
			shakeX = 0.0;
		}
		if (!evolveWhite)
		{
			whiteAlpha = 0.0; // colored-only animation (opt-in / clan already set it up): no white phase
		}

		java.awt.Composite orig = g.getComposite();
		Object origInterp = g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
		double dcx = cx + shakeX;

		// The glow tracks the symbol: pure white early, blending toward the alliance color as the white fades,
		// so the glow finishes colored like the symbol (a colored-only opt-in evolve glows in color throughout).
		float colorIn = (float) (1.0 - Math.max(0.0, Math.min(1.0, whiteAlpha)));
		Color glowCol = new Color(
				clamp255(Math.round(255 + (allianceColor.getRed() - 255) * colorIn)),
				clamp255(Math.round(255 + (allianceColor.getGreen() - 255) * colorIn)),
				clamp255(Math.round(255 + (allianceColor.getBlue() - 255) * colorIn)));
		drawGlow(g, dcx, cy, glowRadius, glowAlpha, baseAlpha, glowCol);

		int iw = colored.getWidth();
		int ih = colored.getHeight();
		java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
		at.translate(dcx, cy);
		at.scale(scale, scale);
		at.translate(-iw / 2.0, -ih / 2.0);
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); // keep the pixel art crisp when scaled
		BufferedImage white = whiteSilhouette(colored);
		// Draw the colored symbol, then the white silhouette on top at the current whiteAlpha - so it reads pure
		// white early and tints toward the alliance color as the white fades across the shake and pop.
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, baseAlpha));
		g.drawImage(colored, at, null);
		if (whiteAlpha > 0.001)
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
					(float) Math.min(1.0, whiteAlpha) * baseAlpha));
			g.drawImage(white, at, null);
		}

		g.setComposite(orig);
		if (origInterp != null)
		{
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, origInterp);
		}
	}

	/** A soft radial glow (in {@code glowColor}) filled as a circle. {@code alpha} is the effective center alpha. */
	private void drawGlow(Graphics2D g, double cx, double cy, double radius, double alpha, float baseAlpha,
			Color glowColor)
	{
		if (alpha <= 0.001 || radius < 1.0)
		{
			return;
		}
		float ga = (float) Math.max(0.0, Math.min(1.0, (alpha / 0.55) * baseAlpha));
		float r = (float) radius;
		Color center = new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), 140);
		Color edge = new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), 0);
		java.awt.RadialGradientPaint glow = new java.awt.RadialGradientPaint(
				new java.awt.geom.Point2D.Double(cx, cy), r,
				new float[]{0f, 1f},
				new Color[]{center, edge});
		java.awt.Composite oc = g.getComposite();
		java.awt.Paint op = g.getPaint();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ga));
		g.setPaint(glow);
		g.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
		g.setPaint(op);
		g.setComposite(oc);
	}

	/** A pure-white version of a symbol (keeps its alpha shape), built once and cached next to the colored one. */
	private BufferedImage whiteSilhouette(BufferedImage src)
	{
		BufferedImage cached = whiteCache.get(src);
		if (cached != null)
		{
			return cached;
		}
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.setComposite(AlphaComposite.SrcIn);
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, src.getWidth(), src.getHeight());
		g.dispose();
		whiteCache.put(src, out);
		return out;
	}

	private static int clamp255(int v)
	{
		return v < 0 ? 0 : Math.min(v, 255);
	}

	private static double easeOut(double t)
	{
		return 1.0 - Math.pow(1.0 - t, 3);
	}

	private static double backOut(double t)
	{
		double c1 = 1.9;
		double c3 = c1 + 1.0;
		return 1.0 + c3 * Math.pow(t - 1.0, 3) + c1 * Math.pow(t - 1.0, 2);
	}

	/**
	 * A brightened copy of the symbol for the capture pulse: a lighter shade of the symbol's OWN color washed
	 * over it (not white), scaled by the pulse so it brightens up then settles back to normal.
	 */
	private static BufferedImage flashed(BufferedImage src, float amount)
	{
		float amt = Math.max(0f, Math.min(1f, amount));
		if (amt <= 0f)
		{
			return src;
		}
		float scale = 1.0f + amt * 0.9f; // up to ~1.9x brighter at the peak
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int p = src.getRGB(x, y);
				int a = p >>> 24;
				int r = (p >> 16) & 0xff;
				int gg = (p >> 8) & 0xff;
				int b = p & 0xff;
				// Keep transparent pixels and the black outline untouched; brighten the colored interior by SCALING
				// each channel (multiplicative), which keeps the hue - a red symbol flares to a brighter red, not
				// toward white - while the shadow/base/highlight shading stays proportional.
				if (a == 0 || (int) Math.round(0.299 * r + 0.587 * gg + 0.114 * b) < 40)
				{
					out.setRGB(x, y, p);
					continue;
				}
				r = Math.min(255, Math.round(r * scale));
				gg = Math.min(255, Math.round(gg * scale));
				b = Math.min(255, Math.round(b * scale));
				out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
			}
		}
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

	// Any world where players can fight - so overhead player info could be used to scout opponents. PVP and
	// Deadman are also covered by WorldType.isPvpWorld, but High Risk carries its own flag (can be set without
	// PVP), and Bounty Hunter / LMS / PVP Arena are all combat worlds. Hidden on all of them, plus Wilderness.
	private static final java.util.EnumSet<WorldType> COMBAT_WORLDS = java.util.EnumSet.of(
			WorldType.PVP, WorldType.DEADMAN, WorldType.HIGH_RISK, WorldType.BOUNTY,
			WorldType.PVP_ARENA, WorldType.LAST_MAN_STANDING);

	/**
	 * True in any PVP context where surfacing player info would break Jagex's anti-scouting rule: the
	 * Wilderness, or any combat world (PVP, Deadman, High Risk, Bounty Hunter, PVP Arena, Last Man Standing).
	 * Overhead symbols and names are suppressed there.
	 */
	private boolean inPvpArea()
	{
		if (client.getVarbitValue(Varbits.IN_WILDERNESS) == 1)
		{
			return true;
		}
		for (WorldType t : client.getWorldType())
		{
			if (COMBAT_WORLDS.contains(t))
			{
				return true;
			}
		}
		return false;
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
