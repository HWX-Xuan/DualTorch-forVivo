package com.dualtorch.app.core;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 极简日志总线：界面、探测页、通知共用同一份记录。
 */
public final class LogBus {

    public interface Sink {
        void onLog(String line);
    }

    private static final String TAG = "DualTorch";
    private static final int MAX = 400;
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogBus() {
    }

    public static void addSink(Sink s) {
        if (s != null && !SINKS.contains(s)) {
            SINKS.add(s);
        }
    }

    public static void removeSink(Sink s) {
        SINKS.remove(s);
    }

    public static void i(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        Log.i(TAG, msg);
        synchronized (LINES) {
            LINES.add(line);
            if (LINES.size() > MAX) {
                LINES.remove(0);
            }
        }
        for (Sink s : SINKS) {
            s.onLog(line);
        }
    }

    public static void e(String msg) {
        i("✗ " + msg);
    }

    public static void ok(String msg) {
        i("✓ " + msg);
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** 最近 n 行，用于打开日志页时回填。 */
    public static List<String> tail(int n) {
        synchronized (LINES) {
            int from = Math.max(0, LINES.size() - Math.max(0, n));
            return new ArrayList<>(LINES.subList(from, LINES.size()));
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
