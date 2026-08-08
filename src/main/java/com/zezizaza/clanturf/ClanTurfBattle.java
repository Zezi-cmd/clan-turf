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

/**
 * One active-world summary for the "active battles" board: the leading clan and its tile count,
 * plus the runner-up clan (for the "vs" display), or a null runner-up if only one clan holds tiles.
 */
final class ClanTurfBattle
{
	private final int world;
	private final String owner;
	private final int ownerTiles;
	private final int totalTiles;
	private final String runnerUp;
	private final int runnerUpTiles;

	ClanTurfBattle(int world, String owner, int ownerTiles, int totalTiles,
			String runnerUp, int runnerUpTiles)
	{
		this.world = world;
		this.owner = owner;
		this.ownerTiles = ownerTiles;
		this.totalTiles = totalTiles;
		this.runnerUp = runnerUp;
		this.runnerUpTiles = runnerUpTiles;
	}

	int getWorld()
	{
		return world;
	}

	String getOwner()
	{
		return owner;
	}

	int getOwnerTiles()
	{
		return ownerTiles;
	}

	int getTotalTiles()
	{
		return totalTiles;
	}

	/** The second-place clan on this world by tile count, or null if only one clan holds tiles. */
	String getRunnerUp()
	{
		return runnerUp;
	}

	int getRunnerUpTiles()
	{
		return runnerUpTiles;
	}
}
