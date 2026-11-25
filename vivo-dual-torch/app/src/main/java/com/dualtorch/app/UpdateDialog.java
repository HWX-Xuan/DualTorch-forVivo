package com.dualtorch.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import com.dualtorch.app.core.LogBus;
import com.dualtorch.app.core.Prefs;
import com.dualtorch.app.core.UpdateChecker;

import java.io.File;
import java.util.Locale;

/**
 * 更新提示弹窗。
 *
 * 视觉沿用应用本身的深色 + 琥珀配色：圆角卡片、琥珀主按钮、ghost 次按钮，
 * 不复用系统 AlertDialog，避免在暗色主题下出现白底和蓝色按钮。
 */
public final class UpdateDialog {

    private UpdateDialog() {
    }

    public static void show(final Activity act, final UpdateChecker.Release rel) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update);
        dialog.setCanceledOnTouchOutside(true);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            int width = dm.widthPixels - (int) (40 * dm.density);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvTitle = dialog.findViewById(R.id.tvUpdateTitle);
        TextView tvMeta = dialog.findViewById(R.id.tvUpdateMeta);
        TextView tvAuthor = dialog.findViewById(R.id.tvUpdateAuthor);
        TextView tvNotes = dialog.findViewById(R.id.tvUpdateNotes);
        final TextView tvProgress = dialog.findViewById(R.id.tvUpdateProgress);
        TextView btnClose = dialog.findViewById(R.id.btnUpdateClose);
        TextView btnSkip = dialog.findViewById(R.id.btnUpdateSkip);
        TextView btnDisable = dialog.findViewById(R.id.btnUpdateDisable);
        TextView btnLater = dialog.findViewById(R.id.btnUpdateLater);
        final TextView btnNow = dialog.findViewById(R.id.btnUpdateNow);
        TextView btnBrowser = dialog.findViewById(R.id.btnUpdateBrowser);

        final String current = UpdateChecker.appVersion(act);
        tvTitle.setText("发现新版本 v" + rel.version);
        tvMeta.setText(String.format(Locale.US, "v%s → v%s%s%s",
                current, rel.version,
                rel.publishedAt.length() >= 10 ? " · 发布于 " + rel.publishedAt.substring(0, 10) : "",
                rel.apkSize > 0 ? " · " + sizeText(rel.apkSize) : ""));
        tvAuthor.setText("作者 HWX_Xuan · 开源在 GitHub");

        String notes = plainNotes(rel.notes);
        if (notes.isEmpty()) {
            notes = "这个版本没有写更新说明。";
        }
        tvNotes.setText(notes);
        tvNotes.setMovementMethod(new ScrollingMovementMethod());

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnLater.setOnClickListener(v -> dialog.dismiss());
        btnSkip.setOnClickListener(v -> {
            Prefs.setSkippedVersion(act, rel.version);
            LogBus.i("已跳过版本 " + rel.version);
            Toast.makeText(act, "已跳过 v" + rel.version + "，下个版本再提示",
                    Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        btnDisable.setOnClickListener(v -> {
            Prefs.setAutoUpdate(act, false);
            LogBus.i("自动更新已关闭");
            Toast.makeText(act, "已关闭自动更新，可在「高级」里重新开启",
                    Toast.LENGTH_LONG).show();
            dialog.dismiss();
        });
        btnBrowser.setOnClickListener(v -> openBrowser(act, rel));

        // 下载完成后如果只是缺「安装未知应用」权限，回来再点一次就能直接装
        final File[] downloaded = {null};
        btnNow.setOnClickListener(v -> {
            if (downloaded[0] != null && downloaded[0].exists()) {
                if (!UpdateChecker.install(act, downloaded[0])) {
                    tvProgress.setVisibility(View.VISIBLE);
                    tvProgress.setText("请允许「安装未知应用」，返回后再点一次「立即更新」");
                }
                return;
            }
            startDownload(act, dialog, rel, tvProgress, btnNow, btnSkip, btnDisable,
                    btnLater, btnClose, downloaded);
        });

        dialog.show();
    }

    private static void startDownload(final Activity act, final Dialog dialog,
                                      final UpdateChecker.Release rel,
                                      final TextView tvProgress, final TextView btnNow,
                                      final TextView btnSkip, final TextView btnDisable,
                                      final TextView btnLater, final TextView btnClose,
                                      final File[] downloaded) {
        if (rel.apkUrl == null || rel.apkUrl.isEmpty()) {
            Toast.makeText(act, "这个版本没有上传安装包，请改用浏览器", Toast.LENGTH_LONG).show();
            return;
        }
        // 下载期间锁住弹窗，避免关掉之后又弹出安装器
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        btnNow.setEnabled(false);
        btnNow.setAlpha(0.55f);
        btnNow.setText("下载中…");
        btnSkip.setEnabled(false);
        btnDisable.setEnabled(false);
        btnLater.setEnabled(false);
        btnClose.setEnabled(false);
        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setText("正在下载更新包…");

        UpdateChecker.download(act, rel, new UpdateChecker.DownloadCallback() {
            @Override
            public void onProgress(long done, long total) {
                if (total > 0) {
                    tvProgress.setText(String.format(Locale.US,
                            "正在下载更新包（已下载 %s / %s）", sizeText(done), sizeText(total)));
                } else {
                    tvProgress.setText("正在下载更新包（已下载 " + sizeText(done) + "）");
                }
            }

            @Override
            public void onDone(File apk) {
                downloaded[0] = apk;
                btnNow.setEnabled(true);
                btnNow.setAlpha(1f);
                btnNow.setText("立即安装");
                btnSkip.setEnabled(true);
                btnDisable.setEnabled(true);
                btnLater.setEnabled(true);
                btnClose.setEnabled(true);
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);
                tvProgress.setText("下载完成 · " + sizeText(apk.length()));
                if (!UpdateChecker.install(act, apk)) {
                    tvProgress.setText("请允许「安装未知应用」，返回后再点一次「立即安装」");
                }
            }

            @Override
            public void onError(String message) {
                btnNow.setEnabled(true);
                btnNow.setAlpha(1f);
                btnNow.setText("重试下载");
                btnSkip.setEnabled(true);
                btnDisable.setEnabled(true);
                btnLater.setEnabled(true);
                btnClose.setEnabled(true);
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);
                tvProgress.setText("下载失败：" + message + "，可改用浏览器更新");
                LogBus.e("更新包下载失败：" + message);
            }
        });
    }

    /** 把 release 的 Markdown 说明转成适合 TextView 的纯文本。 */
    private static String plainNotes(String md) {
        if (md == null) {
            return "";
        }
        String s = md.replace("\r\n", "\n").replace('\r', '\n');
        try {
            s = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        } catch (Throwable ignored) {
        }
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "· ");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        s = s.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    private static void openBrowser(Activity act, UpdateChecker.Release rel) {        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(rel.pageUrl));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(act, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "0 KB";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }
}
