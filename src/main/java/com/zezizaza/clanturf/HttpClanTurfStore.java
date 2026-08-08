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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	private final ClanTurfConfig config;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private ScheduledExecutorService exec;
	private volatile String baseUrl = "http://localhost:8080";
	private volatile int activeWorld = -1;
	private volatile List<ClanTurfPoint> cache = Collections.emptyList();
	private volatile List<ClanTurfBattle> battles = Collections.emptyList();

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
		exec = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "clanturf-sync");
			t.setDaemon(true);
			return t;
		});
		exec.scheduleWithFixedDelay(this::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
		exec.scheduleWithFixedDelay(this::flush, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
		exec.scheduleWithFixedDelay(this::pollBattles, 0, BATTLES_POLL_MS, TimeUnit.MILLISECONDS);
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

	/**
	 * Connect-on-demand: the plugin sets the world to sync while the player is near the GE, or
	 * {@code -1} to go idle (the poller then makes no requests, freeing the connection budget).
	 *
	 * <p>On a world change we drop the previous world's snapshot immediately (so its tiles don't
	 * linger on the new world for a poll cycle), forget unsent claims from the old world, and kick
	 * an immediate poll so the new world's tiles appear right away instead of after the next tick.
	 */
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
		if (w < 0)
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

	private void flush()
	{
		if (pending.isEmpty())
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
			return resp.statusCode() / 100 == 2 ? resp.body() : null;
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
