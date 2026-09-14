package com.finsherov.testping.ping;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Сетевая часть плагина. Все методы здесь блокирующие, поэтому вызывать их
 * можно ТОЛЬКО из асинхронного потока (никогда из main thread Bukkit).
 *
 * ВАЖНО про InetAddress.isReachable():
 * он НЕ гарантирует использование настоящего ICMP Echo Request во всех
 * окружениях. Если у процесса нет прав на отправку ICMP (на Linux это root
 * или CAP_NET_RAW), JVM может использовать запасной механизм — TCP-подключение
 * к порту 7 (echo), который многие хосты и файрволы закрывают. Подробности —
 * в README.md, раздел «Ограничения isReachable()».
 */
public final class PingService {

    /**
     * Разрешает имя хоста в IP-адрес (DNS-запрос выполняется один раз,
     * здесь, в асинхронном потоке).
     *
     * @throws UnknownHostException если хост не удалось разрешить
     */
    public InetAddress resolve(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    /**
     * Отправляет один эхо-запрос и измеряет RTT (round-trip time).
     * Измерение через System.nanoTime(), т.к. это монотонные часы —
     * System.currentTimeMillis() мог бы «прыгнуть» при синхронизации времени.
     *
     * @param address       уже разрешённый адрес (повторного DNS-запроса нет)
     * @param timeoutMillis максимальное время одного запроса
     */
    public PingResult pingOnce(InetAddress address, int timeoutMillis) {
        long start = System.nanoTime();
        try {
            boolean reachable = address.isReachable(timeoutMillis);
            long rttMillis = (System.nanoTime() - start) / 1_000_000L;
            return reachable ? PingResult.success(rttMillis) : PingResult.timeout();
        } catch (Exception e) {
            // Любая ошибка сети не должна уйти в планировщик как необработанное
            // исключение — превращаем её в результат "error" и показываем игроку.
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return PingResult.error(message);
        }
    }
}
