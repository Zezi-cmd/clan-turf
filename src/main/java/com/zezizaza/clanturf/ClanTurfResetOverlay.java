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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * An on-screen countdown to the daily turf reset, shown anywhere on the map (not just at the GE)
 * during the final stretch, so a clan mid-fight isn't caught out by the wipe. It's the plugin's
 * own overlay standing in for the game's "System update" timer, which plugins can't write to.
 */
class ClanTurfResetOverlay extends Overlay
{
	/** Only show the countdown in this final window before the reset. */
	private static final long WINDOW_MS = 10 * 60 * 1000L;

	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;
	private final PanelComponent panel = new PanelComponent();

	@Inject
	ClanTurfResetOverlay(ClanTurfPlugin plugin, ClanTurfConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.resetCountdown())
		{
			return null;
		}
		long ms = plugin.msUntilReset();
		if (ms < 0 || ms > WINDOW_MS)
		{
			return null;
		}

		panel.getChildren().clear();
		panel.getChildren().add(TitleComponent.builder()
				.text("Turf resets in " + format(ms))
				.color(ms < 60_000 ? Color.RED : new Color(0xFF, 0x8C, 0x1A)) // red in the last minute
				.build());
		return panel.render(graphics);
	}

	private static String format(long ms)
	{
		long total = ms / 1000;
		return String.format("%d:%02d", total / 60, total % 60);
	}
}
