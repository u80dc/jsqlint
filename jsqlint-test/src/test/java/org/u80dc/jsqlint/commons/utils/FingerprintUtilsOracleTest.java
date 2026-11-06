package org.u80dc.jsqlint.commons.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.ORACLE;

/** Oracle 方言的字符串、绑定变量及结构保留回归。 @author u80dc */
class FingerprintUtilsOracleTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "q'[Bob's -- /* comment */]'", "Q'{Bob's -- /* comment */}'",
            "q'(Bob's [x])'", "q'<Bob's {x}>'", "q'!Bob's!'", "q'/Bob's/'",
            "q'xa'--b x'", "q''a'b''", "NQ'[中文'--]'", "nQ'<Bob's>'",
            "q'界a'--b界'", "q'🧪a'--b🧪'", "q'[nested [x] and 'quoted']'"
    })
    void alternativeQuotedStringsAreConsumedWhole(String literal) {
        assertEquals("select ? from users where id = :1",
                normalize("SELECT " + literal + " FROM users WHERE id=:1"));
        assertTrue(same("SELECT " + literal + " FROM users", "SELECT 'other' FROM users"));
    }

    @Test
    void quoteContentsCannotEraseFollowingSql() {
        assertFalse(same("SELECT q'[a'--]' FROM users", "SELECT q'[b'--]' FROM orders"));
        assertEquals("select ?, ? from dual",
                normalize("SELECT q'[a'--]' /* ordinary */ , nq'!b'/*!' FROM dual"));
        assertTrue(same("SELECT N'O''Neil', 'path\\' FROM dual", "SELECT 'other', 'text' FROM dual"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT q'[not closed'", "SELECT q'[wrong}'", "SELECT nq'!missing'",
            "SELECT q'", "SELECT q''", "SELECT q' forbidden '", "SELECT q'\twrong\t'",
            "SELECT q'\nwrong\n'", "SELECT :\"open"
    })
    void invalidOrUnterminatedQuotesReportPosition(String sql) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> normalize(sql));
        assertTrue(error.getMessage().contains("position"));
    }

    @Test
    void longAlternativeStringsDoNotUseRecursiveMatching() {
        assertEquals("select ? from dual", normalize("SELECT nq'[" + "a'--/*x*/".repeat(20_000) + "]' FROM dual"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1f", "1F", "1d", "1D", "1.5f", ".5D", "5.f", "1e-3F", "2.5E+8d", "-1.5D",
            "BINARY_FLOAT_NAN", "BINARY_DOUBLE_NAN", "BINARY_FLOAT_INFINITY", "-BINARY_DOUBLE_INFINITY"
    })
    void oracleNumericLiteralsAreMaskedWhole(String literal) {
        assertEquals("select ? from dual", normalize("SELECT " + literal + " FROM dual"));
    }

    @Test
    void numericSuffixesCannotEraseNamesOrBinarySigns() {
        assertFalse(same("SELECT amount+1f FROM t", "SELECT amount-1f FROM t"));
        assertFalse(same("SELECT col1f FROM t", "SELECT col2f FROM t"));
        assertFalse(same("SELECT \"1d\" FROM t", "SELECT \"2d\" FROM t"));
        assertFalse(same("SELECT t.binary_float_nan FROM t", "SELECT t.binary_double_nan FROM t"));
        assertFalse(same("SELECT 1foo FROM t", "SELECT 2foo FROM t"));
        assertFalse(same("SELECT amount IS NAN FROM t", "SELECT amount IS INFINITE FROM t"));
        for (String column : new String[]{"limit", "offset", "returning", "interval"}) {
            assertFalse(same("SELECT " + column + "-1 FROM t", "SELECT " + column + "+1 FROM t"), column);
        }
    }

    @Test
    void positionalNamedAndQuotedBindsKeepIdentity() {
        assertFalse(same("SELECT :1+:1 FROM dual", "SELECT :1+:2 FROM dual"));
        assertEquals("select :1, :12, :user_id, :user#1, :\"Case Name\" from dual",
                normalize("SELECT :1,:12,:user_id,:user#1,:\"Case Name\" FROM dual"));
        assertFalse(same("SELECT :user#1 FROM dual", "SELECT :user#2 FROM dual"));
        assertFalse(same("SELECT :\"ID\" FROM dual", "SELECT :\"id\" FROM dual"));
        assertTrue(normalize("SELECT :\"a--b\", :\"a\"\"b\" FROM dual").contains(":\"a--b\", :\"a\"\"b\""));
        assertEquals("begin :1 := ?; end", normalize("BEGIN :1:=42; END;"));
    }

    @Test
    void oracleIdentifiersAndDatabaseLinksKeepNames() {
        assertTrue(same("SELECT USER#1, seq$1.NEXTVAL FROM APP#1.USERS@REMOTE#1",
                "select user#1, SEQ$1.nextval from app#1.users@remote#1"));
        assertFalse(same("SELECT user#1 FROM t", "SELECT user#2 FROM t"));
        assertFalse(same("SELECT * FROM t@remote#1", "SELECT * FROM t@remote#2"));
        assertFalse(same("SELECT \"ID\" FROM t", "SELECT \"id\" FROM t"));
        assertFalse(same("SELECT q#1 FROM t", "SELECT q#2 FROM t"));
    }

    @Test
    void optimizerHintsIncludingLineHintsAreIgnored() {
        assertTrue(same("SELECT /*+ INDEX(t idx) */ id FROM t", "SELECT /*+ FULL(t) */ id FROM t"));
        assertTrue(same("SELECT --+ INDEX(t idx)\n id FROM t", "SELECT --+ FULL(t)\n id FROM t"));
        String normalized = normalize("SELECT --+ INDEX(t idx)\r\n id FROM t WHERE id=1");
        assertEquals("select id from t where id = ?", normalized);
        assertEquals(normalized, normalize(normalized));
        assertTrue(same("SELECT -- ordinary\n id FROM t", "SELECT id FROM t"));
    }

    @Test
    void inListsFoldOnlyLiteralValues() {
        String one = "SELECT * FROM t WHERE name IN ('a')";
        String many = "SELECT * FROM t WHERE name IN (q'[a]',nq'!b!')";
        assertTrue(same(one, many));
        assertFalse(FingerprintUtils.isSameStructure(one, many, new FingerprintUtils.Options(ORACLE, false)));
        assertTrue(same("SELECT * FROM t WHERE id IN (-1F,2d)", "SELECT * FROM t WHERE id IN (3)"));
        assertFalse(same("SELECT * FROM t WHERE id IN (:1,:2)", "SELECT * FROM t WHERE id IN (:1)"));
    }

    @Test
    void datetimeAndIntervalTypesRemainStructural() {
        assertTrue(same("SELECT DATE '2024-01-01', TIMESTAMP '2024-01-01 01:02:03' FROM dual",
                "SELECT DATE '2025-02-03', TIMESTAMP '2025-02-03 04:05:06' FROM dual"));
        assertFalse(same("SELECT DATE '2024-01-01' FROM dual", "SELECT TIMESTAMP '2024-01-01 00:00:00' FROM dual"));
        assertTrue(same("SELECT INTERVAL '1-2' YEAR TO MONTH FROM dual", "SELECT INTERVAL '3-4' YEAR TO MONTH FROM dual"));
        assertFalse(same("SELECT INTERVAL '1' YEAR FROM dual", "SELECT INTERVAL '1' DAY FROM dual"));
    }

    @Test
    void queryClausesAndLegacyOuterJoinRemainStructural() {
        assertTrue(same("SELECT e.id FROM emp e START WITH e.id=1 CONNECT BY PRIOR e.id=e.manager_id",
                "select e.id from emp e start with e.id = 2 connect by prior e.id = e.manager_id"));
        assertFalse(same("SELECT a.id FROM a,b WHERE a.id=b.id(+)", "SELECT a.id FROM a,b WHERE a.id=b.id"));
        assertTrue(same("SELECT a.id FROM a,b WHERE a.id=b.id(+)", "SELECT a.id FROM a , b WHERE a.id = b.id ( + )"));
        assertTrue(same("SELECT id FROM t ORDER BY id OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY",
                "SELECT id FROM t ORDER BY id OFFSET 3 ROWS FETCH NEXT 4 ROWS ONLY"));
        assertTrue(same("INSERT INTO t(name) VALUES(q'[O'Neil]') RETURNING id INTO :1",
                "INSERT INTO t (name) VALUES ('Alice') RETURNING id INTO :1"));
        assertFalse(same("SELECT a||b FROM t", "SELECT a+b FROM t"));
    }

    @Test
    void namedArgumentsAndPlsqlRangesKeepOperatorBoundaries() {
        assertEquals("begin for i in ? .. ? loop null; end loop; end",
                normalize("BEGIN FOR i IN 1..10 LOOP NULL; END LOOP; END;"));
        assertTrue(same("SELECT f(p=>1) FROM dual", "SELECT f (p => 2) FROM dual"));
        assertFalse(same("SELECT f(p=>1) FROM dual", "SELECT f(p= >1) FROM dual"));
        assertFalse(same("SELECT x^=1 FROM t", "SELECT x^ =1 FROM t"));
    }

    @Test
    void oracleRulesAreOptInAndWorkThroughAllPublicEntries() {
        String sql = "SELECT q'[a]' FROM dual WHERE id=:1";
        assertNotEquals(FingerprintUtils.normalize(sql), normalize(sql));
        assertEquals(FingerprintUtils.fingerprint(sql, ORACLE),
                FingerprintUtils.fingerprint("SELECT q'!b!' FROM dual WHERE id=:1", new FingerprintUtils.Options(ORACLE, true)));
        assertNotEquals(FingerprintUtils.fingerprint("SELECT :1 FROM dual", ORACLE),
                FingerprintUtils.fingerprint("SELECT :2 FROM dual", ORACLE));
        assertEquals(FingerprintUtils.normalize("SELECT 1f FROM dual"), "select 1f from dual");
    }

    private static String normalize(String sql) {
        return FingerprintUtils.normalize(sql, ORACLE);
    }

    private static boolean same(String left, String right) {
        return FingerprintUtils.isSameStructure(left, right, ORACLE);
    }
}
