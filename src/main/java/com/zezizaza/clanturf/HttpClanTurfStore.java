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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Networked store: the same {@link ClanTurfStore} seam, backed by the sync server instead
 * of ConfigManager. This is what makes rival claims visible.
 *
 * <p>Designed to scale (see the server notes): the client never blocks the game thread on
 * the network. A background poller pulls a snapshot for the active world every couple of
 * seconds into an in-memory cache, and claims are queued and flushed in batches once a
 * second. {@link #getClaims}/{@link #putClaim} just touch the cache/queue and return
 * instantly. That keeps message volume proportional to players and tick-rate, not to how
 * frantically everyone is claiming.
 */
@Slf4j
@Singleton
class HttpClanTurfStore implements ClanTurfStore
{
	private static final long POLL_MS = 2000;
	private static final long FLUSH_MS = 1000;
	private static final long BATTLES_POLL_MS = 15000; // the active-battles board updates slowly
	private static final long ALLIANCES_POLL_MS = 20000; // alliances change rarely, so poll gently
	private static final long CONNECT_GRACE_MS = 30000; // cold-start window before we call it down
	private static final long STALE_MS = 45000; // no reply for this long = treat the server as down

	private final ClanTurfConfig config;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private ScheduledExecutorService exec;
	private volatile String baseUrl = "http://localhost:8080";
	private volatile int activeWorld = -1;
	private volatile List<ClanTurfPoint> cache = Collections.emptyList();
	private volatile List<ClanTurfBattle> battles = Collections.emptyList();
	private volatile long globalClaims; // all-time community total from /battles (0 until first poll)

	// Alliance map from /alliances. clan(lower) -> 6-hex color, clan(lower) -> allianceId,
	// allianceId -> member clan names (original case). Global, not per-world.
	private volatile Map<String, String> allyColor = Collections.emptyMap();
	private volatile Map<String, String> allyId = Collections.emptyMap();
	private volatile Map<String, Set<String>> allyMembers = Collections.emptyMap();
	private volatile Map<String, String> allyNameById = Collections.emptyMap(); // allianceId -> name
	private volatile Map<String, String> allyOwnerById = Collections.emptyMap(); // allianceId -> owner clan
	private volatile Map<String, Integer> allyIconById = Collections.emptyMap(); // allianceId -> symbol sprite id

	/** When the poller last started, and when the server last answered - drives connectionStatus(). */
	private volatile long startedMs;
	private volatile long lastOkMs;

	/** False while the player is logged out: pauses every poll so we don't sync from the login screen. */
	private volatile boolean online = true;

	/** Claims made locally but not yet confirmed sent, keyed by tile. */
	private final Map<String, ClanTurfPoint> pending = new ConcurrentHashMap<>();

	/** Guards the cache read-modify-write so a background poll can't clobber a fresh local claim. */
	private final Object cacheLock = new Object();

	@Inject
	HttpClanTurfStore(ClanTurfConfig config)
	{
		this.config = config;
	}

	@Override
	public void start()
	{
		baseUrl = normalize(config.serverUrl());
		startedMs = System.currentTimeMillis();
		lastOkMs = 0;
		online = true;
		exec = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "clanturf-sync");
			t.setDaemon(true);
			return t;
		});
		exec.scheduleWithFixedDelay(this::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
		exec.scheduleWithFixedDelay(this::flush, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
		exec.scheduleWithFixedDelay(this::pollBattles, 0, BATTLES_POLL_MS, TimeUnit.MILLISECONDS);
		exec.scheduleWithFixedDelay(this::pollAlliances, 0, ALLIANCES_POLL_MS, TimeUnit.MILLISECONDS);
		log.info("ClanTurf sync store started against {}", baseUrl);
	}

	@Override
	public void stop()
	{
		if (exec != null)
		{
			exec.shutdownNow();
			exec = null;
		}
		pending.clear();
		cache = Collections.emptyList();
		battles = Collections.emptyList();
		allyColor = Collections.emptyMap();
		allyId = Collections.emptyMap();
		allyMembers = Collections.emptyMap();
		allyNameById = Collections.emptyMap();
		allyOwnerById = Collections.emptyMap();
		allyIconById = Collections.emptyMap();
	}

	@Override
	public Collection<ClanTurfPoint> getClaims(int world)
	{
		return cache;
	}

	@Override
	public List<ClanTurfBattle> getBattles()
	{
		return battles;
	}

	@Override
	public long getGlobalClaims()
	{
		return globalClaims;
	}

	@Override
	public ConnectionStatus connectionStatus()
	{
		long now = System.currentTimeMillis();
		if (lastOkMs > 0)
		{
			// Heard back at least once: online while fresh, offline if the server has gone quiet.
			return now - lastOkMs < STALE_MS ? ConnectionStatus.ONLINE : ConnectionStatus.OFFLINE;
		}
		// No reply yet: a fresh start is still connecting; past the grace window it's likely down.
		return now - startedMs < CONNECT_GRACE_MS ? ConnectionStatus.CONNECTING : ConnectionStatus.OFFLINE;
	}

	/**
	 * Connect-on-demand: the plugin sets the world to sync while the player is near the GE, or
	 * {@code -1} to go idle (the poller then makes no requests, freeing the connection budget).
	 *
	 * <p>On a world change we drop the previous world's snapshot immediately (so its tiles don't
	 * linger on the new world for a poll cycle), forget unsent claims from the old world, and kick
	 * an immediate poll so the new world's tiles appear right away instead of after the next tick.
	 */
	/**
	 * Pause or resume all sync with the player's login state. Logged out there's no world to sync and
	 * nothing to show, so we stop every poll and drop the cache instead of quietly polling the server
	 * from the login screen. On resume we kick an immediate battles poll so the board is fresh.
	 */
	void setOnline(boolean online)
	{
		this.online = online;
		if (!online)
		{
			activeWorld = -1;
			cache = Collections.emptyList();
			pending.clear();
			allyColor = Collections.emptyMap();
			allyId = Collections.emptyMap();
			allyMembers = Collections.emptyMap();
			allyNameById = Collections.emptyMap();
			allyOwnerById = Collections.emptyMap();
			allyIconById = Collections.emptyMap();
		}
		else
		{
			ScheduledExecutorService e = exec;
			if (e != null)
			{
				e.execute(this::pollBattles);
				e.execute(this::pollAlliances);
			}
		}
	}

	void setActiveWorld(int world)
	{
		if (world == activeWorld)
		{
			return;
		}
		int previous = activeWorld;
		activeWorld = world;
		cache = Collections.emptyList();
		if (previous >= 0)
		{
			pending.clear();
		}
		ScheduledExecutorService e = exec;
		if (e != null && world >= 0)
		{
			e.execute(this::poll);
		}
	}

	@Override
	public void putClaim(ClanTurfPoint point)
	{
		pending.put(keyOf(point), point);

		// Optimistic: show our own tile immediately instead of waiting for the round trip.
		synchronized (cacheLock)
		{
			Map<String, ClanTurfPoint> merged = new LinkedHashMap<>();
			for (ClanTurfPoint c : cache)
			{
				merged.put(keyOf(c), c);
			}
			merged.put(keyOf(point), point);
			cache = new ArrayList<>(merged.values());
		}
	}

	@Override
	public void clearClaims(int world)
	{
		pending.clear();
		if (world == activeWorld)
		{
			cache = Collections.emptyList();
		}
		// Clearing the shared server is admin-only; the token comes from config (blank for the
		// public, so a normal user's button just refreshes their local view).
		String token = config.serverAdminToken();
		String path = "/clear?world=" + world
				+ (token == null || token.trim().isEmpty() ? "" : "&token=" + token.trim());
		ScheduledExecutorService e = exec;
		if (e != null)
		{
			e.execute(() -> send("POST", path, null));
		}
	}

	private void poll()
	{
		int w = activeWorld;
		if (!online || w < 0)
		{
			return;
		}
		String body = send("GET", "/claims?world=" + w, null);
		if (body == null)
		{
			return;
		}

		Map<String, ClanTurfPoint> merged = new LinkedHashMap<>();
		for (String line : body.split("\n"))
		{
			if (line.isBlank())
			{
				continue;
			}
			String[] f = line.split(",", 5);
			if (f.length < 5)
			{
				continue;
			}
			try
			{
				ClanTurfPoint p = new ClanTurfPoint(
						Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()),
						Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()), w, f[4]);
				merged.put(keyOf(p), p);
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed line
			}
		}
		// Overlay still-unflushed local claims on top of the server snapshot, reading them inside
		// the lock so a claim made concurrently on the game thread can't be lost.
		synchronized (cacheLock)
		{
			for (ClanTurfPoint p : pending.values())
			{
				merged.put(keyOf(p), p);
			}
			cache = new ArrayList<>(merged.values());
		}
	}

	/** Pulls the active-battles board (independent of the active world, so you can see it anywhere). */
	private void pollBattles()
	{
		if (!online)
		{
			return;
		}
		String body = send("GET", "/battles", null);
		if (body == null)
		{
			return;
		}
		List<ClanTurfBattle> list = new ArrayList<>();
		for (String line : body.split("\n"))
		{
			if (line.isBlank())
			{
				continue;
			}
			if (line.startsWith("GLOBAL,"))
			{
				try
				{
					globalClaims = Long.parseLong(line.substring(7).trim());
				}
				catch (NumberFormatException ignored)
				{
					// skip a malformed counter line
				}
				continue;
			}
			// world,owner,ownerTiles,totalTiles[,runnerUp,runnerUpTiles]; the last two are optional
			// so an older server (4 fields) still parses - the world just shows without a "vs".
			String[] f = line.split(",", 6);
			if (f.length < 4)
			{
				continue;
			}
			try
			{
				String runnerUp = null;
				int runnerUpTiles = 0;
				if (f.length >= 6 && !f[4].trim().isEmpty())
				{
					runnerUp = f[4];
					runnerUpTiles = Integer.parseInt(f[5].trim());
				}
				if (runnerUpTiles <= 0)
				{
					runnerUp = null;
				}
				list.add(new ClanTurfBattle(Integer.parseInt(f[0].trim()), f[1],
						Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
						runnerUp, runnerUpTiles));
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed line
			}
		}
		battles = list;
	}

	/** Pulls the global alliance map: which clans are teamed up and the shared color of each team. */
	private void pollAlliances()
	{
		if (!online)
		{
			return;
		}
		String body = send("GET", "/alliances", null);
		if (body == null)
		{
			return;
		}
		Map<String, String> color = new HashMap<>();
		Map<String, String> id = new HashMap<>();
		Map<String, Set<String>> members = new HashMap<>();
		Map<String, String> nameById = new HashMap<>();
		Map<String, String> colorById = new HashMap<>();
		Map<String, String> ownerById = new HashMap<>();
		Map<String, Integer> iconById = new HashMap<>();
		for (String line : body.split("\n"))
		{
			if (line.isBlank())
			{
				continue;
			}
			if (line.startsWith("A,"))
			{
				String[] f = line.split(",", 4); // A,id,color,name
				if (f.length >= 3)
				{
					colorById.put(f[1], f[2].trim());
					nameById.put(f[1], f.length >= 4 ? f[3] : "");
				}
			}
			else if (line.startsWith("AI,"))
			{
				String[] f = line.split(",", 3); // AI,id,icon
				if (f.length >= 3)
				{
					try
					{
						iconById.put(f[1], Integer.parseInt(f[2].trim()));
					}
					catch (NumberFormatException ignored)
					{
						// skip a malformed icon line
					}
				}
			}
			else if (line.startsWith("M,"))
			{
				String[] f = line.split(",", 4); // M,id,ownerFlag,clan
				if (f.length >= 4)
				{
					String aid = f[1];
					String clan = f[3];
					id.put(clan.toLowerCase(), aid);
					members.computeIfAbsent(aid, k -> new HashSet<>()).add(clan);
					if ("1".equals(f[2]))
					{
						ownerById.put(aid, clan);
					}
				}
			}
		}
		// Fill each member's color from its alliance's A line (A lines precede M lines, but do it in
		// a second pass so ordering never matters).
		for (Map.Entry<String, String> e : id.entrySet())
		{
			String hex = colorById.get(e.getValue());
			if (hex != null)
			{
				color.put(e.getKey(), hex);
			}
		}
		allyColor = color;
		allyId = id;
		allyMembers = members;
		allyNameById = nameById;
		allyOwnerById = ownerById;
		allyIconById = iconById;
	}

	@Override
	public Map<String, String> allianceColors()
	{
		return allyColor; // keyed by lower-case clan; ClanTurfColors lower-cases too, so it lines up
	}

	@Override
	public String allianceIdOf(String clan)
	{
		return clan == null ? null : allyId.get(clan.toLowerCase());
	}

	@Override
	public Set<String> alliesOf(String clan)
	{
		String aid = allianceIdOf(clan);
		if (aid == null)
		{
			return Collections.emptySet();
		}
		Set<String> m = allyMembers.get(aid);
		return m == null ? Collections.emptySet() : m;
	}

	@Override
	public String allianceNameOf(String clan)
	{
		String aid = allianceIdOf(clan);
		return aid == null ? null : allyNameById.get(aid);
	}

	@Override
	public String allianceOwnerClanOf(String clan)
	{
		String aid = allianceIdOf(clan);
		return aid == null ? null : allyOwnerById.get(aid);
	}

	@Override
	public int allianceIconOf(String clan)
	{
		String aid = allianceIdOf(clan);
		Integer icon = aid == null ? null : allyIconById.get(aid);
		return icon == null ? 0 : icon; // 0 = unknown; callers fall back to the default symbol
	}

	@Override
	public int allianceIconByDisplay(String display)
	{
		if (display == null)
		{
			return 0;
		}
		for (Map.Entry<String, Integer> e : allyIconById.entrySet())
		{
			String nm = allyNameById.get(e.getKey());
			if (display.equalsIgnoreCase(nm) || display.equals(e.getKey()))
			{
				return e.getValue() == null ? 0 : e.getValue();
			}
		}
		return 0;
	}

	@Override
	public List<String> allianceMembersByDisplay(String display)
	{
		if (display != null)
		{
			for (Map.Entry<String, Set<String>> e : allyMembers.entrySet())
			{
				String nm = allyNameById.get(e.getKey());
				if (display.equalsIgnoreCase(nm) || display.equals(e.getKey()))
				{
					List<String> out = new java.util.ArrayList<>(e.getValue());
					java.util.Collections.sort(out);
					return out;
				}
			}
		}
		return Collections.emptyList();
	}

	@Override
	public Map<String, String> allianceNames()
	{
		// clan(lower) -> alliance display name, so the plugin can detect a rename (colors alone miss it).
		Map<String, String> out = new HashMap<>();
		for (Map.Entry<String, String> e : allyId.entrySet())
		{
			String nm = allyNameById.get(e.getValue());
			out.put(e.getKey(), nm == null ? "" : nm);
		}
		return out;
	}

	// ---- alliance actions: blocking POSTs, so the plugin runs them off the game/EDT thread ----

	String allianceCreate(String clan, String name, String color, String passcode, int icon)
	{
		return sendResult("POST", "/alliance/create",
				form("clan", clan, "name", name, "color", color, "passcode", passcode,
						"icon", String.valueOf(icon)));
	}

	String allianceJoin(String clan, String passcode)
	{
		return sendResult("POST", "/alliance/join", form("clan", clan, "passcode", passcode));
	}

	String allianceLeave(String clan)
	{
		return sendResult("POST", "/alliance/leave", form("clan", clan));
	}

	String allianceSetColor(String id, String clan, String color)
	{
		return sendResult("POST", "/alliance/color", form("id", id, "clan", clan, "color", color));
	}

	String allianceKick(String id, String clan, String target)
	{
		return sendResult("POST", "/alliance/kick", form("id", id, "clan", clan, "target", target));
	}

	String allianceDisband(String id, String clan)
	{
		return sendResult("POST", "/alliance/disband", form("id", id, "clan", clan));
	}

	/** Owner clan's passcode + blocked clans, bundled from one request. */
	static final class OwnerInfo
	{
		final String passcode;
		final java.util.List<String> blacklist;

		OwnerInfo(String passcode, java.util.List<String> blacklist)
		{
			this.passcode = passcode;
			this.blacklist = blacklist;
		}
	}

	/** Owner clan only: fetch the passcode and blocked clans so staff can re-share the code and un-block
	 *  clans. Returns null if the server refused (not the owner clan) or was unreachable. Blocking - run
	 *  off the EDT. Wire format: "ok,<passcode>" then one "BL,<clan>" line per blocked clan. */
	OwnerInfo allianceOwnerInfo(String id, String clan)
	{
		String resp = sendResult("POST", "/alliance/owner-info", form("id", id, "clan", clan));
		if (resp == null || !resp.startsWith("ok,"))
		{
			return null;
		}
		String pass = null;
		java.util.List<String> bl = new java.util.ArrayList<>();
		for (String line : resp.split("\n"))
		{
			if (line.startsWith("ok,"))
			{
				pass = line.substring(3).trim();
			}
			else if (line.startsWith("BL,"))
			{
				String c = line.substring(3).trim();
				if (!c.isEmpty())
				{
					bl.add(c);
				}
			}
		}
		return new OwnerInfo(pass, bl);
	}

	/** Owner clan only: change the passcode (old must match). Current members stay; new joiners need it. */
	String allianceSetPasscode(String id, String clan, String oldPass, String newPass)
	{
		return sendResult("POST", "/alliance/setpasscode",
				form("id", id, "clan", clan, "old", oldPass, "new", newPass));
	}

	/** Owner clan only: rename the alliance. Server runs the name filter + a one-per-week cap. */
	String allianceSetName(String id, String clan, String name)
	{
		return sendResult("POST", "/alliance/setname", form("id", id, "clan", clan, "name", name));
	}

	/** Owner clan only: set the alliance symbol (a clan-motif sprite id, 3024-3050). */
	String allianceSetIcon(String id, String clan, int icon)
	{
		return sendResult("POST", "/alliance/seticon",
				form("id", id, "clan", clan, "icon", String.valueOf(icon)));
	}

	/** Owner clan only: un-block a previously kicked clan so it can rejoin with the passcode. */
	String allianceUnblacklist(String id, String clan, String target)
	{
		return sendResult("POST", "/alliance/unblacklist", form("id", id, "clan", clan, "target", target));
	}

	/** Kick an immediate alliance re-poll so a create/join shows up without waiting for the timer. */
	void refreshAlliancesSoon()
	{
		ScheduledExecutorService e = exec;
		if (e != null)
		{
			e.execute(this::pollAlliances);
		}
	}

	/** Build a newline-separated {@code key=value} body from alternating key,value pairs; null values skipped. */
	private static String form(String... kv)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			if (kv[i + 1] == null)
			{
				continue;
			}
			sb.append(kv[i]).append('=').append(kv[i + 1]).append('\n');
		}
		return sb.toString();
	}

	/** Like {@link #send} but returns the body on any status, so callers can read the server's error text. */
	private String sendResult(String method, String path, String body)
	{
		try
		{
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
					.timeout(Duration.ofSeconds(5));
			b.method(method, body == null
					? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
			HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() / 100 == 2)
			{
				lastOkMs = System.currentTimeMillis();
			}
			return resp.body();
		}
		catch (Exception e)
		{
			log.debug("{} {} failed: {}", method, path, e.toString());
			return null;
		}
	}

	private void flush()
	{
		if (!online || pending.isEmpty())
		{
			return;
		}
		int w = activeWorld;
		List<ClanTurfPoint> batch = new ArrayList<>(pending.values());
		StringBuilder sb = new StringBuilder();
		for (ClanTurfPoint p : batch)
		{
			sb.append(p.getRegionId()).append(',').append(p.getRegionX()).append(',')
					.append(p.getRegionY()).append(',').append(p.getZ()).append(',')
					.append(p.getClanName()).append('\n');
		}
		String r = send("POST", "/claims?world=" + w, sb.toString());
		if (r != null)
		{
			for (ClanTurfPoint p : batch)
			{
				pending.remove(keyOf(p));
			}
		}
	}

	private String send(String method, String path, String body)
	{
		try
		{
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
					.timeout(Duration.ofSeconds(5));
			if ("GET".equals(method))
			{
				b.GET();
			}
			else
			{
				b.method(method, body == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
			}
			HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() / 100 == 2)
			{
				lastOkMs = System.currentTimeMillis();
				return resp.body();
			}
			return null;
		}
		catch (Exception e)
		{
			log.debug("{} {} failed: {}", method, path, e.toString());
			return null;
		}
	}

	private static String keyOf(ClanTurfPoint p)
	{
		return p.getRegionId() + "," + p.getRegionX() + "," + p.getRegionY() + "," + p.getZ();
	}

	private static String normalize(String url)
	{
		if (url == null || url.trim().isEmpty())
		{
			return "http://localhost:8080";
		}
		url = url.trim();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
