package com.finsherov.testping;

import com.finsherov.testping.command.TestPingCommand;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Главный класс плагина: загружает config.yml, регистрирует команду
 * и хранит состояние защиты от спама (кулдаун + одна активная проверка
 * на игрока). Сами сетевые проверки здесь не выполняются.
 */
public final class TestPingPlugin extends JavaPlugin implements Listener {

    private int pingCount;
    private int pingTimeoutMillis;
    private int pingIntervalMillis;
    private int cooldownSeconds;

    private Map<UUID, Long> lastUse;
    private Set<UUID> running;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        lastUse = new ConcurrentHashMap<UUID, Long>();
        running = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

        TestPingCommand command = new TestPingCommand(this);
        getCommand("testping").setExecutor(command);
        getCommand("testping").setTabCompleter(command);

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("TestPing включён: count=" + pingCount
                + ", timeout=" + pingTimeoutMillis + "мс"
                + ", interval=" + pingIntervalMillis + "мс"
                + ", cooldown=" + cooldownSeconds + "с");
    }

    @Override
    public void onDisable() {
        if (lastUse != null) {
            lastUse.clear();
        }
        if (running != null) {
            running.clear();
        }
    }

    /**
     * Значения читаются из config.yml и ограничиваются разумными пределами,
     * чтобы некорректный конфиг не превратил команду в бесконечный поток запросов.
     */
    private void loadSettings() {
        pingCount = clamp(getConfig().getInt("ping.count", 4), 1, 10);
        pingTimeoutMillis = clamp(getConfig().getInt("ping.timeout", 3000), 100, 10000);
        pingIntervalMillis = clamp(getConfig().getInt("ping.interval", 1000), 0, 5000);
        cooldownSeconds = clamp(getConfig().getInt("cooldown.seconds", 5), 0, 60);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Освобождаем состояние кулдауна, когда игрок выходит с сервера. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastUse.remove(id);
        running.remove(id);
    }

    public int getPingCount() {
        return pingCount;
    }

    public int getPingTimeoutMillis() {
        return pingTimeoutMillis;
    }

    public int getPingIntervalMillis() {
        return pingIntervalMillis;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public Map<UUID, Long> getLastUse() {
        return lastUse;
    }

    public Set<UUID> getRunning() {
        return running;
    }
}
