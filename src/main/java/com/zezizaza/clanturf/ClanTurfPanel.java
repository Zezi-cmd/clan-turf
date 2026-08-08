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
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar scoreboard: a headline of who controls the GE, then a ranked leaderboard where each
 * clan's bar is scaled to the current leader, so the gap between first and the pack reads at a
 * glance. Rebuilt whenever a claim lands or the world changes.
 */
class ClanTurfPanel extends PluginPanel
{
	private final JLabel header = new JLabel();
	private final JLabel headline = new JLabel();
	private final JLabel clanHint = new JLabel();
	private final Leaderboard board = new Leaderboard();
	private final JLabel battlesHeader = new JLabel("Active battles");
	private final JPanel battlesBox = new JPanel();
	private final JButton clearOfflineBtn = new JButton("Clear my tiles (offline)");
	private final IntConsumer onInvade;

	/**
	 * @param onInvade      hop to the given world (from an Active battles "Invade"/"Defend" button)
	 * @param onClearOffline wipe the current world's local claims (only wired while offline)
	 */
	ClanTurfPanel(IntConsumer onInvade, Runnable onClearOffline)
	{
		this.onInvade = onInvade;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(Color.WHITE);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		headline.setFont(FontManager.getRunescapeFont());
		headline.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		headline.setAlignmentX(Component.LEFT_ALIGNMENT);
		headline.setBorder(BorderFactory.createEmptyBorder(3, 0, 8, 0));

		// Shown only when the player isn't in a clan (nothing to claim turf for).
		clanHint.setText("Join a clan to claim turf.");
		clanHint.setFont(FontManager.getRunescapeSmallFont());
		clanHint.setForeground(new Color(0xEB, 0xC7, 0x33)); // amber, stands out from the gray
		clanHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		clanHint.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		clanHint.setVisible(false);

		board.setAlignmentX(Component.LEFT_ALIGNMENT);

		battlesHeader.setFont(FontManager.getRunescapeBoldFont());
		battlesHeader.setForeground(Color.WHITE);
		battlesHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		battlesHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));

		battlesBox.setLayout(new BoxLayout(battlesBox, BoxLayout.Y_AXIS));
		battlesBox.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Offline-only sandbox control. Always visible so it's easy to find, but only enabled
		// while the sync server is off, so nobody can ever wipe shared/server turf from here.
		// Wipes just the current world's local claims.
		clearOfflineBtn.setFont(FontManager.getRunescapeSmallFont());
		clearOfflineBtn.setFocusable(false);
		clearOfflineBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		clearOfflineBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		clearOfflineBtn.setEnabled(false);
		clearOfflineBtn.setToolTipText(
				"Wipes this world's local claims. Available only with the sync server turned off.");
		clearOfflineBtn.addActionListener(e ->
		{
			if (onClearOffline != null)
			{
				onClearOffline.run();
			}
		});

		top.add(header);
		top.add(headline);
		top.add(clanHint);
		top.add(board);
		top.add(battlesHeader);
		top.add(battlesBox);
		top.add(Box.createVerticalStrut(12));
		top.add(clearOfflineBtn);

		add(top, BorderLayout.NORTH);

		showEmpty("Waiting for the client…");
	}

	/** Enable the offline "Clear my tiles" button only while the sync server is off. */
	void setOfflineControls(boolean offline)
	{
		SwingUtilities.invokeLater(() -> clearOfflineBtn.setEnabled(offline));
	}

	void showEmpty(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			header.setText("Clan Turf");
			headline.setText(message);
			clanHint.setVisible(false);
			board.setData(new ArrayList<>(), 0, null);
		});
	}

	/**
	 * @param claims          every claimed tile in the current world
	 * @param world           the world these claims belong to
	 * @param totalTiles      denominator for the GE-share percentage
	 * @param committedLeader the current owner per the sticky/debounced rule (matches the boundary);
	 *                        it wins ties so the panel's #1 and headline never disagree with the wall
	 * @param myClan          the player's own clan, or null/empty if they're not in one (shows a hint)
	 */
	void update(Collection<ClanTurfPoint> claims, int world, int totalTiles, String committedLeader,
			String myClan)
	{
		Map<String, Long> counts = claims.stream()
				.collect(Collectors.groupingBy(ClanTurfPoint::getClanName, Collectors.counting()));

		// Sort by tiles, but the committed owner wins ties - so at 17-17 the incumbent stays #1,
		// exactly like the boundary. A clan only jumps to #1 once it has strictly more tiles.
		List<Entry> ordered = counts.entrySet().stream()
				.sorted((a, b) ->
				{
					boolean ac = a.getKey().equalsIgnoreCase(committedLeader);
					boolean bc = b.getKey().equalsIgnoreCase(committedLeader);
					if (ac != bc)
					{
						return ac ? -1 : 1;
					}
					return Long.compare(b.getValue(), a.getValue());
				})
				.map(e -> new Entry(e.getKey(), ClanTurfColors.forClan(e.getKey()), e.getValue()))
				.collect(Collectors.toList());

		long claimed = ordered.stream().mapToLong(e -> e.tiles).sum();

		boolean clanless = myClan == null || myClan.trim().isEmpty();

		SwingUtilities.invokeLater(() ->
		{
			header.setText("Clan Turf - World " + world);
			clanHint.setVisible(clanless);

			if (ordered.isEmpty())
			{
				headline.setText("No tiles claimed yet - walk the GE.");
			}
			else
			{
				Entry lead = ordered.get(0);
				String hex = String.format("%02x%02x%02x",
						lead.color.getRed(), lead.color.getGreen(), lead.color.getBlue());
				double gePct = totalTiles > 0 ? claimed * 100.0 / totalTiles : 0.0;
				headline.setText("<html>GE owners: <b style='color:#" + hex + "'>"
						+ escape(lead.clan) + "</b> &nbsp;·&nbsp; "
						+ String.format("%.1f", gePct) + "% Stake</html>");
			}

			board.setData(ordered, totalTiles, myClan);
		});
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * Rebuilds the Active battles list (server mode only; empty otherwise).
	 *
	 * @param myClan the player's own clan, so a world it owns shows "Defend" instead of "Invade"
	 */
	void updateBattles(List<ClanTurfBattle> battles, String myClan)
	{
		List<ClanTurfBattle> list = battles != null ? new ArrayList<>(battles) : new ArrayList<>();
		SwingUtilities.invokeLater(() ->
		{
			battlesBox.removeAll();
			if (list.isEmpty())
			{
				JLabel none = new JLabel("None right now.");
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				battlesBox.add(none);
			}
			else
			{
				for (ClanTurfBattle b : list)
				{
					battlesBox.add(battleRow(b, myClan));
				}
			}
			battlesBox.revalidate();
			battlesBox.repaint();
		});
	}

	private JPanel battleRow(ClanTurfBattle b, String myClan)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		String hex = hex(ClanTurfColors.forClan(b.getOwner()));
		JLabel info = new JLabel("<html><b>W" + b.getWorld() + "</b> &nbsp;<span style='color:#"
				+ hex + "'>" + escape(b.getOwner()) + "</span>&nbsp; " + b.getOwnerTiles()
				+ (b.getOwnerTiles() == 1 ? " tile" : " tiles") + "</html>");
		info.setFont(FontManager.getRunescapeSmallFont());
		info.setForeground(Color.WHITE);

		boolean mine = b.getOwner() != null && b.getOwner().equalsIgnoreCase(myClan);
		JButton hop = new JButton(mine ? "Defend" : "Invade");
		hop.setFont(FontManager.getRunescapeSmallFont());
		hop.setFocusable(false);
		hop.addActionListener(e ->
		{
			if (onInvade != null)
			{
				onInvade.accept(b.getWorld());
			}
		});

		row.add(info, BorderLayout.CENTER);
		row.add(hop, BorderLayout.EAST);
		return row;
	}

	private static String hex(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** One clan's standing: name, color, tiles held. */
	private static final class Entry
	{
		final String clan;
		final Color color;
		final long tiles;

		Entry(String clan, Color color, long tiles)
		{
			this.clan = clan;
			this.color = color;
			this.tiles = tiles;
		}
	}

	/** Live animation state for one clan's row: displayed values ease toward the targets. */
	private static final class Row
	{
		final String clan;
		Color color;
		double tiles;        // displayed count (eases toward targetTiles)
		double targetTiles;
		double y;            // displayed top-of-row pixel (eases toward targetY)
		double targetY;
		int rank;            // target rank (1-based), snapped
		boolean leaving;     // dropped out of the standings, animating out then removed
		double alpha = 1.0;  // row opacity; eases to 0 while leaving so it fades out, not cuts

		Row(String clan, Color color, double tiles, double y)
		{
			this.clan = clan;
			this.color = color;
			this.tiles = tiles;
			this.y = y;
		}
	}

	/**
	 * A ranked bar chart. Each clan gets a full-width row whose colored fill is scaled to the
	 * leader (leader = full bar), with rank, name, tile count and GE% drawn over it. When the
	 * standings change, rows slide to their new slot and bars grow/shrink to their new length
	 * rather than snapping - a Swing timer eases the displayed values toward the targets.
	 */
	private static final class Leaderboard extends JComponent
	{
		private static final int ROW_H = 34;
		private static final int BAR_H = 26;
		private static final int GAP = 4;
		private static final int PITCH = ROW_H + GAP;
		private static final int MIN_FILL = 6;
		private static final int ARC = 6; // bar corner radius: mostly rectangular, lightly rounded
		private static final double EASE = 0.25; // per-frame approach; higher = snappier
		private static final double EPS = 0.4;

		private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
		private int totalTiles;
		private String myClan;           // the player's own clan, so its bar can be highlighted
		private double leader = 1;       // displayed leader count (denominator for bar length)
		private double targetLeader = 1;
		private final Timer timer;

		Leaderboard()
		{
			setForeground(Color.WHITE);
			timer = new Timer(16, e -> tick());
		}

		void setData(List<Entry> entries, int totalTiles, String myClan)
		{
			this.totalTiles = totalTiles;
			this.myClan = myClan;
			boolean wasEmpty = rows.isEmpty();

			double maxT = 1;
			for (Entry e : entries)
			{
				maxT = Math.max(maxT, e.tiles);
			}
			targetLeader = maxT;

			// Everything currently shown is "leaving" until re-confirmed by the new standings.
			for (Row r : rows.values())
			{
				r.leaving = true;
			}

			int slot = 0;
			for (Entry e : entries)
			{
				Row r = rows.get(e.clan);
				if (r == null)
				{
					r = new Row(e.clan, e.color, 0.0, slot * PITCH); // new clan grows in place
					rows.put(e.clan, r);
				}
				r.color = e.color;
				r.targetTiles = e.tiles;
				r.targetY = slot * PITCH;
				r.rank = slot + 1;
				r.leaving = false;
				slot++;
			}
			// Parked leavers sit below the survivors and shrink to nothing.
			for (Row r : rows.values())
			{
				if (r.leaving)
				{
					r.targetTiles = 0;
					r.targetY = slot * PITCH;
					slot++;
				}
			}

			if (wasEmpty)
			{
				// First fill: snap so the panel doesn't animate up from zero on open.
				for (Row r : rows.values())
				{
					r.tiles = r.targetTiles;
					r.y = r.targetY;
				}
				leader = targetLeader;
			}
			else if (!timer.isRunning())
			{
				timer.start();
			}

			updatePreferredSize();
			repaint();
		}

		private void tick()
		{
			boolean settled = true;
			leader += (targetLeader - leader) * EASE;
			if (Math.abs(targetLeader - leader) > EPS)
			{
				settled = false;
			}

			Iterator<Row> it = rows.values().iterator();
			while (it.hasNext())
			{
				Row r = it.next();
				r.tiles += (r.targetTiles - r.tiles) * EASE;
				r.y += (r.targetY - r.y) * EASE;
				double targetAlpha = r.leaving ? 0.0 : 1.0;
				r.alpha += (targetAlpha - r.alpha) * EASE;
				if (Math.abs(r.targetTiles - r.tiles) > EPS || Math.abs(r.targetY - r.y) > EPS
						|| Math.abs(targetAlpha - r.alpha) > 0.02)
				{
					settled = false;
				}
				if (r.leaving && r.alpha < 0.03)
				{
					it.remove();
				}
			}

			if (settled)
			{
				for (Row r : rows.values())
				{
					r.tiles = r.targetTiles;
					r.y = r.targetY;
				}
				leader = targetLeader;
				timer.stop();
				updatePreferredSize();
			}
			repaint();
		}

		private void updatePreferredSize()
		{
			int h = Math.max(1, rows.size()) * PITCH;
			setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, h));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
			revalidate();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (rows.isEmpty())
			{
				return;
			}

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			int w = getWidth();
			double denom = Math.max(1.0, leader);
			Font nameFont = FontManager.getRunescapeBoldFont();
			Font statFont = FontManager.getRunescapeSmallFont();

			// Paint by displayed Y so rows crossing during a reorder stack correctly.
			List<Row> ordered = new ArrayList<>(rows.values());
			ordered.sort((a, b) -> Double.compare(a.y, b.y));

			for (Row r : ordered)
			{
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
						(float) Math.max(0.0, Math.min(1.0, r.alpha))));
				int barY = (int) Math.round(r.y) + (ROW_H - BAR_H) / 2;
				int arc = ARC;
				long shownTiles = Math.round(r.tiles);

				// Track.
				g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g2.fillRoundRect(0, barY, w - 1, BAR_H, arc, arc);

				// Clan fill, scaled to the (animated) leader, with a subtle vertical sheen.
				int fillW = (int) Math.round((r.tiles / denom) * (w - 1));
				fillW = Math.max(MIN_FILL, Math.min(w - 1, fillW));
				Color top = brighten(r.color, 40);
				g2.setPaint(new GradientPaint(0, barY, top, 0, barY + BAR_H, r.color));
				g2.fillRoundRect(0, barY, fillW, BAR_H, arc, arc);

				// Outline - the player's own clan gets a brighter, thicker frame to stand out.
				boolean mine = myClan != null && r.clan.equalsIgnoreCase(myClan);
				if (mine)
				{
					g2.setStroke(new BasicStroke(2f));
					g2.setColor(Color.WHITE);
					g2.drawRoundRect(1, barY + 1, w - 3, BAR_H - 2, arc, arc);
					g2.setStroke(new BasicStroke(1f));
				}
				else
				{
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.drawRoundRect(0, barY, w - 1, BAR_H, arc, arc);
				}

				// Rank + name (left), tiles + GE% (right), over a soft shadow for legibility.
				String name = r.rank + ".  " + r.clan + (mine ? "  (you)" : "");
				double gePct = totalTiles > 0 ? shownTiles * 100.0 / totalTiles : 0.0;
				String stat = shownTiles + "  (" + String.format("%.1f", gePct) + "%)";

				g2.setFont(nameFont);
				int textY = barY + (BAR_H + g2.getFontMetrics().getAscent()) / 2 - 2;
				drawShadowed(g2, name, 8, textY, Color.WHITE);

				g2.setFont(statFont);
				int statW = g2.getFontMetrics().stringWidth(stat);
				drawShadowed(g2, stat, w - statW - 8, textY, Color.WHITE);
			}

			g2.dispose();
		}

		private static void drawShadowed(Graphics2D g2, String s, int x, int y, Color c)
		{
			g2.setColor(new Color(0, 0, 0, 170));
			g2.drawString(s, x + 1, y + 1);
			g2.setColor(c);
			g2.drawString(s, x, y);
		}

		private static Color brighten(Color c, int amt)
		{
			return new Color(
					Math.min(255, c.getRed() + amt),
					Math.min(255, c.getGreen() + amt),
					Math.min(255, c.getBlue() + amt));
		}
	}
}
