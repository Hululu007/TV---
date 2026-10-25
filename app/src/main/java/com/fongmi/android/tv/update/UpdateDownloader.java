package com.fongmi.android.tv.update;

import com.fongmi.android.tv.cache.CacheLease;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads one update, trying each source in order without reusing partial files. */
public final class UpdateDownloader {

    private static final long MAX_SIZE = 512L * 1024 * 1024;

    private final OkHttpClient client;
    private final Object lock = new Object();
    private volatile boolean cancelled;
    private Call activeCall;
    private Thread worker;

    public UpdateDownloader(OkHttpClient client) {
        this.client = client.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                // Large APKs may take several minutes; stalled reads still fail after 12 seconds.
                .callTimeout(600, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followSslRedirects(false)
                .build();
    }

    /** Cancellation is permanent; create a new instance for another user action. */
    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            if (activeCall != null) activeCall.cancel();
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        }
    }

    public File download(List<String> urls, File target, long expectedSize, String sha256,
                         Validator validator, Progress progress) throws IOException {
        if (urls == null || urls.isEmpty()) throw new IOException("No update download sources");
        if (validator == null) throw new IOException("Missing update validator");
        String expectedHash = sha256 == null ? "" : sha256.trim();
        if (!expectedHash.isEmpty() && !expectedHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Invalid update SHA-256");
        }
        List<String> candidates = new ArrayList<>(urls);
        File partial = new File(target.getPath() + ".part");
        synchronized (lock) {
            checkCancelled();
            if (worker != null) throw new IOException("Update download already running");
            worker = Thread.currentThread();
        }
        boolean completed = false;
        try (CacheLease lease = CacheLease.acquire(target, partial)) {
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new LocalFileException("Cannot create update directory");
            }
            delete(target);
            delete(partial);
            IOException failures = new IOException("All update download sources failed");
            for (int index = 0; index < candidates.size(); index++) {
                checkCancelled();
                boolean verified = false;
                try {
                    publishSource(progress, index + 1, candidates.size());
                    publish(progress, 0);
                    downloadSource(candidates.get(index), partial, expectedSize, expectedHash, progress);
                    checkCancelled();
                    // Every source must return these exact bytes. A different mirror cannot
                    // repair a package, version, signer or device rejection after SHA verification.
                    verified = !expectedHash.isEmpty();
                    publishVerifying(progress);
                    validator.validate(partial);
                    synchronized (lock) {
                        checkCancelled();
                        if (!partial.renameTo(target)) throw new LocalFileException("Cannot finalize update file");
                        publish(progress, 100);
                        checkCancelled();
                        completed = true;
                        CacheLease.protectFor(target, TimeUnit.MINUTES.toMillis(10));
                        return target;
                    }
                } catch (IOException failure) {
                    checkCancelled();
                    if (verified || failure instanceof LocalFileException) throw failure;
                    failures.addSuppressed(failure);
                } finally {
                    synchronized (lock) {
                        activeCall = null;
                    }
                    delete(partial);
                }
            }
            checkCancelled();
            throw failures;
        } finally {
            synchronized (lock) {
                activeCall = null;
                worker = null;
            }
            if (!completed) delete(target);
            delete(partial);
        }
    }

    private void downloadSource(String url, File partial, long expectedSize, String expectedHash,
                                Progress progress) throws IOException {
        Request request;
        try {
            request = new Request.Builder().url(url).header("Accept-Encoding", "identity").build();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid update download URL", invalid);
        }
        Call call = client.newCall(request);
        synchronized (lock) {
            checkCancelled();
            activeCall = call;
        }
        try (Response response = call.execute()) {
            checkCancelled();
            if (!response.isSuccessful()) throw new IOException("Update HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty update response");
            String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
                throw new IOException("Update source returned a document instead of an APK");
            }
            long declaredSize = body.contentLength();
            long limit = expectedSize > 0 ? expectedSize : MAX_SIZE;
            if (declaredSize == 0 || declaredSize > limit ||
                    (expectedSize > 0 && declaredSize > 0 && declaredSize != expectedSize)) {
                throw new IOException("Unexpected update response size");
            }
            MessageDigest digest = sha256();
            long total = 0;
            long progressSize = expectedSize > 0 ? expectedSize : declaredSize;
            int lastPercent = 0;
            byte[] header = new byte[4];
            int headerSize = 0;
            try (InputStream input = body.byteStream(); FileOutputStream output = openOutput(partial)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (read == 0) continue;
                    if (read > limit - total) throw new IOException("Update exceeds expected size");
                    total += read;
                    if (headerSize < header.length) {
                        int copy = Math.min(read, header.length - headerSize);
                        System.arraycopy(buffer, 0, header, headerSize, copy);
                        headerSize += copy;
                        if (headerSize == 4 && (header[0] != 'P' || header[1] != 'K' || header[2] != 3 || header[3] != 4)) {
                            throw new IOException("Update source returned an invalid APK header");
                        }
                    }
                    try {
                        output.write(buffer, 0, read);
                    } catch (IOException failure) {
                        throw new LocalFileException("Cannot write update file", failure);
                    }
                    digest.update(buffer, 0, read);
                    if (progressSize > 0) {
                        int percent = (int) Math.min(99, total * 100.0 / progressSize);
                        if (percent != lastPercent) {
                            publish(progress, percent);
                            lastPercent = percent;
                        }
                    }
                }
                checkCancelled();
                if (total < 4 || (expectedSize > 0 && total != expectedSize) ||
                        (declaredSize >= 0 && total != declaredSize)) {
                    throw new IOException("Incomplete update download");
                }
                if (!expectedHash.isEmpty() && !expectedHash.equalsIgnoreCase(hex(digest.digest()))) {
                    throw new IOException("Update SHA-256 mismatch");
                }
                try {
                    output.getFD().sync();
                } catch (IOException failure) {
                    throw new LocalFileException("Cannot save update file", failure);
                }
            }
        }
    }

    private void publish(Progress progress, int percent) throws IOException {
        synchronized (lock) {
            checkCancelled();
            if (progress != null) progress.update(percent);
            checkCancelled();
        }
    }

    private void publishSource(Progress progress, int attempt, int total) throws IOException {
        synchronized (lock) {
            checkCancelled();
            if (progress != null) progress.onSource(attempt, total);
            checkCancelled();
        }
    }

    private void publishVerifying(Progress progress) throws IOException {
        synchronized (lock) {
            checkCancelled();
            if (progress != null) progress.onVerifying();
            checkCancelled();
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update download cancelled");
        }
    }

    private static void delete(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new LocalFileException("Cannot remove update file: " + file.getName());
    }

    private static FileOutputStream openOutput(File file) throws IOException {
        try {
            return new FileOutputStream(file) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } catch (IOException failure) {
                        throw new LocalFileException("Cannot close update file", failure);
                    }
                }
            };
        } catch (IOException failure) {
            throw new LocalFileException("Cannot create update file", failure);
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    public interface Validator {
        void validate(File file) throws IOException;
    }

    public interface Progress {
        void update(int percent);

        default void onSource(int attempt, int total) {}

        default void onVerifying() {}
    }

    public static final class LocalFileException extends IOException {
        private LocalFileException(String message) {
            super(message);
        }

        private LocalFileException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
