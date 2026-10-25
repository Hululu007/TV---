package com.fongmi.android.tv.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.fongmi.android.tv.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Mirrors may return an unrelated APK; only our package, version, ABI and signer are accepted. */
public final class UpdateApkValidator implements UpdateDownloader.Validator {
    private final Context context;
    private final int expectedCode;

    public UpdateApkValidator(Context context, int expectedCode) {
        this.context = context;
        this.expectedCode = expectedCode;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void validate(File file) throws IOException {
        try {
            // Bypass the application's source-compatibility PackageManager hook.
            PackageManager manager = context.getApplicationContext() instanceof android.app.Application
                    ? ((android.app.Application) context.getApplicationContext()).getBaseContext().getPackageManager()
                    : context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            PackageInfo installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, flags);
            if (archive == null) throw new ValidationException(Reason.PARSE, "Cannot parse update APK");
            if (!BuildConfig.APPLICATION_ID.equals(archive.packageName)) throw new ValidationException(Reason.PACKAGE, "Wrong update package");
            long code = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
            if (code != expectedCode || code <= BuildConfig.VERSION_CODE) throw new ValidationException(Reason.VERSION, "Wrong update version");
            Set<Signature> expected = signers(installed);
            if (expected.isEmpty() || !expected.equals(signers(archive))) throw new ValidationException(Reason.SIGNATURE, "Wrong update signature");
            String abi = BuildConfig.FLAVOR_abi.replace('_', '-');
            try (ZipFile zip = new ZipFile(file)) {
                if (zip.getEntry("AndroidManifest.xml") == null || zip.getEntry("classes.dex") == null) {
                    throw new ValidationException(Reason.PARSE, "Invalid update APK structure");
                }
                if (!zip.stream().anyMatch(entry -> entry.getName().startsWith("lib/" + abi + "/") && entry.getName().endsWith(".so"))) {
                    throw new ValidationException(Reason.ABI, "Wrong update ABI");
                }
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(Reason.PARSE, "Cannot verify update APK", e);
        }
    }

    public enum Reason {
        PACKAGE, VERSION, SIGNATURE, ABI, PARSE
    }

    public static final class ValidationException extends IOException {
        private final Reason reason;

        private ValidationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        private ValidationException(Reason reason, String message, Exception cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        return signatures == null ? new HashSet<>() : new HashSet<>(Arrays.asList(signatures));
    }
}
