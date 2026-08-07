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

import lombok.Value;

/**
 * One claimed tile. This is the record we persist and, later, the exact shape we
 * broadcast when a sync backend is wired in.
 *
 * <p>Modelled on Ground Markers' {@code GroundMarkerPoint} (regionId + regionX +
 * regionY + z) so tiles reconstruct back to a {@link net.runelite.api.coords.WorldPoint}
 * the same way, with two fields added for this plugin:
 * <ul>
 *   <li>{@code world} - ownership is per world, so a tile on world 307 is a
 *       different claim from the same tile on world 308.</li>
 *   <li>{@code clanName} - stamped from {@code client.getClanChannel().getName()}
 *       at claim time. This is the key simplification: the clan is baked onto the tile
 *       when it is claimed, so the overlay never has to work out anyone else's clan -
 *       it just reads this field.</li>
 * </ul>
 *
 * <p>Color is intentionally <em>not</em> stored. It is derived deterministically from
 * {@code clanName} (see {@code ClanTurfColors}) so every client paints the same clan the
 * same color without agreeing on anything.
 */
@Value
public class ClanTurfPoint
{
	int regionId;
	int regionX;
	int regionY;
	int z;
	int world;
	String clanName;
}
