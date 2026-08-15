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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Local-only store, modelled on Ground Markers. Tiles live in ConfigManager under keys
 * like {@code region_307_12598} (world + region), each holding a JSON list of
 * {@link ClanTurfPoint}. Because ConfigManager is per-account, this is single-player:
 * you see your own clan's claims and nobody else's. Swapping in a realtime backend means
 * writing another {@link ClanTurfStore} implementation - nothing outside this class
 * changes.
 */
@Slf4j
@Singleton
class ConfigClanTurfStore implements ClanTurfStore
{
	static final String GROUP = "clanturf";
	private static final String REGION_PREFIX = "region_";
	private static final Type POINT_LIST = new TypeToken<List<ClanTurfPoint>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	private Runnable changeListener = () ->
	{
	};

	@Inject
	ConfigClanTurfStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	@Override
	public void setChangeListener(Runnable onChange)
	{
		this.changeListener = onChange == null ? () ->
		{
		} : onChange;
	}

	@Override
	public Collection<ClanTurfPoint> getClaims(int world)
	{
		// Merge claims from every region the area touches (the fortress can span a boundary).
		List<ClanTurfPoint> all = new ArrayList<>();
		for (int regionId : GrandExchangeArea.regionIds())
		{
			all.addAll(read(world, regionId));
		}
		return all;
	}

	@Override
	public void putClaim(ClanTurfPoint point)
	{
		List<ClanTurfPoint> points = new ArrayList<>(read(point.getWorld(), point.getRegionId()));

		// Replace any existing claim on this exact tile (last writer wins = takeover).
		points.removeIf(p -> p.getRegionX() == point.getRegionX()
				&& p.getRegionY() == point.getRegionY()
				&& p.getZ() == point.getZ());
		points.add(point);

		write(point.getWorld(), point.getRegionId(), points);
		changeListener.run();
	}

	@Override
	public void removeClaim(ClanTurfPoint point)
	{
		List<ClanTurfPoint> points = new ArrayList<>(read(point.getWorld(), point.getRegionId()));
		boolean removed = points.removeIf(p -> p.getRegionX() == point.getRegionX()
				&& p.getRegionY() == point.getRegionY()
				&& p.getZ() == point.getZ());
		if (removed)
		{
			write(point.getWorld(), point.getRegionId(), points);
			changeListener.run();
		}
	}

	@Override
	public void clearClaims(int world)
	{
		for (int regionId : GrandExchangeArea.regionIds())
		{
			configManager.unsetConfiguration(GROUP, key(world, regionId));
		}
		changeListener.run();
	}

	private List<ClanTurfPoint> read(int world, int regionId)
	{
		String json = configManager.getConfiguration(GROUP, key(world, regionId));
		if (json == null || json.isEmpty())
		{
			return Collections.emptyList();
		}
		try
		{
			List<ClanTurfPoint> points = gson.fromJson(json, POINT_LIST);
			return points == null ? Collections.emptyList() : points;
		}
		catch (RuntimeException ex)
		{
			log.warn("Could not parse ClanTurf claims for {}", key(world, regionId), ex);
			return Collections.emptyList();
		}
	}

	private void write(int world, int regionId, List<ClanTurfPoint> points)
	{
		if (points.isEmpty())
		{
			configManager.unsetConfiguration(GROUP, key(world, regionId));
			return;
		}
		configManager.setConfiguration(GROUP, key(world, regionId), gson.toJson(points, POINT_LIST));
	}

	private static String key(int world, int regionId)
	{
		return REGION_PREFIX + world + "_" + regionId;
	}
}
