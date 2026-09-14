package com.finsherov.testping.command;

import com.finsherov.testping.TestPingPlugin;
import com.finsherov.testping.ping.PingResult;
import com.finsherov.testping.ping.PingService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * /testping <host> — проверка доступности в стиле Windows ping, выполняемая
 * с САМОГО Minecraft-сервера (server -> host -> server), а не с компьютера
 * игрока.
 *
 * Вся сетевая работа выполняется асинхронно через планировщик Bukkit;
 * каждое сообщение игроку возвращается в main thread через runTask().
 * Runtime.exec() не используется вообще — пользовательский host никогда
 * не попадает ни в одну shell-команду (риск инъекции исключён).
 */
public final class TestPingCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "TestPing"
            + ChatColor.DARK_GRAY + "]" + ChatColor.RESET + " ";

    // Принимаем только символы имён хостов / IPv4 / IPv6. Так в плагин не попадёт
    // ни один служебный символ shell, даже если где-то когда-то появится exec.
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,253}$");

    private static final List<String> SUGGESTIONS = Arrays.asList(
            "google.com", "8.8.8.8", "1.1.1.1", "example.com", "yandex.ru");

    private final TestPingPlugin plugin;
    private final PingService pingService = new PingService();

    public TestPingCommand(TestPingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Дублируем проверку из plugin.yml — сообщение гарантированно наше.
        if (!sender.hasPermission("testping.use")) {
            send(sender, PREFIX + ChatColor.RED + "У вас нет разрешения на использование этой команды.");
            return true;
        }

        if (args.length != 1 || args[0].trim().isEmpty()) {
            send(sender, PREFIX + ChatColor.RED + "Использование: /testping <host>");
            return true;
        }

        final String host = args[0].trim();
        if (!HOST_PATTERN.matcher(host).matches()) {
            send(sender, PREFIX + ChatColor.RED + "Некорректный или неизвестный хост: " + ChatColor.WHITE + host);
            return true;
        }

        // Кулдаун действует только на игроков; консоль доверена.
        if (sender instanceof Player) {
            UUID id = ((Player) sender).getUniqueId();

            if (plugin.getRunning().contains(id)) {
                send(sender, PREFIX + ChatColor.RED + "Дождитесь завершения текущей проверки.");
                return true;
            }

            long now = System.currentTimeMillis();
            Long last = plugin.getLastUse().get(id);
            if (last != null) {
                long elapsedSeconds = (now - last) / 1000L;
                long remaining = plugin.getCooldownSeconds() - elapsedSeconds;
                if (remaining > 0) {
                    send(sender, PREFIX + ChatColor.RED + "Подождите ещё " + ChatColor.WHITE
                            + remaining + ChatColor.RED + " сек. перед следующим использованием.");
                    return true;
                }
            }

            // Кулдаун фиксируем сразу, чтобы нельзя было заспамить команду,
            // пока предыдущая проверка ещё выполняется.
            plugin.getLastUse().put(id, now);
            plugin.getRunning().add(id);
        }

        // КРИТИЧНО: сеть трогаем только из асинхронной задачи.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                runCheck(sender, host);
            } finally {
                if (sender instanceof Player) {
                    plugin.getRunning().remove(((Player) sender).getUniqueId());
                }
            }
        });
        return true;
    }

    /** DNS-резолв, серия запросов и статистика — выполняется в async-потоке. */
    private void runCheck(CommandSender sender, String host) {
        InetAddress address;
        try {
            address = pingService.resolve(host);
        } catch (UnknownHostException e) {
            send(sender, PREFIX + ChatColor.RED + "Не удалось разрешить имя хоста: " + ChatColor.WHITE + host);
            return;
        }

        final String ip = address.getHostAddress();
        send(sender, PREFIX + ChatColor.GRAY + "Проверка " + ChatColor.WHITE + host
                + ChatColor.GRAY + " [" + ChatColor.WHITE + ip + ChatColor.GRAY + "]...");

        int count = plugin.getPingCount();
        List<PingResult> results = new ArrayList<PingResult>(count);
        for (int i = 0; i < count; i++) {
            results.add(sendAndPing(sender, address, host, ip));
            // Пауза между запросами, как у Windows ping (кроме последнего).
            if (i < count - 1 && plugin.getPingIntervalMillis() > 0) {
                sleepQuietly(plugin.getPingIntervalMillis());
            }
        }

        sendStatistics(sender, host, results);
    }

    /** Один запрос + вывод его результата игроку. */
    private PingResult sendAndPing(CommandSender sender, InetAddress address, String host, String ip) {
        PingResult result = pingService.pingOnce(address, plugin.getPingTimeoutMillis());
        if (result.isSuccess()) {
            send(sender, PREFIX + ChatColor.GREEN + "Ответ от " + ChatColor.WHITE + ip
                    + ChatColor.GREEN + ": " + ChatColor.WHITE + result.getRttMillis() + "мс");
        } else if (result.isTimedOut()) {
            send(sender, PREFIX + ChatColor.RED + "Ответ от " + ChatColor.WHITE + host
                    + ChatColor.RED + ": превышен интервал ожидания.");
        } else {
            send(sender, PREFIX + ChatColor.RED + "Ошибка при выполнении ping: "
                    + ChatColor.WHITE + result.getErrorMessage());
        }
        return result;
    }

    /** Считает и выводит статистику; min/max/avg — только при наличии ответов. */
    private void sendStatistics(CommandSender sender, String host, List<PingResult> results) {
        int sent = results.size();
        int received = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long total = 0L;

        for (PingResult result : results) {
            if (!result.isSuccess()) {
                continue;
            }
            received++;
            long rtt = result.getRttMillis();
            if (rtt < min) {
                min = rtt;
            }
            if (rtt > max) {
                max = rtt;
            }
            total += rtt;
        }

        int lost = sent - received;
        int percent = (int) Math.round(lost * 100.0 / sent);

        send(sender, PREFIX + ChatColor.AQUA + "Статистика для " + ChatColor.WHITE + host + ChatColor.AQUA + ":");
        send(sender, ChatColor.GRAY + "    Пакетов: отправлено = " + ChatColor.WHITE + sent
                + ChatColor.GRAY + ", получено = " + ChatColor.WHITE + received
                + ChatColor.GRAY + ", потеряно = " + ChatColor.WHITE + lost
                + ChatColor.GRAY + " (" + ChatColor.WHITE + percent + "%" + ChatColor.GRAY + " потерь)");

        if (received == 0) {
            send(sender, PREFIX + ChatColor.RED + "Не удалось получить ни одного ответа.");
            return;
        }

        send(sender, ChatColor.GRAY + "Приблизительное время приема-передачи:");
        send(sender, ChatColor.GRAY + "    Минимальное = " + ChatColor.WHITE + min + "мс"
                + ChatColor.GRAY + ", Максимальное = " + ChatColor.WHITE + max + "мс"
                + ChatColor.GRAY + ", Среднее = " + ChatColor.WHITE + (total / received) + "мс");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Отправка сообщения. Вызывается из async-задачи, поэтому фактический
     * sendMessage() всегда уходит в main thread через планировщик
     * (для консоли работает так же). У консоли коды цветов вычищаются.
     */
    private void send(CommandSender sender, String message) {
        final String text = (sender instanceof Player) ? message : ChatColor.stripColor(message);
        try {
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(text));
        } catch (IllegalPluginAccessException pluginIsDisabling) {
            // Плагин выключается — отдаём сообщение напрямую, чтобы результат не потерялся.
            sender.sendMessage(text);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase();
        List<String> matches = new ArrayList<String>();
        for (String suggestion : SUGGESTIONS) {
            if (suggestion.startsWith(prefix)) {
                matches.add(suggestion);
            }
        }
        return matches;
    }
}
