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
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * A small, XP-tracker-style HUD box: tiles you've claimed this session and your tiles-per-hour
 * rate. Purely local and cosmetic - a practice/efficiency readout that works the same whether the
 * sync server is on or off. Right-click gives a "Reset" entry to start a fresh session.
 */
class ClanTurfTrackerOverlay extends OverlayPanel
{
	private static final Color AMBER = new Color(0xEB, 0xC7, 0x33);

	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;

	@Inject
	ClanTurfTrackerOverlay(ClanTurfPlugin plugin, ClanTurfConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		getMenuEntries().add(
				new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset", "Tiles per hour"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTileTracker())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
				.text("Tiles Per Hour")
				.color(AMBER)
				.build());
		panelComponent.getChildren().add(LineComponent.builder()
				.left("Tiles Claimed:")
				.right(Integer.toString(plugin.getSessionTiles()))
				.build());
		panelComponent.getChildren().add(LineComponent.builder()
				.left("Current TPH:")
				.right(Integer.toString(plugin.getTilesPerHour()))
				.build());
		panelComponent.getChildren().add(LineComponent.builder()
				.left("Max TPH:")
				.right(Integer.toString(plugin.getMaxTilesPerHour()))
				.build());
		return super.render(graphics);
	}
}
