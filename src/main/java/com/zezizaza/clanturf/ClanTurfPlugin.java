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
import java.util.HashSet;
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
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
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

	private WorldPoint lastTile;
	private int lastWorld = -1;

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
	/** Reset-warning thresholds already fired this cycle (cleared after the reset passes). */
	private final Set<Integer> firedResetPings = new HashSet<>();

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

	// Debounced takeover state. The committed leader only changes after a challenger has held the
	// lead for the confirm delay; that single event drives both the boundary animation and the
	// chat announcement, so a see-sawing lead doesn't spam either.
	private volatile String committedLeader;
	private String pendingLeader;
	private long pendingSince;
	private boolean leaderInit;
	private long settleUntil;
	private static final long SETTLE_MS = 3000; // server-mode window to absorb the initial snapshot
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
		panel = new ClanTurfPanel(this::invade, this::clearOfflineTiles);
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
		localStore.clearClaims(client.getWorld());
		refreshClaims();
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
		if (event.getOverlay() == trackerOverlay && "Reset".equals(event.getEntry().getOption()))
		{
			resetTileSession();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Logging in or hopping resets where we think the player is.
		lastTile = null;
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

		int world = client.getWorld();
		if (world != lastWorld)
		{
			lastWorld = world;
			lastTile = null;
			leaderInit = false; // re-baseline the committed leader silently on the new world
			refreshClaims();
		}

		// Are we at/near the GE? Drives connect-on-demand and leader detection.
		WorldPoint here = local.getWorldLocation();
		boolean nearGe = here != null && GrandExchangeArea.near(here, ACTIVE_MARGIN);

		updateResetPings(nearGe);

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
						effectiveClanName());
				panel.updateBattles(battlesForPanel(), effectiveClanName());
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

		WorldPoint wp = local.getWorldLocation();
		if (wp == null || wp.equals(lastTile))
		{
			return;
		}
		lastTile = wp;

		if (!GrandExchangeArea.contains(wp))
		{
			return;
		}

		// The clan is stamped at claim time (our own channel, or the testing override).
		String clanName = effectiveClanName();
		if (clanName == null)
		{
			return;
		}

		ClanTurfPoint point = new ClanTurfPoint(
				wp.getRegionID(), wp.getRegionX(), wp.getRegionY(), wp.getPlane(),
				world, clanName);

		store.putClaim(point); // last-writer-wins = takeover; fires the change listener
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

	/** Wipe the tiles/hour session (right-click "Reset" on the tracker overlay). */
	void resetTileSession()
	{
		sessionClaims = 0;
		firstClaimMs = 0L;
		cachedRate = 0;
		rateCalcMs = 0L;
		maxRate = 0;
	}

	/** Tiles the overlay should paint right now (current world). */
	Collection<ClanTurfPoint> getVisibleClaims()
	{
		return visibleClaims;
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
			list.removeIf(b -> b.getWorld() == world);
			list.add(0, new ClanTurfBattle(world, owner, ownerTiles, total));
		}
		return list;
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
			firedResetPings.clear(); // outside the window -> arm for the next day
			return;
		}
		for (int t : RESET_PING_MINUTES)
		{
			if (ms <= t * 60_000L && firedResetPings.add(t) && nearGe)
			{
				announceClan(CT_TAG + " " + WHITE + "Turf resets in " + t
						+ (t == 1 ? " minute" : " minutes") + "!" + RESET);
			}
		}
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
		if (panel != null)
		{
			panel.update(visibleClaims, world, GrandExchangeArea.totalTiles(), committedLeader,
					effectiveClanName());
			panel.updateBattles(battlesForPanel(), effectiveClanName());
		}
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
			// Baseline immediately so the boundary shows the right color right away. A brief
			// settle window (server mode only) then absorbs the initial snapshot loading in, so
			// arriving at pre-existing turf doesn't fire a bogus takeover. No sync dependency.
			committedLeader = instant;
			pendingLeader = null;
			leaderInit = true;
			settleUntil = now + (store == serverStore ? SETTLE_MS : 0);
			return;
		}

		if (now < settleUntil)
		{
			// Still settling after arrival: track the leader silently.
			committedLeader = instant;
			pendingLeader = null;
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
			if (config.announceTakeovers())
			{
				// Same shape as the !command calls: [CT], "Grand Exchange", and the clan name in
				// the clan's color, the rest white, so every plugin line reads consistently.
				String on = "<col=" + hex(ClanTurfColors.forClan(newLeader)) + ">";
				String name = newLeader.toUpperCase(java.util.Locale.ROOT);
				String msg = on + "[CT]" + RESET + " " + WHITE + "The " + RESET
						+ on + "Grand Exchange" + RESET + WHITE + " belongs to " + RESET
						+ on + name + RESET + WHITE + "!" + RESET;
				announceClan(msg);
			}
			if (config.takeoverSound())
			{
				int delay = config.takeoverSoundDelayMs();
				if (delay <= 0)
				{
					playTakeoverSound();
				}
				else
				{
					executor.schedule(this::playTakeoverSound, delay, TimeUnit.MILLISECONDS);
				}
			}
		}
		log.debug("Takeover: {} -> {}", previous, newLeader);
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
	 * Plays the packaged takeover sound through RuneLite's {@link AudioPlayer} (the Plugin Hub
	 * requires this over the raw Java Sound API). Volume percent is converted to a decibel gain.
	 */
	private void playTakeoverSound()
	{
		float v = Math.max(0.0001f, Math.min(1f, config.takeoverVolume() / 100f));
		float gainDb = (float) (20.0 * Math.log10(v));
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
