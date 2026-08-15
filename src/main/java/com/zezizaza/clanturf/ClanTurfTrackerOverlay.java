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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
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
 * sync server is on or off. Right-click gives "Reset run" (zero Claimed and Current, keep Max) and
 * "Reset all" (full wipe) entries.
 */
class ClanTurfTrackerOverlay extends OverlayPanel
{
	private static final Color AMBER = new Color(0xEB, 0xC7, 0x33);
	private static final double FADE_EASE = 0.15; // per-frame ease for the GE-proximity fade

	private final ClanTurfPlugin plugin;
	private final ClanTurfConfig config;
	private double alpha; // 0..1 fade level, eased toward 1 near the GE and 0 away

	@Inject
	ClanTurfTrackerOverlay(ClanTurfPlugin plugin, ClanTurfConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		getMenuEntries().add(
				new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset run", "Tiles per hour"));
		getMenuEntries().add(
				new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset all", "Tiles per hour"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Fade in near the GE and out when away (like the side panel), so the tracker only shows
		// where the turf war is. Off entirely when the toggle is disabled.
		boolean show = config.showTileTracker() && plugin.isNearGe() && !plugin.isSlugPainting();
		alpha += ((show ? 1.0 : 0.0) - alpha) * FADE_EASE;
		if (alpha < 0.02)
		{
			alpha = 0.0;
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

		Composite prev = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(
				AlphaComposite.SRC_OVER, (float) Math.min(1.0, alpha)));
		Dimension size = super.render(graphics);
		graphics.setComposite(prev);
		return size;
	}
}
