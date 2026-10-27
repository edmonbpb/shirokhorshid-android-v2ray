package com.psiphon3.psiphonlibrary;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

/** Saves Telegram downloads into the public folder Download/ShirOKhorshid/Telegram. */
public final class TelegramDownloader {

    public static final int REQUEST_WRITE_STORAGE = 4102;

    private static final String TAG = "TelegramDownload";
    private static final String FOLDER = "ShirOKhorshid/Telegram";
    private static final int TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 32768;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_ATTEMPTS = 1000;
    private static final long MAX_BYTES = 52428800L;
    private static final String ERROR_FAILED = "download failed";
    private static final String ERROR_STORAGE = "storage is not writable";
    private static final String ERROR_TOO_LARGE = "the file is too large";

    public interface Result {
        void onDone(String path);

        void onError(String message);
    }

    private interface StreamWriter {
        void write(OutputStream output) throws IOException;
    }

    private static final class DownloadException extends IOException {
        DownloadException(String message) {
            super(message);
        }
    }

    private TelegramDownloader() {
    }

    public static boolean hasStoragePermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (ctx == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Returns true when the write is allowed; otherwise asks for it and returns false. */
    public static boolean ensurePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (activity == null || hasStoragePermission(activity)) {
            return activity != null;
        }
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
        return false;
    }

    public static String sanitizeFileName(String name, String mime) {
        StringBuilder cleaned = new StringBuilder();
        if (name != null) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) {
                    continue;
                }
                cleaned.append(c);
            }
        }
        String base = cleaned.toString().trim();
        String extension = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && isExtension(base.substring(dot + 1))) {
            extension = base.substring(dot);
            base = base.substring(0, dot).trim();
        }
        while (base.startsWith(".")) {
            base = base.substring(1).trim();
        }
        if (base.isEmpty()) {
            base = "file";
        }
        if (extension.isEmpty()) {
            extension = extensionFor(mime);
        }
        int room = MAX_NAME_LENGTH - extension.length();
        if (room < 1) {
            extension = extensionFor(mime);
            room = MAX_NAME_LENGTH - extension.length();
        }
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        return base + extension;
    }

    public static void downloadViaSocks(Context ctx, Handler main, int socksPort, String url,
            String cookies, String userAgent, String fileName, String mime, Result result) {
        Context app = appContext(ctx);
        if (app == null || socksPort <= 0 || url == null || url.trim().isEmpty()) {
            deliverError(main, result, ERROR_FAILED);
            return;
        }
        final Context context = app;
        final String name = sanitizeFileName(fileName, mime);
        final String type = mime == null ? "" : mime.trim();
        Thread worker = new Thread(() -> {
            try {
                String path = writeStream(context, name, type, output -> {
                    HttpURLConnection connection = open(url, socksPort, cookies, userAgent);
                    try {
                        copyBody(connection, output);
                    } finally {
                        connection.disconnect();
                    }
                });
                scan(context, path, type);
                deliverDone(main, result, path);
            } catch (DownloadException e) {
                deliverError(main, result, e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "download failed");
                deliverError(main, result, ERROR_FAILED);
            }
        }, "TelegramDownloader");
        worker.setDaemon(true);
        worker.start();
    }

    public static void saveBytes(Context ctx, Handler main, byte[] data, String fileName,
            String mime, Result result) {
        Context app = appContext(ctx);
        if (app == null || data == null || data.length == 0) {
            deliverError(main, result, ERROR_FAILED);
            return;
        }
        if (data.length > MAX_BYTES) {
            deliverError(main, result, ERROR_TOO_LARGE);
            return;
        }
        final String name = sanitizeFileName(fileName, mime);
        final String type = mime == null ? "" : mime.trim();
        try {
            String path = writeStream(app, name, type, output -> output.write(data));
            scan(app, path, type);
            deliverDone(main, result, path);
        } catch (DownloadException e) {
            deliverError(main, result, e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "download failed");
            deliverError(main, result, ERROR_FAILED);
        }
    }

    public static void enqueueSystem(Context ctx, String url, String cookies, String userAgent,
            String fileName, String mime) {
        Context app = appContext(ctx);
        if (app == null || url == null || url.trim().isEmpty()) {
            return;
        }
        DownloadManager.Request request;
        try {
            request = new DownloadManager.Request(Uri.parse(url));
        } catch (RuntimeException e) {
            Log.w(TAG, "download failed");
            return;
        }
        if (cookies != null && !cookies.isEmpty()) {
            request.addRequestHeader("Cookie", cookies);
        }
        if (userAgent != null && !userAgent.isEmpty()) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        String type = mime == null ? "" : mime.trim();
        if (!type.isEmpty()) {
            request.setMimeType(type);
        }
        String name = sanitizeFileName(fileName, mime);
        request.setTitle(name);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                FOLDER + "/" + name);
        DownloadManager manager = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.enqueue(request);
        } catch (RuntimeException e) {
            Log.w(TAG, "download failed");
        }
    }

    private static HttpURLConnection open(String url, int socksPort, String cookies,
            String userAgent) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
        URL current = new URL(url);
        String originalHost = current.getHost();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URLConnection raw = current.openConnection(proxy);
            if (!(raw instanceof HttpURLConnection)) {
                throw new DownloadException(ERROR_FAILED);
            }
            HttpURLConnection connection = (HttpURLConnection) raw;
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            if (cookies != null && !cookies.isEmpty() && originalHost != null
                    && originalHost.equalsIgnoreCase(current.getHost())) {
                connection.setRequestProperty("Cookie", cookies);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            int status = connection.getResponseCode();
            if (isRedirect(status)) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new DownloadException(ERROR_FAILED);
                }
                current = new URL(current, location);
                continue;
            }
            if (status < 200 || status > 299) {
                connection.disconnect();
                throw new DownloadException(ERROR_FAILED);
            }
            return connection;
        }
        throw new DownloadException(ERROR_FAILED);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void copyBody(HttpURLConnection connection, OutputStream output)
            throws IOException {
        long expected = connection.getContentLength();
        if (expected > MAX_BYTES) {
            throw new DownloadException(ERROR_TOO_LARGE);
        }
        InputStream input = connection.getInputStream();
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new DownloadException(ERROR_TOO_LARGE);
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String writeStream(Context ctx, String name, String mime, StreamWriter writer)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeToMediaStore(ctx, name, mime, writer);
        }
        return writeToDownloads(name, writer);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static String writeToMediaStore(Context ctx, String name, String mime,
            StreamWriter writer) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        String finalName = uniqueMediaName(resolver, name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
        if (!mime.isEmpty()) {
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
        }
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri item;
        try {
            item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        } catch (RuntimeException e) {
            throw new DownloadException(ERROR_STORAGE);
        }
        if (item == null) {
            throw new DownloadException(ERROR_STORAGE);
        }
        OutputStream output;
        try {
            output = resolver.openOutputStream(item);
        } catch (RuntimeException e) {
            discard(resolver, item);
            throw new DownloadException(ERROR_STORAGE);
        }
        if (output == null) {
            discard(resolver, item);
            throw new DownloadException(ERROR_STORAGE);
        }
        try {
            writer.write(output);
            output.flush();
        } catch (IOException e) {
            closeQuietly(output);
            discard(resolver, item);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(output);
            discard(resolver, item);
            throw new DownloadException(ERROR_FAILED);
        }
        try {
            output.close();
        } catch (IOException e) {
            discard(resolver, item);
            throw new DownloadException(ERROR_STORAGE);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        try {
            resolver.update(item, done, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not publish the download");
        }
        return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + finalName;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static String uniqueMediaName(ContentResolver resolver, String name) {
        String candidate = name;
        for (int index = 1; index < MAX_ATTEMPTS; index++) {
            if (!mediaNameTaken(resolver, candidate)) {
                return candidate;
            }
            candidate = suffixed(name, index);
        }
        return candidate;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean mediaNameTaken(ContentResolver resolver, String name) {
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = {name, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%"};
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID}, selection, args, null);
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void discard(ContentResolver resolver, Uri item) {
        try {
            resolver.delete(item, null, null);
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static String writeToDownloads(String name, StreamWriter writer) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new DownloadException(ERROR_STORAGE);
        }
        if (!folder.canWrite()) {
            throw new DownloadException(ERROR_STORAGE);
        }
        File target = uniqueFile(folder, name);
        FileOutputStream output;
        try {
            output = new FileOutputStream(target);
        } catch (IOException e) {
            throw new DownloadException(ERROR_STORAGE);
        }
        try {
            writer.write(output);
            output.flush();
        } catch (IOException e) {
            closeQuietly(output);
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "could not delete the partial download");
            }
            throw e;
        }
        try {
            output.close();
        } catch (IOException e) {
            throw new DownloadException(ERROR_STORAGE);
        }
        return target.getAbsolutePath();
    }

    private static File uniqueFile(File folder, String name) {
        File candidate = new File(folder, name);
        for (int index = 1; index < MAX_ATTEMPTS && candidate.exists(); index++) {
            candidate = new File(folder, suffixed(name, index));
        }
        return candidate;
    }

    private static String suffixed(String name, int index) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot) + "-" + index + name.substring(dot);
        }
        return name + "-" + index;
    }

    private static void scan(Context ctx, String path, String mime) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return;
        }
        try {
            MediaScannerConnection.scanFile(ctx, new String[]{path},
                    mime == null || mime.isEmpty() ? null : new String[]{mime}, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not index the download");
        }
    }

    private static Context appContext(Context ctx) {
        if (ctx == null) {
            return null;
        }
        Context app = ctx.getApplicationContext();
        return app == null ? ctx : app;
    }

    private static String extensionFor(String mime) {
        String type = mime == null ? "" : mime.trim().toLowerCase(Locale.US);
        int semicolon = type.indexOf(';');
        if (semicolon > 0) {
            type = type.substring(0, semicolon).trim();
        }
        if ("image/jpeg".equals(type)) {
            return ".jpg";
        }
        if ("image/png".equals(type)) {
            return ".png";
        }
        if ("video/mp4".equals(type)) {
            return ".mp4";
        }
        if ("application/pdf".equals(type)) {
            return ".pdf";
        }
        return ".bin";
    }

    private static boolean isExtension(String suffix) {
        if (suffix.isEmpty() || suffix.length() > 8) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            if (!letter && !digit) {
                return false;
            }
        }
        return true;
    }

    private static void deliverDone(Handler main, Result result, final String path) {
        if (result == null) {
            return;
        }
        if (main == null) {
            result.onDone(path);
            return;
        }
        main.post(() -> result.onDone(path));
    }

    private static void deliverError(Handler main, Result result, final String message) {
        if (result == null) {
            return;
        }
        if (main == null) {
            result.onError(message);
            return;
        }
        main.post(() -> result.onError(message));
    }
}
