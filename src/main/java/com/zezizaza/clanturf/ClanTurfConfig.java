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
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(ConfigClanTurfStore.GROUP)
public interface ClanTurfConfig extends Config
{
	// ------------------------------------------------------------------ appearance

	@ConfigItem(
			keyName = "fillOpacity",
			name = "Tile fill opacity",
			description = "How solid the claimed-tile color is (0 = outline only, 255 = solid).",
			position = 0
	)
	@Range(min = 0, max = 255)
	default int fillOpacity()
	{
		return 40;
	}

	@ConfigItem(
			keyName = "drawOutline",
			name = "Draw tile outline",
			description = "Outline each claimed tile in its clan color.",
			position = 1
	)
	default boolean drawOutline()
	{
		return true;
	}

	@ConfigItem(
			keyName = "outlineOpacity",
			name = "Tile outline opacity",
			description = "How solid the tile outline / territory border is (0 = invisible, 255 = solid).",
			position = 2
	)
	@Range(min = 0, max = 255)
	default int outlineOpacity()
	{
		return 175;
	}

	@ConfigItem(
			keyName = "showBoundary",
			name = "Show GE boundary",
			description = "Draw the GE boundary line (and the takeover animation) on screen.",
			position = 3
	)
	default boolean showBoundary()
	{
		return true;
	}

	@ConfigItem(
			keyName = "boundaryAnimation",
			name = "Border animation",
			description = "On: the GE boundary rises into a wall on takeover. Off: it just fades to the "
					+ "new clan's color as a flat ground line.",
			position = 4
	)
	default boolean boundaryAnimation()
	{
		return true;
	}

	@ConfigItem(
			keyName = "minimapTint",
			name = "Tint GE on minimap",
			description = "Shade the Grand Exchange on the minimap in the current owning clan's color.",
			position = 5
	)
	default boolean minimapTint()
	{
		return true;
	}

	@ConfigItem(
			keyName = "minimapOpacity",
			name = "Minimap opacity",
			description = "How strong the minimap GE tint is (0 = invisible, 100 = full).",
			position = 6
	)
	@Range(min = 0, max = 100)
	default int minimapOpacity()
	{
		return 100;
	}

	// ------------------------------------------------------------------ takeover

	@ConfigItem(
			keyName = "announceTakeovers",
			name = "Announce takeovers",
			description = "Post a message in the clan tab when a clan takes ownership of the GE.",
			position = 7
	)
	default boolean announceTakeovers()
	{
		return true;
	}

	@ConfigItem(
			keyName = "takeoverSound",
			name = "Takeover sound",
			description = "Play a sound when a clan takes ownership of the GE.",
			position = 8
	)
	default boolean takeoverSound()
	{
		return true;
	}

	@ConfigItem(
			keyName = "takeoverVolume",
			name = "Takeover volume",
			description = "Volume of the takeover sound (0-100%).",
			position = 9
	)
	@Range(min = 0, max = 100)
	default int takeoverVolume()
	{
		return 10;
	}

	@ConfigItem(
			keyName = "tileEffects",
			name = "Tile effects",
			description = "Show the takeover tile effects: the shimmer on the conquering clan's tiles "
					+ "and the small walls that rise on nearby tiles.",
			position = 10
	)
	default boolean tileEffects()
	{
		return true;
	}

	// ------------------------------------------------------------------ clan

	@ConfigItem(
			keyName = "customClanColor",
			name = "Custom clan color",
			description = "Paint your own clan's tiles a color you pick instead of the auto-assigned "
					+ "one. Local only - other players still see their own colors.",
			position = 11
	)
	default boolean customClanColor()
	{
		return false;
	}

	@ConfigItem(
			keyName = "clanColor",
			name = "Your clan color",
			description = "Color for your own clan's tiles when 'Custom clan color' is on.",
			position = 12
	)
	default Color clanColor()
	{
		return Color.RED;
	}

	@ConfigItem(
			keyName = "clanChatCommands",
			name = "Clan chat commands",
			description = "Turn !defend<world> and !invade<world> typed in clan chat into formatted "
					+ "Clan Turf calls, validated against the battles board.",
			position = 13
	)
	default boolean clanChatCommands()
	{
		return true;
	}

	// ------------------------------------------------------------------ sync

	@ConfigItem(
			keyName = "useServer",
			name = "Use sync server",
			description = "On: sync claims through the server so rival clans are visible and the turf "
					+ "war is live. Off: local only, you see just your own claims and nothing is sent "
					+ "anywhere. Leave it on to actually play; off is a privacy switch.",
			position = 14
	)
	default boolean useServer()
	{
		return true;
	}

	@ConfigItem(
			keyName = "resetCountdown",
			name = "Reset countdown",
			description = "Show an on-screen countdown to the daily turf reset, and warn in the clan "
					+ "tab while you're at the GE, so fights aren't cut off mid-battle.",
			position = 15
	)
	default boolean resetCountdown()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showTileTracker",
			name = "Tiles/hour tracker",
			description = "Show an on-screen counter of tiles you've claimed this session and your "
					+ "tiles-per-hour rate, so you can practice your movement. Shift + right-click it "
					+ "to reset.",
			position = 16
	)
	default boolean showTileTracker()
	{
		return false;
	}

	// -------------------------------------------------------------- hidden / internal
	// Kept as config (so values persist) but not shown in the settings panel. The animation
	// values below were dialed in during development and are now baked in.

	@ConfigItem(keyName = "barrierHeight", name = "Wall height", description = "Peak wall height.", position = 100, hidden = true)
	@Range(max = 400)
	default int barrierHeight()
	{
		return 160;
	}

	@ConfigItem(keyName = "barrierOpacity", name = "Wall opacity", description = "Wall fill opacity.", position = 101, hidden = true)
	@Range(max = 255)
	default int barrierOpacity()
	{
		return 70;
	}

	@ConfigItem(keyName = "smallRiseMs", name = "Small rise (ms)", description = "Baked.", position = 102, hidden = true)
	@Range(min = 1, max = 4000)
	default int smallRiseMs()
	{
		return 250;
	}

	@ConfigItem(keyName = "smallRiseHeightPct", name = "Small rise height %", description = "Baked.", position = 103, hidden = true)
	@Range(min = 1, max = 100)
	default int smallRiseHeightPct()
	{
		return 50;
	}

	@ConfigItem(keyName = "smallFallMs", name = "Small fall (ms)", description = "Baked.", position = 104, hidden = true)
	@Range(min = 1, max = 4000)
	default int smallFallMs()
	{
		return 300;
	}

	@ConfigItem(keyName = "fullRiseMs", name = "Full rise (ms)", description = "Baked.", position = 105, hidden = true)
	@Range(min = 1, max = 6000)
	default int fullRiseMs()
	{
		return 600;
	}

	@ConfigItem(keyName = "holdMs", name = "Hold at top (ms)", description = "Baked.", position = 106, hidden = true)
	@Range(max = 5000)
	default int holdMs()
	{
		return 700;
	}

	@ConfigItem(keyName = "fallMs", name = "Final fall (ms)", description = "Baked.", position = 107, hidden = true)
	@Range(min = 1, max = 4000)
	default int fallMs()
	{
		return 550;
	}

	@ConfigItem(keyName = "vibratoAmplitude", name = "Vibrato amount", description = "Baked.", position = 108, hidden = true)
	@Range(max = 60)
	default int vibratoAmplitude()
	{
		return 10;
	}

	@ConfigItem(keyName = "vibratoFrequencyHz", name = "Vibrato speed (Hz)", description = "Baked.", position = 109, hidden = true)
	@Range(min = 1, max = 40)
	default int vibratoFrequencyHz()
	{
		return 15;
	}

	@ConfigItem(keyName = "vibratoDecayMs", name = "Vibrato decay (ms)", description = "Baked.", position = 110, hidden = true)
	@Range(max = 3000)
	default int vibratoDecayMs()
	{
		return 800;
	}

	@ConfigItem(keyName = "vibratoStartMs", name = "Vibrato lead (ms)", description = "Baked.", position = 111, hidden = true)
	@Range(max = 3000)
	default int vibratoStartMs()
	{
		return 350;
	}

	@ConfigItem(keyName = "sparkleIntensity", name = "Sparkle intensity", description = "Baked.", position = 112, hidden = true)
	@Range(max = 100)
	default int sparkleIntensity()
	{
		return 70;
	}

	@ConfigItem(keyName = "sparkleSpeed", name = "Sparkle speed", description = "Baked.", position = 113, hidden = true)
	@Range(min = 1, max = 50)
	default int sparkleSpeed()
	{
		return 10;
	}

	@ConfigItem(keyName = "bandStrips", name = "Wall bands", description = "Baked.", position = 114, hidden = true)
	@Range(min = 1, max = 16)
	default int bandStrips()
	{
		return 5;
	}

	@ConfigItem(keyName = "bandBottomPct", name = "Band opacity: bottom %", description = "Baked.", position = 115, hidden = true)
	@Range(max = 100)
	default int bandBottomPct()
	{
		return 100;
	}

	@ConfigItem(keyName = "bandTopPct", name = "Band opacity: top %", description = "Baked.", position = 116, hidden = true)
	@Range(max = 100)
	default int bandTopPct()
	{
		return 32;
	}

	@ConfigItem(keyName = "bandCurve", name = "Band fade curve", description = "Baked.", position = 117, hidden = true)
	@Range(min = 20, max = 400)
	default int bandCurve()
	{
		return 85;
	}

	@ConfigItem(keyName = "tileWallRadius", name = "Tile wall radius", description = "Baked.", position = 118, hidden = true)
	@Range(min = 1, max = 12)
	default int tileWallRadius()
	{
		return 5;
	}

	@ConfigItem(keyName = "tileWallHeight", name = "Tile wall height", description = "Baked.", position = 119, hidden = true)
	@Range(max = 150)
	default int tileWallHeight()
	{
		return 45;
	}

	@ConfigItem(keyName = "takeoverSoundDelayMs", name = "Sound delay (ms)", description = "Baked.", position = 120, hidden = true)
	@Range(max = 5000)
	default int takeoverSoundDelayMs()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "serverUrl",
			name = "Server URL",
			description = "Baked-in sync server address. Editable only by advanced users running their "
					+ "own server (set it via the config file).",
			position = 124,
			hidden = true
	)
	default String serverUrl()
	{
		return "http://137.131.45.90:8080";
	}

	@ConfigItem(
			keyName = "serverAdminToken",
			name = "Admin token",
			description = "Server operator only: matches the server's CLANTURF_ADMIN_TOKEN.",
			position = 121,
			hidden = true
	)
	default String serverAdminToken()
	{
		return "";
	}

	@ConfigItem(
			keyName = "lastOwnClan",
			name = "Last own clan",
			description = "Internal: the last clan we applied your custom color to, so the color is "
					+ "right immediately on the next login.",
			position = 122,
			hidden = true
	)
	default String lastOwnClan()
	{
		return "";
	}

	@ConfigItem(
			keyName = "takeoverConfirmMs",
			name = "Takeover confirm (ms)",
			description = "How long a clan must hold the lead before a takeover is confirmed.",
			position = 123,
			hidden = true
	)
	@Range(min = 0, max = 15000)
	default int takeoverConfirmMs()
	{
		return 3000;
	}
}
