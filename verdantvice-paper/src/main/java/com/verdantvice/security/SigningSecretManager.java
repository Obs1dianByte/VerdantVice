package com.verdantvice.security;

import com.verdantvice.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Generates the HMAC key once and writes it atomically. Permission hardening is
 * best-effort because the exact filesystem capabilities differ across hosts.
 */
public final class SigningSecretManager {
    private static final int KEY_BYTES = 32;

    private final ConfigManager configs;

    public SigningSecretManager(ConfigManager configs) {
        this.configs = configs;
    }

    public byte[] loadOrCreate() {
        Path keyFile = configs.dataPath("data/signing.key");
        try {
            if (Files.exists(keyFile)) {
                return decode(Files.readString(keyFile, StandardCharsets.US_ASCII));
            }

            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            Path temporary = Files.createTempFile(
                    keyFile.getParent(),
                    "signing-",
                    ".key.tmp"
            );
            Files.writeString(
                    temporary,
                    Base64.getEncoder().encodeToString(key),
                    StandardCharsets.US_ASCII
            );
            hardenPermissions(temporary);
            try {
                Files.move(
                        temporary,
                        keyFile,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, keyFile);
            }
            hardenPermissions(keyFile);
            return key;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load or create signing secret", exception);
        }
    }

    private byte[] decode(String encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded.trim());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException("Signing secret has an invalid length");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Signing secret is not valid Base64", exception);
        }
    }

    private void hardenPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(
                    file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // Continue with a Windows ACL when POSIX permissions are unavailable.
        }

        try {
            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            UserPrincipal owner = Files.getOwner(file);
            Set<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(permissions)
                    .build();
            view.setAcl(List.of(entry));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Some managed hosts disallow ACL changes; file content remains random.
        }
    }
}
