package com.lianji.fitlog;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class Updates {
    static final int REQUEST_INSTALL_PERMISSION = 46;
    private static final String REPO = "unpiom/lianyoushu";
    private static final String PROJECT_URL = "https://github.com/" + REPO;
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ASSET = "lianyoushu.apk";
    private static final String RELEASE_STATE = "update_release";
    private static final String LAST_OPENED_VERSION = "last_opened_version";
    private static final String LAST_ANNOUNCEMENT = "last_announcement";
    private static final Pattern VERSION = Pattern.compile("(?:0|[1-9][0-9]{0,8})(?:\\.(?:0|[1-9][0-9]{0,8})){2}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_RELEASE_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 96L * 1024 * 1024;

    private final MainActivity activity;
    private final PackageInfo installed;
    private Release waitingForPermission;
    private AlertDialog progress;

    Updates(MainActivity activity, Bundle state) {
        this.activity = activity;
        try {
            installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), signingFlag());
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Cannot identify installed package", error);
        }
        Bundle saved = state == null ? null : state.getBundle(RELEASE_STATE);
        if (saved != null) waitingForPermission = new Release(saved.getString("version"), saved.getLong("size"), saved.getString("digest"));
    }

    String currentVersion() {
        return installed.versionName;
    }

    LocalDate firstInstallDate() {
        long installedAt = installed.firstInstallTime;
        return installedAt > 0 ? Instant.ofEpochMilli(installedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                : LocalDate.now();
    }

    void showNewVersionAnnouncement() {
        SharedPreferences preferences = activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        long previous = preferences.getLong(LAST_OPENED_VERSION, 0);
        long current = versionCode(installed);
        if (current < previous) return;

        String notes = activity.getString(R.string.update_notes);
        String announcement = current + ":" + Integer.toHexString(notes.hashCode());
        if (announcement.equals(preferences.getString(LAST_ANNOUNCEMENT, null))) return;

        if (!preferences.edit().putLong(LAST_OPENED_VERSION, current).putString(LAST_ANNOUNCEMENT, announcement).commit()) {
            Log.w("Lianji", "Could not save announcement state");
        }
        if (previous != 0 || installed.firstInstallTime != installed.lastUpdateTime) showAnnouncement(notes);
    }

    private void showAnnouncement(String notes) {
        Ui ui = new Ui(activity);
        TextView title = ui.heading(activity.getString(R.string.update_announcement_title), 22);
        title.setGravity(Gravity.CENTER);
        ui.padding(title, 24, 21, 24, 0);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(title)
                .setMessage(notes)
                .setPositiveButton("知道了", null)
                .show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTypeface(activity.getResources().getFont(R.font.announcement_kai));
            message.setTextSize(17);
            message.setLineSpacing(ui.dp(3), 1f);
        }
    }

    void openProject() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
        } catch (ActivityNotFoundException error) {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub", PROJECT_URL));
            show("已复制 GitHub 项目网址。");
        }
    }

    void save(Bundle state) {
        if (waitingForPermission == null) return;
        Bundle release = new Bundle();
        release.putString("version", waitingForPermission.version);
        release.putLong("size", waitingForPermission.size);
        release.putString("digest", waitingForPermission.digest);
        state.putBundle(RELEASE_STATE, release);
    }

    void close() {
        if (progress != null) progress.dismiss();
    }

    void check() {
        String current = currentVersion();
        run("正在检查更新…", "检查更新失败，请检查网络后重试。", () -> latest(current), latest -> {
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("应用更新");
            Release release = latest.update;
            if (release != null) {
                String size = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f MB", release.size / 1048576.0);
                dialog.setMessage("发现新版本 " + release.version + "（" + size + "）。\n当前版本 " + current
                                + "。覆盖安装会保留应用数据。")
                        .setNegativeButton("稍后", null).setPositiveButton("下载并安装", (view, which) -> beginInstall(release));
            } else if (current.equals(latest.version)) {
                dialog.setMessage("当前已是最新发布版。").setPositiveButton("完成", null);
            } else {
                dialog.setMessage("当前版本高于 GitHub 已发布版本。\n当前版本 " + current + " · 已发布 " + latest.version)
                        .setPositiveButton("完成", null);
            }
            dialog.show();
        });
    }

    private void beginInstall(Release release) {
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            downloadAndInstall(release);
            return;
        }
        new AlertDialog.Builder(activity).setTitle("允许安装更新")
                .setMessage("请在系统设置中允许练有数安装应用。返回后会下载并打开系统安装界面。")
                .setNegativeButton("取消", null).setPositiveButton("前往设置", (dialog, which) -> {
                    try {
                        waitingForPermission = release;
                        activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.fromParts("package", activity.getPackageName(), null)), REQUEST_INSTALL_PERMISSION);
                    } catch (RuntimeException error) {
                        waitingForPermission = null;
                        show("无法打开系统安装权限设置。");
                    }
                }).show();
    }

    void onInstallPermissionResult() {
        Release release = waitingForPermission;
        waitingForPermission = null;
        if (release == null) return;
        if (activity.getPackageManager().canRequestPackageInstalls()) downloadAndInstall(release);
        else show("安装权限尚未开启，之后可重新检查更新。");
    }

    private void downloadAndInstall(Release release) {
        run("正在下载并校验安装包…", "下载或校验更新失败，请检查网络和存储空间后重试。", () -> {
            File apk = File.createTempFile("install-update-", ".apk", activity.getCacheDir());
            try {
                download(release, apk);
                verifyPackage(apk, release.version);
                return stageInstall(apk);
            } finally {
                if (!apk.delete()) Log.w("Lianji", "Temporary update file could not be removed");
            }
        }, this::confirmInstall);
    }

    private <T> void run(String message, String errorMessage, Callable<T> work, Consumer<T> onResult) {
        if (progress != null) return;
        progress = new AlertDialog.Builder(activity).setMessage(message).setCancelable(false).show();
        new Thread(() -> {
            T result = null;
            try {
                result = work.call();
            } catch (Exception error) {
                Log.w("Lianji", "Update request failed", error);
            }
            T value = result;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                progress = null;
                if (value instanceof InstallSession && (activity.isFinishing() || activity.isDestroyed())) {
                    ((InstallSession) value).abandon();
                }
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (value == null) show(errorMessage);
                else onResult.accept(value);
            });
        }, "app-update").start();
    }

    private static LatestRelease latest(String currentVersion) throws Exception {
        HttpsURLConnection connection = connect(new URL(API), true);
        JSONObject json;
        try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int read;
            while ((read = stream.read(bytes)) != -1) {
                if (read > MAX_RELEASE_BYTES - buffer.size()) throw new IOException("Release metadata too large");
                buffer.write(bytes, 0, read);
            }
            json = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
        return parseLatestRelease(json, currentVersion);
    }

    static LatestRelease parseLatestRelease(JSONObject json, String currentVersion) throws Exception {
        String tag = json.getString("tag_name");
        if (!tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()) throw new IOException("Invalid release tag");
        String version = tag.substring(1);
        Release update = compareVersions(version, currentVersion) > 0 ? parseReleaseApk(json, version) : null;
        return new LatestRelease(version, update);
    }

    private static Release parseReleaseApk(JSONObject json, String version) throws Exception {
        JSONArray assets = json.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!ASSET.equals(asset.optString("name"))) continue;
            Release release = new Release(version, asset.getLong("size"), asset.getString("digest"));
            if (!release.url().equals(asset.getString("browser_download_url"))) throw new IOException("Unexpected release asset URL");
            return release;
        }
        throw new IOException("Release APK missing");
    }

    static int compareVersions(String left, String right) {
        if (!VERSION.matcher(left).matches() || !VERSION.matcher(right).matches()) throw new IllegalArgumentException("Invalid version");
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < a.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static HttpsURLConnection connect(URL url, boolean metadata) throws IOException {
        for (int redirects = 0; redirects <= 4; redirects++) {
            String host = url.getHost();
            boolean allowed = metadata ? "api.github.com".equals(host) : "github.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host);
            if (!allowed || !"https".equals(url.getProtocol()) || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)) throw new IOException("Unexpected update host");
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "LianYouShu-Android");
            connection.setRequestProperty("Accept", metadata ? "application/vnd.github+json" : "application/octet-stream");
            try {
                int code = connection.getResponseCode();
                if (code == 200) return connection;
                if (code < 300 || code > 399) throw new IOException("Update HTTP status " + code);
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Missing update redirect");
                url = new URL(url, location);
            } catch (IOException | RuntimeException error) {
                connection.disconnect();
                throw error;
            }
            connection.disconnect();
        }
        throw new IOException("Too many update redirects");
    }

    private static void download(Release release, File apk) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpsURLConnection connection = connect(new URL(release.url()), false);
        try (InputStream stream = connection.getInputStream(); OutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[32 * 1024];
            long length = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                length += read;
                if (length > release.size) throw new IOException("Oversized update APK");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            if (length != release.size || !MessageDigest.isEqual(digest.digest(), release.sha256())) {
                throw new IOException("Update APK digest mismatch");
            }
        } finally {
            connection.disconnect();
        }
    }

    private void verifyPackage(File apk, String version) throws IOException {
        PackageInfo candidate = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), signingFlag());
        Signature[] signatures = candidate == null ? null : signers(candidate);
        if (candidate == null || !installed.packageName.equals(candidate.packageName) || !version.equals(candidate.versionName)
                || versionCode(candidate) <= versionCode(installed) || signatures == null || signatures.length == 0
                || !Arrays.equals(signers(installed), signatures)) throw new IOException("Update package or certificate mismatch");
    }

    private InstallSession stageInstall(File apk) throws IOException {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams parameters = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        parameters.setAppPackageName(installed.packageName);
        parameters.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) parameters.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        InstallSession staged = new InstallSession(installer, installer.createSession(parameters));
        try (PackageInstaller.Session session = installer.openSession(staged.id);
             InputStream input = new FileInputStream(apk); OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            session.fsync(output);
            return staged;
        } catch (IOException | RuntimeException error) {
            staged.abandon();
            throw error;
        }
    }

    private void confirmInstall(InstallSession staged) {
        try (PackageInstaller.Session session = staged.installer.openSession(staged.id)) {
            Intent callback = new Intent(activity, InstallResultActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle options = Build.VERSION.SDK_INT >= 35 ? ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle() : null;
            PendingIntent result = PendingIntent.getActivity(activity, staged.id, callback, PendingIntent.FLAG_MUTABLE, options);
            session.commit(result.getIntentSender());
        } catch (IOException | RuntimeException error) {
            staged.abandon();
            Log.w("Lianji", "Unable to open Android package installer", error);
            show("无法打开系统安装界面，请重试。");
        }
    }

    private void show(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT < 28) return info.signatures;
        return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static final class LatestRelease {
        final String version;
        final Release update;

        LatestRelease(String version, Release update) {
            this.version = version;
            this.update = update;
        }
    }

    static final class Release {
        final String version;
        final long size;
        final String digest;

        Release(String version, long size, String digest) {
            if (!VERSION.matcher(version).matches() || size < 1 || size > MAX_APK_BYTES || !DIGEST.matcher(digest).matches()) {
                throw new IllegalArgumentException("Invalid release APK metadata");
            }
            this.version = version;
            this.size = size;
            this.digest = digest;
        }

        String url() {
            return PROJECT_URL + "/releases/download/v" + version + "/" + ASSET;
        }

        byte[] sha256() {
            byte[] bytes = new byte[32];
            for (int i = 0; i < bytes.length; i++) {
                int start = "sha256:".length() + 2 * i;
                bytes[i] = (byte) Integer.parseInt(digest.substring(start, start + 2), 16);
            }
            return bytes;
        }
    }

    private static final class InstallSession {
        final PackageInstaller installer;
        final int id;

        InstallSession(PackageInstaller installer, int id) {
            this.installer = installer;
            this.id = id;
        }

        void abandon() {
            try {
                installer.abandonSession(id);
            } catch (RuntimeException error) {
                Log.w("Lianji", "Install session could not be abandoned", error);
            }
        }
    }
}
