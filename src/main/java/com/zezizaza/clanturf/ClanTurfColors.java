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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a clan name to a stable color. Deterministic, so two players who have never
 * exchanged data still paint the same clan the same color - important once rival
 * claims start showing up from a sync backend.
 *
 * <p>{@link #OVERRIDES} pins specific clans to a fixed color ahead of the hash. It's driven
 * by the local "custom clan color" setting: the plugin registers the player's chosen color
 * for their own clan here, and every color in the plugin picks it up because they all resolve
 * through {@link #forClan}. Purely local - nothing is sent to other players.
 */
final class ClanTurfColors
{
	/** Clan name (lower-case) -> forced color, checked before hashing. Mutated from the game
	 * thread, read from the render thread and the Swing EDT, hence concurrent. */
	private static final Map<String, Color> OVERRIDES = new ConcurrentHashMap<>();

	/** Active color-blindness adjustment, applied to every color {@link #forClan} returns. */
	private static volatile ColorblindMode colorblind = ColorblindMode.NONE;

	private ClanTurfColors()
	{
	}

	/** Set the color-blindness mode (from config); NONE leaves colors untouched. */
	static void setColorblindMode(ColorblindMode mode)
	{
		colorblind = mode == null ? ColorblindMode.NONE : mode;
	}

	/** Pin a clan to a fixed color (local override). */
	static void setOverride(String clanName, Color color)
	{
		if (clanName != null && !clanName.isEmpty() && color != null)
		{
			OVERRIDES.put(clanName.toLowerCase(), color);
		}
	}

	/** Drop a clan's local override so it falls back to its auto (hashed) color. */
	static void removeOverride(String clanName)
	{
		if (clanName != null)
		{
			OVERRIDES.remove(clanName.toLowerCase());
		}
	}

	/**
	 * @param clanName the clan stamped on a tile
	 * @return a fully opaque color; the overlay applies its own alpha from config
	 */
	static Color forClan(String clanName)
	{
		return adjust(baseColor(clanName));
	}

	/** The clan's color before any color-blindness adjustment: a local override, else a stable hash. */
	private static Color baseColor(String clanName)
	{
		if (clanName == null || clanName.isEmpty())
		{
			return Color.GRAY;
		}

		Color override = OVERRIDES.get(clanName.toLowerCase());
		if (override != null)
		{
			return override;
		}

		// Scramble the bits first: raw hashCode() maps near-identical names (rivalA, rivalB)
		// to near-identical hues, so avalanche them before picking a hue on the wheel. Still
		// deterministic -> every client paints the same clan the same color.
		int h = clanName.hashCode();
		h ^= (h >>> 16);
		h *= 0x9E3779B1;
		h ^= (h >>> 16);
		float hue = Math.floorMod(h, 360) / 360f;
		return Color.getHSBColor(hue, 0.65f, 0.90f);
	}

	/**
	 * Daltonize a color for the active color-blindness mode: simulate what that eye sees, then push the
	 * lost difference back onto the channels it can still tell apart, so confusable clans separate more.
	 * NONE returns the color unchanged. Standard LMS-space method (Fidaner).
	 */
	private static Color adjust(Color c)
	{
		ColorblindMode mode = colorblind;
		if (mode == ColorblindMode.NONE)
		{
			return c;
		}
		double r = c.getRed();
		double g = c.getGreen();
		double b = c.getBlue();

		// RGB -> LMS cone response.
		double l = 17.8824 * r + 43.5161 * g + 4.11935 * b;
		double m = 3.45565 * r + 27.1554 * g + 3.86714 * b;
		double s = 0.0299566 * r + 0.184309 * g + 1.46709 * b;

		// Simulate the deficiency in LMS (drop the missing cone).
		double ls = l;
		double ms = m;
		double ss = s;
		switch (mode)
		{
			case PROTANOPIA:
				ls = 2.02344 * m - 2.52581 * s;
				break;
			case DEUTERANOPIA:
				ms = 0.494207 * l + 1.24827 * s;
				break;
			case TRITANOPIA:
			default:
				ss = -0.395913 * l + 0.801109 * m;
				break;
		}

		// LMS -> RGB (what the color-blind eye perceives).
		double sr = 0.0809444479 * ls - 0.130504409 * ms + 0.116721066 * ss;
		double sg = -0.0102485335 * ls + 0.0540193266 * ms - 0.113614708 * ss;
		double sb = -0.000365296938 * ls - 0.00412161469 * ms + 0.693511405 * ss;

		// Redistribute the error (original - perceived) onto the channels still distinguishable.
		double dr = r - sr;
		double dg = g - sg;
		double db = b - sb;
		double ng = 0.7 * dr + dg;
		double nb = 0.7 * dr + db;
		return new Color(clamp(r), clamp(g + ng), clamp(b + nb));
	}

	private static int clamp(double v)
	{
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}
}
