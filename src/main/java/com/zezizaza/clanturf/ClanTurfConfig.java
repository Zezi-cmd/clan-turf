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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(ConfigClanTurfStore.GROUP)
public interface ClanTurfConfig extends Config
{
	@ConfigSection(
			name = "Appearance",
			description = "How claimed tiles, outlines, and the minimap tint look.",
			position = 0
	)
	String appearanceSection = "appearanceSection";

	@ConfigSection(
			name = "Takeover",
			description = "The GE boundary, takeover effects, and takeover announcements.",
			position = 1
	)
	String takeoverSection = "takeoverSection";

	@ConfigSection(
			name = "Clan",
			description = "Your clan color and clan chat rally commands.",
			position = 2
	)
	String clanSection = "clanSection";

	@ConfigSection(
			name = "Network",
			description = "The sync server that makes rival clans visible to each other.",
			position = 3
	)
	String networkSection = "networkSection";

	@ConfigSection(
			name = "Extras",
			description = "Reset countdown and the tiles-per-hour tracker.",
			position = 4
	)
	String extrasSection = "extrasSection";

	// ------------------------------------------------------------------ appearance

	@ConfigItem(
			keyName = "fillOpacity",
			section = appearanceSection,
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
			section = appearanceSection,
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
			section = appearanceSection,
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
			section = appearanceSection,
			name = "Show GE boundary",
			description = "Draw the GE boundary line (and the takeover animation) on screen.",
			position = 9
	)
	default boolean showBoundary()
	{
		return true;
	}

	@ConfigItem(
			keyName = "boundaryAnimation",
			section = takeoverSection,
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
			keyName = "snailTrail",
			section = appearanceSection,
			name = "Snail trail",
			description = "Leave a fading trail in your clan color under your character as it runs across "
					+ "the GE, including the in-between tiles you skip while running. Purely visual - the "
					+ "tiles you actually claim, and your tiles/hour, are exactly the same as with it off.",
			position = 11
	)
	default boolean snailTrail()
	{
		return true;
	}

	@ConfigItem(
			keyName = "minimapTint",
			section = appearanceSection,
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
			section = appearanceSection,
			name = "Minimap opacity",
			description = "How strong the minimap GE tint is (0 = invisible, 100 = full).",
			position = 6
	)
	@Range(min = 0, max = 100)
	default int minimapOpacity()
	{
		return 100;
	}

	@ConfigItem(
			keyName = "worldMap",
			section = appearanceSection,
			name = "Tint GE on world map",
			description = "Draw the Grand Exchange on the world map (the globe icon) in the current owner "
					+ "clan's color, with the clan's name across it. Works anywhere - you don't have to be "
					+ "at the GE to see who holds it on your world.",
			position = 7
	)
	default boolean worldMap()
	{
		return true;
	}

	@ConfigItem(
			keyName = "worldMapOpacity",
			section = appearanceSection,
			name = "Worldmap opacity",
			description = "How strong the world map GE tint is (0 = invisible, 100 = full).",
			position = 8
	)
	@Range(min = 0, max = 100)
	default int worldMapOpacity()
	{
		return 100;
	}

	@ConfigItem(
			keyName = "showPreClaims",
			section = appearanceSection,
			name = "Show Pre-Claims",
			description = "Fill the unwalkable GE pockets (the central building, stalls, staircases) in the "
					+ "current owner's color so a clan's turf reads solid, white when unclaimed. Off leaves "
					+ "them empty. Either way these tiles are never claimable and never counted.",
			position = 10
	)
	default boolean showPreClaims()
	{
		return true;
	}

	// ------------------------------------------------------------------ takeover

	@ConfigItem(
			keyName = "announceTakeovers",
			section = takeoverSection,
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
			section = takeoverSection,
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
			section = takeoverSection,
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
			keyName = "tileWalls",
			section = appearanceSection,
			name = "Tile walls",
			description = "Show the raised tile effects: the wall pop when a tile is taken from a rival, "
					+ "the walls and shimmer that rise near you on a takeover, and the cascading walls of "
					+ "the snail trail. Off = flat tiles everywhere (a snail trail then just fades).",
			position = 12
	)
	default boolean tileWalls()
	{
		return true;
	}

	// ------------------------------------------------------------------ clan

	@ConfigItem(
			keyName = "clanChatCommands",
			section = clanSection,
			name = "Clan chat commands",
			description = "Turn !defend<world> and !invade<world> typed in clan chat into formatted "
					+ "Clan Turf calls, validated against the battles board.",
			position = 11
	)
	default boolean clanChatCommands()
	{
		return true;
	}

	@ConfigItem(
			keyName = "customClanColor",
			section = clanSection,
			name = "Custom clan colors",
			description = "Apply the clan color list below. Local only - other players still see their own "
					+ "colors. Off ignores the list and every clan uses its auto color.",
			position = 12
	)
	default boolean customClanColor()
	{
		return false;
	}

	@ConfigItem(
			keyName = "clanColorWhitelist",
			section = clanSection,
			name = "Clan color list",
			description = "Per-clan colors, local only, as ClanName=RRGGBB separated by commas "
					+ "(e.g. Wrath=EC1F1F,Some Clan=228B22). Click any clan's bar in the side panel - your own "
					+ "included - to set one with the color wheel. Easy to copy and paste to share a palette.",
			position = 13
	)
	default String clanColorWhitelist()
	{
		return "";
	}

	// ------------------------------------------------------------------ sync

	@ConfigItem(
			keyName = "useServer",
			section = networkSection,
			name = "Use sync server",
			description = "On: syncs your claims through the server so rival clans are visible and the "
					+ "turf war is live. This sends your clan name, the Grand Exchange tile coordinates "
					+ "you claim, and your current world number - no account details or personal "
					+ "information. Off: local only, you see just your own claims and nothing is sent "
					+ "anywhere.",
			position = 14
	)
	default boolean useServer()
	{
		return true;
	}

	@ConfigItem(
			keyName = "resetCountdown",
			section = extrasSection,
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
			section = extrasSection,
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

	@ConfigItem(
			keyName = "colorblindMode",
			section = extrasSection,
			name = "Colorblind",
			description = "Adjust every clan color to be easier to tell apart for a type of color blindness "
					+ "(daltonization): Protanopia (red), Deuteranopia (green), Tritanopia (blue). Applies to "
					+ "auto colors and your custom color list. Local only.",
			position = 17
	)
	default ColorblindMode colorblindMode()
	{
		return ColorblindMode.NONE;
	}

	// -------------------------------------------------------------- hidden / internal
	// Kept as config (so values persist) but not shown in the settings panel. The animation
	// values below were dialed in during development and are now baked in.

	@ConfigItem(keyName = "barrierHeight", name = "Wall height", description = "Peak wall height.",
			position = 100, hidden = true)
	@Range(max = 400)
	default int barrierHeight()
	{
		return 160;
	}

	@ConfigItem(keyName = "barrierOpacity", name = "Wall opacity", description = "Wall fill opacity.",
			position = 101, hidden = true)
	@Range(max = 255)
	default int barrierOpacity()
	{
		return 70;
	}

	@ConfigItem(keyName = "smallRiseMs", name = "Small rise (ms)", description = "Baked.",
			position = 102, hidden = true)
	@Range(min = 1, max = 4000)
	default int smallRiseMs()
	{
		return 250;
	}

	@ConfigItem(keyName = "smallRiseHeightPct", name = "Small rise height %", description = "Baked.",
			position = 103, hidden = true)
	@Range(min = 1, max = 100)
	default int smallRiseHeightPct()
	{
		return 50;
	}

	@ConfigItem(keyName = "smallFallMs", name = "Small fall (ms)", description = "Baked.",
			position = 104, hidden = true)
	@Range(min = 1, max = 4000)
	default int smallFallMs()
	{
		return 300;
	}

	@ConfigItem(keyName = "fullRiseMs", name = "Full rise (ms)", description = "Baked.",
			position = 105, hidden = true)
	@Range(min = 1, max = 6000)
	default int fullRiseMs()
	{
		return 600;
	}

	@ConfigItem(keyName = "holdMs", name = "Hold at top (ms)", description = "Baked.",
			position = 106, hidden = true)
	@Range(max = 5000)
	default int holdMs()
	{
		return 700;
	}

	@ConfigItem(keyName = "fallMs", name = "Final fall (ms)", description = "Baked.",
			position = 107, hidden = true)
	@Range(min = 1, max = 4000)
	default int fallMs()
	{
		return 550;
	}

	@ConfigItem(keyName = "vibratoAmplitude", name = "Vibrato amount", description = "Baked.",
			position = 108, hidden = true)
	@Range(max = 60)
	default int vibratoAmplitude()
	{
		return 10;
	}

	@ConfigItem(keyName = "vibratoFrequencyHz", name = "Vibrato speed (Hz)", description = "Baked.",
			position = 109, hidden = true)
	@Range(min = 1, max = 40)
	default int vibratoFrequencyHz()
	{
		return 15;
	}

	@ConfigItem(keyName = "vibratoDecayMs", name = "Vibrato decay (ms)", description = "Baked.",
			position = 110, hidden = true)
	@Range(max = 3000)
	default int vibratoDecayMs()
	{
		return 800;
	}

	@ConfigItem(keyName = "vibratoStartMs", name = "Vibrato lead (ms)", description = "Baked.",
			position = 111, hidden = true)
	@Range(max = 3000)
	default int vibratoStartMs()
	{
		return 350;
	}

	@ConfigItem(keyName = "sparkleIntensity", name = "Sparkle intensity", description = "Baked.",
			position = 112, hidden = true)
	@Range(max = 100)
	default int sparkleIntensity()
	{
		return 70;
	}

	@ConfigItem(keyName = "sparkleSpeed", name = "Sparkle speed", description = "Baked.",
			position = 113, hidden = true)
	@Range(min = 1, max = 50)
	default int sparkleSpeed()
	{
		return 10;
	}

	@ConfigItem(keyName = "bandStrips", name = "Wall bands", description = "Baked.",
			position = 114, hidden = true)
	@Range(min = 1, max = 16)
	default int bandStrips()
	{
		return 5;
	}

	@ConfigItem(keyName = "bandBottomPct", name = "Band opacity: bottom %", description = "Baked.",
			position = 115, hidden = true)
	@Range(max = 100)
	default int bandBottomPct()
	{
		return 100;
	}

	@ConfigItem(keyName = "bandTopPct", name = "Band opacity: top %", description = "Baked.",
			position = 116, hidden = true)
	@Range(max = 100)
	default int bandTopPct()
	{
		return 32;
	}

	@ConfigItem(keyName = "bandCurve", name = "Band fade curve", description = "Baked.",
			position = 117, hidden = true)
	@Range(min = 20, max = 400)
	default int bandCurve()
	{
		return 85;
	}

	@ConfigItem(keyName = "tileWallRadius", name = "Tile wall radius", description = "Baked.",
			position = 118, hidden = true)
	@Range(min = 1, max = 12)
	default int tileWallRadius()
	{
		return 5;
	}

	@ConfigItem(keyName = "tileWallHeight", name = "Tile wall height", description = "Baked.",
			position = 119, hidden = true)
	@Range(max = 150)
	default int tileWallHeight()
	{
		return 45;
	}

	@ConfigItem(keyName = "takeoverSoundDelayMs", name = "Sound delay (ms)", description = "Baked.",
			position = 120, hidden = true)
	@Range(max = 5000)
	default int takeoverSoundDelayMs()
	{
		return 0;
	}

	// World map clan-name gleam - tuned during development, now baked in.

	@ConfigItem(keyName = "gleamSpeedMs", name = "Name gleam: speed (ms)", description = "Baked.",
			position = 124, hidden = true)
	@Range(min = 100, max = 5000)
	default int gleamSpeedMs()
	{
		return 900;
	}

	@ConfigItem(keyName = "gleamPauseMs", name = "Name gleam: pause (ms)", description = "Baked.",
			position = 125, hidden = true)
	@Range(min = 0, max = 10000)
	default int gleamPauseMs()
	{
		return 4000;
	}

	@ConfigItem(keyName = "gleamWidthPct", name = "Name gleam: width", description = "Baked.",
			position = 126, hidden = true)
	@Range(min = 5, max = 200)
	default int gleamWidthPct()
	{
		return 170;
	}

	@ConfigItem(keyName = "gleamFeatherPct", name = "Name gleam: feather", description = "Baked.",
			position = 127, hidden = true)
	@Range(min = 0, max = 49)
	default int gleamFeatherPct()
	{
		return 40;
	}

	@ConfigItem(keyName = "gleamOpacity", name = "Name gleam: opacity", description = "Baked.",
			position = 128, hidden = true)
	@Range(min = 0, max = 255)
	default int gleamOpacity()
	{
		return 215;
	}


	@ConfigItem(
			keyName = "serverUrl",
			section = networkSection,
			name = "Server URL",
			description = "The Clan Turf sync server. Leave as-is unless you run your own. If tiles "
					+ "aren't syncing, make sure this matches http://141.148.136.217:8080.",
			position = 17
	)
	default String serverUrl()
	{
		return "http://141.148.136.217:8080";
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
