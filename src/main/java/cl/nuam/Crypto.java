package cl.nuam;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/** Contraseñas (PBKDF2), TOTP (RFC 6238), hashes y cifrado AES-256-GCM de respaldos. */
public final class Crypto {
    private Crypto() {}
    private static final SecureRandom RND = new SecureRandom();
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static String hashPassword(String pw) {
        try {
            byte[] salt = new byte[16]; RND.nextBytes(salt);
            int it = 120_000;
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, it, 256)).getEncoded();
            return "pbkdf2$" + it + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static boolean verifyPassword(String pw, String stored) {
        try {
            String[] p = stored.split("\\$");
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, Integer.parseInt(p[1]), 256)).getEncoded();
            return MessageDigest.isEqual(h, Base64.getDecoder().decode(p[3]));
        } catch (Exception e) { return false; }
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String token(int bytes) {
        byte[] b = new byte[bytes]; RND.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---------------- TOTP ----------------
    public static String newTotpSecret() {
        byte[] b = new byte[20]; RND.nextBytes(b); return b32(b);
    }
    static String b32(byte[] data) {
        StringBuilder sb = new StringBuilder(); int buf = 0, bits = 0;
        for (byte x : data) { buf = (buf << 8) | (x & 0xff); bits += 8; while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; } }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }
    static byte[] unb32(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); int buf = 0, bits = 0;
        for (char c : s.toUpperCase().replace("=", "").toCharArray()) {
            int v = B32.indexOf(c); if (v < 0) continue;
            buf = (buf << 5) | v; bits += 5;
            if (bits >= 8) { o.write((buf >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return o.toByteArray();
    }
    public static String totp(String secret, long step) {
        try {
            Mac m = Mac.getInstance("HmacSHA1"); m.init(new SecretKeySpec(unb32(secret), "HmacSHA1"));
            byte[] h = m.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static String currentTotp(String secret) { return totp(secret, System.currentTimeMillis() / 30000); }
    public static boolean verifyTotp(String secret, String code) {
        if (secret == null || code == null || !code.trim().matches("\\d{6}")) return false;
        long t = System.currentTimeMillis() / 30000; boolean ok = false;
        for (long d = -1; d <= 1; d++) ok |= MessageDigest.isEqual(totp(secret, t + d).getBytes(), code.trim().getBytes());
        return ok;
    }

    // ---------------- AES-256-GCM para respaldos (RNF-01) ----------------
    private static SecretKeySpec key(byte[] salt) throws GeneralSecurityException {
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(Config.BACKUP_KEY.toCharArray(), salt, 65_536, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }
    public static void encryptFile(Path in, Path out) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12]; RND.nextBytes(salt); RND.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(salt), new GCMParameterSpec(128, iv));
        try (OutputStream os = Files.newOutputStream(out); InputStream is = Files.newInputStream(in)) {
            os.write(salt); os.write(iv);
            try (CipherOutputStream cos = new CipherOutputStream(os, c)) { is.transferTo(cos); }
        }
    }
    public static void decryptFile(Path in, Path out) throws Exception {
        try (InputStream is = Files.newInputStream(in)) {
            byte[] salt = is.readNBytes(16), iv = is.readNBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(salt), new GCMParameterSpec(128, iv));
            try (CipherInputStream cis = new CipherInputStream(is, c); OutputStream os = Files.newOutputStream(out)) { cis.transferTo(os); }
        }
    }
}
