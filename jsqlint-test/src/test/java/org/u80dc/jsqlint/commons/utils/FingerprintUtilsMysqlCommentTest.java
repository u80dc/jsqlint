package org.u80dc.jsqlint.commons.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.*;

/** 可执行注释中的 SQL 参与同一模板分析，普通注释和优化器提示仍被忽略。 @author u80dc */
class FingerprintUtilsMysqlCommentTest {
    @Test
    void executableStructureMatchesItsExpandedSql() {
        assertSame("SELECT /*! DISTINCT */ name FROM users", "SELECT DISTINCT name FROM users");
        assertNotEquals(hash("SELECT /*! DISTINCT */ name FROM users"), hash("SELECT name FROM users"));
        assertNotEquals(hash("SELECT /*! DISTINCT */ name FROM users"), hash("SELECT /*! ALL */ name FROM users"));
        assertSame("/*! SELECT id FROM users WHERE id=1 */", "SELECT id FROM users WHERE id=2");
        assertSame("SELECT id FROM users /*! WHERE active=TRUE */", "SELECT id FROM users WHERE active=FALSE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"40101", "50700", "80000", "80400", "100000"})
    void versionPrefixIsMetadataNotANumericSqlValue(String version) {
        assertEquals("select distinct name from users",
                FingerprintUtils.normalize("SELECT /*!" + version + " DISTINCT */ name FROM users", MYSQL));
        assertSame("SELECT /*!" + version + " DISTINCT */ name FROM users", "SELECT DISTINCT name FROM users");
        assertSame("SELECT /*!" + version + "DISTINCT */ name FROM users", "SELECT DISTINCT name FROM users");
    }

    @Test
    void numbersAfterWhitespaceBelongToTheSqlBody() {
        assertSame("SELECT /*! 80000 */", "SELECT 7");
        assertSame("SELECT /*!1+2*/", "SELECT 3+4");
        assertEquals("select ?", FingerprintUtils.normalize("SELECT /*!80000 1*/", MYSQL));
        assertEquals("", FingerprintUtils.fingerprint("/*!80000 */", MYSQL));
        assertEquals("", FingerprintUtils.fingerprint("/*! */", MYSQL));
    }

    @Test
    void innerValuesWhitespaceAndKeywordCaseAreNormalized() {
        assertSame("SELECT /*!80000 DISTINCT */ id FROM users /*! WHERE name='Alice' AND age>=18 */",
                "select distinct id from users where name='Bob' and age >= 65");
        assertSame("SELECT id FROM users /*! WHERE id IN(1,2,3) */", "SELECT id FROM users WHERE id IN(4)");
        var options = new FingerprintUtils.Options(MYSQL, false);
        assertNotEquals(FingerprintUtils.fingerprint("SELECT id FROM users /*! WHERE id IN(1,2) */", options),
                FingerprintUtils.fingerprint("SELECT id FROM users WHERE id IN(3)", options));
    }

    @Test
    void fragmentsShareOperatorAndKeywordContextWithOuterSql() {
        assertSame("SELECT amount/*!+1*/ FROM t", "SELECT amount+2 FROM t");
        assertNotEquals(hash("SELECT amount/*!+1*/ FROM t"), hash("SELECT amount-2 FROM t"));
        assertSame("SELECT id FROM t WHERE id=/*!-1*/", "SELECT id FROM t WHERE id=2");
        assertSame("SELECT CASE WHEN id=1 /*! THEN 2 ELSE 3 END */ FROM t",
                "SELECT CASE WHEN id=4 THEN 5 ELSE 6 END FROM t");
        assertSame("SELECT id FROM t LIMIT 1 /*! OFFSET 2 */", "SELECT id FROM t LIMIT 3 offset 4");
        assertSame("SELECT t./*! id */ FROM t", "SELECT t.id FROM t");
        assertSame("SELECT id FROM t WHERE id IN(/*!1,2*/,3)", "SELECT id FROM t WHERE id IN(4)");
    }

    @Test
    void markersInsideQuotedTokensAreData() {
        assertSame("SELECT '/*! DISTINCT */' FROM t", "SELECT 'other' FROM t");
        assertSame("SELECT /*! 'a*/b -- c' */ FROM t", "SELECT 'other' FROM t");
        assertSame("SELECT /*! 'a\\'*/b' */ FROM t", "SELECT 'other' FROM t");
        assertSame("SELECT /*! `a*/b` */ FROM t", "SELECT `a*/b` FROM t");
        assertNotEquals(hash("SELECT /*! `id` */ FROM t"), hash("SELECT `name` FROM t"));
        var ansiQuotes = new FingerprintUtils.Options(MYSQL, true).withMysqlAnsiQuotes(true);
        assertEquals(FingerprintUtils.fingerprint("SELECT /*! \"a*/b\" */ FROM t", ansiQuotes),
                FingerprintUtils.fingerprint("SELECT \"a*/b\" FROM t", ansiQuotes));
    }

    @Test
    void ordinaryCommentsAndHintsAreStillIgnored() {
        assertSame("SELECT /* ordinary */ /*+ FULL(t) */ id FROM t", "SELECT id FROM t");
        assertSame("SELECT /*! DISTINCT */ /*+ INDEX(t idx) */ id FROM t", "SELECT DISTINCT id FROM t");
        assertSame("SELECT /*! DISTINCT -- note\n */ id FROM t", "SELECT DISTINCT id FROM t");
        assertSame("SELECT /*! DISTINCT # note\n */ id FROM t", "SELECT DISTINCT id FROM t");
        assertEquals("", FingerprintUtils.fingerprint("/*+ hint */ -- note\n # note", MYSQL));
    }

    @Test
    void executableCommentSyntaxIsOnlyEnabledForMysql() {
        for (var dialect : new FingerprintUtils.Dialect[]{ANSI, POSTGRESQL, ORACLE}) {
            assertEquals(FingerprintUtils.fingerprint("SELECT name FROM t", dialect),
                    FingerprintUtils.fingerprint("SELECT /*! DISTINCT */ name FROM t", dialect));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT /*! DISTINCT", "SELECT /*!80000 DISTINCT", "SELECT /*! 'missing */",
            "SELECT /*! `missing */", "SELECT /*! '*/'", "SELECT /*! DISTINCT -- missing */"})
    void unterminatedExecutableContentsReportAnError(String sql) {
        var error = assertThrows(IllegalArgumentException.class, () -> FingerprintUtils.normalize(sql, MYSQL));
        assertTrue(error.getMessage().contains("position"));
    }

    @Test
    void adjacentFragmentsAndStatementBoundariesRemainDistinct() {
        assertSame("SELECT/*! DISTINCT *//*! name */FROM users", "SELECT DISTINCT name FROM users");
        assertSame("/*! SELECT 1; SELECT 2; */", "SELECT 3; SELECT 4");
        assertNotEquals(hash("SELECT/*! a *//*! b */FROM t"), hash("SELECT ab FROM t"));
        String normalized = FingerprintUtils.normalize("SELECT /*!80000 DISTINCT */ name FROM t /*! WHERE id=1 */", MYSQL);
        assertEquals(normalized, FingerprintUtils.normalize(normalized, MYSQL));
    }

    @Test
    void longAndNumerousExecutableFragmentsDoNotUseRecursiveMatching() {
        assertSame("SELECT /*! '" + "a'':--*/".repeat(20_000) + "' */ FROM t", "SELECT 'other' FROM t");
        assertSame("SELECT " + "/*! */ ".repeat(20_000) + "id FROM t", "SELECT id FROM t");
    }

    private static String hash(String sql) {
        return FingerprintUtils.fingerprint(sql, MYSQL);
    }

    private static void assertSame(String left, String right) {
        assertEquals(hash(left), hash(right), () -> FingerprintUtils.normalize(left, MYSQL)
                + " != " + FingerprintUtils.normalize(right, MYSQL));
    }
}
