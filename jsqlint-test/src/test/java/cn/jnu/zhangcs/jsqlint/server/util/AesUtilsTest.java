package cn.jnu.zhangcs.jsqlint.server.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AesUtils} 单元测试。
 *
 * @author zhangcs
 */
class AesUtilsTest {

    private static final String KEY_32 = "0123456789abcdef0123456789abcdef";

    @Test
    void encryptDecrypt_roundTripWith32CharKey() {
        String data = "jdbc:mysql://localhost:3306/jsqlint?useSSL=false";
        String encrypted = AesUtils.encrypt(data, KEY_32);

        assertThat(encrypted).isNotEqualTo(data);
        assertThat(AesUtils.decrypt(encrypted, KEY_32)).isEqualTo(data);
    }

    @Test
    void encrypt_base64OutputDeterministicInEcbMode() {
        String encrypted1 = AesUtils.encrypt("明文测试abc123", KEY_32);
        String encrypted2 = AesUtils.encrypt("明文测试abc123", KEY_32);

        assertThat(encrypted1).isEqualTo(encrypted2);
        // Base64 可正常解码，且为 AES 块大小的整数倍（PKCS5 填充）
        byte[] decoded = Base64.getDecoder().decode(encrypted1);
        assertThat(decoded.length % 16).isZero();
    }

    @Test
    void encryptDecrypt_chineseContentRoundTrip() {
        String data = "密码:中文内容#¥%……&*";
        assertThat(AesUtils.decrypt(AesUtils.encrypt(data, KEY_32), KEY_32)).isEqualTo(data);
    }

    @Test
    void encryptDecrypt_emptyStringRoundTrip() {
        String encrypted = AesUtils.encrypt("", KEY_32);
        assertThat(AesUtils.decrypt(encrypted, KEY_32)).isEmpty();
    }

    @Test
    void encrypt_shortKeyPaddedTo16Bytes() {
        // 5 字符密钥补空格到 16 字节，加解密双方补齐方式一致即可往返
        String data = "round-trip";
        String encrypted = AesUtils.encrypt(data, "short");
        assertThat(AesUtils.decrypt(encrypted, "short")).isEqualTo(data);
    }

    @Test
    void encrypt_key16To23CharsPaddedTo24Bytes() {
        String data = "24-byte-key-test";
        String encrypted = AesUtils.encrypt(data, "abcdefghijklmnopqr");
        assertThat(AesUtils.decrypt(encrypted, "abcdefghijklmnopqr")).isEqualTo(data);
    }

    @Test
    void encrypt_key24To31CharsPaddedTo32Bytes() {
        String data = "32-byte-key-test";
        String encrypted = AesUtils.encrypt(data, "0123456789abcdefghijklmn");
        assertThat(AesUtils.decrypt(encrypted, "0123456789abcdefghijklmn")).isEqualTo(data);
    }

    @Test
    void encrypt_overlongKeyTruncatedTo32Bytes() {
        String data = "truncate-test";
        String encrypted = AesUtils.encrypt(data, KEY_32 + "overflow-chars");
        // 前 32 字符与 KEY_32 相同，截断后等价
        assertThat(AesUtils.decrypt(encrypted, KEY_32)).isEqualTo(data);
    }

    @Test
    void decrypt_mismatchedKeyThrowsException() {
        String encrypted = AesUtils.encrypt("secret", KEY_32);
        assertThatThrownBy(() -> AesUtils.decrypt(encrypted, KEY_32.replace('0', '9')))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AES解密失败");
    }

    @Test
    void decrypt_invalidBase64ThrowsException() {
        assertThatThrownBy(() -> AesUtils.decrypt("not-valid-base64!!!", KEY_32))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AES解密失败");
    }

    @Test
    void encrypt_nullKeyThrowsRuntimeException() {
        // padKey 抛出的 IllegalArgumentException 被外层 catch 包裹为 RuntimeException
        assertThatThrownBy(() -> AesUtils.encrypt("data", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AES加密失败")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_nullKeyThrowsRuntimeException() {
        assertThatThrownBy(() -> AesUtils.decrypt("abc", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AES解密失败")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encrypt_nullDataThrowsException() {
        assertThatThrownBy(() -> AesUtils.encrypt(null, KEY_32))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AES加密失败");
    }
}
