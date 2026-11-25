package com.dualtorch.app.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Root 通道。
 *
 * 应用层拿不到「点哪一颗 LED」的控制权，是因为 CameraService 只暴露
 * 「按 cameraId 开手电」这一个动作，而 vivo 的 HAL 把它绑到了整组 LED。
 * 驱动层则是分开的，两条常见形态：
 *
 * 1) 高通平台：/sys/class/leds/led:torch_0、led:torch_1、led:flash_0、led:flash_1
 *    往各自的 brightness 写值即可独立点亮，电流上限由 torch_current_max 决定。
 *
 * 2) 联发科平台：/sys/class/flashlight_core/flashlight/flashlight_strobe
 *    一条命令带五个参数：TYPE CT PART LEVEL DURATION
 *    PART 就是灯路编号，LEVEL 是电流档（mt6360 共 30 档，27 档约 1000mA）。
 *
 * 这两条路都需要 root。vivo 旗舰官方不开放 BL 解锁，所以大多数机器走不通，
 * 但一旦有 root，这就是唯一能确定性分开两颗灯的办法。
 */
public final class RootEngine {

    public static class Node {
        public final String path;
        public final String name;
        public final String dir;
        public final int max;

        Node(String dir, String name, int max) {
            this.dir = dir;
            this.name = name;
            this.path = dir + "/" + name;
            this.max = max;
        }

        public boolean isTorch() {
            return name.contains("torch");
        }

        public boolean isFlash() {
            return name.contains("flash");
        }

        public String shortName() {
            return name;
        }

        @Override
        public String toString() {
            return name + "  (max " + max + ")";
        }
    }

    public static class MtkNode {
        public final String path;
        public final String name;

        MtkNode(String path, String name) {
            this.path = path;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Boolean rootCache;

    private RootEngine() {
    }

    public static synchronized boolean hasRoot() {
        if (rootCache != null) {
            return rootCache;
        }
        String out = exec("id");
        rootCache = out != null && out.contains("uid=0");
        return rootCache;
    }

    public static synchronized void resetRootCache() {
        rootCache = null;
    }

    public static String exec(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** leds 子系统的闪光灯节点。 */
    public static List<Node> scan() {
        List<Node> out = new ArrayList<>();
        String ls = exec("ls -1 /sys/class/leds 2>/dev/null");
        if (ls == null) {
            return out;
        }
        for (String name : ls.split("\n")) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.US);
            if (!(lower.contains("flash") || lower.contains("torch") || lower.contains("strobe"))) {
                continue;
            }
            int m = readInt("/sys/class/leds/" + name + "/max_brightness", 255);
            out.add(new Node("/sys/class/leds/" + name, name, m));
        }
        return out;
    }

    /** 联发科 flashlight_core 的调试节点。 */
    public static List<MtkNode> scanMtk() {
        List<MtkNode> out = new ArrayList<>();
        String ls = exec("ls -1 /sys/class/flashlight_core/flashlight 2>/dev/null");
        if (ls == null || ls.isEmpty()) {
            return out;
        }
        for (String name : ls.split("\n")) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            out.add(new MtkNode("/sys/class/flashlight_core/flashlight/" + name, name));
        }
        return out;
    }

    public static boolean hasMtkFlashlight() {
        String r = exec("test -e /sys/class/flashlight_core/flashlight/flashlight_strobe && echo yes");
        return r != null && r.contains("yes");
    }

    public static String platform() {
        String soc = exec("getprop ro.board.platform");
        String hw = exec("getprop ro.hardware");
        String chip = (soc == null ? "" : soc) + " " + (hw == null ? "" : hw);
        String lower = chip.toLowerCase(Locale.US);
        if (lower.contains("mt") || lower.contains("mediatek")) {
            return "MTK · " + chip.trim();
        }
        if (lower.contains("sm") || lower.contains("qcom") || lower.contains("kona")
                || lower.contains("taro") || lower.contains("sun") || lower.contains("pineapple")) {
            return "高通 · " + chip.trim();
        }
        if (hasMtkFlashlight()) {
            return "MTK（按节点推断）";
        }
        return "未知 · " + chip.trim();
    }

    public static int readInt(String path, int def) {
        String s = exec("cat " + path + " 2>/dev/null");
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static String read(String path) {
        String s = exec("cat " + path + " 2>/dev/null");
        return s == null ? "" : s.trim();
    }

    /** 写任意值；返回 null 表示成功。 */
    public static String write(String path, String value) {
        String r = exec("echo '" + value + "' > " + path);
        if (r == null) {
            return "su 不可用";
        }
        String low = r.toLowerCase(Locale.US);
        if (r.isEmpty()) {
            return null;
        }
        if (low.contains("permission denied") || low.contains("read-only")
                || low.contains("no such file") || low.contains("invalid argument")) {
            return r;
        }
        return null;
    }

    public static String setBrightness(String path, int value) {
        return write(path, String.valueOf(value));
    }

    /**
     * 联发科单灯控制。
     *
     * @param type 0 = flash（高电流瞬时），1 = torch（低电流常亮）
     * @param part 灯路编号，0 / 1
     * @param level 电流档，0–30（mt6360），0 表示关闭
     * @param durMs 持续时间，torch 常亮一般写 0
     */
    public static String mtkStrobe(int type, int part, int level, int durMs) {
        String cmd = type + " 0 " + part + " " + level + " " + durMs;
        return write("/sys/class/flashlight_core/flashlight/flashlight_strobe", cmd);
    }

    public static String mtkTorch(int part, boolean on, int level) {
        if (!on) {
            return mtkStrobe(1, part, 0, 0);
        }
        String r = mtkStrobe(1, part, level, 0);
        if (r != null) {
            // 有的实现只认 type 0，再试一次
            r = mtkStrobe(0, part, level, 0);
        }
        return r;
    }
}
