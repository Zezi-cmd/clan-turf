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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

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
	private final Leaderboard board = new Leaderboard(this::openColorPicker);
	private final JLabel battlesHeader = new JLabel("Active battles");
	private final JPanel battlesBox = new JPanel();
	private final JButton clearOfflineBtn = new JButton("Clear all tiles");
	private final JButton serverToggleBtn = new JButton();
	private final IntConsumer onInvade;
	private final Consumer<Boolean> onSetServer; // flips the sync-server (online/offline) config
	private final ColorPickerManager colorPickerManager;
	private final BiConsumer<String, Color> onClanColorChosen; // (clan, chosen color) -> plugin persists
	private final Consumer<Boolean> onSetSlug;   // offline Full Slug toggle -> plugin persists
	private final Consumer<Boolean> onSetEraser; // offline Eraser toggle -> plugin persists
	private final JButton eraserBtn = new JButton();
	private boolean eraserOn;                     // mirrored Eraser state for the button label
	private final Consumer<String> onAddClan;    // offline: add a test clan to paint as
	private final Consumer<String> onSelectClan; // offline: paint as this clan
	private final Consumer<String> onRemoveClan; // offline: remove a test clan
	private final JButton slugBtn = new JButton();
	private final FadePanel sandboxBox = new FadePanel(); // offline-only tools, fades in on going offline
	private final JPanel paintClansBox = new JPanel();    // the paint-as roster rows
	private boolean slugOn;                       // mirrored Full Slug state for the button label
	private boolean serverOn = true;             // current mode, mirrored from the config

	// "Community Claims": the all-time community counter, shown only in server mode, with a count-up
	// animation each time the total ticks up.
	private final FadePanel globalBox = new FadePanel();
	private final JLabel globalHeader = new JLabel("Community Claims");
	private final JLabel globalIntro = new JLabel(); // sits under the title, above the number
	private final JLabel globalCount = new JLabel();
	private final JLabel globalSub = new JLabel();
	private long globalShown;   // the number currently on screen
	private long globalTarget;  // the latest total from the server, animated toward
	private Timer globalTimer;  // eases globalShown up to globalTarget

	// Coming-online reveal: when the sync toggle flips offline -> online, the sections cascade in - the
	// bars fade via the scoreboard, then the battle rows one at a time, then the community counter.
	private boolean revealBarsPending;
	private boolean revealBattlesPending;
	private boolean revealCommunityPending;
	private long revealCommunityAt;
	private long revealHoldUntil;   // hold the offline content until online data arrives, up to this time
	private final Map<FadePanel, Long> reveals = new LinkedHashMap<>();
	private Timer revealTimer;

	// The last Active-battles content we actually rendered. The plugin refreshes the panel every few
	// ticks; without this guard updateBattles() would tear the list down and rebuild it every time,
	// flashing the section. We only rebuild when this signature changes.
	private String lastBattlesSig;
	private static final long REVEAL_FADE_MS = 160;       // per-element fade-in length
	private static final long REVEAL_ROW_STAGGER = 55;    // gap between battle rows in the cascade
	private static final long REVEAL_BATTLES_DELAY = 120; // battles start just after the bars
	private static final long REVEAL_COMMUNITY_GAP = 130; // community starts after the battles cascade
	private static final long REVEAL_HOLD_MS = 3000;      // max hold of offline content on coming online

	/** Sticky scoreboard order (clan names) for the current world, so tied clans hold their slot
	 * instead of shuffling when a new clan arrives. Reset when the world changes. */
	private int boardOrderWorld = -1;
	private final List<String> boardOrder = new ArrayList<>();

	/**
	 * @param onInvade           hop to the given world (from an Active battles "Invade"/"Defend" button)
	 * @param onClearOffline     wipe the current world's local claims (only wired while offline)
	 * @param onSetServer        turn the sync server on/off (the panel's Online/Offline toggle)
	 * @param colorPickerManager opens the RuneLite color wheel when a scoreboard bar is clicked
	 * @param onClanColorChosen  (clan, chosen color) - the plugin persists it (own color vs color list)
	 */
	ClanTurfPanel(IntConsumer onInvade, Runnable onClearOffline, Consumer<Boolean> onSetServer,
			ColorPickerManager colorPickerManager, BiConsumer<String, Color> onClanColorChosen,
			Consumer<Boolean> onSetSlug, Consumer<String> onAddClan, Consumer<String> onSelectClan,
			Consumer<String> onRemoveClan, Consumer<Boolean> onSetEraser)
	{
		this.onInvade = onInvade;
		this.onSetServer = onSetServer;
		this.colorPickerManager = colorPickerManager;
		this.onClanColorChosen = onClanColorChosen;
		this.onSetSlug = onSetSlug;
		this.onAddClan = onAddClan;
		this.onSelectClan = onSelectClan;
		this.onRemoveClan = onRemoveClan;
		this.onSetEraser = onSetEraser;

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

		// Online/Offline toggle, sitting next to the Clear button. Flips the sync-server config; the
		// change swings back through setOfflineControls to relabel this and enable/disable Clear.
		serverToggleBtn.setFont(FontManager.getRunescapeSmallFont());
		serverToggleBtn.setFocusable(false);
		serverToggleBtn.setMaximumSize(new Dimension(72, 28));
		serverToggleBtn.setToolTipText("Online: your claims sync with every clan. Offline: local practice "
				+ "only, nothing is sent.");
		serverToggleBtn.addActionListener(e ->
		{
			if (onSetServer != null)
			{
				onSetServer.accept(!serverOn);
			}
		});

		// "Community Claims": header, a large animated count, and a thank-you line. Hidden until a real
		// total arrives (server mode only).
		globalHeader.setFont(FontManager.getRunescapeBoldFont());
		globalHeader.setForeground(Color.WHITE);
		globalHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		globalHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));

		globalIntro.setFont(FontManager.getRunescapeSmallFont());
		globalIntro.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		globalIntro.setAlignmentX(Component.LEFT_ALIGNMENT);
		globalIntro.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		globalIntro.setOpaque(true);
		globalIntro.setBackground(ColorScheme.DARK_GRAY_COLOR);
		globalIntro.setText("<html><body style='width:170px'>Every tile claimed or stolen by everyone "
				+ "playing Clan Turf since launch.</body></html>");

		globalCount.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
		globalCount.setForeground(new Color(0xEB, 0xC7, 0x33)); // celebratory amber
		globalCount.setAlignmentX(Component.LEFT_ALIGNMENT);
		// Opaque against the panel background so the count-up timer's rapid text changes clear and
		// repaint in place, instead of ghosting old digits and forcing a repaint of the whole section.
		globalCount.setOpaque(true);
		globalCount.setBackground(ColorScheme.DARKER_GRAY_COLOR); // matches the box it sits in

		globalSub.setFont(FontManager.getRunescapeSmallFont());
		globalSub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		globalSub.setAlignmentX(Component.LEFT_ALIGNMENT);
		globalSub.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		globalSub.setOpaque(true); // same, so it repaints cleanly if a count change relays out the box
		globalSub.setBackground(ColorScheme.DARK_GRAY_COLOR);
		globalSub.setText("<html><body style='width:170px'>THANK YOU for downloading my plugin and joining "
				+ "the turf war! Keep pushing that number higher, maybe something interesting will happen"
				+ "...</body></html>");

		// Put the number in a thin bordered box so the whole count row reads as one field.
		JPanel countBox = new JPanel(new BorderLayout());
		countBox.setOpaque(true);
		countBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		countBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		countBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		countBox.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
				BorderFactory.createEmptyBorder(2, 6, 2, 6)));
		countBox.add(globalCount, BorderLayout.WEST);

		globalBox.setLayout(new BoxLayout(globalBox, BoxLayout.Y_AXIS));
		globalBox.setOpaque(false);
		globalBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		globalBox.add(globalHeader);
		globalBox.add(globalIntro);
		globalBox.add(countBox);
		globalBox.add(globalSub);
		globalBox.setVisible(false);

		// Controls row: just the Online/Offline toggle now (Clear moved into the offline sandbox), kept
		// at its current size and left-aligned. Pinned under the GE-owners headline, above the bars, so
		// the scoreboard, battles and community below all animate out beneath it on a toggle.
		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
		controls.setOpaque(false);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		controls.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
		controls.add(serverToggleBtn);
		controls.add(Box.createHorizontalGlue());

		top.add(header);
		top.add(headline);
		top.add(clanHint);
		top.add(controls);
		top.add(board);
		top.add(battlesHeader);
		top.add(battlesBox);
		top.add(globalBox);

		// Feedback / bug report: opens the plugin's GitHub issue tracker in the browser. The plugin
		// collects nothing here - reports live on GitHub, not on our server. Set in a slightly lighter
		// box so it reads as its own footer, apart from the scoreboard above.
		JLabel reportBlurb = new JLabel("<html><body style='width:150px'>I can't be tick-perfect all the "
				+ "time. Found a bug with the plug? 1-tick-click that Report Button.</body></html>");
		reportBlurb.setFont(FontManager.getRunescapeSmallFont());
		reportBlurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reportBlurb.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton reportBtn = new JButton("Report");
		reportBtn.setFont(FontManager.getRunescapeSmallFont());
		reportBtn.setFocusable(false);
		reportBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		reportBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		reportBtn.setToolTipText("Open the Clan Turf bug tracker on GitHub in your browser.");
		reportBtn.addActionListener(e -> LinkBrowser.browse("https://github.com/Zezi-cmd/clan-turf/issues/new"));

		JPanel reportBox = new JPanel();
		reportBox.setLayout(new BoxLayout(reportBox, BoxLayout.Y_AXIS));
		reportBox.setOpaque(true);
		reportBox.setBackground(new Color(0x36, 0x36, 0x36)); // a touch lighter than the panel
		reportBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		reportBox.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		reportBox.add(reportBlurb);
		reportBox.add(Box.createVerticalStrut(6));
		reportBox.add(reportBtn);

		// Offline sandbox: creative/practice tools that only make sense with no live turf war. Hidden
		// online; fades in when you go offline. Phase 1 is the Full Slug paint toggle.
		JLabel sandboxHeader = new JLabel("Offline Tools:");
		sandboxHeader.setFont(FontManager.getRunescapeBoldFont());
		sandboxHeader.setForeground(Color.WHITE);
		sandboxHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		sandboxHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));

		slugBtn.setFont(FontManager.getRunescapeSmallFont());
		slugBtn.setFocusable(false);
		slugBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		slugBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		slugBtn.setToolTipText("Act on every tile you cross, not just the one you land on (applies to "
				+ "claiming and to Surrender). Offline only; hides the tiles/hour tracker while on.");
		slugBtn.addActionListener(e ->
		{
			boolean next = !slugOn;
			setSlug(next); // optimistic label update
			if (onSetSlug != null)
			{
				onSetSlug.accept(next);
			}
		});

		eraserBtn.setFont(FontManager.getRunescapeSmallFont());
		eraserBtn.setFocusable(false);
		eraserBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		eraserBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		eraserBtn.setToolTipText("Surrender: your steps erase claimed tiles back to unclaimed instead of "
				+ "claiming (unclaimed tiles are left alone). Full Slug makes it erase every tile you "
				+ "cross. Offline only.");
		eraserBtn.addActionListener(e ->
		{
			boolean next = !eraserOn;
			setEraser(next); // optimistic label update
			if (onSetEraser != null)
			{
				onSetEraser.accept(next);
			}
		});

		// "Paint as" roster: add test clans and click one to paint as it. Recolor any of them by clicking
		// its scoreboard bar up top, same as always.
		JLabel paintHeader = new JLabel("Claim tiles as");
		paintHeader.setFont(FontManager.getRunescapeSmallFont());
		paintHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		paintHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		paintHeader.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));

		JButton addClanBtn = new JButton("Add clan");
		addClanBtn.setFont(FontManager.getRunescapeSmallFont());
		addClanBtn.setFocusable(false);
		addClanBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		addClanBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		addClanBtn.setToolTipText("Add a test clan you can paint as (offline sandbox only).");
		addClanBtn.addActionListener(e ->
		{
			String name = JOptionPane.showInputDialog(this, "Clan name:", "Add clan",
					JOptionPane.PLAIN_MESSAGE);
			if (name != null && !name.trim().isEmpty() && onAddClan != null)
			{
				onAddClan.accept(name.trim());
			}
		});

		paintClansBox.setLayout(new BoxLayout(paintClansBox, BoxLayout.Y_AXIS));
		paintClansBox.setOpaque(false);
		paintClansBox.setAlignmentX(Component.LEFT_ALIGNMENT);

		sandboxBox.setLayout(new BoxLayout(sandboxBox, BoxLayout.Y_AXIS));
		sandboxBox.setOpaque(false);
		sandboxBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		sandboxBox.add(sandboxHeader);
		sandboxBox.add(clearOfflineBtn);
		sandboxBox.add(Box.createVerticalStrut(4));
		sandboxBox.add(slugBtn);
		sandboxBox.add(eraserBtn);
		sandboxBox.add(paintHeader);
		sandboxBox.add(paintClansBox);
		sandboxBox.add(Box.createVerticalStrut(4));
		sandboxBox.add(addClanBtn);
		sandboxBox.setVisible(false);
		setSlug(false);
		setEraser(false);

		top.add(sandboxBox);

		// Report sits at the very bottom in both modes (below the offline tools when they're shown).
		top.add(Box.createVerticalStrut(10));
		top.add(reportBox);

		add(top, BorderLayout.NORTH);

		showEmpty("Waiting for the client…");
	}

	/**
	 * Reflect the current sync mode: relabel the Online/Offline toggle, and enable the Clear button only
	 * while offline (so nobody can ever wipe shared/server turf from here). Called on startup and on any
	 * change to the sync-server config, whether it came from this panel's toggle or the settings.
	 */
	void setOfflineControls(boolean offline)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean wasOnline = serverOn;
			serverOn = !offline;
			serverToggleBtn.setText(serverOn ? "Online" : "Offline");
			serverToggleBtn.setForeground(serverOn
					? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			clearOfflineBtn.setEnabled(offline);
			// Offline sandbox tools: shown only offline, faded in on the online -> offline switch.
			sandboxBox.setVisible(offline);
			if (offline && wasOnline)
			{
				scheduleReveal(sandboxBox, System.currentTimeMillis());
			}
			if (serverOn && !wasOnline)
			{
				// Just came online: hold the offline content until the server data loads (so it doesn't
				// blank out), then cascade the sections in as each one's data arrives.
				revealBarsPending = true;
				revealBattlesPending = true;
				revealCommunityPending = true;
				revealCommunityAt = System.currentTimeMillis() + REVEAL_BATTLES_DELAY;
				revealHoldUntil = System.currentTimeMillis() + REVEAL_HOLD_MS;
			}
		});
	}

	/** Reflect the offline Full Slug state on its button (from the plugin's saved state, or a click). */
	void setSlug(boolean on)
	{
		SwingUtilities.invokeLater(() ->
		{
			slugOn = on;
			slugBtn.setText(on ? "Full Slug: On" : "Full Slug: Off");
			slugBtn.setForeground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		});
	}

	/** Reflect the offline Eraser state on its button (from the plugin's saved state, or a click). */
	void setEraser(boolean on)
	{
		SwingUtilities.invokeLater(() ->
		{
			eraserOn = on;
			eraserBtn.setText(on ? "Surrender tiles: On" : "Surrender tiles: Off");
			eraserBtn.setForeground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		});
	}

	/** Rebuild the offline "paint as" roster: your clan (locked) plus test clans, the selected one lit. */
	void setPaintClans(List<String> clans, String realClan, String selected)
	{
		SwingUtilities.invokeLater(() ->
		{
			paintClansBox.removeAll();
			for (String clan : clans)
			{
				boolean isReal = realClan != null && clan.equalsIgnoreCase(realClan);
				boolean isSelected = selected != null && clan.equalsIgnoreCase(selected);
				paintClansBox.add(paintClanRow(clan, isReal, isSelected));
			}
			paintClansBox.revalidate();
			paintClansBox.repaint();
		});
	}

	/** One roster row: a clickable clan name in its color, plus an 'x' remove button for test clans. */
	private JPanel paintClanRow(String clan, boolean isReal, boolean isSelected)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(true);
		row.setBackground(isSelected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 4));

		JLabel name = new JLabel((isSelected ? "→ " : "") + clan);
		name.setFont(isSelected ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		name.setForeground(ClanTurfColors.forClan(clan));
		name.setToolTipText("Paint as " + clan);
		name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		name.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onSelectClan != null)
				{
					onSelectClan.accept(clan);
				}
			}
		});
		row.add(name, BorderLayout.CENTER);

		if (!isReal)
		{
			JButton remove = new JButton("x");
			remove.setFont(FontManager.getRunescapeSmallFont());
			remove.setFocusable(false);
			remove.setMargin(new Insets(0, 4, 0, 4));
			remove.setToolTipText("Remove " + clan);
			remove.addActionListener(e ->
			{
				if (onRemoveClan != null)
				{
					onRemoveClan.accept(clan);
				}
			});
			row.add(remove, BorderLayout.EAST);
		}
		return row;
	}

	void showEmpty(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			header.setText("Clan Turf");
			headline.setText(message);
			clanHint.setVisible(false);
			board.setData(new ArrayList<>(), 0, null);
			globalBox.setVisible(false);
		});
	}

	/**
	 * Feed the all-time community "Global Claims" total. Zero (local mode, or before the first poll)
	 * hides the section; a higher number animates the counter up to it. The first real value snaps, so
	 * we don't count up from zero across millions on login.
	 */
	void setGlobalClaims(long serverTotal)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (serverTotal <= 0)
			{
				globalBox.setVisible(false);
				return;
			}
			globalBox.setVisible(true);
			if (revealCommunityPending)
			{
				// Coming online: fade the whole section in, timed to land after the battles cascade.
				revealCommunityPending = false;
				scheduleReveal(globalBox, Math.max(revealCommunityAt, System.currentTimeMillis()));
			}
			if (globalTarget <= 0)
			{
				// First real total: snap, so we don't count up from zero across millions on login.
				globalShown = serverTotal;
				globalTarget = serverTotal;
				showCount(serverTotal);
				return;
			}
			// The server total is authoritative, but our optimistic local ticks can run ahead of it
			// between polls, so only ever animate UP to it - never jump the counter backward.
			if (serverTotal > globalTarget)
			{
				globalTarget = serverTotal;
				startCountUp();
			}
		});
	}

	/**
	 * One tile the local player just claimed: tick the counter up right away for instant feedback. The
	 * periodic server total ({@link #setGlobalClaims}) then folds in everyone else's with a bigger
	 * count-up. No-op until the first server total has arrived, so it only counts in server mode.
	 */
	void addLocalClaim()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (globalTarget <= 0)
			{
				return; // no real total yet (local mode, or before the first poll)
			}
			globalTarget += 1;
			startCountUp();
		});
	}

	/** Eases the displayed number up to the latest target - bigger jumps first, settling in. */
	private void startCountUp()
	{
		if (globalTimer != null && globalTimer.isRunning())
		{
			return; // already animating; it reads globalTarget each tick, so it chases the new value
		}
		globalTimer = new Timer(40, e ->
		{
			long diff = globalTarget - globalShown;
			if (diff <= 0)
			{
				globalShown = globalTarget;
				showCount(globalShown);
				globalTimer.stop();
				return;
			}
			globalShown += Math.max(1, diff / 8);
			if (globalShown > globalTarget)
			{
				globalShown = globalTarget;
			}
			showCount(globalShown);
		});
		globalTimer.start();
	}

	private static String fmt(long v)
	{
		return String.format("%,d", v);
	}

	/** Set the community count text and color it by tier, so the number visibly climbs the ranks as the
	 * community total grows (white -&gt; gold -&gt; green -&gt; cyan -&gt; purple -&gt; orange). */
	private void showCount(long v)
	{
		globalCount.setText(fmt(v));
		globalCount.setForeground(colorForClaims(v));
	}

	/** Tiered color for the community counter, loot-beam style: a bigger total climbs to a rarer color.
	 * Thresholds and colors are easy to retune. */
	private static Color colorForClaims(long n)
	{
		if (n >= 100_000_000L)
		{
			return new Color(0xFF7A33); // 100M+  orange
		}
		if (n >= 50_000_000L)
		{
			return new Color(0xB84BFF); // 50M+   purple
		}
		if (n >= 10_000_000L)
		{
			return new Color(0x33D6EB); // 10M+   cyan
		}
		if (n >= 1_000_000L)
		{
			return new Color(0x4BE04B); // 1M+    green
		}
		if (n >= 100_000L)
		{
			return new Color(0xEBC733); // 100k+  gold
		}
		return Color.WHITE;             // < 100k white
	}

	/** A panel that can be faded in (alpha 0 -&gt; 1), used for the coming-online reveal cascade. */
	private static final class FadePanel extends JPanel
	{
		private float alpha = 1f;

		FadePanel()
		{
			setOpaque(false); // non-opaque so the fade composites cleanly over the parent
		}

		FadePanel(java.awt.LayoutManager layout)
		{
			super(layout);
			setOpaque(false); // non-opaque so the fade composites cleanly over the parent
		}

		void setAlpha(float a)
		{
			alpha = Math.max(0f, Math.min(1f, a));
			repaint();
		}

		@Override
		public void paint(Graphics g)
		{
			if (alpha >= 1f)
			{
				super.paint(g);
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			super.paint(g2);
			g2.dispose();
		}
	}

	/** Fade a panel in starting at {@code startMs} (epoch ms), driven by one shared reveal timer. */
	private void scheduleReveal(FadePanel c, long startMs)
	{
		c.setAlpha(0f);
		reveals.put(c, startMs);
		if (revealTimer == null)
		{
			revealTimer = new Timer(25, e -> tickReveals());
		}
		if (!revealTimer.isRunning())
		{
			revealTimer.start();
		}
	}

	private void tickReveals()
	{
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<FadePanel, Long>> it = reveals.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<FadePanel, Long> e = it.next();
			long start = e.getValue();
			float a = now <= start ? 0f : Math.min(1f, (now - start) / (float) REVEAL_FADE_MS);
			e.getKey().setAlpha(a);
			if (a >= 1f)
			{
				it.remove();
			}
		}
		if (reveals.isEmpty())
		{
			revealTimer.stop();
		}
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
			String myClan, ClanTurfBattle currentBattle, ClanTurfStore.ConnectionStatus status,
			boolean clanHintDue)
	{
		Map<String, Long> counts = claims.stream()
				.collect(Collectors.groupingBy(ClanTurfPoint::getClanName, Collectors.counting()));

		// Sticky, stable order so tied clans hold their slot instead of shuffling when a new clan
		// arrives or ties them. The committed owner still wins ties for #1 (matches the boundary);
		// among the rest, a clan only passes another by STRICTLY out-tiling it. Reset per world.
		if (world != boardOrderWorld)
		{
			boardOrderWorld = world;
			boardOrder.clear();
		}
		List<String> base = stickyBase(counts.keySet(), counts);
		base.sort((a, b) ->
		{
			boolean ac = a.equalsIgnoreCase(committedLeader);
			boolean bc = b.equalsIgnoreCase(committedLeader);
			if (ac != bc)
			{
				return ac ? -1 : 1;
			}
			return Long.compare(counts.get(b), counts.get(a)); // stable: ties keep the sticky base
		});
		boardOrder.clear();
		boardOrder.addAll(base);
		List<Entry> ordered = base.stream()
				.map(clan -> new Entry(clan, ClanTurfColors.forClan(clan), counts.get(clan)))
				.collect(Collectors.toList());

		long claimed = ordered.stream().mapToLong(e -> e.tiles).sum();

		// Only show the "join a clan" hint once we're confident: clan-less AND the channel has had time
		// to load. Otherwise it flashes at clan members during the login/connect wait before their clan
		// channel arrives.
		boolean clanless = (myClan == null || myClan.trim().isEmpty()) && clanHintDue;

		SwingUtilities.invokeLater(() ->
		{
			if (revealBarsPending)
			{
				// Coming online: hold the offline bars until the server's claims load (empty until the
				// first poll), instead of blanking them. Checked here on the EDT so it sees the flag that
				// setOfflineControls arms (also on the EDT). Release on real data, or when the hold times out.
				if (ordered.isEmpty() && System.currentTimeMillis() < revealHoldUntil)
				{
					return;
				}
				revealBarsPending = false;
			}

			header.setText("Clan Turf - World " + world);
			clanHint.setVisible(clanless);

			if (ordered.isEmpty())
			{
				if (currentBattle != null && currentBattle.getOwner() != null)
				{
					// Away from the GE the local bars are gated off, but the always-on battles data
					// still knows who holds your world - keep the headline live.
					Color oc = ClanTurfColors.forClan(currentBattle.getOwner());
					String ohex = String.format("%02x%02x%02x",
							oc.getRed(), oc.getGreen(), oc.getBlue());
					double pct = currentBattle.getTotalTiles() > 0
							? currentBattle.getOwnerTiles() * 100.0 / currentBattle.getTotalTiles() : 0.0;
					headline.setText("<html>GE owners: <b style='color:#" + ohex + "'>"
							+ escape(currentBattle.getOwner()) + "</b> &nbsp;·&nbsp; "
							+ String.format("%.1f", pct) + "% Stake</html>");
				}
				else if (status == ClanTurfStore.ConnectionStatus.CONNECTING)
				{
					// Cold start: the server hasn't answered yet, so don't imply the GE is empty.
					headline.setText("Connecting to the sync server…");
				}
				else if (status == ClanTurfStore.ConnectionStatus.OFFLINE)
				{
					headline.setText("Sync server unreachable - retrying.");
				}
				else
				{
					headline.setText("No tiles claimed yet - walk the GE.");
				}
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
	 * @param myClan       the player's own clan, so a world it owns shows "Defend" instead of "Invade"
	 * @param currentWorld the world you're on, so its row drops the button and shows larger
	 */
	void updateBattles(List<ClanTurfBattle> battles, String myClan, int currentWorld)
	{
		List<ClanTurfBattle> list = battles != null ? new ArrayList<>(battles) : new ArrayList<>();
		SwingUtilities.invokeLater(() ->
		{
			if (revealBattlesPending && list.isEmpty() && System.currentTimeMillis() < revealHoldUntil)
			{
				return; // coming online: hold the offline battles until the server's load in
			}
			// Skip the teardown/rebuild when nothing changed, so the list doesn't flash every refresh.
			// A pending reveal always rebuilds (it needs fresh rows to cascade in).
			String sig = battlesSignature(list, myClan, currentWorld);
			if (!revealBattlesPending && sig.equals(lastBattlesSig))
			{
				return;
			}
			lastBattlesSig = sig;
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
				boolean cascade = revealBattlesPending; // set when we just came online
				revealBattlesPending = false;
				long base = System.currentTimeMillis() + REVEAL_BATTLES_DELAY;
				int i = 0;
				for (ClanTurfBattle b : list)
				{
					FadePanel row = battleRow(b, myClan, currentWorld);
					battlesBox.add(row);
					if (cascade)
					{
						scheduleReveal(row, base + i * REVEAL_ROW_STAGGER); // top-down cascade
					}
					i++;
				}
				if (cascade)
				{
					// Line the community counter up to arrive just after the last battle row.
					revealCommunityAt = base + (long) list.size() * REVEAL_ROW_STAGGER + REVEAL_COMMUNITY_GAP;
				}
			}
			battlesBox.revalidate();
			battlesBox.repaint();
		});
	}

	/**
	 * A stable fingerprint of the Active-battles content: everything that changes what the rows look
	 * like (the world you're on and your clan drive the current-world pin and Defend/Invade labels, so
	 * they're included). If two calls produce the same string, the rendered list would be identical.
	 */
	private static String battlesSignature(List<ClanTurfBattle> list, String myClan, int currentWorld)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(currentWorld).append('|').append(myClan == null ? "" : myClan).append('#');
		for (ClanTurfBattle b : list)
		{
			sb.append(b.getWorld()).append(',').append(b.getOwner()).append(',')
					.append(b.getOwnerTiles()).append(',').append(b.getTotalTiles()).append(',')
					.append(b.getRunnerUp()).append(',').append(b.getRunnerUpTiles()).append(',')
					// Include the clans' current colors so a recolor rebuilds the rows too.
					.append(ClanTurfColors.forClan(b.getOwner()).getRGB()).append(',')
					.append(ClanTurfColors.forClan(b.getRunnerUp()).getRGB()).append(';');
		}
		return sb.toString();
	}

	/**
	 * The sticky base order for the scoreboard: clans shown last time keep their prior relative
	 * order, and any new clans are appended (most tiles first, name as a stable tiebreak). The
	 * caller then stable-sorts this by rank, so a tie holds whoever was already ahead.
	 */
	private List<String> stickyBase(Collection<String> clans, Map<String, Long> counts)
	{
		List<String> base = new ArrayList<>();
		for (String clan : boardOrder)
		{
			if (clans.contains(clan) && !base.contains(clan))
			{
				base.add(clan);
			}
		}
		List<String> fresh = new ArrayList<>();
		for (String clan : clans)
		{
			if (!base.contains(clan))
			{
				fresh.add(clan);
			}
		}
		fresh.sort((a, b) ->
		{
			int d = Long.compare(counts.get(b), counts.get(a));
			return d != 0 ? d : a.compareTo(b);
		});
		base.addAll(fresh);
		return base;
	}

	private FadePanel battleRow(ClanTurfBattle b, String myClan, int currentWorld)
	{
		FadePanel row = new FadePanel(new BorderLayout(6, 0));
		boolean mine = b.getOwner() != null && b.getOwner().equalsIgnoreCase(myClan);
		boolean current = b.getWorld() == currentWorld; // the world you're on: no button, larger text
		// Every row gets a left accent bar in the owning clan's color (a quick "who holds this world"
		// cue), with a divider underneath.
		javax.swing.border.Border divider =
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR);
		javax.swing.border.Border accent =
				BorderFactory.createMatteBorder(0, 3, 0, 0, ClanTurfColors.forClan(b.getOwner()));
		row.setBorder(BorderFactory.createCompoundBorder(divider,
				BorderFactory.createCompoundBorder(accent,
						BorderFactory.createEmptyBorder(5, 5, 5, 0))));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
				b.getRunnerUp() != null ? (current ? 58 : 46) : (current ? 34 : 30)));

		String ownerHex = hex(ClanTurfColors.forClan(b.getOwner()));

		if (current)
		{
			// Active world: world tag pinned left, the matchup centered in the space to its right so
			// it sits between W# and the panel's right edge.
			JLabel worldLabel = new JLabel("<html><b>W" + b.getWorld() + "</b></html>");
			worldLabel.setFont(FontManager.getRunescapeFont());
			worldLabel.setForeground(Color.WHITE);
			row.add(worldLabel, BorderLayout.WEST);

			String ownerName = "<span style='color:#" + ownerHex + "'>" + escape(b.getOwner())
					+ "</span>";
			String matchup;
			if (b.getRunnerUp() != null)
			{
				// Each clan and its tile count are centered in one column, so the clan name sits
				// directly above its tiles, with the "vs" between the two columns.
				String upHex = hex(ClanTurfColors.forClan(b.getRunnerUp()));
				String upName = "<span style='color:#" + upHex + "'>" + escape(b.getRunnerUp())
						+ "</span>";
				matchup = "<html><table cellpadding=0 cellspacing=0>"
						+ "<tr><td align='center'>" + ownerName + "</td><td>&nbsp;vs&nbsp;</td>"
						+ "<td align='center'>" + upName + "</td></tr>"
						+ "<tr><td align='center'>" + b.getOwnerTiles() + tileWord(b.getOwnerTiles())
						+ "</td><td></td><td align='center'>" + b.getRunnerUpTiles()
						+ tileWord(b.getRunnerUpTiles()) + "</td></tr></table></html>";
			}
			else
			{
				matchup = "<html>" + ownerName + "&nbsp;" + b.getOwnerTiles()
						+ tileWord(b.getOwnerTiles()) + "</html>";
			}
			JLabel matchupLabel = new JLabel(matchup);
			matchupLabel.setFont(FontManager.getRunescapeFont());
			matchupLabel.setForeground(Color.WHITE);
			matchupLabel.setHorizontalAlignment(JLabel.CENTER);
			row.add(matchupLabel, BorderLayout.CENTER);
			return row;
		}

		// Other worlds: a single compact label plus the Invade/Defend button.
		String worldTag = "<b>W" + b.getWorld() + "</b>";
		String ownerCell = "<span style='color:#" + ownerHex + "'>" + escape(b.getOwner())
				+ "</span>&nbsp;" + b.getOwnerTiles();
		String html;
		if (b.getRunnerUp() != null)
		{
			String upHex = hex(ClanTurfColors.forClan(b.getRunnerUp()));
			String upCell = "<span style='color:#" + upHex + "'>" + escape(b.getRunnerUp())
					+ "</span>&nbsp;" + b.getRunnerUpTiles();
			html = "<html><table cellpadding=0 cellspacing=0>"
					+ "<tr><td>" + worldTag + "&nbsp;</td><td>" + ownerCell
					+ "</td><td rowspan=2 valign='middle'>&nbsp;vs&nbsp;</td></tr>"
					+ "<tr><td></td><td>" + upCell + "</td></tr></table></html>";
		}
		else
		{
			html = "<html>" + worldTag + "&nbsp; " + ownerCell + "</html>";
		}
		JLabel info = new JLabel(html);
		info.setFont(FontManager.getRunescapeSmallFont());
		info.setForeground(Color.WHITE);
		row.add(info, BorderLayout.CENTER);

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
		row.add(hop, BorderLayout.EAST);
		return row;
	}

	private static String hex(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private static String tileWord(int n)
	{
		return n == 1 ? " tile" : " tiles";
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
	 * Opens the RuneLite color wheel for a clan (from clicking its scoreboard bar), seeded with that
	 * clan's current color. On close the chosen color goes to the plugin, which persists it as your own
	 * color or into the shared clan color list. Local only - nobody else sees your palette.
	 */
	private void openColorPicker(String clan)
	{
		if (clan == null || clan.isEmpty() || colorPickerManager == null)
		{
			return;
		}
		Window parent = SwingUtilities.getWindowAncestor(this);
		RuneliteColorPicker picker = colorPickerManager.create(
				parent, ClanTurfColors.forClan(clan), "Color for " + clan, true);
		picker.setLocationRelativeTo(parent);
		picker.setOnClose(c -> onClanColorChosen.accept(clan, c));
		picker.setVisible(true);
	}

	/**
	 * A ranked bar chart. Each clan gets a full-width row whose colored fill is scaled to the
	 * leader (leader = full bar), with rank, name, tile count and GE% drawn over it. When the
	 * standings change, rows slide to their new slot and bars grow/shrink to their new length
	 * rather than snapping - a Swing timer eases the displayed values toward the targets.
	 *
	 * <p>Bars are interactive: hovering highlights the row, and clicking opens the color wheel for
	 * that clan via {@code onClickClan}.
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
		private boolean opened;          // snap the very first fill (login/open); animate re-entries
		private final Timer timer;
		private final Consumer<String> onClickClan; // clicking a bar -> recolor that clan
		private String hoveredClan;                 // bar under the cursor, for the hover highlight

		Leaderboard(Consumer<String> onClickClan)
		{
			this.onClickClan = onClickClan;
			setForeground(Color.WHITE);
			timer = new Timer(16, e -> tick());
			MouseAdapter ma = new MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					setHover(clanAt(e.getY()));
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setHover(null);
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					String clan = clanAt(e.getY());
					if (clan != null && Leaderboard.this.onClickClan != null)
					{
						Leaderboard.this.onClickClan.accept(clan);
					}
				}
			};
			addMouseListener(ma);
			addMouseMotionListener(ma);
		}

		/** The clan whose bar currently sits under mouse-y, or null. */
		private String clanAt(int my)
		{
			for (Row r : rows.values())
			{
				int top = (int) Math.round(r.y);
				if (!r.leaving && my >= top && my < top + ROW_H)
				{
					return r.clan;
				}
			}
			return null;
		}

		private void setHover(String clan)
		{
			if (!java.util.Objects.equals(clan, hoveredClan))
			{
				hoveredClan = clan;
				setCursor(clan != null
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
				repaint();
			}
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
					r.alpha = 0.0; // start transparent so it fades in (mirrors the fade-out on leave)
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

			if (wasEmpty && !opened)
			{
				// Very first fill (login/panel open): snap so it doesn't animate up from zero.
				// Later refills (e.g. walking back into GE range) animate in instead of popping.
				for (Row r : rows.values())
				{
					r.tiles = r.targetTiles;
					r.y = r.targetY;
					r.alpha = 1.0;
				}
				leader = targetLeader;
			}
			else if (!timer.isRunning())
			{
				timer.start();
			}
			if (!rows.isEmpty())
			{
				opened = true;
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
			int h = rows.size() * PITCH; // 0 when empty, so it collapses fully out of GE range
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

				// Hover highlight: a soft brightening over the row under the cursor (click to recolor).
				if (hoveredClan != null && r.clan.equalsIgnoreCase(hoveredClan))
				{
					g2.setColor(new Color(255, 255, 255, 45));
					g2.fillRoundRect(0, barY, w - 1, BAR_H, arc, arc);
				}

				// Rank + name (left), tiles + GE% (right), over a soft shadow for legibility.
				String name = r.rank + ".  " + r.clan;
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
