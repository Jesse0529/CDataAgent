package com.AIBI.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM 加解密工具 — 用于 API Key 等敏感配置的静态加密。
 * <p>
 * 使用 256 位密钥（通过 SHA-256 从任意长度的密钥材料派生），
 * 每次加密生成随机 12 字节 IV，密文附加 IV 前缀后 Base64 编码。
 */
public class AesGcmUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BIT_LEN = 128;

    private static SecretKeySpec secretKey;

    /** 不可实例化 */
    private AesGcmUtil() {}

    /**
     * 初始化加密密钥。应用启动时由 {@code ModelManager} 调用。
     * @param keyMaterial 任意长度的密钥材料（SHA-256 衍生为 256 位）
     */
    public static void init(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new IllegalArgumentException("加密密钥材料不能为空");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = md.digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("AES 密钥初始化失败", e);
        }
    }

    /**
     * 加密明文。
     * @param plaintext 明文
     * @return Base64(IV + 密文)
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        ensureInitialized();
        try {
            byte[] iv = new byte[IV_LEN];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BIT_LEN, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LEN + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LEN);
            System.arraycopy(ciphertext, 0, combined, IV_LEN, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败", e);
        }
    }

    /**
     * 解密密文。
     * @param encrypted Base64(IV + 密文)
     * @return 明文
     */
    public static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return encrypted;
        ensureInitialized();
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            if (combined.length < IV_LEN) {
                throw new RuntimeException("密文长度异常");
            }

            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LEN);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LEN, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BIT_LEN, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败", e);
        }
    }

    /**
     * 判断字符串是否已加密（Base64 编码且长度符合 AES-GCM 输出特征）。
     */
    public static boolean isEncrypted(String text) {
        if (text == null || text.isEmpty()) return false;
        // AES-GCM 加密后 IV(12) + 密文(>0) → Base64 至少 20 字符
        return text.length() >= 20 && text.matches("^[A-Za-z0-9+/=]+$");
    }

    private static void ensureInitialized() {
        if (secretKey == null) {
            throw new IllegalStateException("AES 密钥未初始化，请先配置 model.encryption.key");
        }
    }
}
