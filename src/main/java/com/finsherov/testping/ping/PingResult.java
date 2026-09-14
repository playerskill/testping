package com.finsherov.testping.ping;

/**
 * Неизменяемый (immutable) результат одного эхо-запроса.
 *
 * Три возможных исхода:
 *  - success()  — хост ответил, есть RTT в миллисекундах;
 *  - timeout()  — ответа нет за отведённое время (считается потерей);
 *  - error()    — сетевая ошибка (считается потерей).
 */
public final class PingResult {

    private final boolean success;
    private final boolean timedOut;
    private final long rttMillis;
    private final String errorMessage;

    private PingResult(boolean success, boolean timedOut, long rttMillis, String errorMessage) {
        this.success = success;
        this.timedOut = timedOut;
        this.rttMillis = rttMillis;
        this.errorMessage = errorMessage;
    }

    public static PingResult success(long rttMillis) {
        return new PingResult(true, false, rttMillis, null);
    }

    public static PingResult timeout() {
        return new PingResult(false, true, -1L, null);
    }

    public static PingResult error(String message) {
        return new PingResult(false, false, -1L, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public long getRttMillis() {
        return rttMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
