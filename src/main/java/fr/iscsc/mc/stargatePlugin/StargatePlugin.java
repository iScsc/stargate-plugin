package fr.iscsc.mc.stargatePlugin;

import fr.iscsc.mc.stargatePlugin.welcome.WelcomeIntro;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the plugin.
 *
 * <p>The server finds this class through the {@code main:} line of {@code plugin.yml}, creates
 * a single instance of it, and calls its lifecycle methods:
 * <ul>
 *   <li>{@link #onEnable()} once the plugin is loaded (at server start, after the worlds are
 *   loaded because {@code plugin.yml} says {@code load: POSTWORLD}),</li>
 *   <li>{@link #onDisable()} when the server stops or the plugin is unloaded.</li>
 * </ul>
 * This class should stay small: it only creates each feature and plugs it into the server.
 */
public final class StargatePlugin extends JavaPlugin {

    private WelcomeIntro welcomeIntro;

    @Override
    public void onEnable() {
        welcomeIntro = new WelcomeIntro(this);
        // A "listener" is an object whose @EventHandler methods the server calls when something
        // happens in the game (a player joins, moves, breaks a block...). Registering it is what
        // makes the server actually call those methods.
        getServer().getPluginManager().registerEvents(welcomeIntro, this);
    }

    @Override
    public void onDisable() {
        // Players still watching the intro are in spectator mode high in the sky. Put them back
        // on the ground before the plugin goes away, since nothing would do it afterwards.
        if (welcomeIntro != null) {
            welcomeIntro.finishAll();
        }
    }
}
