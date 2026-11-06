package org.u80dc.jsqlint.commons.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.MYSQL;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.POSTGRESQL;

/** 指纹不能吞掉结构，常量值和格式差异则应归入同一模板。 @author u80dc */
@Isolated("Locale 回归会临时修改 JVM 默认区域设置")
class FingerprintUtilsTest {
    @Test
    void commentsInsideStringsCannotEraseFollowingSql() {
        assertFalse(same("SELECT '--a' FROM users WHERE id=1", "SELECT '--b' FROM orders WHERE id=2"));
        assertTrue(same("SELECT '--a /* x */' FROM users WHERE id=1", "SELECT 'b' FROM users WHERE id=2"));
        assertTrue(same("SELECT 'a''--b' FROM t", "SELECT 'x' FROM t"));
        assertTrue(same("SELECT /* ' -- */ id FROM t -- tail\n WHERE id=1", "SELECT id FROM t WHERE id=2"));
    }

    @Test
    void quotedIdentifiersKeepTheirNamesCaseAndContents() {
        assertFalse(same("SELECT \"id\" FROM users", "SELECT \"name\" FROM users"));
        assertFalse(same("SELECT \"ID\" FROM users", "SELECT \"id\" FROM users"));
        assertFalse(same("SELECT \"a--b\" FROM t", "SELECT \"a/*b*/\" FROM t"));
        assertTrue(FingerprintUtils.normalize("SELECT \"a  b\", \"a\"\"b\" FROM t").contains("\"a  b\""));
    }

    @Test
    void unaryNumbersDoNotEraseBinaryOperators() {
        assertFalse(same("SELECT price +1 FROM t", "SELECT price -1 FROM t"));
        assertFalse(same("SELECT 1+2", "SELECT 1-2"));
        assertTrue(same("SELECT price+1 FROM t", "SELECT price + 99 FROM t"));
        assertTrue(same("SELECT -1, +2 FROM t WHERE price=-3", "SELECT 2, -4 FROM t WHERE price=5"));
        assertTrue(same("SELECT price * -1 FROM t", "SELECT price*2 FROM t"));
    }

    @Test
    void punctuationWhitespaceAndBooleanValuesAreNormalized() {
        assertTrue(same("SELECT a,b FROM t WHERE id=1", " select a , b from t where id = 2; "));
        assertTrue(same("SELECT f(a,b), t.id FROM t", "SELECT f ( a , b ) , t . id FROM t"));
        assertTrue(same("SELECT * FROM t WHERE active=TRUE", "SELECT * FROM t WHERE active=false"));
        assertFalse(same("SELECT a,b FROM t", "SELECT ab FROM t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {".5", "5.", "0.5", "1e-3", "2.5E+8", "0xABCD", "0Xabcd"})
    void wholeNumericLiteralsAreReplaced(String number) {
        assertTrue(same("SELECT * FROM t WHERE amount="+number, "SELECT * FROM t WHERE amount=42"));
    }

    @Test
    void digitsInIdentifiersAreRetained() {
        assertFalse(same("SELECT col1 FROM table1", "SELECT col2 FROM table2"));
        assertFalse(same("SELECT 用户1 FROM 表1", "SELECT 用户2 FROM 表2"));
    }

    @Test
    void defaultLocaleDoesNotChangeFingerprint() {
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String expected=FingerprintUtils.fingerprint("SELECT ID FROM ITEMS WHERE ID=1");
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(expected,FingerprintUtils.fingerprint("SELECT ID FROM ITEMS WHERE ID=2"));
            assertTrue(same("SELECT ID FROM ITEMS", "select id from items"));
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void longQuotedValuesDoNotUseTheCallStack() {
        assertTrue(same("SELECT '"+"x".repeat(100_000)+"' FROM t", "SELECT 'short' FROM t"));
        assertTrue(same("SELECT '"+"a''b".repeat(30_000)+"' FROM t", "SELECT 'short' FROM t"));
    }

    @Test
    void onlyFlatLiteralInListsAreCollapsed() {
        assertTrue(same("SELECT * FROM t WHERE id IN (1,2,3)", "SELECT * FROM t WHERE id in (9)"));
        assertFalse(same("SELECT * FROM t WHERE id IN (a,b)", "SELECT * FROM t WHERE id IN (a)"));
        assertFalse(same("SELECT * FROM t WHERE id IN (SELECT id FROM a)", "SELECT * FROM t WHERE id IN (SELECT id FROM b)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 'unterminated", "SELECT \"unterminated", "SELECT 1 /* unterminated"})
    void unterminatedTokensDoNotProduceFingerprints(String sql) {
        assertThrows(IllegalArgumentException.class,()->FingerprintUtils.fingerprint(sql));
    }

    @Test
    void hashAndEmptyInputContractsRemainExplicit() {
        assertEquals("",FingerprintUtils.normalize(null));
        assertEquals("",FingerprintUtils.fingerprint(" /* no SQL */ "));
        assertEquals("",FingerprintUtils.fingerprint(" \n "));
        assertTrue(same(null,""));
        assertTrue(FingerprintUtils.fingerprint("SELECT 1").matches("[0-9a-f]{64}"));
        assertEquals(FingerprintUtils.fingerprint("SELECT 1"),FingerprintUtils.fingerprint("SELECT 2"));
    }

    @Test
    void inListCardinalityCanBeRetained() {
        var options = FingerprintUtils.Options.defaults().withCollapseInLists(false);
        String one = "SELECT * FROM t WHERE id IN (1)";
        String many = "SELECT * FROM t WHERE id IN (-1,+2,3)";
        assertTrue(same(one, many));
        assertFalse(FingerprintUtils.isSameStructure(one, many, options));
        assertNotEquals(FingerprintUtils.fingerprint(one, options), FingerprintUtils.fingerprint(many, options));
        assertEquals("select * from t where id in (?, ?, ?)", FingerprintUtils.normalize(many, options));
        assertFalse(same("SELECT * FROM t WHERE id IN (1+2)", one));
        assertFalse(same("SELECT * FROM t WHERE (a,b) IN ((1,2),(3,4))",
                "SELECT * FROM t WHERE (a,b) IN ((1,2))"));
    }

    @Test
    void mysqlQuoteModesAreExplicit() {
        String id = "SELECT \"id\" FROM users";
        String name = "SELECT \"name\" FROM users";
        assertFalse(same(id, name));
        assertTrue(FingerprintUtils.isSameStructure(id, name, MYSQL));
        assertFalse(FingerprintUtils.isSameStructure(id, name,
                new FingerprintUtils.Options(MYSQL, true).withMysqlAnsiQuotes(true)));
        assertNotEquals(FingerprintUtils.fingerprint(id), FingerprintUtils.fingerprint(id, MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT `id` FROM users", "SELECT `name` FROM users", MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT `ID` FROM users", "SELECT `id` FROM users", MYSQL));
        assertTrue(FingerprintUtils.normalize("SELECT `a  --``b` FROM t", MYSQL).contains("`a  --``b`"));
    }

    @Test
    void mysqlDoesNotAssumeCaseInsensitiveTableNames() {
        assertFalse(FingerprintUtils.isSameStructure("SELECT * FROM Users", "SELECT * FROM users", MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT * FROM VALUE", "SELECT * FROM value", MYSQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT * FROM Users WHERE id=1",
                "select * from Users where id = 9", MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT a.TRUE FROM t a", "SELECT a.FALSE FROM t a", MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT db.SELECT.id", "SELECT db.select.id", MYSQL));
    }

    @Test
    void mysqlBackslashEscapesCanBeDisabled() {
        var defaults = new FingerprintUtils.Options(MYSQL, true);
        String escaped = "SELECT 'a\\'--b' FROM users";
        assertTrue(FingerprintUtils.isSameStructure(escaped, "SELECT 'x' FROM users", defaults));
        // 同一文本在 NO_BACKSLASH_ESCAPES 下不再是一个完整字符串。
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintUtils.normalize(escaped, defaults.withMysqlBackslashEscapes(false)));
        assertTrue(FingerprintUtils.isSameStructure("SELECT 'path\\' FROM users", "SELECT 'x' FROM users",
                defaults.withMysqlBackslashEscapes(false)));
        assertTrue(FingerprintUtils.isSameStructure("SELECT 'a''b' FROM users", "SELECT 'x' FROM users",
                defaults.withMysqlBackslashEscapes(false)));
    }

    @Test
    void mysqlCommentsFollowMysqlRules() {
        assertTrue(FingerprintUtils.isSameStructure("SELECT 1 # tail\n FROM t", "SELECT 2 FROM t", MYSQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT 1 -- tail\n FROM t", "SELECT 2 FROM t", MYSQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT price--1 FROM t", "SELECT price - 2 FROM t", MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT price--1 FROM t", "SELECT price + 2 FROM t", MYSQL));
        assertEquals("select ?", FingerprintUtils.normalize("SELECT 1--comment", POSTGRESQL));
    }

    @Test
    void executableSqlIsIncludedAndHintsAreIgnored() {
        String sql = "SELECT /*! STRAIGHT_JOIN */ id FROM t";
        assertFalse(FingerprintUtils.isSameStructure(sql, "SELECT id FROM t", MYSQL));
        assertEquals("select straight_join id from t", FingerprintUtils.normalize(sql, MYSQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT /*+ NO_INDEX(t) */ id FROM t",
                "SELECT /*+ INDEX(t) */ id FROM t", MYSQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT /* ordinary */ id FROM t", "SELECT id FROM t", MYSQL));
    }

    @Test
    void postgresDollarStringsEscapeStringsAndNestedCommentsAreAtomic() {
        assertTrue(FingerprintUtils.isSameStructure("SELECT $$a'-- /* x */$$ FROM t", "SELECT 'b' FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT $tag$a $$ b$tag$ FROM t", "SELECT 'b' FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT E'a\\'--b' FROM t", "SELECT 'b' FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT /* outer /* inner */ tail */ id FROM t",
                "SELECT id FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT 'path\\' FROM t", "SELECT 'b' FROM t", POSTGRESQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT U&\"id\" FROM t", "SELECT U&\"name\" FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT N'a', B'01', X'AB'", "SELECT N'b', B'10', X'CD'", POSTGRESQL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT $$not closed", "SELECT $tag$x$TAG$", "SELECT E'a\\'",
            "SELECT 1 /* outer /* inner */"})
    void unterminatedPostgresTokensAreRejected(String sql) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FingerprintUtils.normalize(sql, POSTGRESQL));
        assertTrue(error.getMessage().contains("position"));
    }

    @Test
    void postgresOperatorsCastsAndJdbcPlaceholdersKeepTheirBoundaries() {
        assertTrue(FingerprintUtils.isSameStructure("SELECT * FROM t WHERE id=?", "SELECT * FROM t WHERE id = ?", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT id::int FROM t WHERE id=-1",
                "SELECT id :: int FROM t WHERE id = 9", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT data->>'name' FROM t", "SELECT data ->> 'id' FROM t", POSTGRESQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT data->>'name' FROM t", "SELECT data->'name' FROM t", POSTGRESQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT data ?| keys FROM t", "SELECT data ?& keys FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.isSameStructure("SELECT data #>> '{a}' FROM t", "SELECT data #>> '{b}' FROM t", POSTGRESQL));
        assertTrue(FingerprintUtils.normalize("SELECT data @? '$.a' FROM t", POSTGRESQL).contains("@?"));
        assertTrue(FingerprintUtils.normalize("SELECT data ?? 'a' FROM t", POSTGRESQL).contains("??"));
        assertFalse(FingerprintUtils.isSameStructure("SELECT id<=2 FROM t", "SELECT id< =2 FROM t", POSTGRESQL));
    }

    @Test
    void namedAndPositionalBindingsKeepTheirIdentity() {
        assertFalse(FingerprintUtils.isSameStructure("SELECT $1 + $1", "SELECT $1 + $2", POSTGRESQL));
        assertTrue(FingerprintUtils.normalize("SELECT $1::int", POSTGRESQL).contains("$1 :: int"));
        assertFalse(same("SELECT :id + :id", "SELECT :id + :other"));
        assertTrue(FingerprintUtils.normalize("SELECT * FROM t WHERE id IN (:a,:b)").contains(":a, :b"));
    }

    @Test
    void qualifiedWordsAndNonLiteralOperandsRetainBinarySigns() {
        assertFalse(same("SELECT t.limit -1 FROM t", "SELECT t.limit +1 FROM t"));
        assertFalse(same("SELECT t.true FROM t", "SELECT t.false FROM t"));
        assertFalse(FingerprintUtils.isSameStructure("SELECT offset -1 FROM t", "SELECT offset +1 FROM t", MYSQL));
        assertFalse(same("SELECT -price FROM t", "SELECT price FROM t"));
        assertFalse(same("SELECT f(price)-1 FROM t", "SELECT f(price)+1 FROM t"));
        assertFalse(same("SELECT (price)-1 FROM t", "SELECT (price)+1 FROM t"));
        assertFalse(same("SELECT t.in(1,2)", "SELECT t.in(1)"));
        assertTrue(same("SELECT CASE WHEN x>-1 THEN -2 ELSE +3 END FROM t",
                "SELECT CASE WHEN x>5 THEN 6 ELSE 7 END FROM t"));
        assertTrue(same("SELECT * FROM t WHERE id BETWEEN -1 AND +2",
                "SELECT * FROM t WHERE id BETWEEN 3 AND 4"));
    }

    @Test
    void nullPredicatesAndMultipleStatementsRemainStructural() {
        assertFalse(same("SELECT * FROM t WHERE a IS NULL", "SELECT * FROM t WHERE a IS TRUE"));
        assertFalse(same("SELECT * FROM t WHERE a IS NULL", "SELECT * FROM t WHERE a IS NOT NULL"));
        assertTrue(same("SELECT 1; SELECT 2;", "SELECT 3;SELECT 4"));
        assertFalse(same("SELECT 1; SELECT 2", "SELECT 3"));
    }

    @Test
    void longNonLiteralSignChainsDoNotRequireRepeatedSuffixScanning() {
        String normalized = FingerprintUtils.normalize("SELECT " + "+ ".repeat(20_000) + "price FROM t");
        assertTrue(normalized.endsWith("price from t"));
        assertEquals(20_000, normalized.chars().filter(c -> c == '+').count());
    }

    @Test
    void mysqlDigitStartingIdentifiersAreNotPartiallyMasked() {
        assertFalse(FingerprintUtils.isSameStructure("SELECT 123col FROM t", "SELECT 456col FROM t", MYSQL));
        assertFalse(FingerprintUtils.isSameStructure("SELECT col$1 FROM t", "SELECT col$2 FROM t", MYSQL));
        assertThrows(IllegalArgumentException.class, () -> FingerprintUtils.normalize("SELECT `missing", MYSQL));
    }

    @Test
    void optionsAndCallsDoNotShareMutableState() {
        var defaults = FingerprintUtils.Options.defaults();
        var preserveLength = defaults.withCollapseInLists(false);
        assertTrue(defaults.collapseInLists());
        assertFalse(preserveLength.collapseInLists());
        String sql = "SELECT \"Name\" FROM t WHERE id IN (1,2)";
        String expected = FingerprintUtils.normalize(sql);
        FingerprintUtils.normalize(sql, MYSQL);
        FingerprintUtils.normalize(sql, preserveLength);
        assertEquals(expected, FingerprintUtils.normalize(sql));
        assertThrows(NullPointerException.class, () -> new FingerprintUtils.Options(null, true));
        assertThrows(NullPointerException.class, () -> FingerprintUtils.normalize("", (FingerprintUtils.Options) null));
    }

    private static boolean same(String left, String right) {
        return FingerprintUtils.isSameStructure(left, right);
    }
}
