package cn.jnu.zhangcs.jsqlint.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FingerprintUtils} 的行为测试。
 *
 * <p>覆盖归一化各步骤（注释、字面量、IN 列表、运算符空白）、
 * 指纹稳定性与结构等价判断。</p>
 */
class FingerprintUtilsTest {

    @Test
    void normalizeStripsLineAndBlockComments() {
        assertEquals(
                "select * from users",
                FingerprintUtils.normalize("select * -- trailing comment\nfrom /* inline */ users"));
    }

    @Test
    void normalizeLowercasesKeywords() {
        assertEquals(
                "select id from users",
                FingerprintUtils.normalize("SELECT ID FROM USERS"));
    }

    @Test
    void normalizeReplacesStringAndHexLiterals() {
        String normalized = FingerprintUtils.normalize(
                "select * from users where name = 'bob''s' and flag = 0xABCD");
        assertFalse(normalized.contains("bob"));
        assertFalse(normalized.contains("0xabcd"));
        assertTrue(normalized.contains("?"));
    }

    @Test
    void normalizeReplacesNumericLiteralsWithoutTouchingIdentifiers() {
        String normalized = FingerprintUtils.normalize(
                "select col1 from t1 where id = 123 and price = 9.5 and ratio = 1e3");
        assertFalse(normalized.contains("123"));
        assertFalse(normalized.contains("9.5"));
        assertFalse(normalized.contains("1e3"));
        // 标识符中的数字必须保留
        assertTrue(normalized.contains("col1"));
        assertTrue(normalized.contains("t1"));
    }

    @Test
    void normalizeCollapsesInList() {
        assertEquals(
                FingerprintUtils.normalize("select * from t where id in (?,?, ?)"),
                FingerprintUtils.normalize("select * from t where id in (?)"));
    }

    @Test
    void normalizeCanonicalizesComparisonOperatorSpacing() {
        assertEquals(
                FingerprintUtils.normalize("select * from t where id=1"),
                FingerprintUtils.normalize("select * from t where id = 1"));
    }

    @Test
    void normalizeReturnsEmptyForNullOrBlank() {
        assertEquals("", FingerprintUtils.normalize(null));
        assertEquals("", FingerprintUtils.normalize("   "));
    }

    @Test
    void normalizeCollapsesWhitespace() {
        assertEquals(
                "select id from users",
                FingerprintUtils.normalize("select\n\t id   from\nusers"));
    }

    @Test
    void fingerprintIsStableAndSha256Hex() {
        String fp1 = FingerprintUtils.fingerprint("SELECT * FROM users WHERE id = 1");
        String fp2 = FingerprintUtils.fingerprint("select * from users where id = 6");

        assertEquals(fp1, fp2);
        assertEquals(64, fp1.length());
        assertTrue(fp1.matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprintReturnsEmptyForEmptyInput() {
        assertEquals("", FingerprintUtils.fingerprint(null));
        assertEquals("", FingerprintUtils.fingerprint(""));
    }

    @Test
    void fingerprintDiffersWhenStructureDiffers() {
        String fp1 = FingerprintUtils.fingerprint("SELECT * FROM users WHERE id = 1");
        String fp2 = FingerprintUtils.fingerprint("SELECT * FROM orders WHERE id = 1");
        String fp3 = FingerprintUtils.fingerprint("DELETE FROM users WHERE id = 1");

        assertNotEquals(fp1, fp2);
        assertNotEquals(fp1, fp3);
    }

    @Test
    void isSameStructureIgnoresLiteralsAndComments() {
        assertTrue(FingerprintUtils.isSameStructure(
                "select name from users where id = 42",
                "SELECT name FROM users WHERE id = 7 -- find by id"));
        assertFalse(FingerprintUtils.isSameStructure(
                "select name from users",
                "select name from orders"));
    }

    @Test
    void doubleQuotedLiteralIsMasked() {
        String normalized = FingerprintUtils.normalize("select * from t where name = \"alice\"");
        assertFalse(normalized.contains("alice"));
        assertTrue(normalized.contains("?"));
    }

    @Test
    void negativeNumberIsMaskedAsLiteral() {
        String normalized = FingerprintUtils.normalize("select * from t where amount > -100");
        assertFalse(normalized.contains("100"));
    }
}
