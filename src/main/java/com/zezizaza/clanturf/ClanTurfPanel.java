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
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import java.awt.Dialog;
import java.awt.Image;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * Sidebar scoreboard: a headline of who controls the GE, then a ranked leaderboard where each
 * clan's bar is scaled to the current leader, so the gap between first and the pack reads at a
 * glance. Rebuilt whenever a claim lands or the world changes.
 */
class ClanTurfPanel extends PluginPanel
{
	// Font hierarchy matched to the Emote Wheel panel: bold 20 title, bold 17 orange section headers.
	private static final Font TITLE_FONT = FontManager.getRunescapeBoldFont().deriveFont(20f);
	private static final Font HEADER_FONT = FontManager.getRunescapeBoldFont().deriveFont(17f);
	/** Faint gold left-bar shown while hovering a paint-as row - a low-opacity brand orange, so it
	 * reads as a dimmer version of the selected row's solid bar. */
	private static final Color HOVER_BAR = new Color(
			ColorScheme.BRAND_ORANGE.getRed(), ColorScheme.BRAND_ORANGE.getGreen(),
			ColorScheme.BRAND_ORANGE.getBlue(), 110);

	// Native RuneLite icons (rename pencil + the Screen Markers confirm/cancel set), so the row's edit,
	// confirm and remove buttons all match. Drawn pencil is only a fallback if a resource ever moves.
	private static final Icon PENCIL_ICON = loadIcon("/net/runelite/client/plugins/config/mdi_rename.png");
	private static final Icon CHECK_ICON =
			loadIcon("/net/runelite/client/plugins/screenmarkers/confirm_icon.png");
	private static final Icon CLOSE_ICON =
			loadIcon("/net/runelite/client/plugins/screenmarkers/cancel_icon.png");

	private final JLabel header = new JLabel();
	private final JLabel headline = new JLabel();
	private final JLabel clanHint = new JLabel();
	private java.util.function.ToIntFunction<String> allianceIconLookup = s -> 0; // display name -> icon id
	private java.util.function.Function<String, java.util.List<String>> allianceRosterLookup =
			d -> java.util.Collections.emptyList(); // display name -> member clans
	private Map<String, Long> allianceTileCounts = java.util.Collections.emptyMap(); // lower(clan) -> tiles held
	private final java.util.Map<Integer, java.awt.image.BufferedImage> spriteRawCache = new java.util.HashMap<>();
	private final java.util.Set<Integer> spriteRequested = new java.util.HashSet<>();
	private final Leaderboard board = new Leaderboard(this::openColorPicker, this::leaderboardRoster,
			this::leaderboardAllianceIconId, this::leaderboardIconImage);
	private final JLabel battlesHeader = new JLabel("Active battles");
	private final JPanel battlesBox = new JPanel();

	// Alliance section (collapsible, sits under Active battles).
	private final JLabel allianceHeader = new JLabel();
	private final JPanel allianceBody = new JPanel();
	private final JPanel allianceJoinCreate = new JPanel();
	private final JPanel allianceMemberPanel = new JPanel();
	private final JLabel allianceStatus = new JLabel();
	private final JLabel allianceMembersLabel = new JLabel();
	private final JPanel allianceSwatch = new JPanel();     // create-color preview + click to pick
	private final JPanel allianceOwnSwatch = new JPanel();  // holds the current alliance color (picker seed)
	private static final int ALLIANCE_MAX_WORDS = 2;     // name shape: up to 2 words...
	private static final int ALLIANCE_MAX_WORD_LEN = 10; // ...each up to 10 characters, so it fits everywhere
	private static final int BATTLE_NAME_CLIP = 12;      // clip names in a single-line Active-battles row
	private static final int BATTLE_VS_CLIP = 10;        // tighter clip for the two-column current-world matchup
	private final JTextField nameField = new JTextField();
	private final JLabel allianceNameLabel = new JLabel();
	private final JTextField createPass = new JTextField();
	private final JTextField joinPass = new JTextField();
	private final StyledButton createBtn = new StyledButton("Create alliance", 26);
	private final StyledButton joinBtn = new StyledButton("Join alliance", 26);
	private final StyledButton leaveBtn = new StyledButton("Leave alliance", 26);
	private final StyledButton changeColorBtn = new StyledButton("Change alliance color", 26);
	private final JLabel alliancePasscodeLabel = new JLabel(); // owner clan only: click to copy the code
	private String alliancePasscodeValue;                      // the raw passcode the label copies
	private Timer passcodeCopyTimer;                           // reverts the "copied" flash back to the code
	private Timer statusClearTimer;                            // clears transient alliance status messages
	private final StyledButton changePasscodeBtn = new StyledButton("Change passcode", 26);
	private final StyledButton changeNameBtn = new StyledButton("Change name", 26);
	private final StyledButton changeIconBtn = new StyledButton("Change icon", 26);
	private final JLabel allianceIconLabel = new JLabel();  // symbol to the left of the alliance name
	private final JLabel allianceIconLabelR = new JLabel(); // matching symbol to the right of the name
	private final JPanel allianceNameRow = new JPanel();    // [icon][name][icon] side by side
	private int allianceIconValue;                          // current alliance symbol sprite id (0 = none)
	private final java.util.Map<Integer, ImageIcon> iconCache = new java.util.HashMap<>(); // spriteId -> icon
	private SpriteManager spriteManager;                    // loads clan-symbol sprites from the player cache
	private Consumer<Integer> onChangeIcon;                 // owner: set the alliance symbol
	private final JPanel allianceMembersList = new JPanel(); // owner view: member rows, each with a kick X
	private String membersRowsSig; // guard so the member rows only rebuild when they actually change
	private Consumer<String> onKickClan;    // owner: kick + block an allied clan
	private Consumer<String> onChangeName;  // owner: rename the alliance
	private BiConsumer<String, String> onChangePasscode; // owner: (old, new) change the passcode
	private Color createColor = new Color(0x8a, 0x2b, 0xe2); // default alliance color (purple)
	private int createIcon = 3024; // symbol chosen in the create form (Skull default)
	private final JButton createIconBtn = new JButton(); // create form: click to pick the alliance symbol
	private boolean allianceCollapsed = true; // Alliance Tools starts collapsed on a fresh install
	private boolean allianceInAlliance = false;
	private boolean allianceOnline = true;
	private boolean allianceCanManage = false;
	private boolean allianceIsOwnerClan = false; // our clan created the alliance -> Disband, not Leave
	private boolean battlesCollapsed = false;
	private boolean globalCollapsed = true; // Community Claims starts collapsed on a fresh install
	private boolean signedIn = false; // logged in with live data; hides battles/alliance at the login screen
	private boolean globalHasData = false;
	private String battlesBase = "Active battles";
	private final StyledButton clearOfflineBtn = new StyledButton("Clear all tiles", 30);
	private final StyledButton serverToggleBtn = new StyledButton("Online", 30);
	private final Consumer<Boolean> onSetServer; // flips the sync-server (online/offline) config
	private final ColorPickerManager colorPickerManager;
	private final BiConsumer<String, Color> onClanColorChosen; // (clan, chosen color) -> plugin persists
	private final Consumer<Boolean> onSetSlug;   // offline Full Slug toggle -> plugin persists
	private final Consumer<Boolean> onSetEraser; // offline Eraser toggle -> plugin persists
	private final StyledButton eraserBtn = new StyledButton("Surrender tiles: Off", 30);
	private boolean eraserOn;                     // mirrored Eraser state for the button label
	private final Consumer<String> onAddClan;    // offline: add a test clan to paint as
	private final Consumer<String> onSelectClan; // offline: paint as this clan
	private final Consumer<String> onRemoveClan; // offline: remove a test clan
	private final BiConsumer<String, String> onRenameClan; // offline: (old, new) rename a test clan
	private final Consumer<String[]> onCreateAlliance; // {name, colorHex, passcode} -> create alliance
	private final Consumer<String> onJoinAlliance; // (passcode) -> join an alliance
	private final Runnable onLeaveAlliance;        // leave the current alliance
	private final Consumer<String> onChangeAllianceColor; // owner-only: (colorHex) -> recolor alliance
	private final StyledButton slugBtn = new StyledButton("Full Slug: Off", 30);
	private final FadePanel sandboxBox = new FadePanel(); // offline-only tools, fades in on going offline
	private final JPanel paintClansBox = new JPanel();    // the paint-as roster rows
	private boolean slugOn;                       // mirrored Full Slug state for the button label
	private boolean serverOn = true;             // current mode, mirrored from the config

	// Headline "Stake" easter egg: click the headline to flip the stake between a percent and a raw
	// tiles/total count. headlineOwner is the "GE owners: <b>X</b>" prefix while a stake is shown, else null.
	private boolean stakeAsFraction;
	private String headlineOwner;
	private long stakeClaimed;
	private int stakeTotal;

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
	 * @param onClearOffline     wipe the current world's local claims (only wired while offline)
	 * @param onSetServer        turn the sync server on/off (the panel's Online/Offline toggle)
	 * @param colorPickerManager opens the RuneLite color wheel when a scoreboard bar is clicked
	 * @param onClanColorChosen  (clan, chosen color) - the plugin persists it (own color vs color list)
	 */
	ClanTurfPanel(Runnable onClearOffline, Consumer<Boolean> onSetServer,
			ColorPickerManager colorPickerManager, BiConsumer<String, Color> onClanColorChosen,
			Consumer<Boolean> onSetSlug, Consumer<String> onAddClan, Consumer<String> onSelectClan,
			Consumer<String> onRemoveClan, BiConsumer<String, String> onRenameClan,
			Consumer<Boolean> onSetEraser, Consumer<String[]> onCreateAlliance,
			Consumer<String> onJoinAlliance, Runnable onLeaveAlliance,
			Consumer<String> onChangeAllianceColor)
	{
		this.onSetServer = onSetServer;
		this.onCreateAlliance = onCreateAlliance;
		this.onJoinAlliance = onJoinAlliance;
		this.onLeaveAlliance = onLeaveAlliance;
		this.onChangeAllianceColor = onChangeAllianceColor;
		this.colorPickerManager = colorPickerManager;
		this.onClanColorChosen = onClanColorChosen;
		this.onSetSlug = onSetSlug;
		this.onAddClan = onAddClan;
		this.onSelectClan = onSelectClan;
		this.onRemoveClan = onRemoveClan;
		this.onRenameClan = onRenameClan;
		this.onSetEraser = onSetEraser;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

		header.setFont(TITLE_FONT);
		header.setForeground(Color.WHITE);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		headline.setFont(FontManager.getRunescapeFont());
		headline.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		headline.setAlignmentX(Component.LEFT_ALIGNMENT);
		headline.setBorder(BorderFactory.createEmptyBorder(3, 0, 8, 0));
		// Easter egg: click the headline while a stake is shown to flip percent <-> tiles/total. Not a
		// button - just a hand cursor and a slight brighten on hover so it hints at being clickable.
		headline.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (headlineOwner != null)
				{
					stakeAsFraction = !stakeAsFraction;
					renderHeadline();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (headlineOwner != null)
				{
					headline.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
					headline.setForeground(Color.WHITE);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				headline.setCursor(Cursor.getDefaultCursor());
				headline.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
		});

		// Shown only when the player isn't in a clan (nothing to claim turf for).
		clanHint.setText("Join a clan to claim turf.");
		clanHint.setFont(FontManager.getRunescapeSmallFont());
		clanHint.setForeground(new Color(0xEB, 0xC7, 0x33)); // amber, stands out from the gray
		clanHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		clanHint.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		clanHint.setVisible(false);

		board.setAlignmentX(Component.LEFT_ALIGNMENT);

		battlesHeader.setFont(HEADER_FONT);
		battlesHeader.setForeground(ColorScheme.BRAND_ORANGE);
		battlesHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		battlesHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
		battlesHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		battlesHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleBattles();
			}
		});
		renderBattlesHeader();

		battlesBox.setLayout(new BoxLayout(battlesBox, BoxLayout.Y_AXIS));
		battlesBox.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Offline-only sandbox control. Always visible so it's easy to find, but only enabled
		// while the sync server is off, so nobody can ever wipe shared/server turf from here.
		// Wipes just the current world's local claims.
		clearOfflineBtn.setEnabled(false);
		clearOfflineBtn.setToolTipText(
				"Wipes this world's local claims. Available only with the sync server turned off.");
		clearOfflineBtn.onClick(() ->
		{
			if (onClearOffline != null)
			{
				onClearOffline.run();
			}
		});

		// Online/Offline toggle, sitting next to the Clear button. Flips the sync-server config; the
		// change swings back through setOfflineControls to relabel this and enable/disable Clear.
		// Fixed width so it doesn't collapse in the horizontal controls row (styled buttons default to
		// a zero preferred width, which is fine only for the full-width offline buttons).
		serverToggleBtn.setPreferredSize(new Dimension(80, 30));
		serverToggleBtn.setPreferredSize(new Dimension(80, 30));
		serverToggleBtn.setMinimumSize(new Dimension(80, 30));
		serverToggleBtn.setMaximumSize(new Dimension(80, 30));
		serverToggleBtn.setToolTipText("Online: your claims sync with every clan. Offline: local practice "
				+ "only, nothing is sent.");
		serverToggleBtn.onClick(() ->
		{
			if (onSetServer != null)
			{
				onSetServer.accept(!serverOn);
			}
		});

		// "Community Claims": header, a large animated count, and a thank-you line. Hidden until a real
		// total arrives (server mode only).
		globalHeader.setFont(HEADER_FONT);
		globalHeader.setForeground(ColorScheme.BRAND_ORANGE);
		globalHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		globalHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
		globalHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		globalHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleGlobal();
			}
		});

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
		globalBox.add(globalIntro);
		globalBox.add(countBox);
		globalBox.add(globalSub);
		applyGlobalVisibility(); // no data yet -> header and box both hidden

		// Controls row: just the Online/Offline toggle now (Clear moved into the offline sandbox), kept
		// at its current size and left-aligned. Pinned under the GE-owners headline, above the bars, so
		// the scoreboard, battles and community below all animate out beneath it on a toggle.
		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
		controls.setOpaque(false);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		// Max height must fit the 30px button plus this border (2+8), or the button's height goes
		// unstable when sections below collapse and the layout recomputes.
		controls.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		controls.add(serverToggleBtn);
		controls.add(Box.createHorizontalGlue());

		top.add(header);
		top.add(headline);
		top.add(clanHint);
		top.add(controls);
		top.add(board);
		top.add(battlesHeader);
		top.add(battlesBox);
		buildAllianceSection();
		top.add(allianceHeader);
		top.add(allianceBody);
		top.add(globalHeader);
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
		reportBtn.setFont(FontManager.getRunescapeFont());
		reportBtn.setFocusable(false);
		reportBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		reportBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, reportBtn.getPreferredSize().height));
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
		JLabel sandboxHeader = new JLabel("Offline Tools");
		sandboxHeader.setFont(HEADER_FONT);
		sandboxHeader.setForeground(ColorScheme.BRAND_ORANGE);
		sandboxHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		sandboxHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));

		slugBtn.setToolTipText("Act on every tile you cross, not just the one you land on (applies to "
				+ "claiming and to Surrender). Offline only; hides the tiles/hour tracker while on.");
		slugBtn.onClick(() ->
		{
			boolean next = !slugOn;
			setSlug(next); // optimistic label update
			if (onSetSlug != null)
			{
				onSetSlug.accept(next);
			}
		});

		eraserBtn.setToolTipText("Surrender: your steps erase claimed tiles back to unclaimed instead of "
				+ "claiming (unclaimed tiles are left alone). Full Slug makes it erase every tile you "
				+ "cross. Offline only.");
		eraserBtn.onClick(() ->
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
		paintHeader.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		StyledButton addClanBtn = new StyledButton("Add clan", 30);
		addClanBtn.setToolTipText("Add a test clan you can paint as (offline sandbox only). "
				+ "It's auto-named - use the pencil to rename it.");
		addClanBtn.onClick(() ->
		{
			if (onAddClan != null)
			{
				onAddClan.accept(null); // null = auto-name (CLAN1, CLAN2, ...)
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
		sandboxBox.add(Box.createVerticalStrut(4));
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
			serverToggleBtn.setLabel(serverOn ? "Online" : "Offline");
			serverToggleBtn.setLabelColor(serverOn
					? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			battlesBase = serverOn ? "Active battles" : "Offline battles";
			renderBattlesHeader();
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
			slugBtn.setLabel(on ? "Full Slug: On" : "Full Slug: Off");
			slugBtn.setLabelColor(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		});
	}

	/** Reflect the offline Eraser state on its button (from the plugin's saved state, or a click). */
	void setEraser(boolean on)
	{
		SwingUtilities.invokeLater(() ->
		{
			eraserOn = on;
			eraserBtn.setLabel(on ? "Surrender tiles: On" : "Surrender tiles: Off");
			eraserBtn.setLabelColor(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		});
	}

	/** Rebuild the offline "paint as" roster: your clan (locked) plus test clans, the selected one lit. */
	void setPaintClans(List<String> clans, String realClan, String selected)
	{
		SwingUtilities.invokeLater(() ->
		{
			paintClansBox.removeAll();
			boolean first = true;
			for (String clan : clans)
			{
				if (!first)
				{
					paintClansBox.add(Box.createVerticalStrut(4)); // even 4px gap between rows
				}
				first = false;
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
		// Match the 30px button height so the roster lines up with Add clan and the offline buttons.
		row.setPreferredSize(new Dimension(0, 30));
		row.setMinimumSize(new Dimension(0, 30));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		// Selected clan gets a brand-orange bar down its left edge (like the Emote Wheel slots) instead
		// of an arrow prefix; the unselected bar is the row color, so the text stays aligned either way.
		setRowBar(row, isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);

		// Hover feedback: a faint gold left bar (a low-opacity version of the selected bar) so you can
		// see which row you're pointing at, instead of the usual full-background highlight. The selected
		// row keeps its solid bar. getMousePosition avoids flicker when the pointer crosses the buttons.
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				setRowBar(row, isSelected ? ColorScheme.BRAND_ORANGE : HOVER_BAR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (row.getMousePosition(true) == null)
				{
					setRowBar(row, isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
				}
			}
		});

		JLabel name = new JLabel(clan);
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

		// Edit/remove buttons show only on the selected test clan, so the roster stays clean; selecting a
		// row reveals them (the name shifts to make room for the pencil on the left).
		if (!isReal && isSelected)
		{
			// Pencil toggles inline rename: click once to edit (icon becomes a check), then click the
			// check or press Enter to confirm; Escape cancels. x removes. Real clan has neither button.
			JButton edit = iconButton(PENCIL_ICON, "Rename " + clan);
			JButton remove = iconButton(CLOSE_ICON, "Remove " + clan);
			remove.addActionListener(e ->
			{
				if (onRemoveClan != null)
				{
					onRemoveClan.accept(clan);
				}
			});

			// Holds the open rename field for this row, or null when not editing.
			final JTextField[] field = { null };
			edit.addActionListener(e ->
			{
				if (field[0] != null)
				{
					// Confirming.
					String nn = field[0].getText().trim();
					field[0] = null;
					edit.setIcon(PENCIL_ICON);
					edit.setToolTipText("Rename " + clan);
					if (onRenameClan != null && !nn.isEmpty())
					{
						onRenameClan.accept(clan, nn); // renames + rebuilds the roster
					}
					else
					{
						restoreLabel(row, name);
					}
					return;
				}
				// Entering edit mode: name -> text field, pencil -> check.
				JTextField tf = new JTextField(clan);
				tf.setFont(FontManager.getRunescapeFont());
				tf.setForeground(Color.WHITE);
				tf.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				tf.setCaretColor(Color.WHITE);
				tf.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
				field[0] = tf;
				row.remove(name);
				row.add(tf, BorderLayout.CENTER);
				row.revalidate();
				row.repaint();
				edit.setIcon(CHECK_ICON);
				edit.setToolTipText("Confirm rename");
				SwingUtilities.invokeLater(() ->
				{
					tf.requestFocusInWindow();
					tf.selectAll();
				});
				tf.addActionListener(ev -> edit.doClick()); // Enter = click the check
				tf.addKeyListener(new KeyAdapter()
				{
					@Override
					public void keyPressed(KeyEvent ke)
					{
						if (ke.getKeyCode() == KeyEvent.VK_ESCAPE)
						{
							field[0] = null;
							edit.setIcon(PENCIL_ICON);
							edit.setToolTipText("Rename " + clan);
							restoreLabel(row, name);
						}
					}
				});
			});

			// Edit pencil on the left of the name, remove x on the right.
			row.add(edit, BorderLayout.WEST);
			row.add(remove, BorderLayout.EAST);
		}
		return row;
	}

	/** Loads a native RuneLite icon by classpath path, falling back to a drawn pencil if it's missing. */
	private static Icon loadIcon(String path)
	{
		BufferedImage img = ImageUtil.loadImageResource(ClanTurfPanel.class, path);
		return img != null ? new ImageIcon(img) : makePencilIcon();
	}

	/** A fixed-size, flat (no background) square icon button that shows just an outline on hover. */
	private static JButton iconButton(Icon icon, String tooltip)
	{
		JButton b = new JButton(icon);
		b.setFocusable(false);
		b.setToolTipText(tooltip);
		b.setContentAreaFilled(false); // no button background - just the icon
		b.setMargin(new Insets(0, 0, 0, 0));
		b.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
		Dimension d = new Dimension(22, 22);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
		// Just an outline on hover (same 1px inset, so the icon doesn't shift).
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				b.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
			}
		});
		return b;
	}

	/** Fallback pencil, drawn only if the native RuneLite edit icon resource can't be found. */
	private static Icon makePencilIcon()
	{
		int s = 12;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(2, 10, 8, 4);  // pencil body
		g.drawLine(8, 4, 10, 2);  // toward the tip
		g.drawLine(2, 10, 3, 11); // eraser end
		g.dispose();
		return new ImageIcon(img);
	}

	/** Renders the GE-owners headline with the stake as a percent, or as tiles/total when toggled. */
	private void renderHeadline()
	{
		if (headlineOwner == null)
		{
			return;
		}
		String stake = stakeAsFraction
				? stakeClaimed + " / " + stakeTotal
				: String.format("%.1f", stakeTotal > 0 ? stakeClaimed * 100.0 / stakeTotal : 0.0) + "% Stake";
		headline.setText("<html>" + headlineOwner + " &nbsp;·&nbsp; " + stake + "</html>");
	}

	/** Sets a paint-as row's left bar to the given color (solid orange selected, faint gold on hover). */
	private static void setRowBar(JPanel row, Color barColor)
	{
		row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0, barColor),
				BorderFactory.createEmptyBorder(3, 6, 3, 4)));
		row.repaint();
	}

	/** Put the clan's name label back in the row's center (rename canceled or rejected). */
	private void restoreLabel(JPanel row, JLabel name)
	{
		BorderLayout bl = (BorderLayout) row.getLayout();
		Component center = bl.getLayoutComponent(BorderLayout.CENTER);
		if (center != null)
		{
			row.remove(center);
		}
		row.add(name, BorderLayout.CENTER);
		row.revalidate();
		row.repaint();
	}

	void showEmpty(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			header.setText("Clan Turf");
			headlineOwner = null; // no stake shown in the empty state, so clicking does nothing
			headline.setText(message);
			clanHint.setVisible(false);
			board.setData(new ArrayList<>(), 0, null);
			globalHasData = false;
			applyGlobalVisibility();
			setSignedIn(false); // login screen: hide battles + alliance until we're actually in
		});
	}

	/**
	 * Active battles and Alliance Tools only mean anything once you're logged in - at the login screen
	 * they'd sit there empty and then jump around as data lands. So hide both while signed out and let
	 * them appear with the first live update, the way Community Claims already gates itself on having a
	 * total. The Online/Offline toggle and the Report footer stay visible the whole time.
	 */
	private void setSignedIn(boolean in)
	{
		signedIn = in;
		battlesHeader.setVisible(in);
		battlesBox.setVisible(in && !battlesCollapsed);
		allianceHeader.setVisible(in);
		allianceBody.setVisible(in && !allianceCollapsed);
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
				globalHasData = false;
				applyGlobalVisibility();
				return;
			}
			globalHasData = true;
			applyGlobalVisibility();
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
			boolean clanHintDue, Map<String, Long> perClanTiles)
	{
		final Map<String, Long> perClanTilesFinal =
				perClanTiles == null ? java.util.Collections.emptyMap() : perClanTiles;
		Map<String, Long> counts = claims.stream()
				.collect(Collectors.groupingBy(ClanTurfPoint::getClanName, Collectors.counting()));

		// Rank by tiles held: whoever has the most is #1, so out-tiling a rival puts you on top even
		// before the debounced owner flip catches up. A tie is broken in the committed owner's favor
		// (matches the boundary), then by the sticky base order so tied clans don't shuffle. Per world.
		if (world != boardOrderWorld)
		{
			boardOrderWorld = world;
			boardOrder.clear();
		}
		List<String> base = stickyBase(counts.keySet(), counts);
		base.sort((a, b) ->
		{
			int byTiles = Long.compare(counts.get(b), counts.get(a));
			if (byTiles != 0)
			{
				return byTiles; // most tiles first
			}
			boolean ac = a.equalsIgnoreCase(committedLeader);
			boolean bc = b.equalsIgnoreCase(committedLeader);
			if (ac != bc)
			{
				return ac ? -1 : 1; // tie: committed owner holds #1
			}
			return 0; // full tie: keep the stable sticky base order
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
			if (!signedIn)
			{
				setSignedIn(true); // first live update after login: reveal battles + alliance
			}
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
					headlineOwner = "GE owners: <b style='color:#" + ohex + "'>"
							+ escape(currentBattle.getOwner()) + "</b>";
					stakeClaimed = currentBattle.getOwnerTiles();
					stakeTotal = currentBattle.getTotalTiles();
					renderHeadline();
				}
				else if (status == ClanTurfStore.ConnectionStatus.CONNECTING)
				{
					// Cold start: the server hasn't answered yet, so don't imply the GE is empty.
					headlineOwner = null;
					headline.setText("Connecting to the sync server…");
				}
				else if (status == ClanTurfStore.ConnectionStatus.OFFLINE)
				{
					headlineOwner = null;
					headline.setText("Sync server unreachable - retrying.");
				}
				else
				{
					headlineOwner = null;
					headline.setText("No tiles claimed yet - walk the GE.");
				}
			}
			else
			{
				Entry lead = ordered.get(0);
				String hex = String.format("%02x%02x%02x",
						lead.color.getRed(), lead.color.getGreen(), lead.color.getBlue());
				headlineOwner = "GE owners: <b style='color:#" + hex + "'>" + escape(lead.clan) + "</b>";
				stakeClaimed = claimed;
				stakeTotal = totalTiles;
				renderHeadline();
			}

			allianceTileCounts = perClanTilesFinal; // per-clan breakdown for the expanded-alliance drawer
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
		boolean current = b.getWorld() == currentWorld; // the world you're on: larger text
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

			String ownerName = "<span style='color:#" + ownerHex + "'>"
					+ escape(clip(b.getOwner(), BATTLE_VS_CLIP)) + "</span>";
			String matchup;
			if (b.getRunnerUp() != null)
			{
				// Each clan and its tile count are centered in one column, so the clan name sits
				// directly above its tiles, with the "vs" between the two columns.
				String upHex = hex(ClanTurfColors.forClan(b.getRunnerUp()));
				String upName = "<span style='color:#" + upHex + "'>"
						+ escape(clip(b.getRunnerUp(), BATTLE_VS_CLIP)) + "</span>";
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

		// Other worlds: a single compact info label (the world's in the label, hop to it yourself).
		String worldTag = "<b>W" + b.getWorld() + "</b>";
		String ownerCell = "<span style='color:#" + ownerHex + "'>" + escape(clip(b.getOwner(), BATTLE_NAME_CLIP))
				+ "</span>&nbsp;" + b.getOwnerTiles();
		String html;
		if (b.getRunnerUp() != null)
		{
			String upHex = hex(ClanTurfColors.forClan(b.getRunnerUp()));
			String upCell = "<span style='color:#" + upHex + "'>" + escape(clip(b.getRunnerUp(), BATTLE_NAME_CLIP))
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

	/** Shorten a clan/alliance name with a trailing ellipsis so an Active-battles row can't overflow the
	 *  panel and clip. The scoreboard drawer and world map still show the full name. */
	private static String clip(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)).trim() + "…";
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
		int icon;            // alliance symbol sprite id for this row (0 = not an alliance, no icon)
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

	private void toggleBattles()
	{
		battlesCollapsed = !battlesCollapsed;
		battlesBox.setVisible(!battlesCollapsed);
		renderBattlesHeader();
		revalidate();
		repaint();
	}

	private void renderBattlesHeader()
	{
		battlesHeader.setText(battlesBase + (battlesCollapsed ? "  ▸" : "  ▾"));
	}

	private void toggleGlobal()
	{
		globalCollapsed = !globalCollapsed;
		applyGlobalVisibility();
		revalidate();
		repaint();
	}

	/** Show the Community Claims section per whether there's data and whether it's collapsed. The counter
	 *  itself always stays visible (with data); collapsing only hides the intro and thank-you lines above
	 *  and below it, so the number slides up under the header and back down when reopened. */
	private void applyGlobalVisibility()
	{
		globalHeader.setVisible(globalHasData);
		globalBox.setVisible(globalHasData);
		globalIntro.setVisible(globalHasData && !globalCollapsed);
		globalSub.setVisible(globalHasData && !globalCollapsed);
		globalHeader.setText("Community Claims" + (globalCollapsed ? "  ▸" : "  ▾"));
	}

	/** Builds the collapsible Alliance section: a create/join sub-panel and an in-alliance sub-panel. */
	private void buildAllianceSection()
	{
		updateAllianceHeaderText();
		allianceHeader.setFont(HEADER_FONT);
		allianceHeader.setForeground(ColorScheme.BRAND_ORANGE);
		allianceHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		allianceHeader.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
		allianceHeader.setToolTipText("Team up so allied clans don't take each other's tiles.");
		allianceHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		allianceHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				allianceCollapsed = !allianceCollapsed;
				allianceBody.setVisible(!allianceCollapsed);
				updateAllianceHeaderText();
				revalidate();
				repaint();
			}
		});

		allianceBody.setLayout(new BoxLayout(allianceBody, BoxLayout.Y_AXIS));
		allianceBody.setOpaque(false);
		allianceBody.setAlignmentX(Component.LEFT_ALIGNMENT);

		allianceStatus.setFont(FontManager.getRunescapeSmallFont());
		allianceStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		allianceStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		allianceStatus.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		// Create / join (shown when not in an alliance).
		allianceJoinCreate.setLayout(new BoxLayout(allianceJoinCreate, BoxLayout.Y_AXIS));
		allianceJoinCreate.setOpaque(false);
		allianceJoinCreate.setAlignmentX(Component.LEFT_ALIGNMENT);

		allianceSwatch.setPreferredSize(new Dimension(36, 36));
		allianceSwatch.setMaximumSize(new Dimension(36, 36));
		allianceSwatch.setBackground(createColor);
		allianceSwatch.setToolTipText("Pick your alliance color");
		allianceSwatch.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		allianceSwatch.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				openAllianceColorPicker();
			}
		});
		styleField(nameField);
		styleField(createPass);
		styleField(joinPass);
		limitAllianceName(nameField);
		nameField.setToolTipText("Up to 2 words, 10 letters each");
		createPass.setToolTipText("Leave blank and a random passcode is generated for you");
		joinPass.setToolTipText("Alliance passcode");
		createBtn.onClick(() ->
		{
			if (onCreateAlliance == null)
			{
				return;
			}
			String nm = nameField.getText().trim();
			if (nm.isEmpty())
			{
				setAllianceStatus("Enter an alliance name.");
				return;
			}
			onCreateAlliance.accept(new String[]{nm, hex6(createColor),
					createPass.getText().trim(), String.valueOf(createIcon)});
		});
		createIconBtn.setPreferredSize(new Dimension(36, 36));
		createIconBtn.setMaximumSize(new Dimension(36, 36));
		createIconBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		createIconBtn.setFocusable(false);
		createIconBtn.addActionListener(e -> openIconPicker(id ->
		{
			createIcon = id;
			iconFor(id, ic -> createIconBtn.setIcon(scaleIcon(ic, 28)));
		}));
		joinBtn.onClick(() ->
		{
			if (onJoinAlliance != null && !joinPass.getText().trim().isEmpty())
			{
				onJoinAlliance.accept(joinPass.getText().trim());
			}
		});

		allianceJoinCreate.add(smallLabel("Create an alliance:"));
		allianceJoinCreate.add(Box.createVerticalStrut(4));
		allianceJoinCreate.add(smallLabel("Name (up to 2 words, 10 letters each)"));
		allianceJoinCreate.add(Box.createVerticalStrut(2));
		allianceJoinCreate.add(nameField);
		allianceJoinCreate.add(Box.createVerticalStrut(8));
		allianceJoinCreate.add(smallLabel("Passcode"));
		allianceJoinCreate.add(Box.createVerticalStrut(2));
		allianceJoinCreate.add(createPass);
		allianceJoinCreate.add(Box.createVerticalStrut(8));
		allianceJoinCreate.add(smallLabel("Pick an alliance color and symbol."));
		allianceJoinCreate.add(Box.createVerticalStrut(3));
		JPanel colorSymbolRow = new JPanel();
		colorSymbolRow.setLayout(new BoxLayout(colorSymbolRow, BoxLayout.X_AXIS));
		colorSymbolRow.setOpaque(false);
		colorSymbolRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		colorSymbolRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		colorSymbolRow.add(allianceSwatch);
		colorSymbolRow.add(Box.createHorizontalStrut(8));
		colorSymbolRow.add(createIconBtn);
		colorSymbolRow.add(Box.createHorizontalGlue());
		allianceJoinCreate.add(colorSymbolRow);
		allianceJoinCreate.add(Box.createVerticalStrut(7));
		allianceJoinCreate.add(createBtn);
		allianceJoinCreate.add(Box.createVerticalStrut(10));
		allianceJoinCreate.add(thinDivider());
		allianceJoinCreate.add(Box.createVerticalStrut(10));
		allianceJoinCreate.add(smallLabel("Join an alliance (enter its passcode):"));
		allianceJoinCreate.add(Box.createVerticalStrut(3));
		allianceJoinCreate.add(joinPass);
		allianceJoinCreate.add(Box.createVerticalStrut(7));
		allianceJoinCreate.add(joinBtn);

		// In an alliance (shown when joined): color, members, leave.
		allianceMemberPanel.setLayout(new BoxLayout(allianceMemberPanel, BoxLayout.Y_AXIS));
		allianceMemberPanel.setOpaque(false);
		allianceMemberPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		allianceOwnSwatch.setPreferredSize(new Dimension(24, 24));
		allianceOwnSwatch.setMaximumSize(new Dimension(24, 24));
		allianceOwnSwatch.setBackground(createColor);
		allianceMembersLabel.setFont(FontManager.getRunescapeSmallFont());
		allianceMembersLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		allianceMembersLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		leaveBtn.onClick(() ->
		{
			if (onLeaveAlliance == null)
			{
				return;
			}
			String msg = allianceIsOwnerClan
					? "This will disband the alliance and remove every clan from it. Are you sure?"
					: "This will remove your clan from the alliance. Are you sure?";
			String title = allianceIsOwnerClan ? "Disband alliance" : "Leave alliance";
			int r = JOptionPane.showConfirmDialog(this, msg, title,
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (r == JOptionPane.YES_OPTION)
			{
				onLeaveAlliance.run();
			}
		});
		changeColorBtn.onClick(() ->
		{
			if (colorPickerManager == null || onChangeAllianceColor == null)
			{
				return;
			}
			Window parent = SwingUtilities.getWindowAncestor(this);
			RuneliteColorPicker picker = colorPickerManager.create(parent,
					allianceOwnSwatch.getBackground(), "Alliance color", true);
			picker.setLocationRelativeTo(parent);
			picker.setOnClose(c -> onChangeAllianceColor.accept(hex6(c)));
			picker.setVisible(true);
		});
		alliancePasscodeLabel.setFont(FontManager.getRunescapeSmallFont());
		alliancePasscodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		alliancePasscodeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		alliancePasscodeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		alliancePasscodeLabel.setVisible(false);
		alliancePasscodeLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (alliancePasscodeValue == null || alliancePasscodeValue.isEmpty())
				{
					return;
				}
				Toolkit.getDefaultToolkit().getSystemClipboard()
						.setContents(new StringSelection(alliancePasscodeValue), null);
				alliancePasscodeLabel.setText("Passcode copied");
				if (passcodeCopyTimer != null)
				{
					passcodeCopyTimer.stop();
				}
				passcodeCopyTimer = new Timer(1500, ev -> renderPasscode());
				passcodeCopyTimer.setRepeats(false);
				passcodeCopyTimer.start();
			}
		});
		allianceNameLabel.setFont(HEADER_FONT);
		allianceNameLabel.setForeground(ColorScheme.BRAND_ORANGE);
		allianceNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		// The alliance symbol sits to the left of the name, as one row.
		allianceIconLabel.setVisible(false);
		allianceIconLabelR.setVisible(false);
		allianceNameRow.setLayout(new BoxLayout(allianceNameRow, BoxLayout.X_AXIS));
		allianceNameRow.setOpaque(false);
		allianceNameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		allianceNameRow.add(allianceIconLabel);
		allianceNameRow.add(Box.createHorizontalStrut(6));
		allianceNameRow.add(allianceNameLabel);
		allianceNameRow.add(Box.createHorizontalStrut(6));
		allianceNameRow.add(allianceIconLabelR);
		allianceMembersList.setLayout(new BoxLayout(allianceMembersList, BoxLayout.Y_AXIS));
		allianceMembersList.setOpaque(false);
		allianceMembersList.setAlignmentX(Component.LEFT_ALIGNMENT);
		changePasscodeBtn.onClick(this::openChangePasscode);
		changeNameBtn.onClick(this::openChangeName);
		changeIconBtn.onClick(() -> openIconPicker(id ->
		{
			if (onChangeIcon != null)
			{
				onChangeIcon.accept(id);
			}
		}));
		// The member panel's contents are (re)built by layoutMemberPanel() whenever the view shape changes
		// (owner vs joined vs read-only), so hidden buttons never leave phantom gaps.
		allianceMemberPanel.setVisible(false);

		allianceBody.add(allianceStatus);
		allianceBody.add(allianceJoinCreate);
		allianceBody.add(allianceMemberPanel);
	}

	/**
	 * Update the alliance section: whether we're online, in an alliance, our alliance color, and the
	 * member list. Called from the plugin as the alliance state changes. Only re-lays out on a real
	 * state change, so it can't steal focus from the passcode fields while you type.
	 */
	void setAlliance(boolean online, boolean inAlliance, boolean canManage, String name, String colorHex,
			boolean isOwnerClan, java.util.List<String> members, java.util.List<String> kickable)
	{
		boolean changed = online != allianceOnline || inAlliance != allianceInAlliance
				|| canManage != allianceCanManage || isOwnerClan != allianceIsOwnerClan;
		allianceOnline = online;
		allianceInAlliance = inAlliance;
		allianceCanManage = canManage;
		allianceIsOwnerClan = isOwnerClan;
		updateAllianceHeaderText();
		if (!online)
		{
			allianceStatus.setText("Alliances are online only.");
			allianceJoinCreate.setVisible(false);
			allianceMemberPanel.setVisible(false);
		}
		else if (inAlliance)
		{
			allianceJoinCreate.setVisible(false);
			allianceMemberPanel.setVisible(true);
			allianceNameLabel.setText(name == null || name.isEmpty() ? "Your alliance" : name);
			Color col = colorHex == null ? null : parseHex(colorHex);
			// The alliance name renders in the team color directly (no hash-color flash while it loads).
			allianceNameLabel.setForeground(col != null ? col : ColorScheme.BRAND_ORANGE);
			if (col != null)
			{
				allianceOwnSwatch.setBackground(col);
				allianceOwnSwatch.repaint();
			}
			// The owner clan disbands (kills the alliance); a joined clan just leaves.
			leaveBtn.setLabel(isOwnerClan ? "Disband alliance" : "Leave alliance");
			if (!canManage)
			{
				allianceStatus.setText(" "); // read-only view: just the alliance name + clan list
			}
			// Owner staff get a row per member with a kick X; everyone else gets a plain name list. Both
			// carry the same "Allied clans:" header so the manage and read-only views line up.
			boolean owner = canManage && isOwnerClan;
			if (owner)
			{
				rebuildMemberRows(members, kickable);
			}
			else
			{
				allianceMembersLabel.setText(membersHtml(members));
			}
			if (changed)
			{
				layoutMemberPanel(owner, canManage);
			}
		}
		else
		{
			// Not in an alliance: only Admin+ get the create/join controls.
			allianceJoinCreate.setVisible(canManage);
			allianceMemberPanel.setVisible(false);
			if (!canManage)
			{
				allianceStatus.setText("Your clan is not in an alliance.");
			}
			// The "team up..." blurb now lives as a tooltip on the Alliance header, so managers just see
			// the create/join form here with no extra hint text.
		}
		// Hide the status line entirely when it's blank, so the name + clans sit up under the header
		// instead of leaving a gap.
		String st = allianceStatus.getText();
		allianceStatus.setVisible(st != null && !st.trim().isEmpty());
		if (changed)
		{
			revalidate();
			repaint();
		}
	}

	/** (Re)build the in-alliance panel for the current view: owner staff get the full toolkit, a joined
	 *  clan's staff get just Leave, and a read-only member gets name + clans. Called only when the view
	 *  shape changes, so hidden buttons never leave phantom gaps and the three action buttons stay evenly
	 *  spaced. */
	private void layoutMemberPanel(boolean owner, boolean canManage)
	{
		allianceMemberPanel.removeAll();
		allianceMemberPanel.add(allianceNameRow);
		allianceMemberPanel.add(Box.createVerticalStrut(6)); // breathing room below the flanking symbols
		allianceMemberPanel.add(owner ? allianceMembersList : allianceMembersLabel);
		if (owner)
		{
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(alliancePasscodeLabel);
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(changeNameBtn);
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(changeIconBtn);
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(changePasscodeBtn);
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(changeColorBtn);
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(leaveBtn);
		}
		else if (canManage)
		{
			allianceMemberPanel.add(Box.createVerticalStrut(6));
			allianceMemberPanel.add(leaveBtn);
		}
		allianceMemberPanel.revalidate();
		allianceMemberPanel.repaint();
	}

	/** Owner clan only: show the alliance passcode with click-to-copy, or hide it when null/blank. */
	void setAlliancePasscode(String code)
	{
		if (code == null || code.isEmpty())
		{
			alliancePasscodeValue = null;
			alliancePasscodeLabel.setVisible(false);
			return;
		}
		alliancePasscodeValue = code;
		renderPasscode();
		alliancePasscodeLabel.setVisible(true);
	}

	private void renderPasscode()
	{
		if (alliancePasscodeValue != null && !alliancePasscodeValue.isEmpty())
		{
			alliancePasscodeLabel.setText("<html>Passcode: " + alliancePasscodeValue
					+ "<br>(click to copy)</html>");
		}
	}

	/** Owner staff view: one row per member clan; each non-owner clan gets a small "x" that kicks + blocks
	 *  it. Rebuilt only when the roster changes, so it doesn't flicker on every refresh. */
	private void rebuildMemberRows(java.util.List<String> members, java.util.List<String> kickable)
	{
		java.util.Set<String> kick = new java.util.HashSet<>();
		if (kickable != null)
		{
			for (String k : kickable)
			{
				kick.add(k.toLowerCase());
			}
		}
		String sig = members + "|" + kick;
		if (sig.equals(membersRowsSig))
		{
			return;
		}
		membersRowsSig = sig;
		allianceMembersList.removeAll();
		allianceMembersList.add(smallLabel("Allied clans:")); // match the read-only view's header
		for (String m : members)
		{
			boolean canKick = kick.contains(m.toLowerCase());
			allianceMembersList.add(clanRow("• " + m, canKick ? "x" : null, () -> confirmKick(m)));
		}
		allianceMembersList.revalidate();
		allianceMembersList.repaint();
	}

	/** A "&lt;clan name&gt; ......... [x]" row. actionLabel null = just the name, no button. */
	private JPanel clanRow(String clan, String actionLabel, Runnable action)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		JLabel name = new JLabel(clan);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		p.add(name);
		p.add(Box.createHorizontalGlue());
		if (actionLabel != null && action != null)
		{
			JLabel x = new JLabel(actionLabel);
			x.setFont(FontManager.getRunescapeSmallFont());
			x.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			x.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
			x.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					x.setForeground(Color.WHITE);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					x.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					action.run();
				}
			});
			p.add(x);
		}
		return p;
	}

	private void confirmKick(String clan)
	{
		if (onKickClan == null)
		{
			return;
		}
		int r = JOptionPane.showConfirmDialog(this,
				"Remove " + clan + " from the alliance and block it from rejoining? Are you sure?",
				"Remove clan", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (r == JOptionPane.YES_OPTION)
		{
			onKickClan.accept(clan);
		}
	}

	/** Owner staff: popup to rename the alliance. */
	private void openChangeName()
	{
		if (onChangeName == null)
		{
			return;
		}
		JTextField field = new JTextField();
		limitAllianceName(field);
		JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
		form.add(new JLabel("New alliance name (up to 2 words, 10 letters each)"));
		form.add(field);
		int r = JOptionPane.showConfirmDialog(this, form, "Change name",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r == JOptionPane.OK_OPTION)
		{
			String n = field.getText().trim();
			if (!n.isEmpty())
			{
				onChangeName.accept(n);
			}
		}
	}

	/** Owner staff: popup to change the passcode. Enter the current code plus a new one. */
	private void openChangePasscode()
	{
		if (onChangePasscode == null)
		{
			return;
		}
		JTextField oldField = new JTextField();
		JTextField newField = new JTextField();
		JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
		form.add(new JLabel("Current passcode"));
		form.add(oldField);
		form.add(new JLabel("New passcode"));
		form.add(newField);
		int r = JOptionPane.showConfirmDialog(this, form, "Change passcode",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r == JOptionPane.OK_OPTION)
		{
			String o = oldField.getText().trim();
			String n = newField.getText().trim();
			if (!o.isEmpty() && !n.isEmpty())
			{
				onChangePasscode.accept(o, n);
			}
		}
	}

	/** Wire the owner-only alliance actions (kick, rename, change passcode) after construction. */
	void setAllianceOwnerHandlers(Consumer<String> onKick, Consumer<String> onRename,
			BiConsumer<String, String> onChangePass, Consumer<Integer> onIcon)
	{
		this.onKickClan = onKick;
		this.onChangeName = onRename;
		this.onChangePasscode = onChangePass;
		this.onChangeIcon = onIcon;
	}

	/** The plugin hands us its SpriteManager so we can load clan-symbol sprites from the player's cache. */
	void setSpriteManager(SpriteManager sm)
	{
		this.spriteManager = sm;
		iconFor(createIcon, ic -> createIconBtn.setIcon(scaleIcon(ic, 28))); // load the create-form preview
	}

	/** The plugin wires this so the scoreboard can ask "is this display name an alliance, and which icon?" */
	void setAllianceIconLookup(java.util.function.ToIntFunction<String> f)
	{
		this.allianceIconLookup = f == null ? s -> 0 : f;
	}

	private int leaderboardAllianceIconId(String display)
	{
		return allianceIconLookup.applyAsInt(display);
	}

	/** The plugin wires this so clicking a bar's symbol can list that alliance's member clans. */
	void setAllianceRosterLookup(java.util.function.Function<String, java.util.List<String>> f)
	{
		this.allianceRosterLookup = f == null ? d -> java.util.Collections.emptyList() : f;
	}

	/**
	 * Member clans of an alliance (by its scoreboard display name), each paired with the tiles that clan
	 * holds in the current world, ranked most-tiles-first. Feeds the inline drawer's per-clan breakdown.
	 * The roster is server truth (so a clan with 0 tiles this world still shows, at 0); counts are local.
	 */
	private java.util.List<Map.Entry<String, Long>> leaderboardRoster(String display)
	{
		java.util.List<String> members = allianceRosterLookup.apply(display);
		java.util.List<Map.Entry<String, Long>> out = new ArrayList<>(members.size());
		for (String m : members)
		{
			long tiles = allianceTileCounts.getOrDefault(m.toLowerCase(java.util.Locale.ROOT), 0L);
			out.add(new java.util.AbstractMap.SimpleEntry<>(m, tiles));
		}
		out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		return out;
	}

	/** Raw clan-symbol sprite for the scoreboard gutter, cached; loads async and repaints when ready. */
	private java.awt.image.BufferedImage leaderboardIconImage(int id)
	{
		if (id <= 0 || spriteManager == null)
		{
			return null;
		}
		java.awt.image.BufferedImage cached = spriteRawCache.get(id);
		if (cached != null)
		{
			return cached;
		}
		if (spriteRequested.add(id)) // first request for this id - load it, then repaint the board
		{
			spriteManager.getSpriteAsync(id, 0, img ->
			{
				if (img != null)
				{
					SwingUtilities.invokeLater(() ->
					{
						spriteRawCache.put(id, img);
						board.repaint();
					});
				}
			});
		}
		return null;
	}

	/** Show the alliance's symbol next to the name; loads the sprite async the first time. 0 = hide. */
	void setAllianceIcon(int icon)
	{
		allianceIconValue = icon;
		if (icon <= 0)
		{
			allianceIconLabel.setIcon(null);
			allianceIconLabel.setVisible(false);
			allianceIconLabelR.setIcon(null);
			allianceIconLabelR.setVisible(false);
			return;
		}
		iconFor(icon, ic ->
		{
			if (allianceIconValue == icon)
			{
				ImageIcon scaled = scaleIcon(ic, 22);
				allianceIconLabel.setIcon(scaled);
				allianceIconLabel.setVisible(true);
				allianceIconLabelR.setIcon(scaled);
				allianceIconLabelR.setVisible(true);
				revalidate();
				repaint();
			}
		});
	}

	/** Popup grid of the 27 clan symbols; clicking one passes its sprite id to {@code onPick}. */
	private void openIconPicker(Consumer<Integer> onPick)
	{
		if (onPick == null)
		{
			return;
		}
		Window parent = SwingUtilities.getWindowAncestor(this);
		JDialog dlg = new JDialog(parent, "Choose alliance symbol", Dialog.ModalityType.APPLICATION_MODAL);
		JPanel grid = new JPanel(new java.awt.GridLayout(0, 6, 4, 4));
		grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (int id = ICON_MIN; id <= ICON_MAX; id++)
		{
			final int fid = id;
			JButton cell = new JButton();
			cell.setPreferredSize(new Dimension(42, 42));
			cell.setFocusable(false);
			iconFor(id, ic -> cell.setIcon(scaleIcon(ic, 32)));
			cell.addActionListener(e ->
			{
				onPick.accept(fid);
				dlg.dispose();
			});
			grid.add(cell);
		}
		dlg.add(grid);
		dlg.pack();
		dlg.setLocationRelativeTo(parent);
		dlg.setVisible(true);
	}

	/** Fetch a clan-symbol icon, cached; runs {@code ready} on the EDT once it's available. */
	private void iconFor(int id, Consumer<ImageIcon> ready)
	{
		ImageIcon cached = iconCache.get(id);
		if (cached != null)
		{
			ready.accept(cached);
			return;
		}
		if (spriteManager == null)
		{
			return;
		}
		spriteManager.getSpriteAsync(id, 0, img ->
		{
			if (img == null)
			{
				return;
			}
			ImageIcon ic = new ImageIcon(img);
			SwingUtilities.invokeLater(() ->
			{
				iconCache.put(id, ic);
				ready.accept(ic);
			});
		});
	}

	private static ImageIcon scaleIcon(ImageIcon ic, int size)
	{
		return new ImageIcon(ic.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH));
	}

	private static final int ICON_MIN = 3024; // first clan-symbol sprite id (27 contiguous: 3024-3050)
	private static final int ICON_MAX = 3050;

	/** The header shows the collapse arrow. Managers see "Alliance Tools" with a tooltip noting the controls
	 *  inside are Admin+ only; everyone else just sees "Alliance". */
	private void updateAllianceHeaderText()
	{
		String base = allianceCanManage ? "Alliance Tools" : "Alliance";
		allianceHeader.setText(base + (allianceCollapsed ? "  ▸" : "  ▾"));
		allianceHeader.setToolTipText(allianceCanManage ? "Admin only options." : null);
	}

	/** Show a one-line status/result under the Alliance header (e.g. "Passcode taken"). */
	void setAllianceStatus(String text)
	{
		boolean blank = text == null || text.trim().isEmpty();
		// Wrap in HTML so long results (e.g. the weekly-rename message) flow onto multiple lines in the
		// narrow panel instead of being cut off. Already-HTML text is passed through untouched.
		String shown = blank ? " "
				: (text.startsWith("<html") ? text
				: "<html><body style='width:160px'>" + text + "</body></html>");
		allianceStatus.setText(shown);
		allianceStatus.setVisible(!blank);
		if (statusClearTimer != null)
		{
			statusClearTimer.stop();
		}
		if (!blank)
		{
			// Action results (created / disbanded / errors) are transient - clear them after a few seconds
			// so they don't linger above the alliance name. Persistent hints bypass this method.
			statusClearTimer = new Timer(3500, e ->
			{
				allianceStatus.setText(" ");
				allianceStatus.setVisible(false);
				revalidate();
				repaint();
			});
			statusClearTimer.setRepeats(false);
			statusClearTimer.start();
		}
		revalidate();
		repaint();
	}

	private void openAllianceColorPicker()
	{
		if (colorPickerManager == null)
		{
			return;
		}
		Window parent = SwingUtilities.getWindowAncestor(this);
		RuneliteColorPicker picker = colorPickerManager.create(parent, createColor, "Alliance color", true);
		picker.setLocationRelativeTo(parent);
		picker.setOnClose(c ->
		{
			createColor = c;
			allianceSwatch.setBackground(c);
			allianceSwatch.repaint();
		});
		picker.setVisible(true);
	}

	private JLabel smallLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A full-width 1px horizontal rule, matching the divider style used in the Active battles rows. */
	private static JComponent thinDivider()
	{
		JPanel d = new JPanel();
		d.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		d.setPreferredSize(new Dimension(0, 1));
		d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		d.setAlignmentX(Component.LEFT_ALIGNMENT);
		return d;
	}

	private static void styleField(JTextField f)
	{
		f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		f.setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	/** Constrain an alliance-name field to the shape the map and bar can show: at most 2 words, each at most
	 *  10 characters, single spaces only (no leading or double spaces). The edit is accepted only if the
	 *  resulting text still fits that shape, so a user simply can't type a 4th word or an 11th letter. The
	 *  server enforces the same rule; this just makes it obvious while typing. */
	private static void limitAllianceName(JTextField f)
	{
		((javax.swing.text.AbstractDocument) f.getDocument()).setDocumentFilter(
				new javax.swing.text.DocumentFilter()
				{
					@Override
					public void insertString(FilterBypass fb, int off, String s,
							javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
					{
						replace(fb, off, 0, s, attr);
					}

					@Override
					public void replace(FilterBypass fb, int off, int len, String s,
							javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
					{
						String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
						String next = cur.substring(0, off) + (s == null ? "" : s) + cur.substring(off + len);
						if (allianceNameTypingOk(next))
						{
							super.replace(fb, off, len, s, attr);
						}
					}
				});
	}

	/** True while a name is still a valid work-in-progress: up to 3 space-separated slots, each up to 10
	 *  chars, no leading space and no double spaces. A single trailing space is allowed (starting a word). */
	private static boolean allianceNameTypingOk(String s)
	{
		if (s.isEmpty())
		{
			return true;
		}
		if (s.startsWith(" ") || s.contains("  "))
		{
			return false;
		}
		String[] parts = s.split(" ", -1); // keep a trailing empty slot so "wrath " reads as 2 slots
		if (parts.length > ALLIANCE_MAX_WORDS)
		{
			return false;
		}
		for (String p : parts)
		{
			if (p.length() > ALLIANCE_MAX_WORD_LEN)
			{
				return false;
			}
		}
		return true;
	}

	/** A left-aligned horizontal row of a fixed-size swatch/control and a stretchy control beside it. */
	private static JPanel row(Component a, Component b)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		p.add(a);
		p.add(Box.createHorizontalStrut(6));
		p.add(b);
		return p;
	}

	private static String hex6(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private static Color parseHex(String hex)
	{
		try
		{
			return new Color(Integer.parseInt(hex, 16));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static String membersHtml(java.util.List<String> members)
	{
		if (members == null || members.isEmpty())
		{
			return "No members.";
		}
		StringBuilder sb = new StringBuilder("<html>Allied clans:");
		for (String m : members)
		{
			sb.append("<br>&bull; ").append(escapeHtml(m)); // no trailing <br>, so no dead gap below
		}
		return sb.append("</html>").toString();
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
		// display name -> member clans each paired with the tiles it holds (for the inline drawer breakdown)
		private final java.util.function.Function<String, java.util.List<Map.Entry<String, Long>>> rosterLookup;
		private final java.util.function.ToIntFunction<String> allianceIconId; // display name -> icon id (0=none)
		private final java.util.function.IntFunction<java.awt.image.BufferedImage> iconImage; // id -> sprite
		private static final int ICON_GUTTER = 32;  // right-side space reserved on every bar for the symbol
		private static final int EXP_PAD = 6;       // inner padding of the inline alliance-info drawer
		private static final int EXP_LINE = 15;     // line height inside the drawer
		private String hoveredBarClan;              // bar under the cursor (bar hover highlight)
		private String hoveredIconClan;             // symbol under the cursor (icon hover highlight)
		private String expandedClan;                // alliance row whose inline info drawer is open, or null

		Leaderboard(Consumer<String> onClickClan,
				java.util.function.Function<String, java.util.List<Map.Entry<String, Long>>> rosterLookup,
				java.util.function.ToIntFunction<String> allianceIconId,
				java.util.function.IntFunction<java.awt.image.BufferedImage> iconImage)
		{
			this.onClickClan = onClickClan;
			this.rosterLookup = rosterLookup;
			this.allianceIconId = allianceIconId;
			this.iconImage = iconImage;
			setForeground(Color.WHITE);
			timer = new Timer(16, e -> tick());
			MouseAdapter ma = new MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					updateHover(e.getX(), e.getY());
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					clearHover();
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					Row r = rowAt(e.getY());
					if (r == null)
					{
						return;
					}
					int bw = getWidth() - ICON_GUTTER;
					if (r.icon > 0 && e.getX() >= bw)
					{
						toggleExpand(r.clan); // clicked the alliance symbol -> open/close its info drawer
					}
					else if (Leaderboard.this.onClickClan != null)
					{
						Leaderboard.this.onClickClan.accept(r.clan); // clicked the bar -> recolor
					}
				}
			};
			addMouseListener(ma);
			addMouseMotionListener(ma);
		}

		/** The row whose bar currently sits under mouse-y, or null. */
		private Row rowAt(int my)
		{
			for (Row r : rows.values())
			{
				int top = (int) Math.round(r.y);
				if (!r.leaving && my >= top && my < top + ROW_H)
				{
					return r;
				}
			}
			return null;
		}

		/** Split hover state from a cursor position: symbol gutter lights the icon, elsewhere lights the bar. */
		private void updateHover(int mx, int my)
		{
			Row r = rowAt(my);
			String bar = null;
			String icon = null;
			if (r != null)
			{
				int bw = getWidth() - ICON_GUTTER;
				if (r.icon > 0 && mx >= bw)
				{
					icon = r.clan;
				}
				else
				{
					bar = r.clan;
				}
			}
			setHover(bar, icon);
		}

		private void clearHover()
		{
			setHover(null, null);
		}

		private void setHover(String bar, String icon)
		{
			if (!java.util.Objects.equals(bar, hoveredBarClan)
					|| !java.util.Objects.equals(icon, hoveredIconClan))
			{
				hoveredBarClan = bar;
				hoveredIconClan = icon;
				setCursor((bar != null || icon != null)
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
				repaint();
			}
		}

		/** Open the inline info drawer under an alliance row, or close it if that row is already open. */
		private void toggleExpand(String clan)
		{
			expandedClan = java.util.Objects.equals(expandedClan, clan) ? null : clan;
			relayout();
			updatePreferredSize();
			if (!timer.isRunning())
			{
				timer.start();
			}
			repaint();
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
				r.icon = allianceIconId == null ? 0 : allianceIconId.applyAsInt(e.clan);
				r.targetTiles = e.tiles;
				r.rank = slot + 1;
				r.leaving = false;
				slot++;
			}
			// Parked leavers shrink to nothing (relayout sits them below the survivors).
			for (Row r : rows.values())
			{
				if (r.leaving)
				{
					r.targetTiles = 0;
				}
			}
			// An alliance that has left the board (or lost its symbol) can't keep its drawer open.
			if (expandedClan != null)
			{
				Row ex = rows.get(expandedClan);
				if (ex == null || ex.leaving || ex.icon <= 0)
				{
					expandedClan = null;
				}
			}
			relayout();

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
			int h = 0; // 0 when empty, so it collapses fully out of GE range
			for (Row r : rows.values())
			{
				h += PITCH;
				if (r.icon > 0 && r.clan.equalsIgnoreCase(expandedClan))
				{
					h += drawerHeight(r);
				}
			}
			setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, h));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
			revalidate();
		}

		/** Assign each row's target Y in rank order, opening a gap under the expanded alliance row. */
		private void relayout()
		{
			List<Row> survivors = new ArrayList<>();
			List<Row> leavers = new ArrayList<>();
			for (Row r : rows.values())
			{
				(r.leaving ? leavers : survivors).add(r);
			}
			survivors.sort(java.util.Comparator.comparingInt(a -> a.rank));
			int y = 0;
			for (Row r : survivors)
			{
				r.targetY = y;
				y += PITCH;
				if (r.icon > 0 && r.clan.equalsIgnoreCase(expandedClan))
				{
					y += drawerHeight(r);
				}
			}
			for (Row r : leavers)
			{
				r.targetY = y;
				y += PITCH;
			}
		}

		/** Pixel height of the inline drawer: the alliance name, an "Allied clans:" header, one line per member. */
		private int drawerHeight(Row r)
		{
			java.util.List<Map.Entry<String, Long>> members = rosterLookup == null ? null : rosterLookup.apply(r.clan);
			int n = (members == null || members.isEmpty()) ? 1 : members.size();
			return EXP_PAD * 2 + EXP_LINE * (n + 2);
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
				int bw = w - ICON_GUTTER; // bars are shortened to leave a right gutter for the alliance symbol
				long shownTiles = Math.round(r.tiles);

				// Track.
				g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g2.fillRoundRect(0, barY, bw - 1, BAR_H, arc, arc);

				// Clan fill, scaled to the (animated) leader, with a subtle vertical sheen.
				int fillW = (int) Math.round((r.tiles / denom) * (bw - 1));
				fillW = Math.max(MIN_FILL, Math.min(bw - 1, fillW));
				Color top = brighten(r.color, 40);
				g2.setPaint(new GradientPaint(0, barY, top, 0, barY + BAR_H, r.color));
				g2.fillRoundRect(0, barY, fillW, BAR_H, arc, arc);

				// Outline - the player's own clan gets a brighter, thicker frame to stand out.
				boolean mine = myClan != null && r.clan.equalsIgnoreCase(myClan);
				if (mine)
				{
					g2.setStroke(new BasicStroke(2f));
					g2.setColor(Color.WHITE);
					g2.drawRoundRect(1, barY + 1, bw - 3, BAR_H - 2, arc, arc);
					g2.setStroke(new BasicStroke(1f));
				}
				else
				{
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.drawRoundRect(0, barY, bw - 1, BAR_H, arc, arc);
				}

				// Bar hover highlight: a soft brightening over the bar under the cursor (click to recolor).
				if (hoveredBarClan != null && r.clan.equalsIgnoreCase(hoveredBarClan))
				{
					g2.setColor(new Color(255, 255, 255, 45));
					g2.fillRoundRect(0, barY, bw - 1, BAR_H, arc, arc);
				}

				// Alliance symbol in the right gutter - only alliances have one; solo clans leave it blank.
				if (r.icon > 0)
				{
					// Icon hover highlight: only the symbol lights up (click to open its info drawer).
					if (hoveredIconClan != null && r.clan.equalsIgnoreCase(hoveredIconClan))
					{
						g2.setColor(new Color(255, 255, 255, 55));
						g2.fillRoundRect(bw, barY, ICON_GUTTER - 1, BAR_H, arc, arc);
					}
					java.awt.image.BufferedImage img = iconImage == null ? null : iconImage.apply(r.icon);
					if (img != null)
					{
						int isz = BAR_H - 4;
						int ix = bw + (ICON_GUTTER - isz) / 2;
						int iy = barY + (BAR_H - isz) / 2;
						g2.drawImage(img, ix, iy, isz, isz, null);
					}
				}

				// Rank + name (left), tiles + GE% (right), over a soft shadow for legibility.
				String name = r.rank + ".  " + r.clan;
				double gePct = totalTiles > 0 ? shownTiles * 100.0 / totalTiles : 0.0;
				String stat = shownTiles + "  (" + String.format("%.1f", gePct) + "%)";

				// Measure the stat first so the name can be clipped to whatever space is left, then ellipsized
				// if it still doesn't fit - a long alliance name shortens to "..." on the bar rather than
				// running under the count (the world map shows it in full, stacked per word).
				g2.setFont(statFont);
				int statW = g2.getFontMetrics().stringWidth(stat);
				int textY = barY + (BAR_H + g2.getFontMetrics().getAscent()) / 2 - 2;

				g2.setFont(nameFont);
				int nameMaxW = bw - statW - 8 - 8 - 6; // bar minus stat, both 8px insets, plus a 6px gap
				name = ellipsize(g2.getFontMetrics(), name, nameMaxW);
				drawShadowed(g2, name, 8, textY, Color.WHITE);

				g2.setFont(statFont);
				drawShadowed(g2, stat, bw - statW - 8, textY, Color.WHITE);

				// Inline alliance-info drawer, opened by clicking the row's symbol.
				if (r.icon > 0 && r.clan.equalsIgnoreCase(expandedClan))
				{
					int dh = drawerHeight(r);
					int dTop = (int) Math.round(r.y) + ROW_H;
					int dw = w - ICON_GUTTER;
					g2.setColor(new Color(0, 0, 0, 90));
					g2.fillRoundRect(0, dTop, dw - 1, dh - 1, arc, arc);
					g2.setColor(new Color(r.color.getRed(), r.color.getGreen(), r.color.getBlue(), 160));
					g2.drawRoundRect(0, dTop, dw - 1, dh - 1, arc, arc);

					g2.setFont(statFont);
					int lineY = dTop + EXP_PAD + g2.getFontMetrics().getAscent();
					// The bar clips a long name; the drawer shows it in full on its own first line.
					String fullName = ellipsize(g2.getFontMetrics(), r.clan, dw - 16);
					drawShadowed(g2, fullName, 8, lineY, r.color);
					lineY += EXP_LINE;
					drawShadowed(g2, "Allied clans:", 8, lineY, ColorScheme.LIGHT_GRAY_COLOR);
					List<Map.Entry<String, Long>> members = rosterLookup == null ? null : rosterLookup.apply(r.clan);
					if (members == null || members.isEmpty())
					{
						lineY += EXP_LINE;
						drawShadowed(g2, "(none)", 16, lineY, Color.LIGHT_GRAY);
					}
					else
					{
						for (Map.Entry<String, Long> m : members)
						{
							lineY += EXP_LINE;
							drawShadowed(g2, "- " + m.getKey(), 16, lineY, Color.WHITE);
							String cnt = String.valueOf(m.getValue());
							int cw = g2.getFontMetrics().stringWidth(cnt);
							drawShadowed(g2, cnt, dw - cw - 8, lineY, Color.WHITE);
						}
					}
				}
			}

			g2.dispose();
		}

		/** Trim a string with a trailing "..." so it fits maxW pixels; returns it unchanged if it already fits. */
		private static String ellipsize(FontMetrics fm, String s, int maxW)
		{
			if (maxW <= 0 || fm.stringWidth(s) <= maxW)
			{
				return s;
			}
			String ell = "...";
			int ew = fm.stringWidth(ell);
			int w = 0;
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < s.length(); i++)
			{
				int cw = fm.charWidth(s.charAt(i));
				if (w + cw + ew > maxW)
				{
					break;
				}
				sb.append(s.charAt(i));
				w += cw;
			}
			return sb.toString().replaceAll("\\s+$", "") + ell;
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

	/**
	 * A rounded, hand-painted button matching the Emote Wheel panel: dark rounded fill that lightens
	 * on hover, a hover-grow and press-pop, and a centered clan-styled label. Replaces the small stock
	 * JButtons so the offline tools read as one clean set instead of cramped system buttons.
	 */
	private static class StyledButton extends JPanel
	{
		private static final Font BUTTON_FONT = FontManager.getRunescapeBoldFont();

		private String label;
		private Color labelColor = Color.WHITE;
		private boolean hover;
		private boolean active = true; // our own enabled flag (JPanel has no visual disabled state)
		private Runnable onClick;

		StyledButton(String label, int height)
		{
			this.label = label;
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setPreferredSize(new Dimension(0, height));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					if (active)
					{
						hover = true;
						repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					if (active && contains(e.getPoint()) && onClick != null)
					{
						onClick.run();
					}
				}
			});
		}

		void onClick(Runnable r)
		{
			onClick = r;
		}

		void setLabel(String s)
		{
			label = s;
			repaint();
		}

		void setLabelColor(Color c)
		{
			labelColor = c;
			repaint();
		}

		@Override
		public void setEnabled(boolean b)
		{
			super.setEnabled(b);
			active = b;
			setCursor(Cursor.getPredefinedCursor(b ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			if (!active)
			{
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
			}
			int w = getWidth();
			int h = getHeight();
			g2.setColor(hover && active
					? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, w - 1, h - 1, 6, 6);
			g2.setFont(BUTTON_FONT);
			FontMetrics fm = g2.getFontMetrics();
			int tx = Math.max(6, (w - fm.stringWidth(label)) / 2);
			int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
			g2.setColor(labelColor);
			g2.drawString(label, tx, ty);
			g2.dispose();
		}
	}
}
