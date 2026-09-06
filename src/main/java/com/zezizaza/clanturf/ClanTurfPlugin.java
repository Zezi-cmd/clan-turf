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

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
		name = "Clan Turf",
		description = "Claim Grand Exchange tiles for your clan by walking on them, colored per clan and counted per world",
		tags = {"clan", "turf", "territory", "grand exchange", "ge", "tiles"}
)
public class ClanTurfPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ScheduledExecutorService executor;
	@Inject private OverlayManager overlayManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ColorPickerManager colorPickerManager;
	@Inject private SpriteManager spriteManager;
	@Inject private ConfigManager configManager;
	@Inject private ClanTurfConfig config;
	@Inject private ClanTurfOverlay overlay;
	@Inject private ClanTurfMinimapOverlay minimapOverlay;
	@Inject private ClanTurfResetOverlay resetOverlay;
	@Inject private ClanTurfTrackerOverlay trackerOverlay;
	@Inject private ClanTurfWorldMapOverlay worldMapOverlay;
	@Inject private ClanTurfBarkOverlay barkOverlay;
	@Inject private AudioPlayer audioPlayer;

	// The seam pays off here: both stores implement ClanTurfStore, and startUp picks one.
	// Local = ConfigManager (your claims only). Server = synced (rivals visible).
	@Inject private ConfigClanTurfStore localStore;
	@Inject private HttpClanTurfStore serverStore;
	private volatile ClanTurfStore store;

	private ClanTurfPanel panel;
	private NavigationButton navButton;

	/** Claims for the current world, refreshed on claim/world change. Read by the overlay. */
	private volatile List<ClanTurfPoint> visibleClaims = Collections.emptyList();
	/** Whether the player is near the GE right now, so overlays (the TPH tracker) can fade on it. */
	private volatile boolean nearGeNow;

	private WorldPoint lastTile;
	private int lastWorld = -1;

	/** How long a snail-trail tile takes to fade out, in ms (shared with the overlay's fade math). */
	private static final long TRAIL_MS = 2000L;
	/** Snail-trail tiles: world tile -&gt; when the model last passed over it, for the fading overlay. */
	private final Map<WorldPoint, Long> trail = new HashMap<>();
	/** Last tile stamped into the trail, so each tile is stamped once on entry (not re-stamped every
	 * frame while you stand on it, which would pin it at full instead of letting it fade out). */
	private WorldPoint lastTrailTile;
	/** Offline sandbox: the clan your steps paint as, or null to paint as your real clan. */
	private String selectedPaintClan;
	/** Last real clan we pushed to the panel's paint-as list, so we only rebuild it when it changes. */
	private String lastPaintPushClan = "__none__";
	/** Offline eraser: tiles just erased, world tile -&gt; timestamp, for the overlay's white flash. */
	private final Map<WorldPoint, Long> eraseFlash = new HashMap<>();
	private static final long ERASE_FLASH_MS = 500L;
	/** The model tile last game tick, so slug-off Surrender only erases once you MOVE onto a new one
	 * (flipping Surrender on doesn't wipe the tile you're standing on). */
	private WorldPoint lastEraseTile;

	/** The GE cast that reacts to a takeover with overhead barks, matched as name PREFIXES so
	 * "Farid Morrisane (ores and bars)" matches on "farid morrisane". Pets and passers-by aren't in
	 * the set, so they never bark. */
	private static final java.util.Set<String> GE_NPC_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
			"brugsen bursen", "grand exchange clerk", "banker", "farid morrisane", "abigaila",
			"perdu", "emblem trader", "hofuthand", "relobo blinyo", "bob barter", "clerk",
			"murky matt", "meredith", "zamorakian recruiter", "saradominist recruiter"));
	/** Active and fading-out barks. ClanTurfBarkOverlay draws each above its NPC in that owner's clan
	 * color (we render the text ourselves, since the game's overhead text is plain white and ignores
	 * color). Each carries its own color/owner so a superseded chorus can fade out while the new one
	 * fades in, instead of hard-cutting. */
	private final List<Bark> barks = new ArrayList<>();
	private static final long BARK_MS = 4000L;
	/** Fade in/out duration for a bark (must mirror the overlay's fade), also the quick fade applied to
	 * a chorus that a fresh takeover supersedes. */
	private static final long BARK_FADE_MS = 700L;
	/** Max random delay before an NPC pops its bark, so the crowd announces in a quick stagger rather
	 * than all at once. Kept short so a chorus fully reveals fast. Each NPC announces once per takeover. */
	private static final long BARK_STAGGER_MS = 700L;

	/** A single NPC's takeover bark: its line, owner color/name, reveal time and expiry. {@code expireAt}
	 * is normally revealAt + BARK_MS, but is pulled in early when a newer takeover supersedes it. */
	static final class Bark
	{
		final NPC npc;
		final String text;
		final Color color;
		final String clan; // upper-cased owner name, for coloring just the name in the line
		final long revealAt;
		long expireAt;

		Bark(NPC npc, String text, Color color, String clan, long revealAt, long expireAt)
		{
			this.npc = npc;
			this.text = text;
			this.color = color;
			this.clan = clan;
			this.revealAt = revealAt;
			this.expireAt = expireAt;
		}
	}

	// Lore-flavored bark pools, one per GE character (%s = the new owner clan, upper-cased), chosen
	// by name in lorePool(). Anyone unmatched uses GENERIC_LINES. Kept short to fit an overhead line.
	private static final String[] GENERIC_LINES = {
		"%s turf!",
		"%s runs the Exchange now!",
		"New owners: %s!",
		"The Exchange belongs to %s!",
		"%s took the GE!",
		"All hail %s!",
		"%s holds the Grand Exchange!",
		"This is %s ground now!"
	};
	// Brugsen Bursen - gnome director/founder of the Grand Exchange; professionally resigned.
	private static final String[] BRUGSEN_LINES = {
		"Well. %s owns the place now.",
		"I suppose I'm working for %s.",
		"%s has acquired my Exchange."
	};
	// Grand Exchange clerks - dry bureaucrats at the central desks.
	private static final String[] CLERK_LINES = {
		"%s's name is on the ledger. Lovely.",
		"Apparently we're taking orders from %s now.",
		"%s. That's going to be annoying to spell."
	};
	// The lone clan-portal Clerk - a clerk who's also an enthusiastic fan of every clan.
	private static final String[] CLERK_PORTAL_LINES = {
		"I'll make sure %s gets the good portal.",
		"Wonderful! I do like %s.",
		"%s has my full support!"
	};
	// The bankers standing beside the clerks - dry money jokes.
	private static final String[] BANKER_LINES = {
		"%s has the deed. I have the coins.",
		"Keeping an eye on %s's account.",
		"At least %s pays its bills."
	};
	// Farid Morrisane - son of Ali Morrisane; ores, bars and gems price guide.
	private static final String[] FARID_LINES = {
		"Father would have charged %s more.",
		"%s got the better deal.",
		"Father would've called this good business."
	};
	// Abigaila - a refugee from Morytania; deadpan.
	private static final String[] ABIGAILA_LINES = {
		"%s owns this place? Oh. Good.",
		"%s seems preferable to vampires.",
		"Is %s friendly? Please say yes."
	};
	// Perdu - dwarven merchant of the Lost Property shop; reclaims lost items.
	private static final String[] PERDU_LINES = {
		"Lost the GE? %s found it.",
		"%s found something rather large.",
		"Filed under %s's lost property."
	};
	// Emblem Trader - mysterious Bounty Hunter / Wilderness merchant; casually threatening.
	private static final String[] EMBLEM_LINES = {
		"No bounty on %s today. Shame.",
		"%s didn't even need a target.",
		"%s's looking rather dangerous today."
	};
	// Hofuthand - the dwarf; weapons and armour price guide.
	private static final String[] HOFUTHAND_LINES = {
		"%s knows how to make an entrance!",
		"Looks like %s came prepared.",
		"I wouldn't argue with %s."
	};
	// Relobo Blinyo - came from Shilo Village to sell logs.
	private static final String[] RELOBO_LINES = {
		"I leave Shilo for five minutes...",
		"%s's been busy.",
		"%s took it while I was away."
	};
	// Bob Barter - herbs and potions guide; stupidly literal about trading.
	private static final String[] BOB_LINES = {
		"%s's got the market cornered.",
		"%s's making some interesting trades.",
		"%s's quite the bargain hunter."
	};
	// Murky Matt (Matthew Grey) - runes price guide; cryptic and vague.
	private static final String[] MURKY_LINES = {
		"The runes said this would happen.",
		"I asked Matthew. He said %s.",
		"The runes point at %s again."
	};
	// Meredith - runs the Games Zone board games.
	private static final String[] MEREDITH_LINES = {
		"Well played, %s.",
		"%s wins. No rematch.",
		"%s's been winning all day."
	};
	// Zamorakian recruiter - Castle Wars, for chaos and Zamorak; smug.
	private static final String[] ZAMORAK_LINES = {
		"Now that's a name I can get behind.",
		"Chaos looks good on %s.",
		"Zamorak approves of %s."
	};
	// Saradominist recruiter - Castle Wars, for order and Saradomin; politely passive-aggressive.
	private static final String[] SARADOMIN_LINES = {
		"Very good, %s. Very orderly.",
		"%s keeps the place tidy.",
		"%s meets acceptable standards."
	};

	/** When we most recently had no clan channel, so the panel only shows the "join a clan" hint once
	 * the channel has had time to load (it arrives seconds after login and drops on a hop). Otherwise
	 * the hint flashes at clan members during the login/connect wait. 0 = we currently have a clan. */
	private long clanlessSinceMs;
	private static final long CLAN_GRACE_MS = 6000L;

	/** Bump this when a new update changelog should be shown; anyone whose stored "lastUpdateSeen"
	 *  differs gets these lines printed once on their next login. */
	private static final String UPDATE_ID = "v2";
	/** DEV ONLY: while true, the changelog shows on every login and is never marked as seen, for
	 *  testing the look. SET THIS TO false BEFORE RELEASING. */
	private static final boolean ALWAYS_SHOW_UPDATE = false;
	/** Header label. Kept as "[Update]" for now. Set to null in a future release to auto-use the
	 *  Hub-built jar version instead (see updateMessage()). */
	private static final String UPDATE_LABEL = "[Update]";
	private static final String[] UPDATE_LINES = {
		"New: Alliances. Team up with other clans - allied tiles share one color and count as one team.",
		"Owners and Admins of your clan can create an alliance with a passcode or join one, all from the side panel.",
		"The clan that made the alliance can recolor it, remove clans, or disband it.",
	};

	/** Set when we log in with an unseen update; the changelog fires on the next game tick, since chat
	 *  isn't ready at the state-change event itself. */
	private boolean showUpdateNextTick;
	/** Guards the changelog against re-firing on teleports / POH portals within one login; reset only
	 *  on LOGIN_SCREEN / HOPPING. */
	private boolean updateShownThisLogin;

	/** Clans (lower-case) we've locally recolored from the color list, so we can clear them on a change. */
	private final Set<String> whitelistApplied = new HashSet<>();
	private final Set<String> allianceApplied = new HashSet<>();
	private Map<String, String> lastAllianceColors = java.util.Collections.emptyMap();
	private Map<String, String> lastAllianceNames = java.util.Collections.emptyMap(); // detect a rename (colors miss it)
	private String cachedPasscode;    // owner clan only: the alliance passcode (refetched on a throttle)
	private String cachedPasscodeAid; // the alliance id the cached passcode/blacklist belong to
	private java.util.List<String> cachedBlacklist = java.util.Collections.emptyList(); // owner clan: blocked
	private long lastOwnerInfoMs;     // throttle: refetch owner-info (passcode + blacklist) at most every 20s

	/** Tiles-per-hour tracker: every claim this session (each new tile you step onto, retakes
	 * included), the timestamp of the first claim, and a once-per-second cached rate so the
	 * on-screen number ticks instead of scrolling every frame. */
	private int sessionClaims;
	private long firstClaimMs;
	private int cachedRate;
	private long rateCalcMs;
	private int maxRate;

	/** Daily turf reset (matches the server's default CLANTURF_RESET_HOUR). */
	private static final int RESET_HOUR_UTC = 0;
	private static final int[] RESET_PING_MINUTES = {10, 5, 1};
	private static final long RESET_PING_CLEAR_MS = 11L * 60 * 1000; // past the last ping -> arm next day
	/** Countdown at the previous tick, so a ping fires only on a live downward threshold crossing.
	 * -1 = not yet baselined this cycle (login or first entry into the window). */
	private long prevResetMs = -1L;

	/** Previous countdown for the reset-moment detector (distinct from the ping baseline); paired
	 * with dissolveFlashMs it lets the overlay start the tile dissolve when the daily wipe fires. */
	private long resetDissolvePrevMs = -1L;
	/** Bumped when tiles are about to be wiped (daily reset or the Clear button) so the overlay
	 * plays the staggered fade-out instead of a hard cut. */
	private long dissolveFlashMs = 0L;

	/** Sticky runner-up for the current world's synthesized battle row: held until another non-owner
	 * clan STRICTLY passes it, mirroring the server's stickyRank and the leader's sticky rule. */
	private int synthRunnerUpWorld = -1;
	private String synthRunnerUp;

	/** !defend<world> / !invade<world> (short forms !def / !inv accepted), case-insensitive with an
	 * optional space (e.g. "!defend 307", "!inv307"). */
	private static final Pattern CT_COMMAND =
			Pattern.compile("(?i)^\\s*!(def(?:end)?|inv(?:ade)?)\\s*(\\d{1,5})\\s*$");
	/** Tag prefixing the neutral/error Clan Turf lines. Command calls colour [CT] and the world by
	 * the clan they're about instead (your colour to defend, the target's colour to invade). */
	private static final String CT_TAG = "<col=ffcc33>[CT]</col>";
	private static final String WHITE = "<col=ffffff>";
	private static final String RESET = "</col>";

	/** Throttles panel rebuilds while polling the server every tick. */
	private int panelTicks;
	private static final int PANEL_UPDATE_TICKS = 3;

	/** Tiles beyond the GE within which the client still syncs (so turf shows as you approach). */
	private static final int ACTIVE_MARGIN = 25;
	/** Takeover sound: full inside the GE, then falls off (squared) with distance out, silent by
	 * SOUND_MAX_DIST tiles. */
	private static final int SOUND_MAX_DIST = 55;

	// Debounced takeover state. The committed leader only changes after a challenger has held the
	// lead for the confirm delay; that single event drives both the boundary animation and the
	// chat announcement, so a see-sawing lead doesn't spam either.
	private volatile String committedLeader;
	/** Owner we've already announced a takeover for on the current world, so the global (away-from-GE)
	 * detection off the battles feed doesn't double-fire what the near-GE detection already announced. */
	private String announcedOwner;
	private String pendingLeader;
	private long pendingSince;
	private boolean leaderInit;
	private long settleUntil;
	private static final long SETTLE_MS = 3000; // server-mode window to absorb the initial snapshot
	private boolean settling; // true during the post-arrival settle window (see updateLeader)
	/** Tiles I've claimed since the current settle window began, so the pre-existing owner can be read
	 * from the server snapshot without my own fresh claims counting. Lets a genuinely empty GE fire a
	 * real takeover on my first claim, while arriving at already-owned turf stays silent. */
	private final java.util.Set<String> arrivalTiles = new java.util.HashSet<>();
	// Boundary animation state, read by the overlay.
	private volatile long animStartMs;
	private volatile Color animFrom;
	private volatile Color animTo;

	@Provides
	ClanTurfConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(ClanTurfConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new ClanTurfPanel(this::clearOfflineTiles, this::setUseServer,
				colorPickerManager, this::onClanColorChosen, this::setSlug,
				this::addSandboxClan, this::selectPaintClan, this::removeSandboxClan,
				this::renameSandboxClan, this::setEraser,
				this::createAlliance, this::joinAlliance, this::leaveAlliance,
				this::changeAllianceColor);
		panel.setSpriteManager(spriteManager);
		panel.setAllianceIconLookup(serverStore::allianceIconByDisplay);
		panel.setAllianceRosterLookup(serverStore::allianceMembersByDisplay);
		panel.setAllianceOwnerHandlers(this::kickAllianceClan, this::changeAllianceName,
				this::changeAlliancePasscode, this::changeAllianceIcon);
		panel.setSlug(config.fullSlug());
		panel.setEraser(config.eraser());
		navButton = NavigationButton.builder()
				.tooltip("Clan Turf")
				.icon(buildIcon())
				.priority(7)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);

		overlayManager.add(overlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(resetOverlay);
		overlayManager.add(trackerOverlay);
		overlayManager.add(worldMapOverlay);
		overlayManager.add(barkOverlay);

		// Start both stores once, for the plugin's whole lifetime. The server poller is then only
		// paused/resumed on toggle (see selectStore), never recreated - a recreate-per-toggle
		// interrupted live requests and could knock the whole client offline.
		localStore.start();
		serverStore.start();
		selectStore();

		lastTile = null;
		lastWorld = -1;
		panelTicks = 0;
		committedLeader = null;
		announcedOwner = null;
		pendingLeader = null;
		leaderInit = false;
		animStartMs = 0;
		animFrom = null;
		animTo = null;
		// Custom clan colors come entirely from the color list (keyed by clan name), so they apply on
		// login right away - no waiting for the clan channel.
		ClanTurfColors.setColorblindMode(config.colorblindMode());
		applyWhitelist();
		refreshClaims();

		// If the plugin updated while already logged in, queue the changelog for the next tick.
		if (client.getGameState() == GameState.LOGGED_IN && shouldShowUpdate())
		{
			showUpdateNextTick = true;
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(resetOverlay);
		overlayManager.remove(trackerOverlay);
		overlayManager.remove(worldMapOverlay);
		overlayManager.remove(barkOverlay);
		clientToolbar.removeNavigation(navButton);
		serverStore.setChangeListener(null);
		localStore.setChangeListener(null);
		serverStore.stop();
		localStore.stop();
		visibleClaims = Collections.emptyList();
		lastTile = null;
		lastWorld = -1;
		for (String c : whitelistApplied)
		{
			ClanTurfColors.removeOverride(c);
		}
		for (String c : allianceApplied)
		{
			ClanTurfColors.removeOverride(c);
		}
		whitelistApplied.clear();
		allianceApplied.clear();
		ClanTurfColors.setColorblindMode(ColorblindMode.NONE);
		clearBarks();
	}

	/** Picks the local or networked store from the config and starts it. */
	private void selectStore()
	{
		store = config.useServer() ? serverStore : localStore;
		if (store == localStore)
		{
			// Local store is event-driven: repaint when we write. (Server mode polls per tick.)
			localStore.setChangeListener(this::refreshClaims);
		}
		else
		{
			serverStore.setChangeListener(null);
		}
		// The sync poller is started once in startUp and lives for the whole plugin. Toggling online/
		// offline only pauses or resumes it - it is never torn down. Recreating the poller on every flip
		// interrupted in-flight requests and thrashed connections, which could take the whole client
		// offline. It stays paused unless we are both in online mode AND logged in.
		serverStore.setOnline(config.useServer() && client.getGameState() == GameState.LOGGED_IN);
		if (panel != null)
		{
			panel.setOfflineControls(!config.useServer());
		}
	}

	/**
	 * Wipe the current world's local claims. Offline sandbox only: guarded on {@code !useServer}
	 * and routed to the local store, so this can never touch shared/server turf.
	 */
	private void clearOfflineTiles()
	{
		if (config.useServer())
		{
			return;
		}
		triggerTileDissolve(); // fade the tiles out instead of a hard cut
		localStore.clearClaims(client.getWorld());
		refreshClaims();
	}

	/**
	 * Flip the sync-server setting from the panel's Online/Offline toggle. Writing the config fires
	 * onConfigChanged, which swaps the store live and calls setOfflineControls, so the panel toggle and
	 * the settings checkbox always agree - this is just a convenient mirror of that one config item.
	 */
	private void setUseServer(boolean on)
	{
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "useServer", on);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ConfigClanTurfStore.GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		// Make "Use sync server" take effect immediately instead of needing a plugin off/on.
		if ("useServer".equals(key))
		{
			// Just switch which store is active and pause/resume the poller - no stop()/start(), so a
			// rapid toggle can't interrupt live requests or thrash connections (that was dropping the
			// whole game client).
			selectStore();
			leaderInit = false;
			committedLeader = null;
			applyWhitelist(); // switching online/offline changes whether the offline color list applies
			refreshClaims();
		}
		// Recolor live when the custom-color toggle or either color list changes.
		else if ("customClanColor".equals(key) || "clanColorWhitelist".equals(key)
				|| "offlineClanColors".equals(key))
		{
			applyWhitelist();
			refreshClaims();
		}
		else if ("colorblindMode".equals(key))
		{
			ClanTurfColors.setColorblindMode(config.colorblindMode());
			refreshClaims();
		}
		else if ("blockedAllies".equals(key))
		{
			syncBlockedAllies();
		}
	}

	/** The owner edited the Blocked allies list in settings: push the difference (vs the server's current
	 *  blacklist) to the server - a name added blocks/kicks that clan, a name removed un-blocks it. Ignored
	 *  unless your clan owns an alliance, and it skips the plugin's own mirror writes so it can't loop. */
	private void syncBlockedAllies()
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		String aid = clan == null ? null : store.allianceIdOf(clan);
		if (aid == null || !isOwnerClan(clan) || !canManageAlliance())
		{
			return;
		}
		java.util.Map<String, String> now = new java.util.HashMap<>(); // lower-case -> display name
		for (String c : parseClanCsv(config.blockedAllies()))
		{
			now.put(c.toLowerCase(), c);
		}
		java.util.Set<String> had = new java.util.HashSet<>();
		for (String c : cachedBlacklist)
		{
			had.add(c.toLowerCase());
		}
		if (now.keySet().equals(had))
		{
			return; // no real change (or it was our own mirror write) - don't loop
		}
		final String faid = aid;
		final String fclan = clan;
		final java.util.List<String> prev = new java.util.ArrayList<>(cachedBlacklist);
		executor.execute(() ->
		{
			for (Map.Entry<String, String> e : now.entrySet())
			{
				if (!had.contains(e.getKey()))
				{
					serverStore.allianceKick(faid, fclan, e.getValue()); // block, and kick if a member
				}
			}
			for (String c : prev)
			{
				if (!now.containsKey(c.toLowerCase()))
				{
					serverStore.allianceUnblacklist(faid, fclan, c); // un-block
				}
			}
			javax.swing.SwingUtilities.invokeLater(() -> lastOwnerInfoMs = 0); // refetch to reconcile
		});
	}

	/** Split a comma-separated clan list into trimmed, non-empty names. */
	private static java.util.List<String> parseClanCsv(String raw)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		if (raw != null)
		{
			for (String part : raw.split(","))
			{
				String s = part.trim();
				if (!s.isEmpty())
				{
					out.add(s);
				}
			}
		}
		return out;
	}

	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		if (event.getOverlay() != trackerOverlay)
		{
			return;
		}
		String option = event.getEntry().getOption();
		if ("Reset run".equals(option))
		{
			resetTileRun();
		}
		else if ("Reset all".equals(option))
		{
			resetTileAll();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Logging in or hopping resets where we think the player is.
		lastTile = null;

		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			// Resume the poller only if we're in online mode (it stays paused, not stopped, offline).
			serverStore.setOnline(config.useServer());
			if (!updateShownThisLogin && shouldShowUpdate())
			{
				showUpdateNextTick = true; // chat isn't ready yet, so fire on the first tick
			}
		}
		else if (state == GameState.HOPPING)
		{
			updateShownThisLogin = false; // a hop re-arms the guard (only matters in dev preview mode)
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// Logged out to the login/world-select screen: pause sync and blank the panel so the
			// scoreboard bars and battles don't linger over the login screen. Login re-drives it all.
			serverStore.setOnline(false);
			lastWorld = -1;
			clanlessSinceMs = 0; // re-grace the clan hint on the next login instead of firing instantly
			updateShownThisLogin = false;
			refreshClaims();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		maybeShowUpdate();

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		// Alliance data arrives on a background poll; the map reference is swapped each poll, so this
		// re-applies the shared team colors only when the alliance set actually changed.
		if (!store.allianceColors().equals(lastAllianceColors)
				|| !store.allianceNames().equals(lastAllianceNames))
		{
			applyWhitelist();      // re-applies team colors and records the new alliance map
			updateAlliancePanel(); // reflect join/leave/kick in the panel (member list, Leave button)
			// Membership changing relabels the leader between a clan and its alliance (Wrath <-> WRATH).
			// Re-baseline the boundary silently so that relabel isn't announced as a bogus takeover.
			leaderInit = false;
		}

		// Keep the paint-as roster's "your clan" entry current as the clan channel loads or changes, and
		// remember the name so it shows instantly next login instead of waiting for the channel.
		String realClanNow = effectiveClanName();
		if (realClanNow != null && !realClanNow.equals(config.lastClan()))
		{
			configManager.setConfiguration(ConfigClanTurfStore.GROUP, "lastClan", realClanNow);
		}
		if (!java.util.Objects.equals(realClanNow, lastPaintPushClan))
		{
			lastPaintPushClan = realClanNow;
			pushPaintClans();
		}

		// Track how long we've had no clan channel, so the panel doesn't call a clan member "clan-less"
		// during the seconds it takes the channel to load after login (or reload after a hop).
		if (effectiveClanName() != null)
		{
			clanlessSinceMs = 0;
		}
		else if (clanlessSinceMs == 0)
		{
			clanlessSinceMs = System.currentTimeMillis();
		}

		int world = client.getWorld();
		if (world != lastWorld)
		{
			lastWorld = world;
			lastTile = null;
			leaderInit = false; // re-baseline the committed leader silently on the new world
			announcedOwner = null; // re-baseline the global takeover detector on the new world
			animStartMs = 0;    // don't let a takeover animation bleed from the old world onto the new
			clearBarks();       // and don't drag overhead barks across a hop
			trail.clear();      // snail-trail tiles are per-world coords; don't drag them across a hop
			eraseFlash.clear();
			lastTrailTile = null;
			refreshClaims();
		}

		// Are we at/near the GE? Drives connect-on-demand and leader detection.
		WorldPoint here = local.getWorldLocation();
		boolean nearGe = here != null && GrandExchangeArea.near(here, ACTIVE_MARGIN);
		nearGeNow = nearGe;

		// Drop barks that have fully faded out (each carries its own expiry).
		if (!barks.isEmpty())
		{
			long nowMs = System.currentTimeMillis();
			barks.removeIf(b -> nowMs >= b.expireAt);
		}

		updateResetPings(nearGe);

		// Daily reset moment (countdown wraps from ~0 back up to a full day) -> dissolve the tiles.
		// Server mode only; local mode doesn't wipe at reset (the Clear button handles that case).
		if (store == serverStore)
		{
			long msReset = msUntilReset();
			if (resetDissolvePrevMs >= 0 && resetDissolvePrevMs < 30_000L && msReset > 60_000L)
			{
				triggerTileDissolve();
			}
			resetDissolvePrevMs = msReset;
		}

		// Server mode: only sync while near the GE (connect-on-demand), then pull the latest cache
		// every tick so rival claims appear live. Cache read is non-blocking; panel is throttled.
		if (store == serverStore)
		{
			serverStore.setActiveWorld(nearGe ? world : -1);

			visibleClaims = new java.util.ArrayList<>(store.getClaims(world));
			if (++panelTicks >= PANEL_UPDATE_TICKS)
			{
				panelTicks = 0;
				panel.update(aggregatedClaims(), world, GrandExchangeArea.totalTiles(), committedLeader,
						displayClan(effectiveClanName()), findBattle(world), store.connectionStatus(),
						clanHintDue(), perClanTileCounts());
				panel.updateBattles(battlesForPanel(), displayClan(effectiveClanName()), client.getWorld());
				panel.setGlobalClaims(store.getGlobalClaims());
			}
		}

		// Debounced takeover detection (boundary animation + announcement), only while near the
		// GE. Leaving resets the baseline so returning re-adopts the current owner silently.
		if (nearGe)
		{
			updateLeader();
		}
		else
		{
			leaderInit = false;
		}
		checkGlobalTakeover(nearGe);

		// Claim the tile you're standing on: the true/server tile, once per tick. Running skips every
		// other tile - correct, that's where you actually land. Snail-trail mode adds a purely visual
		// trail over the skipped tiles per frame in onClientTick; it never changes what gets claimed.
		// Slug off = act once per tick, skipping the in-between tiles when you run. Claiming uses the true
		// tile (where you actually land). Surrender erases the tile under the character MODEL (tracks your
		// feet, not the true tile out front) and only once you MOVE onto a new tile, so flipping Surrender
		// on doesn't wipe the tile you're standing on. Slug on -> onClientTick drives every crossed tile.
		LocalPoint mlp = local.getLocalLocation();
		WorldPoint modelTile = mlp == null ? null : WorldPoint.fromLocalInstance(client, mlp);
		if (!isSlugPainting())
		{
			if (isErasing())
			{
				if (modelTile != null && !modelTile.equals(lastEraseTile))
				{
					eraseTile(modelTile);
				}
			}
			else
			{
				tryClaim(local.getWorldLocation(), world);
			}
		}
		lastEraseTile = modelTile; // tracked every tick, so Surrender starts fresh from where you stand
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		// While a takeover chorus is up, wipe each barking NPC's own yellow idle text every frame so it
		// can't clash with our colored bark. We render our bark separately, so this only kills the native
		// overhead text, not ours.
		if (!barks.isEmpty())
		{
			for (Bark b : barks)
			{
				if (b.npc != null && b.npc.getOverheadText() != null)
				{
					b.npc.setOverheadText(null);
				}
			}
		}

		// Snail trail: sample the tile under the moving character model each frame and remember it with
		// a timestamp, so the overlay can paint a fading trail beneath your feet - including the tiles you
		// skip while running. Visual only: it never claims or counts a tile (that stays true-tile on the
		// game tick), so it can't become a faster way to grab turf.
		pruneTrail();
		boolean slug = isSlugPainting();
		boolean surrender = isErasing();
		// Trail obeys the Snail trail setting: clan-colored when you have a clan to paint as, white while
		// surrendering, and white for a no-clanner (visual feedback only - they still can't claim). Off =
		// no trail. Near-GE gating is applied below.
		boolean wantTrail = config.snailTrail();
		if ((!wantTrail && !slug) || !nearGeNow)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		LocalPoint lp = local.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		// Stamp each tile once as the model enters it (the tile you land on included), then let it fade.
		// Standing still doesn't re-stamp, so the tile you stop on fades out like the rest instead of
		// being pinned at full while you're parked on it.
		WorldPoint wp = WorldPoint.fromLocalInstance(client, lp);
		if (wp != null && !wp.equals(lastTrailTile))
		{
			lastTrailTile = wp;
			if (GrandExchangeArea.contains(wp))
			{
				if (wantTrail)
				{
					trail.put(wp, System.currentTimeMillis());
				}
				// Slug scope (offline sandbox): the action hits every crossed tile, not just the one you
				// land on. Surrender erases, otherwise claim. Offline-gated, so it never touches live play.
				if (slug)
				{
					if (surrender)
					{
						eraseTile(wp);
					}
					else
					{
						tryClaim(wp, client.getWorld());
					}
				}
			}
		}
	}

	/** Full slug paint mode: on only offline (local store) with the side-panel toggle set. */
	boolean isSlugPainting()
	{
		return store == localStore && config.fullSlug();
	}

	/** Side-panel toggle for offline Full Slug - the scope switch: it makes both claiming and surrender
	 * hit every tile you cross instead of just the one you land on. Persisted. */
	void setSlug(boolean on)
	{
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "fullSlug", on);
	}

	/** Offline Surrender mode: your steps erase instead of claim. On only offline with the toggle set. */
	boolean isErasing()
	{
		return store == localStore && config.eraser();
	}

	/** Side-panel toggle for offline Surrender (erase instead of claim). Persisted. Composes with Slug. */
	void setEraser(boolean on)
	{
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "eraser", on);
	}

	/** Erase the claim on a crossed tile, if any, and flash it white. Offline eraser only. */
	private void eraseTile(WorldPoint wp)
	{
		for (ClanTurfPoint c : visibleClaims)
		{
			if (c.getZ() == wp.getPlane() && c.getRegionId() == wp.getRegionID()
					&& c.getRegionX() == wp.getRegionX() && c.getRegionY() == wp.getRegionY())
			{
				store.removeClaim(c);
				eraseFlash.put(wp, System.currentTimeMillis());
				return;
			}
		}
	}

	/** Drop snail-trail tiles older than the fade window so the map stays small. */
	private void pruneTrail()
	{
		long now = System.currentTimeMillis();
		eraseFlash.values().removeIf(t -> t < now - ERASE_FLASH_MS);
		if (trail.isEmpty())
		{
			return;
		}
		trail.values().removeIf(t -> t < now - TRAIL_MS);
	}

	/**
	 * Stamp a claim for {@code wp} if it's a new tile inside the GE and we know our clan. Shared by
	 * both claim modes - the game-tick true-tile path and the per-frame follow-model path - so the
	 * dedupe ({@link #lastTile}), GE bounds check, tracker, and store write stay identical between them.
	 */
	private void tryClaim(WorldPoint wp, int world)
	{
		if (wp == null || wp.equals(lastTile))
		{
			return;
		}

		if (!GrandExchangeArea.contains(wp))
		{
			return;
		}

		// The clan is stamped at claim time: your real clan online, or the selected sandbox clan offline.
		String clanName = paintClan();
		if (clanName == null)
		{
			// Can't claim yet - e.g. the clan channel is still loading for a few seconds right after a
			// world hop. Don't mark this tile as seen, so the claim retries and lands once the clan is
			// available; otherwise the tile you hopped in on never gets claimed until you step off it.
			return;
		}
		lastTile = wp; // mark seen only once we actually claim, so a not-yet-claimable tile is retried

		ClanTurfPoint point = new ClanTurfPoint(
				wp.getRegionID(), wp.getRegionX(), wp.getRegionY(), wp.getPlane(),
				world, clanName);

		// Who currently holds this tile, if anyone (there's only one owner per tile).
		String tk = tileKey(point);
		String currentOwner = null;
		for (ClanTurfPoint c : visibleClaims)
		{
			if (tk.equals(tileKey(c)))
			{
				currentOwner = c.getClanName();
				break;
			}
		}

		// Don't take an ally's tile: if a clan in our alliance holds it, walk over it and leave it theirs.
		if (currentOwner != null && !currentOwner.equalsIgnoreCase(clanName)
				&& sameAlliance(clanName, currentOwner))
		{
			return; // lastTile is already set above, so this tile won't be reprocessed
		}

		// A genuine gain (for the community counter) is a tile not already ours - empty or a rival's.
		// Re-walking our own turf claims nothing new, so it must not tick the counter (the server counts
		// gains the same way, so the optimistic ticks stay in step with the authoritative total).
		boolean gain = !clanName.equals(currentOwner);

		store.putClaim(point); // last-writer-wins = takeover; fires the change listener
		arrivalTiles.add(tk); // one of my fresh claims - excluded from the pre-existing owner
		if (gain && panel != null)
		{
			panel.addLocalClaim(); // tick the community counter for a genuine new/stolen tile only
		}
		log.debug("Claimed {},{} plane {} for {} on world {}",
				wp.getRegionX(), wp.getRegionY(), wp.getPlane(), clanName, world);

		// Tiles/hour tracker: count genuine gains only - a fresh or stolen tile, the same 'gain' rule the
		// community counter uses - so dancing on your own turf doesn't inflate it. Full Slug is a drawing
		// tool, not a run, so it never touches the numbers either (and the tracker is hidden while it's on).
		if (gain && !isSlugPainting())
		{
			sessionClaims++;
			if (firstClaimMs == 0L)
			{
				firstClaimMs = System.currentTimeMillis();
			}
		}
	}

	/** Claims made this session, retakes included (for the tiles/hour tracker overlay). */
	int getSessionTiles()
	{
		return sessionClaims;
	}

	/** Session claim rate in tiles per hour, cached to once a second so the readout doesn't flicker. */
	int getTilesPerHour()
	{
		if (sessionClaims == 0 || firstClaimMs == 0L)
		{
			return 0;
		}
		long now = System.currentTimeMillis();
		long elapsed = now - firstClaimMs;
		if (elapsed < 3000L)
		{
			return cachedRate; // brief warm-up so the first claims don't spike the rate sky-high
		}
		if (now - rateCalcMs >= 1000L)
		{
			// Recompute at most once a second so the on-screen number ticks instead of scrolling.
			cachedRate = (int) Math.round(sessionClaims * 3_600_000.0 / elapsed);
			rateCalcMs = now;
			if (cachedRate > maxRate)
			{
				maxRate = cachedRate;
			}
		}
		return cachedRate;
	}

	/** Highest tiles-per-hour rate seen this session (for the tracker's "Max TPH" line). */
	int getMaxTilesPerHour()
	{
		return maxRate;
	}

	/** Reset the run only: zero Claimed and Current TPH but keep Max TPH ("Reset run" on the tracker). */
	void resetTileRun()
	{
		sessionClaims = 0;
		firstClaimMs = 0L;
		cachedRate = 0;
		rateCalcMs = 0L;
	}

	/** Full wipe including Max TPH, matching a fresh client start ("Reset all" on the tracker). */
	void resetTileAll()
	{
		resetTileRun();
		maxRate = 0;
	}

	/** Tiles the overlay should paint right now (current world). */
	Collection<ClanTurfPoint> getVisibleClaims()
	{
		return visibleClaims;
	}

	/** Claims with allied clans relabeled to their alliance - what the overlay draws, so an alliance's
	 *  territory reads as one team (no internal seams between allied clans, tiles merge with filler). */
	Collection<ClanTurfPoint> getDisplayClaims()
	{
		return aggregatedClaims();
	}

	/** Whether the player is near the GE right now (read by the TPH tracker overlay to fade in/out). */
	boolean isNearGe()
	{
		return nearGeNow;
	}

	/** Snail-trail tiles (world tile -&gt; timestamp) the overlay paints as a fading trail. */
	Map<WorldPoint, Long> getTrail()
	{
		return trail;
	}

	/** How long a snail-trail tile takes to fade out, in ms (the overlay's fade uses this). */
	long getTrailFadeMs()
	{
		return TRAIL_MS;
	}

	/** The snail-trail color: white while erasing (a neutral run), your clan's color when you have one to
	 * paint as, or neutral white for a no-clanner (they get the walking visual but never claim). */
	Color getTrailColor()
	{
		if (isErasing())
		{
			return Color.WHITE;
		}
		String clan = paintClan();
		return clan == null ? Color.WHITE : ClanTurfColors.forClan(clan);
	}

	/** Tiles just erased, for the overlay's white flash, and how long that flash lasts. */
	Map<WorldPoint, Long> getEraseFlash()
	{
		return eraseFlash;
	}

	long getEraseFlashMs()
	{
		return ERASE_FLASH_MS;
	}

	/**
	 * The clan new claims are stamped for. Online it's always your real clan; offline it's the selected
	 * sandbox clan (your real clan, or an added test clan) so you can paint a battle as different sides.
	 */
	private String paintClan()
	{
		if (store == localStore && selectedPaintClan != null)
		{
			return selectedPaintClan;
		}
		return effectiveClanName();
	}

	/** Parse the persisted offline sandbox clan list (comma-separated names). */
	private List<String> sandboxClans()
	{
		List<String> out = new ArrayList<>();
		for (String s : config.sandboxClans().split(","))
		{
			String n = s.trim();
			if (!n.isEmpty())
			{
				out.add(n);
			}
		}
		return out;
	}

	/** Add an offline test clan, then refresh the panel. A null/blank name auto-names it CLAN1, CLAN2,
	 * ... (the Add clan button uses this); the pencil then renames it. Deduped against the list + real clan. */
	void addSandboxClan(String name)
	{
		String real = effectiveClanName();
		List<String> clans = sandboxClans();
		String n = name == null ? "" : name.trim();
		if (n.isEmpty())
		{
			n = nextClanName(clans, real); // auto-name
		}
		if (n.equalsIgnoreCase(real))
		{
			return;
		}
		for (String c : clans)
		{
			if (c.equalsIgnoreCase(n))
			{
				return; // already present
			}
		}
		clans.add(n);
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "sandboxClans", String.join(",", clans));
		pushPaintClans();
	}

	/** The next free "CLAN<n>" name not already used by a test clan or your real clan. */
	private String nextClanName(List<String> existing, String real)
	{
		for (int i = 1; i < 1000; i++)
		{
			String candidate = "CLAN" + i;
			boolean taken = candidate.equalsIgnoreCase(real);
			for (String c : existing)
			{
				if (c.equalsIgnoreCase(candidate))
				{
					taken = true;
					break;
				}
			}
			if (!taken)
			{
				return candidate;
			}
		}
		return "CLAN" + System.currentTimeMillis();
	}

	/** Rename an offline test clan and carry its claimed tiles (this world) over to the new name. Blank,
	 * unchanged, or already-taken names are ignored (the panel just reverts). */
	void renameSandboxClan(String oldName, String newName)
	{
		String nn = newName == null ? "" : newName.trim();
		String real = effectiveClanName();
		List<String> clans = sandboxClans();
		if (oldName == null || nn.isEmpty() || nn.equalsIgnoreCase(oldName) || nn.equalsIgnoreCase(real))
		{
			pushPaintClans();
			return;
		}
		for (String c : clans)
		{
			if (!c.equalsIgnoreCase(oldName) && c.equalsIgnoreCase(nn))
			{
				pushPaintClans(); // name already in use
				return;
			}
		}
		boolean found = false;
		for (int i = 0; i < clans.size(); i++)
		{
			if (clans.get(i).equalsIgnoreCase(oldName))
			{
				clans.set(i, nn);
				found = true;
				break;
			}
		}
		if (!found)
		{
			pushPaintClans();
			return;
		}
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "sandboxClans", String.join(",", clans));
		migrateClaims(oldName, nn);
		if (selectedPaintClan != null && selectedPaintClan.equalsIgnoreCase(oldName))
		{
			selectedPaintClan = nn;
		}
		pushPaintClans();
	}

	/** Re-stamp this world's tiles held by {@code oldName} onto {@code newName}, so a rename keeps the turf. */
	private void migrateClaims(String oldName, String newName)
	{
		int world = client.getWorld();
		List<ClanTurfPoint> moving = new ArrayList<>();
		for (ClanTurfPoint p : store.getClaims(world))
		{
			if (p.getClanName().equalsIgnoreCase(oldName))
			{
				moving.add(p);
			}
		}
		for (ClanTurfPoint p : moving)
		{
			store.removeClaim(p);
			store.putClaim(new ClanTurfPoint(p.getRegionId(), p.getRegionX(), p.getRegionY(),
					p.getZ(), world, newName));
		}
	}

	/** Remove an offline test clan; if it was selected, fall back to painting as your real clan. */
	void removeSandboxClan(String name)
	{
		List<String> clans = sandboxClans();
		clans.removeIf(c -> c.equalsIgnoreCase(name));
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "sandboxClans", String.join(",", clans));
		if (selectedPaintClan != null && selectedPaintClan.equalsIgnoreCase(name))
		{
			selectedPaintClan = null;
		}
		pushPaintClans();
	}

	/** Pick which clan your steps paint as. Selecting your real clan clears the override. */
	void selectPaintClan(String name)
	{
		String real = effectiveClanName();
		selectedPaintClan = (name == null || name.equalsIgnoreCase(real)) ? null : name;
		pushPaintClans();
	}

	/** Push the current paint-as roster (your real clan first, then test clans) and selection to the panel. */
	private void pushPaintClans()
	{
		String real = effectiveClanName();
		if (real == null || real.isEmpty())
		{
			real = config.lastClan(); // show your clan right away, before the channel finishes loading
		}
		List<String> roster = new ArrayList<>();
		if (real != null && !real.isEmpty())
		{
			roster.add(real);
		}
		roster.addAll(sandboxClans());
		String selected = paintClan();
		if (selected == null)
		{
			selected = real; // default: your clan highlighted
		}
		panel.setPaintClans(roster, real, selected);
	}

	/** Start the tile dissolve (daily reset or the Clear button); the overlay watches this stamp. */
	void triggerTileDissolve()
	{
		dissolveFlashMs = System.currentTimeMillis();
	}

	/** Epoch ms of the last dissolve trigger, so the overlay knows when to start fading tiles out. */
	long getDissolveFlashMs()
	{
		return dissolveFlashMs;
	}

	/**
	 * The battles list for the panel: the server's active-worlds list plus the world you're
	 * currently on (synthesized from local claims), so your own turf always shows - even right
	 * after a login before the server has logged fresh activity there. The current world is put
	 * first and de-duplicated against the server list.
	 */
	private List<ClanTurfBattle> battlesForPanel()
	{
		List<ClanTurfBattle> list = new java.util.ArrayList<>(store.getBattles());
		int world = client.getWorld();
		List<ClanTurfPoint> claims = aggregatedClaims(); // allied clans count as one team here too
		if (world > 0 && claims != null && !claims.isEmpty())
		{
			Map<String, Integer> counts = new HashMap<>();
			int total = 0;
			for (ClanTurfPoint p : claims)
			{
				counts.merge(p.getClanName(), 1, Integer::sum);
				total++;
			}
			// Owner: the sticky/committed leader if it still holds tiles here, else the top count.
			String owner = committedLeader;
			int ownerTiles = owner != null ? counts.getOrDefault(owner, 0) : 0;
			if (ownerTiles == 0)
			{
				owner = null;
				for (Map.Entry<String, Integer> e : counts.entrySet())
				{
					if (e.getValue() > ownerTiles)
					{
						ownerTiles = e.getValue();
						owner = e.getKey();
					}
				}
			}
			// Runner-up with the same sticky-on-tie rule as the leader and the server: keep the
			// incumbent until another non-owner clan strictly passes it. Reset on a world change.
			if (world != synthRunnerUpWorld)
			{
				synthRunnerUpWorld = world;
				synthRunnerUp = null;
			}
			String runnerUp = null;
			int runnerUpTiles = 0;
			if (synthRunnerUp != null && !synthRunnerUp.equals(owner))
			{
				runnerUp = synthRunnerUp;
				runnerUpTiles = counts.getOrDefault(synthRunnerUp, 0);
			}
			for (Map.Entry<String, Integer> e : counts.entrySet())
			{
				if (owner != null && owner.equals(e.getKey()))
				{
					continue;
				}
				if (e.getValue() > runnerUpTiles)
				{
					runnerUpTiles = e.getValue();
					runnerUp = e.getKey();
				}
			}
			if (runnerUpTiles == 0)
			{
				runnerUp = null;
			}
			synthRunnerUp = runnerUp;
			list.removeIf(b -> b.getWorld() == world);
			list.add(0, new ClanTurfBattle(world, owner, ownerTiles, total, runnerUp, runnerUpTiles));
		}
		// Hierarchy: your current world on top, then worlds your clan holds, then rivals. Stable sort,
		// so each group keeps the server's tile-count order underneath.
		String myClan = effectiveClanName();
		list.sort((a, b) -> Integer.compare(battleRank(a, world, myClan), battleRank(b, world, myClan)));
		return list;
	}

	/** Active-battles ordering: 0 = your current world, 1 = a world your clan owns, 2 = a rival's. */
	private static int battleRank(ClanTurfBattle b, int currentWorld, String myClan)
	{
		if (b.getWorld() == currentWorld)
		{
			return 0;
		}
		if (myClan != null && myClan.equalsIgnoreCase(b.getOwner()))
		{
			return 1;
		}
		return 2;
	}

	/** The clan tiles are stamped for: our clan channel, or null if we're not in a clan. */
	private String effectiveClanName()
	{
		ClanChannel channel = client.getClanChannel();
		if (channel != null && channel.getName() != null && !channel.getName().isEmpty())
		{
			return channel.getName();
		}
		// The clan channel can read empty for a stretch after login - notably the first login of the day -
		// even while clan chat already works. Rather than treat a known member as clan-less (blocking
		// claims and flashing "join a clan" over the connecting message), fall back to the clan we last
		// saw them in. The live channel takes over the instant it loads, so a clan change still updates
		// within a tick. Only a player we have never seen in any clan (no saved name) is treated as
		// clan-less and shown the hint.
		String last = config.lastClan();
		return (last == null || last.trim().isEmpty()) ? null : last;
	}

	/** True once we're confident the player really is clan-less: the clan channel has had time to load
	 * after login/hop, so an empty clan is genuine rather than not-yet-loaded. Gates the panel hint. */
	private boolean clanHintDue()
	{
		return clanlessSinceMs != 0 && System.currentTimeMillis() - clanlessSinceMs > CLAN_GRACE_MS;
	}

	/** Prints the update changelog once, on the first tick after login, if it hasn't been shown yet. */
	private void maybeShowUpdate()
	{
		if (!showUpdateNextTick)
		{
			return;
		}
		showUpdateNextTick = false;
		if (!shouldShowUpdate())
		{
			return;
		}
		updateShownThisLogin = true;
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", updateMessage(), null);
		if (!ALWAYS_SHOW_UPDATE)
		{
			configManager.setConfiguration(ConfigClanTurfStore.GROUP, "lastUpdateSeen", UPDATE_ID);
		}
	}

	/** True when this update's changelog has not been shown yet and update messages are enabled. */
	private boolean shouldShowUpdate()
	{
		if (!config.showUpdateMessage())
		{
			return false;
		}
		return ALWAYS_SHOW_UPDATE || !UPDATE_ID.equals(config.lastUpdateSeen());
	}

	/** The whole changelog as one gold, multi-line chat message; only the [Update] label is white. */
	private String updateMessage()
	{
		String label = UPDATE_LABEL;
		if (label == null)
		{
			String v = getClass().getPackage().getImplementationVersion();
			label = (v == null || v.isEmpty()) ? "[Update]" : "v" + v;
		}
		String gold = "<col=ff981f>";
		String white = "<col=ffffff>";
		StringBuilder sb = new StringBuilder(gold).append("Clan Turf ").append(white).append(label);
		for (String line : UPDATE_LINES)
		{
			sb.append("<br>").append(gold).append("* ").append(line);
		}
		return sb.toString();
	}

	/**
	 * Applies the "Clan color list" overrides (any clan, including your own), local only. Clears
	 * whatever it applied last time first (so removed entries revert to their auto color), then
	 * re-registers the current list. Gated by the "Custom clan colors" toggle. Everything paints
	 * through {@link ClanTurfColors#forClan}, so these recolor tiles, outline, boundary, minimap and
	 * panel at once.
	 */
	private void applyWhitelist()
	{
		for (String c : whitelistApplied)
		{
			ClanTurfColors.removeOverride(c);
		}
		for (String c : allianceApplied)
		{
			ClanTurfColors.removeOverride(c);
		}
		whitelistApplied.clear();
		allianceApplied.clear();
		// Alliance colors first, as the team default: allied clans and the alliance's scoreboard label
		// all share one color.
		lastAllianceColors = store.allianceColors();
		lastAllianceNames = store.allianceNames();
		for (Map.Entry<String, String> e : lastAllianceColors.entrySet())
		{
			Color col = hexColor(e.getValue());
			if (col != null)
			{
				ClanTurfColors.setOverride(e.getKey(), col);
				allianceApplied.add(e.getKey().toLowerCase());
				String disp = displayClan(e.getKey());
				if (disp != null && !disp.equalsIgnoreCase(e.getKey()))
				{
					ClanTurfColors.setOverride(disp, col);
					allianceApplied.add(disp.toLowerCase());
				}
			}
		}
		// Then your own custom list on top, so clicking a bar to pick a local color beats even the
		// alliance color for your own view. Offline, the sandbox list layers on too.
		if (config.customClanColor())
		{
			applyColorList(config.clanColorWhitelist());
			if (store == localStore)
			{
				applyColorList(config.offlineClanColors());
			}
		}
		// Rebuild the paint-as roster so its label colors track the new overrides right away, instead of
		// only refreshing the next time a row is clicked.
		if (panel != null)
		{
			pushPaintClans();
		}
	}

	/** Parse a 6-hex RRGGBB string into an opaque Color, or null if malformed. */
	private static Color hexColor(String hex)
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

	/** True when both clans are in the same alliance, so we leave each other's tiles alone. */
	private boolean sameAlliance(String a, String b)
	{
		String ida = store.allianceIdOf(a);
		return ida != null && ida.equals(store.allianceIdOf(b));
	}

	/**
	 * True if the local player is at least Administrator in their clan, so alliance setup/management is
	 * done by clan leadership (Owner, Deputy Owner, Administrator - rank >= 100). Everyone below just
	 * inherits the alliance automatically. Client-side gate: the server never sees ranks, so it's a
	 * leadership convenience, not a hard lock.
	 */
	private boolean canManageAlliance()
	{
		if (config.allianceIgnoreRank())
		{
			return true; // testing toggle (Extras); overrides the rank gate - hide/remove before release
		}
		ClanChannel channel = client.getClanChannel();
		Player local = client.getLocalPlayer();
		if (channel == null || local == null || local.getName() == null)
		{
			return false;
		}
		String me = Text.sanitize(local.getName());
		for (ClanChannelMember m : channel.getMembers())
		{
			if (m != null && m.getName() != null && Text.sanitize(m.getName()).equalsIgnoreCase(me))
			{
				return m.getRank() != null
						&& m.getRank().getRank() >= ClanRank.ADMINISTRATOR.getRank();
			}
		}
		return false;
	}

	/** True if {@code clan} is the owner (creating) clan of its alliance - its Admin+ manage color/disband. */
	private boolean isOwnerClan(String clan)
	{
		if (clan == null)
		{
			return false;
		}
		String owner = store.allianceOwnerClanOf(clan);
		return owner != null && owner.equalsIgnoreCase(clan);
	}

	/** The scoreboard label for a clan: its alliance's name (or id) if it's allied, else the clan itself. */
	private String displayClan(String clan)
	{
		if (clan == null)
		{
			return null;
		}
		String aid = store.allianceIdOf(clan);
		if (aid == null)
		{
			return clan;
		}
		String name = store.allianceNameOf(clan);
		return (name == null || name.isEmpty()) ? aid : name;
	}

	/**
	 * The claim set relabeled so allied clans collapse under a single alliance name - used only for the
	 * scoreboard and the leader/headline, so every viewer sees the alliance as one team. The overlay,
	 * takeover barks, and claim logic keep the real per-clan names. Returns the original list unchanged
	 * when no alliances exist, so non-alliance play is byte-for-byte the same.
	 */
	private java.util.List<ClanTurfPoint> aggregatedClaims()
	{
		if (store.allianceColors().isEmpty())
		{
			return visibleClaims;
		}
		java.util.List<ClanTurfPoint> out = new java.util.ArrayList<>(visibleClaims.size());
		for (ClanTurfPoint p : visibleClaims)
		{
			String disp = displayClan(p.getClanName());
			if (disp != null && !disp.equals(p.getClanName()))
			{
				out.add(new ClanTurfPoint(p.getRegionId(), p.getRegionX(), p.getRegionY(),
						p.getZ(), p.getWorld(), disp));
			}
			else
			{
				out.add(p);
			}
		}
		return out;
	}

	/**
	 * Tiles each real clan holds in the current world, keyed by lower-cased clan name. Built from the
	 * un-aggregated claims (real per-clan names), so the panel can break an alliance's combined bar down
	 * per member clan in the expanded drawer. Keys are lower-cased so they match server roster names.
	 */
	private java.util.Map<String, Long> perClanTileCounts()
	{
		java.util.Map<String, Long> m = new java.util.HashMap<>();
		for (ClanTurfPoint p : visibleClaims)
		{
			String clan = p.getClanName();
			if (clan != null)
			{
				m.merge(clan.toLowerCase(java.util.Locale.ROOT), 1L, Long::sum);
			}
		}
		return m;
	}

	// ---- alliance actions from the panel; the network runs off the EDT ----

	private void createAlliance(String[] args)
	{
		if (store != serverStore)
		{
			panel.setAllianceStatus("Go online to use alliances.");
			return;
		}
		String clan = effectiveClanName();
		if (clan == null)
		{
			panel.setAllianceStatus("You need to be in a clan first.");
			return;
		}
		if (!canManageAlliance())
		{
			panel.setAllianceStatus("Only your clan's Owner / Deputy / Admin can manage alliances.");
			return;
		}
		String name = args.length > 0 ? args[0] : "";
		String colorHex = args.length > 1 ? args[1] : "";
		String passcode = args.length > 2 ? args[2] : "";
		int icon = 0;
		if (args.length > 3)
		{
			try
			{
				icon = Integer.parseInt(args[3]);
			}
			catch (NumberFormatException ignored)
			{
				// server defaults to Skull when the icon is 0/out of range
			}
		}
		final int createIcon = icon;
		panel.setAllianceStatus("Creating…");
		executor.execute(() ->
		{
			String resp = serverStore.allianceCreate(clan, name, colorHex,
					passcode == null || passcode.isEmpty() ? null : passcode, createIcon);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (resp != null && resp.startsWith("ok,"))
				{
					String[] f = resp.trim().split(",");
					if (f.length >= 4)
					{
						configManager.setConfiguration(ConfigClanTurfStore.GROUP,
								"allianceOwnerToken", f[3]);
					}
					panel.setAllianceStatus("Alliance created. Passcode: "
							+ (f.length >= 3 ? f[2] : "?"));
					serverStore.refreshAlliancesSoon();
				}
				else
				{
					panel.setAllianceStatus("Couldn't create: " + shortErr(resp));
				}
			});
		});
	}

	private void joinAlliance(String passcode)
	{
		if (store != serverStore)
		{
			panel.setAllianceStatus("Go online to use alliances.");
			return;
		}
		String clan = effectiveClanName();
		if (clan == null)
		{
			panel.setAllianceStatus("You need to be in a clan first.");
			return;
		}
		if (!canManageAlliance())
		{
			panel.setAllianceStatus("Only your clan's Owner / Deputy / Admin can manage alliances.");
			return;
		}
		panel.setAllianceStatus("Joining…");
		executor.execute(() ->
		{
			String resp = serverStore.allianceJoin(clan, passcode);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (resp != null && resp.startsWith("ok,"))
				{
					// Joiners aren't the owner, so drop any stale owner token we might have held.
					configManager.unsetConfiguration(ConfigClanTurfStore.GROUP, "allianceOwnerToken");
					panel.setAllianceStatus("Joined the alliance!");
					serverStore.refreshAlliancesSoon();
				}
				else
				{
					panel.setAllianceStatus("Couldn't join: " + shortErr(resp));
				}
			});
		});
	}

	private void leaveAlliance()
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		if (clan == null)
		{
			return;
		}
		if (!canManageAlliance())
		{
			panel.setAllianceStatus("Only your clan's Owner / Deputy / Admin can manage alliances.");
			return;
		}
		String aid = store.allianceIdOf(clan);
		if (aid == null)
		{
			return;
		}
		boolean owner = isOwnerClan(clan);
		panel.setAllianceStatus(owner ? "Disbanding…" : "Leaving…");
		executor.execute(() ->
		{
			if (owner)
			{
				serverStore.allianceDisband(aid, clan);
			}
			else
			{
				serverStore.allianceLeave(clan);
			}
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				configManager.unsetConfiguration(ConfigClanTurfStore.GROUP, "allianceOwnerToken");
				panel.setAllianceStatus(owner ? "Alliance disbanded." : "Left the alliance.");
				serverStore.refreshAlliancesSoon();
			});
		});
	}

	/** Owner-only: recolor the alliance (needs our stored owner token). Joiners have no token, so the
	 *  panel never shows them the button, and the server rejects it anyway. */
	private void changeAllianceColor(String hex)
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		String aid = clan == null ? null : store.allianceIdOf(clan);
		if (aid == null || !isOwnerClan(clan) || !canManageAlliance())
		{
			panel.setAllianceStatus("Only the owner clan's Admin+ can change the color.");
			return;
		}
		panel.setAllianceStatus("Updating color…");
		executor.execute(() ->
		{
			String resp = serverStore.allianceSetColor(aid, clan, hex);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (resp != null && resp.startsWith("ok"))
				{
					panel.setAllianceStatus("Color updated.");
					serverStore.refreshAlliancesSoon();
				}
				else
				{
					panel.setAllianceStatus("Couldn't change color: " + shortErr(resp));
				}
			});
		});
	}

	private static String shortErr(String resp)
	{
		if (resp == null)
		{
			return "no response";
		}
		String s = resp.trim();
		return s.isEmpty() ? "error" : s;
	}

	/** Owner clan staff: kick an allied clan and block it from rejoining. */
	private void kickAllianceClan(String target)
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		String aid = clan == null ? null : store.allianceIdOf(clan);
		if (aid == null || target == null || !isOwnerClan(clan) || !canManageAlliance())
		{
			return;
		}
		panel.setAllianceStatus("Removing " + target + "...");
		executor.execute(() ->
		{
			String resp = serverStore.allianceKick(aid, clan, target);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				panel.setAllianceStatus(resp != null && resp.startsWith("ok") ? " "
						: "Couldn't remove: " + shortErr(resp));
				lastOwnerInfoMs = 0; // force a fresh owner-info fetch so the blocked list updates
				serverStore.refreshAlliancesSoon();
			});
		});
	}

	/** Owner clan staff: set the alliance symbol (a clan-motif sprite id, 3024-3050). */
	private void changeAllianceIcon(int icon)
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		String aid = clan == null ? null : store.allianceIdOf(clan);
		if (aid == null || !isOwnerClan(clan) || !canManageAlliance())
		{
			return;
		}
		panel.setAllianceStatus("Changing icon...");
		executor.execute(() ->
		{
			String resp = serverStore.allianceSetIcon(aid, clan, icon);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (resp != null && resp.startsWith("ok"))
				{
					panel.setAllianceStatus("Icon changed.");
					panel.setAllianceIcon(icon); // show it now, don't wait on the throttled poll
					serverStore.refreshAlliancesSoon();
				}
				else
				{
					panel.setAllianceStatus("Couldn't change icon: " + shortErr(resp));
				}
			});
		});
	}

	/** Owner clan staff: rename the alliance (the server runs the name filter + a one-per-week cap). */
	private void changeAllianceName(String newName)
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		String aid = clan == null ? null : store.allianceIdOf(clan);
		if (aid == null || newName == null || newName.trim().isEmpty()
				|| !isOwnerClan(clan) || !canManageAlliance())
		{
			return;
		}
		panel.setAllianceStatus("Renaming...");
		executor.execute(() ->
		{
			String resp = serverStore.allianceSetName(aid, clan, newName.trim());
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (resp != null && resp.startsWith("ok"))
				{
					panel.setAllianceStatus("Name changed.");
					serverStore.refreshAlliancesSoon(); // pull the new name in quickly
				}
				else
				{
					panel.setAllianceStatus("Couldn't rename: " + shortErr(resp));
				}
			});
		});
	}

	/** Owner clan staff: change the alliance passcode (the old code must match). */
	private void changeAlliancePasscode(String oldPass, String newPass)
	{
		if (store != serverStore)
		{
			return;
		}
		String clan = effectiveClanName();
		String aid = clan == null ? null : store.allianceIdOf(clan);
		if (aid == null || !isOwnerClan(clan) || !canManageAlliance())
		{
			return;
		}
		panel.setAllianceStatus("Changing passcode...");
		executor.execute(() ->
		{
			String resp = serverStore.allianceSetPasscode(aid, clan, oldPass, newPass);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (resp != null && resp.startsWith("ok"))
				{
					panel.setAllianceStatus("Passcode changed.");
					cachedPasscode = newPass;
					panel.setAlliancePasscode(newPass); // show it now, don't wait on the throttled refetch
					lastOwnerInfoMs = 0;                // refetch later to reconcile with the server
				}
				else
				{
					panel.setAllianceStatus("Couldn't change passcode: " + shortErr(resp));
				}
			});
		});
	}

	/** Push the current alliance state (store + our clan) into the panel section each refresh. */
	private void updateAlliancePanel()
	{
		if (panel == null)
		{
			return;
		}
		boolean online = store == serverStore;
		String clan = effectiveClanName();
		String aid = (online && clan != null) ? store.allianceIdOf(clan) : null;
		boolean canManage = online && canManageAlliance();
		if (aid == null)
		{
			clearOwnerInfo();
			panel.setAlliance(online, false, canManage, null, null, false,
					java.util.Collections.emptyList(), java.util.Collections.emptyList());
			panel.setAlliancePasscode(null);
			panel.setAllianceIcon(0);
			return;
		}
		java.util.List<String> members = new java.util.ArrayList<>(store.alliesOf(clan));
		java.util.Collections.sort(members);
		String colorHex = store.allianceColors().get(clan.toLowerCase());
		String name = store.allianceNameOf(clan);
		boolean isOwnerClan = isOwnerClan(clan);
		// Members you (the owner clan's staff) can kick: everyone but your own clan. Joiners get an empty
		// list, so no kick controls appear for them.
		java.util.List<String> kickable = java.util.Collections.emptyList();
		if (isOwnerClan && canManage)
		{
			kickable = new java.util.ArrayList<>(members);
			kickable.removeIf(m -> m.equalsIgnoreCase(clan));
		}
		panel.setAlliance(true, true, canManage, name, colorHex, isOwnerClan, members, kickable);
		int icon = store.allianceIconOf(clan);
		panel.setAllianceIcon(icon <= 0 ? 3024 : icon); // fall back to the Skull default
		// Owner clan's staff can see + copy the passcode to re-share it; joiners never see it.
		if (isOwnerClan && canManage)
		{
			refreshOwnerInfo(aid, clan);
			boolean fresh = aid.equals(cachedPasscodeAid);
			panel.setAlliancePasscode(fresh ? cachedPasscode : null);
		}
		else
		{
			clearOwnerInfo();
			panel.setAlliancePasscode(null);
		}
	}

	private void clearOwnerInfo()
	{
		cachedPasscode = null;
		cachedPasscodeAid = null;
		cachedBlacklist = java.util.Collections.emptyList();
		lastOwnerInfoMs = 0;
	}

	/** Owner clan only: (re)fetch the passcode + blacklist off the EDT, throttled to every 20s so a
	 *  passcode change by another admin propagates without hammering the server. */
	private void refreshOwnerInfo(String aid, String clan)
	{
		long now = System.currentTimeMillis();
		boolean sameAlliance = aid.equals(cachedPasscodeAid);
		if (sameAlliance && now - lastOwnerInfoMs < 20000)
		{
			return; // fetched recently; the throttle window catches any change soon enough
		}
		if (!sameAlliance)
		{
			cachedPasscode = null; // different alliance - drop the stale code until the new one loads
			cachedBlacklist = java.util.Collections.emptyList();
		}
		cachedPasscodeAid = aid;
		lastOwnerInfoMs = now;
		executor.execute(() ->
		{
			HttpClanTurfStore.OwnerInfo info = serverStore.allianceOwnerInfo(aid, clan);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (info == null || !aid.equals(cachedPasscodeAid))
				{
					return; // failed, or we left/switched alliances mid-request
				}
				cachedPasscode = info.passcode;
				cachedBlacklist = info.blacklist;
				panel.setAlliancePasscode(info.passcode);
				updateBlacklistConfig();
			});
		});
	}

	/** Mirror the blocked-clan list into the config string so it shows in the Blocked allies settings. */
	private void updateBlacklistConfig()
	{
		String joined = String.join(", ", cachedBlacklist);
		if (!joined.equals(config.blockedAllies()))
		{
			configManager.setConfiguration(ConfigClanTurfStore.GROUP, "blockedAllies", joined);
		}
	}

	/** Register each ClanName=RRGGBB entry from a list as a local override, tracked for later removal. */
	private void applyColorList(String raw)
	{
		for (Map.Entry<String, Color> e : parseWhitelist(raw).entrySet())
		{
			ClanTurfColors.setOverride(e.getKey(), e.getValue());
			whitelistApplied.add(e.getKey().toLowerCase());
		}
	}

	/** Parse "ClanName=RRGGBB,Other=00FF00" into clan -&gt; color; bad entries are skipped. */
	private static Map<String, Color> parseWhitelist(String raw)
	{
		Map<String, Color> out = new LinkedHashMap<>();
		if (raw == null || raw.trim().isEmpty())
		{
			return out;
		}
		for (String part : raw.split(","))
		{
			int eq = part.lastIndexOf('=');
			if (eq <= 0)
			{
				continue;
			}
			String name = part.substring(0, eq).trim();
			String hex = part.substring(eq + 1).trim().replace("#", "");
			if (name.isEmpty() || hex.length() != 6)
			{
				continue;
			}
			try
			{
				out.put(name, new Color(Integer.parseInt(hex, 16)));
			}
			catch (NumberFormatException ignored)
			{
				// skip a malformed hex value
			}
		}
		return out;
	}

	/** Insert or replace one clan's color in the list string (case-insensitive on the name). */
	private static String upsertWhitelist(String raw, String clan, Color color)
	{
		Map<String, Color> map = parseWhitelist(raw);
		map.entrySet().removeIf(e -> e.getKey().equalsIgnoreCase(clan));
		map.put(clan, color);
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Color> e : map.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			Color c = e.getValue();
			sb.append(e.getKey()).append('=')
					.append(String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
		}
		return sb.toString();
	}

	/**
	 * A color chosen from the side-panel color wheel for {@code clan} (any clan, including your own) is
	 * upserted into the shared "Clan color list" and the "Custom clan colors" master toggle is switched
	 * on so it takes effect. The config writes fan back through {@link #onConfigChanged}, which re-applies
	 * the overrides and repaints. Runs on the Swing EDT; config writes are thread-safe.
	 */
	void onClanColorChosen(String clan, Color color)
	{
		if (clan == null || color == null)
		{
			return;
		}
		configManager.setConfiguration(ConfigClanTurfStore.GROUP, "customClanColor", true);
		// Offline, colors go to the sandbox's own list so test clans don't pollute the shareable one.
		if (store == localStore)
		{
			configManager.setConfiguration(ConfigClanTurfStore.GROUP, "offlineClanColors",
					upsertWhitelist(config.offlineClanColors(), clan, color));
		}
		else
		{
			configManager.setConfiguration(ConfigClanTurfStore.GROUP, "clanColorWhitelist",
					upsertWhitelist(config.clanColorWhitelist(), clan, color));
		}
		// Apply the new color and rebuild the paint-as roster now, so its labels recolor immediately
		// instead of waiting for the next select (the config-change path also refreshes, harmlessly).
		applyWhitelist();
		pushPaintClans();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		handleClanCommand(event);
	}

	/**
	 * Turns a {@code !defend<world>} / {@code !invade<world>} typed in clan chat into a formatted
	 * Clan Turf call. The human types the command (that IS the real clan message everyone gets);
	 * every plugin user's client then detects it, validates it against the shared battles board,
	 * and replaces the text in place with the {@code [CT]} announcement - kept attributed to
	 * whoever typed it and staying in the clan tab. A plugin can't send clan messages itself, so
	 * this read-and-replace is the correct, ToS-safe approach.
	 */
	private void handleClanCommand(ChatMessage event)
	{
		if (!config.clanChatCommands() || event.getType() != ChatMessageType.CLAN_CHAT)
		{
			return;
		}
		Matcher m = CT_COMMAND.matcher(Text.removeTags(event.getMessage()));
		if (!m.matches())
		{
			return;
		}
		boolean defend = m.group(1).toLowerCase(java.util.Locale.ROOT).startsWith("def");
		int world;
		try
		{
			world = Integer.parseInt(m.group(2));
		}
		catch (NumberFormatException e)
		{
			return;
		}

		// Validate against the shared battles board + our own clan, same source the panel uses.
		String myClan = effectiveClanName();
		ClanTurfBattle battle = findBattle(world);
		boolean owned = battle != null && myClan != null
				&& battle.getOwner() != null && battle.getOwner().equalsIgnoreCase(myClan);
		boolean valid = defend ? owned : (battle != null && !owned);

		if (!valid)
		{
			// Only the person who typed it gets a quiet reason; everyone else just sees their raw text.
			if (senderIsLocalPlayer(event))
			{
				String why = defend
						? "your clan doesn't hold World " + world + "."
						: (battle == null ? "World " + world + " isn't on the battles board."
								: "your clan already holds World " + world + ".");
				announceClan(CT_TAG + " " + why);
			}
			return;
		}

		// Colour follows the clan the call is about: your clan for a defend (your world), the current
		// owner's colour for an invade (their world). [CT] and the world both use it; rest is white.
		String clanCol = "<col=" + hex(ClanTurfColors.forClan(defend ? myClan : battle.getOwner())) + ">";
		String call = defend
				? clanCol + "[CT]" + RESET + " " + WHITE + "Report to the Grand Exchange in " + RESET
						+ clanCol + "World " + world + RESET + WHITE + " to defend!" + RESET
				: clanCol + "[CT]" + RESET + " " + WHITE + "Invade " + RESET
						+ clanCol + "World " + world + RESET + WHITE + "!" + RESET;
		event.getMessageNode().setValue(call);
		client.refreshChat();
	}

	/**
	 * The current world's GE owner for the world-map overlay, read from the always-on battles board so
	 * it works anywhere (not only at the GE). Falls back to the committed leader when we're at the GE
	 * and the battles feed hasn't caught up yet.
	 */
	String getWorldMapOwner()
	{
		ClanTurfBattle b = findBattle(client.getWorld());
		if (b != null && b.getOwner() != null)
		{
			return b.getOwner();
		}
		return committedLeader;
	}

	/** The GE owner's alliance symbol sprite id (0 if the owner isn't an alliance) - for the world map. */
	int getWorldMapOwnerIcon()
	{
		return store.allianceIconByDisplay(getWorldMapOwner());
	}

	/** The battle for a given world from the shared board, or null if it isn't contested. */
	private ClanTurfBattle findBattle(int world)
	{
		for (ClanTurfBattle b : store.getBattles())
		{
			if (b.getWorld() == world)
			{
				return b;
			}
		}
		return null;
	}

	private boolean senderIsLocalPlayer(ChatMessage event)
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return false;
		}
		return Text.sanitize(event.getName()).equalsIgnoreCase(Text.sanitize(local.getName()));
	}

	/** Posts a Clan Turf line into the clan chat tab (a clan system message) so all plugin chatter
	 * lives in one place. Renders only for players who are in a clan. */
	private void announceClan(String message)
	{
		client.addChatMessage(ChatMessageType.CLAN_MESSAGE, "", message, null);
	}

	/** Milliseconds until the next daily turf reset (fixed UTC hour, matching the server). Read by
	 * the on-screen countdown overlay, which shows everywhere in the final minutes. */
	long msUntilReset()
	{
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		ZonedDateTime next = now.withHour(RESET_HOUR_UTC).withMinute(0).withSecond(0).withNano(0);
		if (!next.isAfter(now))
		{
			next = next.plusDays(1);
		}
		return Duration.between(now, next).toMillis();
	}

	/**
	 * Warns in the clan tab as the daily reset approaches, but only while at the GE (like the other
	 * announcements). Each threshold is consumed by time passing whether or not you're present, so
	 * arriving late doesn't dump every past warning at once; a message only shows if you're at the
	 * GE the moment the threshold is crossed.
	 */
	private void updateResetPings(boolean nearGe)
	{
		if (!config.resetCountdown())
		{
			return;
		}
		long ms = msUntilReset();
		if (ms > RESET_PING_CLEAR_MS)
		{
			prevResetMs = -1L; // outside the window -> re-baseline for the next day
			return;
		}
		if (prevResetMs < 0L)
		{
			// First tick this cycle (login or just entered the window): baseline only, so a
			// threshold already passed before we started watching doesn't fire retroactively.
			prevResetMs = ms;
		}
		for (int t : RESET_PING_MINUTES)
		{
			long mark = t * 60_000L;
			if (prevResetMs > mark && ms <= mark && nearGe)
			{
				announceClan(CT_TAG + " " + WHITE + "Turf resets in " + t
						+ (t == 1 ? " minute" : " minutes") + "!" + RESET);
			}
		}
		prevResetMs = ms;
	}

	/** RRGGBB hex for a colour, for chat {@code <col=...>} tags. */
	private static String hex(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}


	private void refreshClaims()
	{
		int world = client.getWorld();
		visibleClaims = new java.util.ArrayList<>(store.getClaims(world));
		if (panel == null)
		{
			return;
		}
		// If the alliance set changed since the last paint (e.g. a disband/leave just cleared it), re-apply
		// the team colors NOW, before painting. Otherwise the scoreboard shows a just-freed clan in its old
		// alliance color for one frame until onGameTick catches up (the "flash back to purple").
		if (!store.allianceColors().equals(lastAllianceColors)
				|| !store.allianceNames().equals(lastAllianceNames))
		{
			applyWhitelist();
			leaderInit = false;
		}
		updateAlliancePanel();
		// Before login there's no world and no reason to mention the server: startUp() calls this at
		// the login screen, where "Connecting to the sync server..." is nonsense. Show a plain waiting
		// message until we're actually in-game; the connection wording only kicks in once logged in.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.showEmpty("Waiting for the client…");
			panel.updateBattles(Collections.emptyList(), effectiveClanName(), -1);
			return;
		}
		panel.update(aggregatedClaims(), world, GrandExchangeArea.totalTiles(), committedLeader,
				displayClan(effectiveClanName()), findBattle(world), store.connectionStatus(), clanHintDue(),
				perClanTileCounts());
		panel.updateBattles(battlesForPanel(), displayClan(effectiveClanName()), client.getWorld());
		panel.setGlobalClaims(store.getGlobalClaims());
	}

	/** Committed (debounced) leading clan, for the boundary's resting color. Null = none. */
	String getBoundaryLeader()
	{
		return committedLeader;
	}

	long getAnimStartMs()
	{
		return animStartMs;
	}

	Color getAnimFrom()
	{
		return animFrom;
	}

	Color getAnimTo()
	{
		return animTo;
	}

	/**
	 * Recomputes the leader from the current claims and commits a change only after a challenger
	 * has held the lead for the confirm delay. A commit fires the boundary animation and (if
	 * enabled) the chat announcement, so a see-sawing lead spams neither.
	 */
	private void updateLeader()
	{
		String instant = stickyLeader(aggregatedClaims(), committedLeader);
		long now = System.currentTimeMillis();

		if (!leaderInit)
		{
			// New arrival (world hop, login, or GE re-entry): baseline the boundary to the PRE-EXISTING
			// owner - the snapshot minus my own just-made claims - and start a short settle window. A
			// rival's turf streaming in then doesn't fire a bogus takeover, but a genuinely empty GE
			// stays "unowned", so my first claim there reads as a real takeover.
			arrivalTiles.clear();
			committedLeader = preExistingLeader();
			pendingLeader = null;
			leaderInit = true;
			settling = true;
			settleUntil = now + (store == serverStore ? SETTLE_MS : 0);
			return;
		}

		if (now < settleUntil)
		{
			// Still settling: keep tracking the pre-existing owner (excludes my fresh claims), so the
			// snapshot finishing loading can't be mistaken for a takeover.
			committedLeader = preExistingLeader();
			pendingLeader = null;
			return;
		}

		if (settling)
		{
			// Settle just ended; committedLeader is the pre-existing owner. If my claims have since made
			// me the leader - I took a genuinely empty GE, or out-tiled the old owner within the window -
			// that's a real takeover, so fire it now (the settle window already served as the hold).
			settling = false;
			if (!Objects.equals(instant, committedLeader))
			{
				String previous = committedLeader;
				committedLeader = instant;
				pendingLeader = null;
				onTakeover(previous, committedLeader);
			}
			return;
		}

		if (Objects.equals(instant, committedLeader))
		{
			pendingLeader = null; // no standing challenge
			return;
		}

		if (!Objects.equals(instant, pendingLeader))
		{
			pendingLeader = instant;
			pendingSince = now;
		}
		else if (now - pendingSince >= config.takeoverConfirmMs())
		{
			String previous = committedLeader;
			committedLeader = instant;
			pendingLeader = null;
			onTakeover(previous, committedLeader);
		}
	}

	/** Fires the boundary animation and the chat announcement for a confirmed takeover. */
	private void onTakeover(String previous, String newLeader)
	{
		animFrom = previous == null ? Color.WHITE : ClanTurfColors.forClan(previous);
		animTo = newLeader == null ? Color.WHITE : ClanTurfColors.forClan(newLeader);
		animStartMs = System.currentTimeMillis();

		if (newLeader != null)
		{
			announceTakeover(newLeader);
			fireTakeoverSound();
			fireTakeoverBarks(newLeader);
		}
		log.debug("Takeover: {} -> {}", previous, newLeader);
	}

	/**
	 * Overhead "chorus": on a confirmed takeover the crowd of Grand Exchange NPCs reacts with random
	 * barks naming the new owner. Purely visual (RuneLite overhead text, decays on its own) - nothing
	 * is posted to chat. Every NPC near the GE speaks, except the dense clerk cluster in the middle,
	 * which fires every other one so it doesn't become a solid wall of bubbles. One-shot on the event;
	 * never looped. Runs on the client thread (called from the GameTick takeover path).
	 */
	private void fireTakeoverBarks(String newLeader)
	{
		if (newLeader == null || !config.npcBarks())
		{
			return;
		}
		long now = System.currentTimeMillis();
		// Supersede the current chorus by fading it out quickly rather than wiping it, so a rapid
		// re-take crossfades (old owner fades as the new owner fades in) instead of hard-cutting.
		for (Bark b : barks)
		{
			b.expireAt = Math.min(b.expireAt, now + BARK_FADE_MS);
		}
		Color color = ClanTurfColors.forClan(newLeader);
		String clan = newLeader.toUpperCase(java.util.Locale.ROOT);
		int clerkIdx = 0;
		int bankerIdx = 0;
		for (NPC npc : client.getNpcs())
		{
			if (npc == null)
			{
				continue;
			}
			String name = npc.getName();
			if (name == null)
			{
				continue;
			}
			String nl = name.toLowerCase(java.util.Locale.ROOT);
			if (!isGeNpc(nl))
			{
				continue; // only the GE cast reacts - never pets or passers-by
			}
			WorldPoint loc = npc.getWorldLocation();
			if (loc == null || !GrandExchangeArea.near(loc, ACTIVE_MARGIN))
			{
				continue;
			}
			if (nl.startsWith("grand exchange clerk") && clerkIdx++ > 0)
			{
				continue; // 4 GE clerks cluster in the center: only the first one speaks
			}
			if (nl.startsWith("banker") && bankerIdx++ > 0)
			{
				continue; // 4 bankers cluster in the center: only the first one speaks
			}
			long revealAt = now + java.util.concurrent.ThreadLocalRandom.current()
					.nextLong(BARK_STAGGER_MS);
			String[] pool = lorePool(nl);
			String line = String.format(
					pool[java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.length)], clan);
			barks.add(new Bark(npc, line, color, clan, revealAt, revealAt + BARK_MS));
		}
	}

	/** True if an NPC name (lower-cased) starts with one of the GE cast prefixes. */
	private boolean isGeNpc(String nameLower)
	{
		for (String base : GE_NPC_NAMES)
		{
			if (nameLower.startsWith(base))
			{
				return true;
			}
		}
		return false;
	}

	/** The lore-flavored bark pool for a GE NPC by (lower-cased) name; generic if unmatched. */
	private String[] lorePool(String nl)
	{
		if (nl.startsWith("grand exchange clerk"))
		{
			return CLERK_LINES;
		}
		if (nl.startsWith("clerk"))
		{
			return CLERK_PORTAL_LINES;
		}
		if (nl.startsWith("banker"))
		{
			return BANKER_LINES;
		}
		if (nl.startsWith("brugsen"))
		{
			return BRUGSEN_LINES;
		}
		if (nl.startsWith("farid"))
		{
			return FARID_LINES;
		}
		if (nl.startsWith("abigaila"))
		{
			return ABIGAILA_LINES;
		}
		if (nl.startsWith("perdu"))
		{
			return PERDU_LINES;
		}
		if (nl.startsWith("emblem"))
		{
			return EMBLEM_LINES;
		}
		if (nl.startsWith("hofuthand"))
		{
			return HOFUTHAND_LINES;
		}
		if (nl.startsWith("relobo"))
		{
			return RELOBO_LINES;
		}
		if (nl.startsWith("bob barter"))
		{
			return BOB_LINES;
		}
		if (nl.startsWith("murky matt"))
		{
			return MURKY_LINES;
		}
		if (nl.startsWith("meredith"))
		{
			return MEREDITH_LINES;
		}
		if (nl.startsWith("zamorakian"))
		{
			return ZAMORAK_LINES;
		}
		if (nl.startsWith("saradominist"))
		{
			return SARADOMIN_LINES;
		}
		return GENERIC_LINES;
	}

	/** Wipes all active/fading takeover barks (world change, shutdown). */
	private void clearBarks()
	{
		barks.clear();
	}

	/** The current takeover barks (active and fading) for the bark overlay to draw. */
	List<Bark> getBarks()
	{
		return barks;
	}

	/** The fade in/out duration, in ms, shared with the overlay. */
	long getBarkFadeMs()
	{
		return BARK_FADE_MS;
	}

	/** Posts the "[CT] The Grand Exchange belongs to X" callout in the clan tab (if enabled). */
	private void announceTakeover(String newLeader)
	{
		if (newLeader == null || !config.announceTakeovers())
		{
			return;
		}
		// [CT], "Grand Exchange" and the clan name in the clan's color, the rest white.
		String on = "<col=" + hex(ClanTurfColors.forClan(newLeader)) + ">";
		String name = newLeader.toUpperCase(java.util.Locale.ROOT);
		String msg = on + "[CT]" + RESET + " " + WHITE + "The " + RESET
				+ on + "Grand Exchange" + RESET + WHITE + " belongs to " + RESET
				+ on + name + RESET + WHITE + "!" + RESET;
		announceClan(msg);
	}

	/**
	 * Global takeover detection off the always-on battles feed: fires the callout when your current
	 * world's owner flips while you are AWAY from the GE (near the GE, {@link #updateLeader} handles
	 * it in real time from claims). Message only for now; a proximity-scaled sound is layered on next.
	 */
	private void checkGlobalTakeover(boolean nearGe)
	{
		if (nearGe)
		{
			// Near the GE updateLeader is authoritative; keep our baseline synced so leaving the GE
			// doesn't re-announce the owner we already committed to.
			announcedOwner = committedLeader;
			return;
		}
		ClanTurfBattle cur = findBattle(client.getWorld());
		String owner = cur == null ? null : cur.getOwner();
		if (owner == null)
		{
			return; // no battles data for this world yet
		}
		if (announcedOwner == null)
		{
			announcedOwner = owner; // baseline silently the first time we have data
			return;
		}
		if (!owner.equalsIgnoreCase(announcedOwner))
		{
			announceTakeover(owner);
			fireTakeoverSound();
			announcedOwner = owner;
		}
	}

	/**
	 * The clan with the most tiles, but sticky: a tie keeps the current leader; ownership only
	 * changes hands when a challenger has strictly MORE tiles. Null before anyone claims.
	 */
	private static String stickyLeader(Collection<ClanTurfPoint> claims, String current)
	{
		Map<String, Integer> counts = new HashMap<>();
		String maxClan = null;
		int maxCount = 0;
		for (ClanTurfPoint p : claims)
		{
			int c = counts.merge(p.getClanName(), 1, Integer::sum);
			if (c > maxCount)
			{
				maxCount = c;
				maxClan = p.getClanName();
			}
		}
		if (maxClan == null)
		{
			return null;
		}
		int currentCount = current == null ? 0 : counts.getOrDefault(current, 0);
		if (maxCount > currentCount)
		{
			return maxClan;
		}
		return currentCount > 0 ? current : maxClan;
	}

	/**
	 * The GE's owner as it existed on arrival: the leader of the current claims MINUS the tiles I've
	 * claimed since this settle window began. That's the server snapshot's owner (a rival, my own clan,
	 * or nobody) without my just-made claims tipping it, so the settle logic can tell "I took an empty
	 * GE" (returns null) from "I arrived on turf someone already held".
	 */
	private String preExistingLeader()
	{
		java.util.List<ClanTurfPoint> agg = aggregatedClaims();
		if (arrivalTiles.isEmpty())
		{
			return stickyLeader(agg, committedLeader);
		}
		java.util.List<ClanTurfPoint> pre = new java.util.ArrayList<>(agg.size());
		for (ClanTurfPoint p : agg)
		{
			if (!arrivalTiles.contains(tileKey(p)))
			{
				pre.add(p);
			}
		}
		return stickyLeader(pre, committedLeader);
	}

	/** Stable per-tile key (region id + local coords + plane) for the arrival-claims set. */
	private static String tileKey(ClanTurfPoint p)
	{
		return p.getRegionId() + ":" + p.getRegionX() + ":" + p.getRegionY() + ":" + p.getZ();
	}

	/**
	 * Fires the takeover sound with its volume scaled by your distance to the GE at THIS moment
	 * (a one-shot, set-at-trigger level - not a live fade): full inside the GE, falling off (squared)
	 * to silent by {@link #SOUND_MAX_DIST} tiles outside. So a takeover across the map still pings
	 * your chat but doesn't blast a sound out of nowhere.
	 */
	private void fireTakeoverSound()
	{
		if (!config.takeoverSound())
		{
			return;
		}
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		int outside = GrandExchangeArea.distanceTo(here); // 0 when inside the GE box
		double prox;
		if (outside > 0)
		{
			// Outside the GE: loudest at the wall, falling off quickly (squared) to silent by the max.
			double base = outside >= SOUND_MAX_DIST ? 0.0 : 1.0 - (double) outside / SOUND_MAX_DIST;
			prox = base * base;
		}
		else
		{
			prox = 1.0; // inside the GE: full volume
		}
		if (prox <= 0.0)
		{
			return; // too far -> silent (the chat callout still fired)
		}
		float v = Math.max(0.0001f, Math.min(1f, (float) (config.takeoverVolume() / 100.0 * prox)));
		float gainDb = (float) (20.0 * Math.log10(v));
		int delay = config.takeoverSoundDelayMs();
		if (delay <= 0)
		{
			playTakeoverSound(gainDb);
		}
		else
		{
			executor.schedule(() -> playTakeoverSound(gainDb), delay, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Plays the packaged takeover sound through RuneLite's {@link AudioPlayer} (the Plugin Hub
	 * requires this over the raw Java Sound API) at the given decibel gain.
	 */
	private void playTakeoverSound(float gainDb)
	{
		try
		{
			audioPlayer.play(getClass(), "VictoryClaim.wav", gainDb);
		}
		catch (Exception e)
		{
			log.debug("Takeover sound failed to play", e);
		}
	}

	/** Loads the packaged icon; falls back to a drawn placeholder if the resource is missing. */
	private static BufferedImage buildIcon()
	{
		try
		{
			return ImageUtil.loadImageResource(ClanTurfPlugin.class, "icon.png");
		}
		catch (Exception e)
		{
			log.warn("icon.png not found on the classpath, using placeholder", e);
			BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = icon.createGraphics();
			g.setColor(new Color(0x30, 0xC0, 0x60));
			g.fillRect(2, 2, 12, 12);
			g.setColor(Color.WHITE);
			g.drawRect(2, 2, 11, 11);
			g.dispose();
			return icon;
		}
	}
}
