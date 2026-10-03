package fr.iscsc.mc.stargatePlugin.welcome;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On join, shows the player a top-down view of the spot where they would spawn, then a welcome
 * title, then a dialog whose button drops them back at that spot in survival mode.
 *
 * <p>How it works, step by step:
 * <ol>
 *   <li>{@link #onJoin} fires when a player connects. {@link #start} remembers where the player
 *   is (their spawn spot), switches them to spectator mode (invisible, no body, can't touch
 *   anything) and moves them high above that spot, looking straight down.</li>
 *   <li>A title is shown in the middle of the screen, and a few seconds later a dialog (a menu
 *   window drawn by the Minecraft client) opens with the server presentation.</li>
 *   <li>Until the player clicks "Jouer", {@link #onMove} and the other event handlers stop them
 *   from flying away.</li>
 *   <li>Clicking "Jouer" calls {@link #finish}, which puts them back on their spawn spot in
 *   survival mode.</li>
 * </ol>
 *
 * <p>Text is built with Adventure {@link Component}s, Paper's rich-text library: each
 * {@code Component.text(...)} is a piece of text with its own color and style, and
 * {@code append} glues pieces together.
 */
public final class WelcomeIntro implements Listener {

    /** Blocks above the highest block at the spawn column. */
    private static final int VIEW_HEIGHT = 40;
    // How long the title takes to appear, stays fully visible, and takes to disappear.
    private static final Duration TITLE_FADE_IN = Duration.ofMillis(500);
    private static final Duration TITLE_STAY = Duration.ofSeconds(3);
    private static final Duration TITLE_FADE_OUT = Duration.ofMillis(500);
    /**
     * Delay before the dialog opens, so the title and the view are visible first (the client
     * blurs the world behind an open dialog). Minecraft counts time in "ticks": the server runs
     * 20 ticks per second, so {@code 20 * 4} is 4 seconds.
     */
    private static final long DIALOG_DELAY_TICKS = 20 * 4;

    /** Width in pixels of the dialog text blocks. */
    private static final int BODY_WIDTH = 300;

    // ---- Texts shown to the player ----

    /** Big text in the middle of the screen, with the smaller subtitle below it. */
    private static final Component TITLE = Component.text("Stargate", NamedTextColor.GOLD, TextDecoration.BOLD);
    private static final Component SUBTITLE = Component.text("Supaero Minecraft Server", NamedTextColor.YELLOW);
    /** Title bar of the dialog window. */
    private static final Component DIALOG_TITLE = Component.text("Bienvenue sur Stargate", NamedTextColor.GOLD);
    /**
     * Content of the dialog, displayed top to bottom. Each entry is one text block; see the
     * {@link #paragraph}, {@link #heading} and {@link #feature} helpers at the bottom of the file.
     */
    private static final List<DialogBody> DIALOG_BODY = List.of(
            paragraph(Component.text()
                    .append(Component.text("Stargate", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(" est un serveur "))
                    .append(Component.text("survie", NamedTextColor.GREEN))
                    .append(Component.text(" ouvert à tous les étudiants de Supaéro"))
                    .build()),

            heading("Fonctionnalités", NamedTextColor.AQUA),
            feature("Claim de chunks", "protège tes constructions et tes coffres."),
            feature("Chat vocal", "discute avec les joueurs proches de toi."),
            feature("Homes", "enregistre tes lieux favoris pour y revenir."),
            feature("Téléportation", "rejoins facilement tes amis."),
            feature("Monde amélioré", "génération retravaillée, avec de meilleurs biomes et structures à explorer."),
            paragraph(Component.text()
                    .append(Component.text("Tape "))
                    .append(Component.text("/help", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .append(Component.text(" pour tout savoir sur les commandes du serveur ("))
                    .append(Component.text("/claim", NamedTextColor.GREEN))
                    .append(Component.text(", "))
                    .append(Component.text("/home", NamedTextColor.GREEN))
                    .append(Component.text(", …)."))
                    .build()),

            heading("⚠ Serveur en bêta", NamedTextColor.RED),
            paragraph(Component.text("Le serveur est encore en phase de test. La zone autour du spawn pourrait "
                    + "être modifiée plus tard")),
            paragraph(Component.text()
                    .append(Component.text("Pour ne rien perdre, installe-toi "))
                    .append(Component.text("loin du spawn", NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text("."))
                    .build()));
    /** Label of the button that ends the intro. */
    private static final Component PLAY_LABEL = Component.text("Jouer", NamedTextColor.GREEN);

    // ---- State ----

    /** Our plugin instance; Paper asks for it when scheduling tasks or creating keys. */
    private final JavaPlugin plugin;
    /**
     * Where each player currently in the intro should be returned to. Players are identified by
     * their UUID (a permanent unique id) rather than the Player object, which is replaced on every
     * reconnection. A player is "in the intro" exactly when they have an entry here.
     */
    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
    /**
     * Copy of the return location stored on the player, so a player caught in the intro by a
     * crash is not left in the sky on their next join.
     *
     * <p>A PersistentDataContainer (PDC) lets a plugin attach its own data to a player (or an
     * item, a block...) and it is saved with the player's file. Each value is stored under a
     * {@link NamespacedKey} like {@code stargate-plugin:intro_return_location}, so plugins
     * don't overwrite each other's data.
     */
    private final NamespacedKey returnLocationKey;

    public WelcomeIntro(JavaPlugin plugin) {
        this.plugin = plugin;
        this.returnLocationKey = new NamespacedKey(plugin, "intro_return_location");
    }

    // ---- Event handlers ----
    // Methods annotated with @EventHandler are called by the server when the event in their
    // parameter happens. The method name doesn't matter, only the parameter type does.

    /** A player finished connecting and is now in the world. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    /**
     * A player disconnected. If they left during the intro, put them back on the ground now,
     * because the server saves the player's position and game mode right after this event.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (returnLocations.containsKey(player.getUniqueId())) {
            finish(player);
        }
    }

    // The next three handlers freeze the player during the intro. Cancelling an event
    // (setCancelled(true)) tells the server not to let the action happen.
    //
    // "priority = LOW" runs our handler before most other plugins, so they see the event
    // already cancelled. "ignoreCancelled = true" skips our handler if another plugin already
    // cancelled the event, since there is nothing left to do then.

    /**
     * Called every time a player moves or turns their head. We only block actual movement, so
     * players can still look around from their viewpoint.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (isInIntro(event.getPlayer()) && event.hasChangedPosition()) {
            event.setCancelled(true);
        }
    }

    /** Spectators can teleport to other players from a menu (number keys); block that. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (isInIntro(event.getPlayer()) && event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
        }
    }

    /** Spectators can click an entity to see through its eyes; block that too. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpectateEntity(PlayerStartSpectatingEntityEvent event) {
        if (isInIntro(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ---- Intro logic ----

    /** Returns every player still in the intro to their spawn spot, e.g. when the plugin is disabled. */
    public void finishAll() {
        // Iterate over a copy, because finish() removes entries from the map we are looping on.
        for (UUID id : List.copyOf(returnLocations.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                finish(player);
            }
        }
    }

    private boolean isInIntro(Player player) {
        return returnLocations.containsKey(player.getUniqueId());
    }

    /** Puts the player into the intro: sky view, title, then the dialog after a delay. */
    private void start(Player player) {
        // Normally the player's current location is where they would start playing. But if a
        // location was saved on the player, the server crashed during their last intro: their
        // current location is then up in the sky, and the saved one is the real spawn spot.
        Location spawn = readStoredLocation(player.getPersistentDataContainer());
        if (spawn == null) {
            spawn = player.getLocation();
            storeLocation(player.getPersistentDataContainer(), spawn);
        }
        returnLocations.put(player.getUniqueId(), spawn);

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(overheadView(spawn));
        player.showTitle(Title.title(TITLE, SUBTITLE,
                Title.Times.times(TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT)));

        // Run code later without blocking the server: the scheduler calls the lambda after
        // DIALOG_DELAY_TICKS. Using the player's own scheduler means the task is automatically
        // dropped if the player disconnects before then. The null argument is an optional task
        // to run in that case, which we don't need.
        player.getScheduler().runDelayed(plugin, task -> {
            // The player may already have left the intro (for example if the plugin was
            // reloaded), in which case there is nothing to show.
            if (isInIntro(player)) {
                player.showDialog(welcomeDialog());
            }
        }, null, DIALOG_DELAY_TICKS);
    }

    /** Ends the intro: back to the spawn spot, in survival mode. Does nothing if not in the intro. */
    private void finish(Player player) {
        Location spawn = returnLocations.remove(player.getUniqueId());
        if (spawn == null) {
            return;
        }
        player.closeDialog();
        player.clearTitle();
        player.teleport(spawn);
        player.setGameMode(GameMode.SURVIVAL);
        // The player is safely on the ground, so the crash backup is no longer needed.
        player.getPersistentDataContainer().remove(returnLocationKey);
    }

    /**
     * Builds the welcome dialog. Dialogs are menu windows drawn by the Minecraft client: the
     * server only describes what to show (title, text blocks, buttons) and the client draws it
     * and tells the server when a button is clicked.
     */
    private Dialog welcomeDialog() {
        ActionButton play = ActionButton.builder(PLAY_LABEL)
                // What happens on click: Paper calls this lambda on the server. "audience" is
                // whoever clicked, which is our player.
                .action(DialogAction.customClick(
                        (response, audience) -> {
                            if (audience instanceof Player player) {
                                // Click callbacks may not run on the player's thread, and changing
                                // a player (teleport, game mode) must happen there. So we hand the
                                // work over to the player's scheduler, which runs it on the right
                                // thread as soon as possible.
                                player.getScheduler().run(plugin, task -> finish(player), null);
                            }
                        },
                        // The button only works once, so a double click can't finish twice.
                        ClickCallback.Options.builder().uses(1).build()))
                .build();

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(DIALOG_TITLE)
                        // The Escape key can't close the dialog: the only way out is "Jouer".
                        .canCloseWithEscape(false)
                        // Close the window once the button is clicked.
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(DIALOG_BODY)
                        .build())
                // A "notice" dialog has a single button at the bottom.
                .type(DialogType.notice(play)));
    }

    // ---- Text helpers for DIALOG_BODY ----

    /** A block of text in the dialog. Long text wraps to BODY_WIDTH. */
    private static DialogBody paragraph(Component text) {
        return DialogBody.plainMessage(text, BODY_WIDTH);
    }

    /** A bold, underlined section heading. */
    private static DialogBody heading(String text, NamedTextColor color) {
        return paragraph(Component.text(text, color, TextDecoration.BOLD, TextDecoration.UNDERLINED));
    }

    /** A bullet line: the feature name in yellow, followed by its description in grey. */
    private static DialogBody feature(String name, String description) {
        return paragraph(Component.text()
                .append(Component.text("• " + name, NamedTextColor.YELLOW))
                .append(Component.text(" : " + description, NamedTextColor.GRAY))
                .build());
    }

    // ---- Location helpers ----

    /**
     * A point above the spawn column, looking straight down. In Minecraft, Y is the height, and
     * pitch is the vertical head angle: 90 means looking straight down, -90 straight up.
     */
    private static Location overheadView(Location spawn) {
        World world = spawn.getWorld();
        // Y of the topmost non-air block at this X/Z (ground, tree top, roof...).
        int ground = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ());
        // Never go above the top of the world.
        double y = Math.min(ground + VIEW_HEIGHT, world.getMaxHeight() - 1);
        return new Location(world, spawn.getX(), y, spawn.getZ(), spawn.getYaw(), 90f);
    }

    /**
     * Saves a location on the player. A PDC can't hold a Location directly, so it is turned into
     * a text like {@code minecraft:overworld;12.5;64.0;-30.5;90.0;0.0}
     * (world;x;y;z;yaw;pitch). Yaw is the horizontal direction the player is facing.
     */
    private void storeLocation(PersistentDataContainer pdc, Location location) {
        String value = String.join(";",
                location.getWorld().getKey().asString(),
                Double.toString(location.getX()),
                Double.toString(location.getY()),
                Double.toString(location.getZ()),
                Float.toString(location.getYaw()),
                Float.toString(location.getPitch()));
        pdc.set(returnLocationKey, PersistentDataType.STRING, value);
    }

    /**
     * Reads back a location saved by {@link #storeLocation}. Returns null if there is none, or if
     * it is unusable (for example, its world was deleted since).
     */
    private Location readStoredLocation(PersistentDataContainer pdc) {
        String value = pdc.get(returnLocationKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(";");
        NamespacedKey worldKey = parts.length == 6 ? NamespacedKey.fromString(parts[0].toLowerCase(Locale.ROOT)) : null;
        World world = worldKey != null ? Bukkit.getWorld(worldKey) : null;
        if (world == null) {
            plugin.getLogger().warning("Ignoring unusable stored intro location: " + value);
            return null;
        }
        return new Location(world,
                Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
    }
}
