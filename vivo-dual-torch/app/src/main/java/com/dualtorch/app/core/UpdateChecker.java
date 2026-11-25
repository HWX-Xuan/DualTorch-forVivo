package com.dualtorch.app.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.dualtorch.app.ApkProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub Releases 更新检查。
 *
 * 只做三件事：读 /releases/latest、比较版本号、把 APK 下到缓存目录。
 * 没有第三方依赖，也不上报任何设备信息 —— 请求就是一次普通的 GET。
 */
public final class UpdateChecker {

    public static final String REPO = "HWX-Xuan/DualTorch-forVivo";
    public static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "DualTorch-Android";

    private UpdateChecker() {
    }

    /** 一次 release 里我们关心的字段。 */
    public static class Release {
        public String version = "";       // 1.0.2（去掉 v）
        public String tag = "";           // v1.0.2
        public String name = "";
        public String notes = "";
        public String publishedAt = "";
        public String apkUrl = "";
        public long apkSize = 0;
        public String pageUrl = PAGE;
    }

    public interface Callback {
        /**
         * @param release 有新版时非空；已是最新时为空
         * @param error   出错时的说明；成功为 null
         */
        void onResult(Release release, String error);
    }

    public interface DownloadCallback {
        void onProgress(long done, long total);

        void onDone(File apk);

        void onError(String message);
    }

    /** 读当前安装包的 versionName。 */
    public static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    /** 后台线程请求 latest release；回调切回主线程。 */
    public static void check(final Context ctx, final Callback cb) {
        new Thread(() -> {
            Release rel = null;
            String err = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API).openConnection();
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code == 404) {
                    err = "仓库还没有发布版本";
                } else if (code == 403) {
                    err = "请求太频繁，稍后再试";
                } else if (code != 200) {
                    err = "检查失败（HTTP " + code + "）";
                } else {
                    String body = readAll(c.getInputStream());
                    rel = parse(new JSONObject(body));
                    if (rel.version.isEmpty()) {
                        err = "版本号解析失败";
                        rel = null;
                    } else if (compareVersion(rel.version, appVersion(ctx)) <= 0) {
                        rel = null;      // 已是最新
                    }
                }
            } catch (Throwable t) {
                err = "网络不可用：" + t.getClass().getSimpleName();
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            final Release result = rel;
            final String message = err;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onResult(result, message));
        }, "update-check").start();
    }

    private static Release parse(JSONObject o) {
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.version = stripV(r.tag);
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        r.publishedAt = o.optString("published_at", "");
        r.pageUrl = o.optString("html_url", PAGE);
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String n = a.optString("name", "");
                if (n.toLowerCase().endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url", "");
                    r.apkSize = a.optLong("size", 0);
                    break;
                }
            }
        }
        return r;
    }

    /** v1.0.2 → 1.0.2 */
    private static String stripV(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    /** 语义化比较：a > b 返回 1，相等 0，小于 -1。 */
    public static int compareVersion(String a, String b) {
        int[] x = parseVersion(a);
        int[] y = parseVersion(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int vx = i < x.length ? x[i] : 0;
            int vy = i < y.length ? y[i] : 0;
            if (vx != vy) {
                return vx > vy ? 1 : -1;
            }
        }
        return 0;
    }

    private static int[] parseVersion(String s) {
        String v = stripV(s);
        String[] parts = v.split("[^0-9]+");
        int count = 0;
        for (String p : parts) {
            if (!p.isEmpty()) {
                count++;
            }
        }
        int[] out = new int[Math.max(1, count)];
        int i = 0;
        for (String p : parts) {
            if (!p.isEmpty() && i < out.length) {
                try {
                    out[i++] = Integer.parseInt(p);
                } catch (Throwable ignored) {
                    out[i++] = 0;
                }
            }
        }
        return out;
    }

    /** 下载 APK 到 cacheDir/update/，回调在主线程。 */
    public static void download(final Context ctx, final Release rel,
                                final DownloadCallback cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            File tmp = null;
            try {
                File dir = new File(ctx.getCacheDir(), "update");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建缓存目录");
                }
                String fileName = "DualTorch-" + (rel.version.isEmpty() ? "latest" : rel.version) + ".apk";
                tmp = new File(dir, fileName);
                if (tmp.exists() && !tmp.delete()) {
                    throw new IllegalStateException("无法清理旧安装包");
                }

                c = (HttpURLConnection) new URL(rel.apkUrl).openConnection();
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                if (code != 200) {
                    throw new IllegalStateException("下载失败（HTTP " + code + "）");
                }
                long total = c.getContentLengthLong();
                if (total <= 0) {
                    total = rel.apkSize;
                }
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    long lastPost = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastPost > 120) {
                            lastPost = now;
                            postProgress(cb, done, total);
                        }
                    }
                    out.flush();
                    postProgress(cb, done, total);
                }
                File result = tmp;
                main(() -> cb.onDone(result));
            } catch (Throwable t) {
                if (tmp != null && tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                main(() -> cb.onError(msg));
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }, "update-download").start();
    }

    private static void postProgress(DownloadCallback cb, long done, long total) {
        main(() -> cb.onProgress(done, total));
    }

    private static void main(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /**
     * 拉起系统安装器；没有「安装未知应用」权限时先跳到设置页。
     *
     * @return true 表示已经拉起安装器；false 表示跳去了权限设置，或拉起失败
     */
    public static boolean install(Context ctx, File apk) {
        if (Build.VERSION.SDK_INT >= 26) {
            boolean allowed;
            try {
                allowed = ctx.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                allowed = true;
            }
            if (!allowed) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) {
                }
                return false;
            }
        }
        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            LogBus.e("拉起安装器失败：" + t);
            return false;
        }
    }
}
