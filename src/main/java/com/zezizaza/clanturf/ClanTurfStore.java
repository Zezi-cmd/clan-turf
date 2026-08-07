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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The seam. Everything above this interface (claim detection, overlay, panel) is
 * backend-agnostic; everything about <em>where tiles live and how they sync</em> lives
 * behind it.
 *
 * <p>V1 ships {@link ConfigClanTurfStore}, which persists to ConfigManager and is
 * therefore local-only - you see your own clan's claims and no one else's. When a
 * realtime backend is added, it implements this same interface: {@link #putClaim} also
 * publishes the claim, and incoming remote claims land via the same {@link #putClaim}
 * before {@link #setChangeListener} fires to repaint. No caller changes.
 */
interface ClanTurfStore
{
	/**
	 * @param world the world whose ownership we are looking at ({@code client.getWorld()})
	 * @return every claimed tile in that world, across the tracked region(s)
	 */
	Collection<ClanTurfPoint> getClaims(int world);

	/**
	 * Record a claim. Last writer wins - walking onto a tile another clan holds
	 * overwrites it, which is exactly the takeover rule. A backend implementation would
	 * additionally broadcast here.
	 */
	void putClaim(ClanTurfPoint point);

	/**
	 * Remove every claim in the given world (across all tracked regions). Fires the change
	 * listener so the overlay and panel clear. Handy for testing.
	 */
	void clearClaims(int world);

	/**
	 * Register a callback fired whenever the claim set changes (local write today; also
	 * remote writes once a backend exists) so the overlay/panel can repaint. The local
	 * store only ever calls this from its own {@link #putClaim}.
	 */
	default void setChangeListener(Runnable onChange)
	{
	}

	/**
	 * Active-world summaries for the "active battles" board. Only the networked store returns
	 * anything; local play has no cross-world view, so this is empty.
	 */
	default List<ClanTurfBattle> getBattles()
	{
		return Collections.emptyList();
	}

	/** Start any background work (e.g. the sync poller). No-op for the local store. */
	default void start()
	{
	}

	/** Stop background work and release resources. No-op for the local store. */
	default void stop()
	{
	}
}
