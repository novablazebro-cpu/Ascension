package com.leadsystems.minecraft.ascension.session;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Salted, truncated SHA-256 of a player's IP. Only equality matters (same-IP alt detection on an
 * offline-mode server), so raw IPs are never stored. The salt lives in the plugin folder.
 */
public final class IpHasher {

    private final byte[] salt;

    public IpHasher(Path saltFile) throws IOException {
        if (Files.isRegularFile(saltFile)) {
            this.salt = HexFormat.of().parseHex(Files.readString(saltFile, StandardCharsets.UTF_8).trim());
        } else {
            byte[] fresh = new byte[16];
            new SecureRandom().nextBytes(fresh);
            Files.createDirectories(saltFile.toAbsolutePath().getParent());
            Files.writeString(saltFile, HexFormat.of().formatHex(fresh), StandardCharsets.UTF_8);
            this.salt = fresh;
        }
    }

    /** Hash of the address, or null if unknown. */
    public String hash(InetAddress address) {
        if (address == null) {
            return null;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update(address.getAddress());
            return HexFormat.of().formatHex(sha.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
