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

/**
 * Shared takeover-animation timing, so everything that reacts to a takeover (the boundary wall
 * and the minimap tint) uses the exact same color trajectory and can't drift apart.
 *
 * <p>The color crossfade follows the wall's choreography: old&rarr;new on the small rise,
 * back new&rarr;old on the small fall, old&rarr;new again on the full rise, then holds new
 * through the pause and the final descent. {@link #colorMix} returns that blend fraction
 * (0 = old clan color, 1 = new); callers do the actual {@link #lerp}.
 */
final class ClanTurfAnim
{
	private ClanTurfAnim()
	{
	}

	/** Total animation length in ms: small rise + small fall + full rise + hold + final fall. */
	static long totalMs(ClanTurfConfig c)
	{
		return Math.max(1, c.smallRiseMs()) + Math.max(1, c.smallFallMs())
				+ Math.max(1, c.fullRiseMs()) + c.holdMs() + Math.max(1, c.fallMs());
	}

	/**
	 * @param elapsed ms since the takeover started
	 * @return blend fraction 0..1 (0 = old clan color, 1 = new), matching the wall exactly.
	 */
	static double colorMix(ClanTurfConfig c, long elapsed)
	{
		final long d1 = Math.max(1, c.smallRiseMs());
		final long d2 = Math.max(1, c.smallFallMs());
		final long d3 = Math.max(1, c.fullRiseMs());
		final long hold = c.holdMs();
		final long d5 = Math.max(1, c.fallMs());

		final long tFallStart = d1;
		final long tFullStart = d1 + d2;
		final long tTop = d1 + d2 + d3;
		final long tHoldEnd = tTop + hold;
		final long total = tHoldEnd + d5;

		if (elapsed <= 0)
		{
			return 0.0;
		}
		if (elapsed >= total)
		{
			return 1.0;
		}
		if (elapsed < tFallStart)
		{
			return elapsed / (double) d1;                       // small rise: old -> new
		}
		if (elapsed < tFullStart)
		{
			return 1.0 - (elapsed - tFallStart) / (double) d2;  // small fall: new -> old
		}
		if (elapsed < tTop)
		{
			return (elapsed - tFullStart) / (double) d3;        // full rise: old -> new
		}
		return 1.0;                                             // hold + final fall: stay new
	}

	/**
	 * Minimap variant of {@link #colorMix}. The first two beats (small rise old&rarr;new, small
	 * fall new&rarr;old) stay in sync with the wall, but the final rise to the new color is
	 * stretched across all the wall's remaining time (full rise + hold + final fall) so the
	 * minimap is still visibly fading right up until the wall settles, instead of finishing early.
	 */
	static double minimapColorMix(ClanTurfConfig c, long elapsed)
	{
		final long d1 = Math.max(1, c.smallRiseMs());
		final long d2 = Math.max(1, c.smallFallMs());

		final long tFallStart = d1;
		final long tFullStart = d1 + d2;
		final long total = totalMs(c);

		if (elapsed <= 0)
		{
			return 0.0;
		}
		if (elapsed >= total)
		{
			return 1.0;
		}
		if (elapsed < tFallStart)
		{
			return elapsed / (double) d1;                       // small rise: old -> new
		}
		if (elapsed < tFullStart)
		{
			return 1.0 - (elapsed - tFallStart) / (double) d2;  // small fall: new -> old
		}
		return (elapsed - tFullStart) / (double) (total - tFullStart); // slow final rise to new
	}

	/** Linear blend between two colors, {@code t} clamped to 0..1. */
	static Color lerp(Color a, Color b, double t)
	{
		double u = t < 0 ? 0 : (t > 1 ? 1 : t);
		int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * u);
		int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * u);
		int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * u);
		return new Color(r, g, bl);
	}
}
