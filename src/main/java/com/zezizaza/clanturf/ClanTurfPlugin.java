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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

@Slf4j
@PluginDescriptor(
		name = "Clan Turf",
		description = "Claim Grand Exchange tiles for your clan by walking on them, colored per clan and counted per world",
		tags = {"clan", "turf", "territory", "grand exchange", "ge", "tiles"}
)
public class ClanTurfPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ScheduledExecutorService executor;
	@Inject private OverlayManager overlayManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private WorldService worldService;
	@Inject private ConfigManager configManager;
	@Inject private ClanTurfConfig config;
	@Inject private ClanTurfOverlay overlay;
	@Inject private ClanTurfMinimapOverlay minimapOverlay;
	@Inject private ClanTurfResetOverlay resetOverlay;
	@Inject private ClanTurfTrackerOverlay trackerOverlay;
	@Inject private AudioPlayer audioPlayer;

	// The seam pays off here: both stores implement ClanTurfStore, and startUp picks one.
	// Local = ConfigManager (your claims only). Server = synced (rivals visible).
	@Inject private ConfigClanTurfStore localStore;
	@Inject private HttpClanTurfStore serverStore;
	private ClanTurfStore store;

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

	/** When we most recently had no clan channel, so the panel only shows the "join a clan" hint once
	 * the channel has had time to load (it arrives seconds after login and drops on a hop). Otherwise
	 * the hint flashes at clan members during the login/connect wait. 0 = we currently have a clan. */
	private long clanlessSinceMs;
	private static final long CLAN_GRACE_MS = 6000L;

	/** The clan name we currently have a local color override registered for (custom clan color). */
	private String ownColorClan;

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

	// "Invade" world-hop: the same quick-hop dance the World Hopper plugin uses. We stage a target
	// world, open the world switcher, and hop once it's up; a game message clears it if we can't.
	private net.runelite.api.World quickHopTargetWorld;
	private int hopAttempts;
	private static final int HOP_MAX_ATTEMPTS = 3;

	@Provides
	ClanTurfConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(ClanTurfConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new ClanTurfPanel(this::invade, this::clearOfflineTiles, this::setUseServer);
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
		// Pre-register the remembered clan's custom color so the first frame after login is already
		// right, instead of showing the auto color until the clan channel loads seconds later.
		ownColorClan = null;
		if (config.customClanColor() && !config.lastOwnClan().isEmpty())
		{
			ownColorClan = config.lastOwnClan();
			ClanTurfColors.setOverride(ownColorClan, config.clanColor());
		}
		refreshClaims();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(resetOverlay);
		overlayManager.remove(trackerOverlay);
		clientToolbar.removeNavigation(navButton);
		if (store != null)
		{
			store.setChangeListener(null);
			store.stop();
		}
		visibleClaims = Collections.emptyList();
		lastTile = null;
		lastWorld = -1;
		if (ownColorClan != null)
		{
			ClanTurfColors.removeOverride(ownColorClan);
			ownColorClan = null;
		}
	}

	/** Picks the local or networked store from the config and starts it. */
	private void selectStore()
	{
		store = config.useServer() ? serverStore : localStore;
		store.start();
		if (store == localStore)
		{
			// Local store is event-driven: repaint when we write. (Server mode polls per tick.)
			store.setChangeListener(this::refreshClaims);
		}
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
		// Make "Use sync server" take effect immediately instead of needing a plugin off/on.
		if (ConfigClanTurfStore.GROUP.equals(event.getGroup()) && "useServer".equals(event.getKey()))
		{
			if (store != null)
			{
				store.setChangeListener(null);
				store.stop();
			}
			selectStore();
			leaderInit = false;
			committedLeader = null;
			refreshClaims();
		}
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
			if (store == serverStore)
			{
				serverStore.setOnline(true); // resume syncing now that we're back in-game
			}
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// Logged out to the login/world-select screen: stop all sync and blank the panel so the
			// scoreboard bars and battles don't linger over the login screen. Login re-drives it all.
			if (store == serverStore)
			{
				serverStore.setOnline(false);
			}
			lastWorld = -1;
			clanlessSinceMs = 0; // re-grace the clan hint on the next login instead of firing instantly
			refreshClaims();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		driveHop(); // progress any pending "Invade" world-hop, even mid-relog

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		updateOwnClanColor();

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
			trail.clear();      // snail-trail tiles are per-world coords; don't drag them across a hop
			lastTrailTile = null;
			refreshClaims();
		}

		// Are we at/near the GE? Drives connect-on-demand and leader detection.
		WorldPoint here = local.getWorldLocation();
		boolean nearGe = here != null && GrandExchangeArea.near(here, ACTIVE_MARGIN);
		nearGeNow = nearGe;

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
				panel.update(visibleClaims, world, GrandExchangeArea.totalTiles(), committedLeader,
						effectiveClanName(), findBattle(world), store.connectionStatus(), clanHintDue());
				panel.updateBattles(battlesForPanel(), effectiveClanName(), client.getWorld());
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
		tryClaim(local.getWorldLocation(), world);
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		// Snail trail: sample the tile under the moving character model each frame and remember it with
		// a timestamp, so the overlay can paint a fading trail beneath your feet - including the tiles you
		// skip while running. Visual only: it never claims or counts a tile (that stays true-tile on the
		// game tick), so it can't become a faster way to grab turf.
		pruneTrail();
		if (!config.snailTrail() || !nearGeNow || effectiveClanName() == null)
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
				trail.put(wp, System.currentTimeMillis());
			}
		}
	}

	/** Drop snail-trail tiles older than the fade window so the map stays small. */
	private void pruneTrail()
	{
		if (trail.isEmpty())
		{
			return;
		}
		long cutoff = System.currentTimeMillis() - TRAIL_MS;
		trail.values().removeIf(t -> t < cutoff);
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

		// The clan is stamped at claim time (our own channel, or the testing override).
		String clanName = effectiveClanName();
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

		// A genuine gain (for the community counter) is a tile not already ours - empty or a rival's.
		// Re-walking our own turf claims nothing new, so it must not tick the counter (the server counts
		// gains the same way, so the optimistic ticks stay in step with the authoritative total).
		String tk = tileKey(point);
		boolean gain = true;
		for (ClanTurfPoint c : visibleClaims)
		{
			if (clanName.equals(c.getClanName()) && tk.equals(tileKey(c)))
			{
				gain = false;
				break;
			}
		}

		store.putClaim(point); // last-writer-wins = takeover; fires the change listener
		arrivalTiles.add(tk); // one of my fresh claims - excluded from the pre-existing owner
		if (gain && panel != null)
		{
			panel.addLocalClaim(); // tick the community counter for a genuine new/stolen tile only
		}
		log.debug("Claimed {},{} plane {} for {} on world {}",
				wp.getRegionX(), wp.getRegionY(), wp.getPlane(), clanName, world);

		// Tiles/hour tracker: every claim counts (retakes included); the clock starts on the first.
		sessionClaims++;
		if (firstClaimMs == 0L)
		{
			firstClaimMs = System.currentTimeMillis();
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

	/** The clan whose color the snail trail draws in (the local player's), or null if not in one. */
	String getTrailClan()
	{
		return effectiveClanName();
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
		List<ClanTurfPoint> claims = visibleClaims;
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
		if (channel == null || channel.getName() == null || channel.getName().isEmpty())
		{
			return null;
		}
		return channel.getName();
	}

	/** True once we're confident the player really is clan-less: the clan channel has had time to load
	 * after login/hop, so an empty clan is genuine rather than not-yet-loaded. Gates the panel hint. */
	private boolean clanHintDue()
	{
		return clanlessSinceMs != 0 && System.currentTimeMillis() - clanlessSinceMs > CLAN_GRACE_MS;
	}

	/**
	 * Registers (or clears) a local color override for the player's own clan, from the "Custom
	 * clan color" setting. Everything paints through {@link ClanTurfColors#forClan}, so this one
	 * registration recolors the player's tiles, outline, boundary, minimap and panel at once.
	 */
	private void updateOwnClanColor()
	{
		if (!config.customClanColor())
		{
			// Feature off: make sure no override lingers.
			if (ownColorClan != null)
			{
				ClanTurfColors.removeOverride(ownColorClan);
				ownColorClan = null;
			}
			return;
		}

		String clan = effectiveClanName();
		if (clan == null)
		{
			// Clan channel not loaded yet (right after login / a world hop). Keep whatever override
			// we already have rather than dropping it, so tiles don't flash back to the auto color.
			return;
		}

		if (!clan.equals(ownColorClan))
		{
			if (ownColorClan != null)
			{
				ClanTurfColors.removeOverride(ownColorClan);
			}
			ownColorClan = clan;
			// Remember it so next login can paint the right color immediately, before the clan
			// channel has loaded (which is what caused the long hashed-color pause).
			configManager.setConfiguration(ConfigClanTurfStore.GROUP, "lastOwnClan", clan);
		}
		ClanTurfColors.setOverride(clan, config.clanColor());
	}

	/** Invade a battle's world: stage a quick-hop. Called from the panel on the Swing EDT. */
	void invade(int worldId)
	{
		clientThread.invoke(() -> beginHop(worldId));
	}

	/** Builds the hop target from the world list. Must run on the client thread. */
	private void beginHop(int worldId)
	{
		WorldResult worldResult = worldService.getWorlds();
		if (worldResult == null)
		{
			return;
		}
		World world = worldResult.findWorld(worldId);
		if (world == null)
		{
			return;
		}

		net.runelite.api.World rsWorld = client.createWorld();
		rsWorld.setActivity(world.getActivity());
		rsWorld.setAddress(world.getAddress());
		rsWorld.setId(world.getId());
		rsWorld.setPlayerCount(world.getPlayers());
		rsWorld.setLocation(world.getLocation());
		rsWorld.setTypes(WorldUtil.toWorldTypes(world.getTypes()));

		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			client.changeWorld(rsWorld);
			return;
		}

		quickHopTargetWorld = rsWorld;
		hopAttempts = 0;
	}

	/** Progresses a staged hop each tick: open the world switcher, then hop once it's up. */
	private void driveHop()
	{
		if (quickHopTargetWorld == null)
		{
			return;
		}
		if (client.getWidget(InterfaceID.Worldswitcher.BUTTONS) == null)
		{
			client.openWorldHopper();
			if (++hopAttempts >= HOP_MAX_ATTEMPTS)
			{
				resetHop(); // give up rather than spin forever
			}
		}
		else
		{
			client.hopToWorld(quickHopTargetWorld);
			resetHop();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE
				&& "Please finish what you're doing before using the World Switcher.".equals(event.getMessage()))
		{
			resetHop();
			return;
		}
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

	private void resetHop()
	{
		hopAttempts = 0;
		quickHopTargetWorld = null;
	}

	private void refreshClaims()
	{
		int world = client.getWorld();
		visibleClaims = new java.util.ArrayList<>(store.getClaims(world));
		if (panel == null)
		{
			return;
		}
		// Before login there's no world and no reason to mention the server: startUp() calls this at
		// the login screen, where "Connecting to the sync server..." is nonsense. Show a plain waiting
		// message until we're actually in-game; the connection wording only kicks in once logged in.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.showEmpty("Waiting for the client…");
			panel.updateBattles(Collections.emptyList(), effectiveClanName(), -1);
			return;
		}
		panel.update(visibleClaims, world, GrandExchangeArea.totalTiles(), committedLeader,
				effectiveClanName(), findBattle(world), store.connectionStatus(), clanHintDue());
		panel.updateBattles(battlesForPanel(), effectiveClanName(), client.getWorld());
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
		String instant = stickyLeader(visibleClaims, committedLeader);
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
		}
		log.debug("Takeover: {} -> {}", previous, newLeader);
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
		if (arrivalTiles.isEmpty())
		{
			return stickyLeader(visibleClaims, committedLeader);
		}
		java.util.List<ClanTurfPoint> pre = new java.util.ArrayList<>(visibleClaims.size());
		for (ClanTurfPoint p : visibleClaims)
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
