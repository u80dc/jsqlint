package cn.jnu.zhangcs.jsqlint.server.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES加密解密工具类
 */
@Slf4j
public class AesUtils {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /**
     * AES解密
     *
     * @param encryptedData Base64编码的加密数据
     * @param key           密钥（16/24/32字节）
     * @return 解密后的字符串
     */
    public static String decrypt(String encryptedData, String key) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(padKey(key).getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);

            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedData);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES解密失败: {}", e.getMessage());
            throw new RuntimeException("AES解密失败", e);
        }
    }

    /**
     * AES加密
     *
     * @param data 待加密的字符串
     * @param key  密钥（16/24/32字节）
     * @return Base64编码的加密数据
     */
    public static String encrypt(String data, String key) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(padKey(key).getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            log.error("AES加密失败: {}", e.getMessage());
            throw new RuntimeException("AES加密失败", e);
        }
    }

    /**
     * 补齐密钥到16/24/32字节
     */
    private static String padKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        if (key.length() < 16) {
            return String.format("%-16s", key).substring(0, 16);
        } else if (key.length() < 24) {
            return String.format("%-24s", key).substring(0, 24);
        } else if (key.length() < 32) {
            return String.format("%-32s", key).substring(0, 32);
        }
        return key.substring(0, 32);
    }
}