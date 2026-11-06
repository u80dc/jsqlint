package org.u80dc.jsqlint.commons.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.*;

/** 按公开契约验证格式、条件值、关键字大小写和普通注释均不影响指纹。 @author u80dc */
class FingerprintUtilsContractTest {
    @ParameterizedTest
    @EnumSource(FingerprintUtils.Dialect.class)
    void formattingOutsideTokensDoesNotChangeFingerprint(FingerprintUtils.Dialect dialect) {
        String sql = "SELECT u.id,u.name FROM users u WHERE (u.id>=1 AND u.name='a') OR u.id IN(2,3)";
        String formatted = "\r\n SELECT\t u . id ,\n u . name\nFROM\tusers u\n"
                + "WHERE ( u . id >= 1\n AND u . name = 'a' )\r\nOR u.id IN ( 2 , 3 ) ; \n";
        assertSameFingerprint(sql, formatted, dialect);
        assertSameFingerprint("SELECT id FROM users", "SELECT\u00a0id\u3000FROM users", dialect);
    }

    @ParameterizedTest
    @EnumSource(FingerprintUtils.Dialect.class)
    void changingOnlyConditionValuesDoesNotChangeFingerprint(FingerprintUtils.Dialect dialect) {
        assertSameFingerprint(
                "SELECT id FROM users WHERE age>=18 AND name='Alice' AND active=TRUE AND score BETWEEN -1 AND 2",
                "SELECT id FROM users WHERE age>=65 AND name='O''Neil' AND active=FALSE AND score BETWEEN .5 AND 1e3",
                dialect);
        assertSameFingerprint("SELECT id FROM users WHERE score=NULL", "SELECT id FROM users WHERE score=42", dialect);
        assertSameFingerprint("SELECT id FROM users WHERE id IN(NULL,1,2)", "SELECT id FROM users WHERE id IN(3)", dialect);
        assertSameFingerprint("SELECT id FROM users WHERE name LIKE 'A%'", "SELECT id FROM users WHERE name LIKE '%Z'", dialect);
    }

    @ParameterizedTest
    @EnumSource(FingerprintUtils.Dialect.class)
    void keywordsAreCaseInsensitive(FingerprintUtils.Dialect dialect) {
        assertSameFingerprint(
                "SELECT CASE WHEN age>18 THEN 'adult' ELSE 'child' END AS category FROM users WHERE id=1 ORDER BY age DESC",
                "sElEcT case when age>18 then 'adult' else 'child' end as category from users wHeRe id=1 order by age desc",
                dialect);
    }

    @ParameterizedTest
    @EnumSource(FingerprintUtils.Dialect.class)
    void ordinaryCommentsAndHintsAreIgnored(FingerprintUtils.Dialect dialect) {
        String sql = "SELECT id FROM users WHERE age>=18";
        assertSameFingerprint(sql, "/* header ' */ SELECT /* comment */ id -- line\nFROM users WHERE age>=18 /* tail */", dialect);
        assertSameFingerprint(sql, "SELECT /*+ INDEX(users idx) */ id FROM users WHERE age>=18", dialect);
        String executable = "SELECT /*! STRAIGHT_JOIN */ id FROM users WHERE age>=18";
        if (dialect == MYSQL) {
            assertSameFingerprint("SELECT STRAIGHT_JOIN id FROM users WHERE age>=18", executable, dialect);
            assertDifferentFingerprint(sql, executable, dialect);
        } else {
            assertSameFingerprint(sql, executable, dialect);
        }
        // MySQL 要求 -- 后紧跟空白，--+ 在该方言中是运算符序列，不是注释。
        String linePrefix = dialect == MYSQL ? "-- +" : "--+";
        assertSameFingerprint(sql, "SELECT " + linePrefix + " FULL(users)\n id FROM users WHERE age>=18", dialect);
        assertSameFingerprint("SELECT id FROM users WHERE age=-1", "SELECT id FROM users WHERE age=-/* note */1", dialect);
        assertEquals("", FingerprintUtils.fingerprint("/*+ comment */ /* ordinary */ " + linePrefix + " hint", dialect));
    }

    @ParameterizedTest
    @EnumSource(FingerprintUtils.Dialect.class)
    void combinedChangesStillProduceTheSameFingerprint(FingerprintUtils.Dialect dialect) {
        String sql = "SELECT id,name FROM users WHERE age>=18 AND name='Alice'";
        String changed = "/* header */ select\n id , name\tFROM users\n"
                + "wHeRe age >= 99 -- different age\n AND name = 'Bob'; /* end */";
        assertSameFingerprint(sql, changed, dialect);
    }

    @ParameterizedTest
    @EnumSource(FingerprintUtils.Dialect.class)
    void fieldsOperatorsAndQuotedIdentifiersRemainSignificant(FingerprintUtils.Dialect dialect) {
        assertDifferentFingerprint("SELECT id FROM users WHERE age=1", "SELECT id FROM users WHERE score=1", dialect);
        assertDifferentFingerprint("SELECT id FROM users WHERE age=1", "SELECT id FROM users WHERE age>1", dialect);
        assertDifferentFingerprint("SELECT id FROM users WHERE a=1 AND b=2", "SELECT id FROM users WHERE a=1 OR b=2", dialect);
        assertDifferentFingerprint("SELECT id FROM users", "SELECT id FROM orders", dialect);
        if (dialect != MYSQL) {
            assertDifferentFingerprint("SELECT \"a--b\" FROM users", "SELECT \"a/*b*/\" FROM users", dialect);
            assertDifferentFingerprint("SELECT \"a b\" FROM users", "SELECT \"a  b\" FROM users", dialect);
        }
        assertDifferentFingerprint("SELECT id FROM users WHERE age IS NULL", "SELECT id FROM users WHERE age IS NOT NULL", dialect);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT count(id) AS total FROM Users LIMIT 10 offset 2",
            "SELECT sum(age), avg(age), min(age), max(age) FROM Users",
            "SELECT CASE WHEN age>18 THEN 1 ELSE 2 end FROM Users",
            "INSERT INTO Users(id) value(1) ON duplicate KEY UPDATE id=2",
            "SELECT id FROM Users WHERE name LIKE 'A%' escape '!'",
            "SELECT id FROM Users ORDER BY id LIMIT 1 FOR UPDATE skip locked",
            "SELECT id FROM Users ORDER BY id LIMIT 1 FOR UPDATE nowait",
            "SELECT sum(age) OVER (ORDER BY id ROWS BETWEEN unbounded preceding AND current ROW) FROM Users"
    })
    void mysqlNonReservedKeywordsAndBuiltinsIgnoreCase(String sql) {
        // 只改变关键字/函数的大小写，保留可能区分大小写的 Users 表名与字段名。
        String changed = sql.replace("count", "COUNT").replace("sum", "SUM").replace("avg", "AVG")
                .replace("min", "MIN").replace("max", "MAX").replace("offset", "OFFSET")
                .replace("end", "END").replace("value(", "VALUE(").replace("duplicate", "DUPLICATE")
                .replace("escape", "ESCAPE").replace("skip locked", "SKIP LOCKED").replace("nowait", "NOWAIT")
                .replace("unbounded preceding", "UNBOUNDED PRECEDING").replace("current", "CURRENT");
        assertSameFingerprint(sql, changed, MYSQL);
    }

    @Test
    void mysqlKeywordLikeTableAndColumnNamesAreNotBlindlyLowercased() {
        for (String name : new String[]{"Users", "VALUE", "OFFSET", "END", "COUNT"}) {
            assertDifferentFingerprint("SELECT id FROM " + name, "SELECT id FROM " + name.toLowerCase(java.util.Locale.ROOT), MYSQL);
        }
        assertDifferentFingerprint("SELECT db.COUNT(id) FROM t", "SELECT db.count(id) FROM t", MYSQL);
        assertDifferentFingerprint("INSERT INTO COUNT(id) VALUES(1)", "INSERT INTO count(id) VALUES(1)", MYSQL);
        assertSameFingerprint("SELECT `a -- b` FROM Users", "SELECT `a -- b` /* ignored */ FROM Users", MYSQL);
        assertDifferentFingerprint("SELECT `a -- b` FROM Users", "SELECT `a b` FROM Users", MYSQL);
    }

    @Test
    void mysqlExpressionAndTypeKeywordsIgnoreCase() {
        assertSameFingerprint("SELECT DISTINCTROW id FROM Users", "select distinctrow id from Users", MYSQL);
        assertSameFingerprint("SELECT HIGH_PRIORITY SQL_SMALL_RESULT id FROM Users",
                "select high_priority sql_small_result id from Users", MYSQL);
        assertSameFingerprint("SELECT age DIV 2, age MOD 3 FROM Users", "select age div 2, age mod 3 from Users", MYSQL);
        assertSameFingerprint("SELECT CAST(age AS SIGNED), CAST(age AS CHAR) FROM Users",
                "select cast(age as signed), cast(age as char) from Users", MYSQL);
        assertSameFingerprint("SELECT id FROM Users WHERE created_at>TIMESTAMP '2025-01-01 00:00:00'",
                "select id from Users where created_at>timestamp '2026-01-01 00:00:00'", MYSQL);
        assertSameFingerprint("SELECT id FROM Users WHERE created_at>NOW()-INTERVAL 1 DAY",
                "select id from Users where created_at>now()-interval 2 day", MYSQL);
        assertSameFingerprint("SELECT id FROM Users WHERE created_at>NOW()+INTERVAL -1 DAY",
                "select id from Users where created_at>now()+interval 2 day", MYSQL);
        assertSameFingerprint("SELECT CAST(created_at AS DATETIME(6)) FROM Users",
                "select cast(created_at as datetime(6)) from Users", MYSQL);
        assertSameFingerprint("SELECT SUM(age) OVER(ORDER BY created_at RANGE INTERVAL 1 DAY PRECEDING) FROM Users",
                "select sum(age) over(order by created_at range interval 2 day preceding) from Users", MYSQL);
    }

    @Test
    void nullValuesAreMaskedButNullPredicatesAndStatementsRemainStructural() {
        assertSameFingerprint("SELECT id FROM t WHERE score=NULL", "SELECT id FROM t WHERE score='value'", ANSI);
        assertSameFingerprint("SELECT COALESCE(NULL,1) FROM t", "SELECT COALESCE(2,3) FROM t", ANSI);
        assertDifferentFingerprint("SELECT id FROM t WHERE score IS NULL", "SELECT id FROM t WHERE score IS TRUE", ANSI);
        assertEquals("begin null; end", FingerprintUtils.normalize("BEGIN NULL; END;", ORACLE));
        assertEquals("begin if ? = ? then null; else null; end if; end",
                FingerprintUtils.normalize("BEGIN IF 1=1 THEN NULL; ELSE NULL; END IF; END;", ORACLE));
    }

    @Test
    void commentsFollowTheirDialectAndNeverStripQuotedContents() {
        assertSameFingerprint("SELECT id FROM Users", "SELECT # a\n id FROM Users # b", MYSQL);
        assertSameFingerprint("SELECT id FROM t", "SELECT /* outer /* nested */ tail */ id FROM t", POSTGRESQL);
        assertSameFingerprint("SELECT q'[a'--/*x*/]' FROM t", "SELECT 'value' FROM t", ORACLE);
        assertSameFingerprint("SELECT $$a'--/*x*/$$ FROM t", "SELECT 'value' FROM t", POSTGRESQL);
        assertDifferentFingerprint("SELECT '--x' FROM users", "SELECT '--y' FROM orders", ANSI);
        assertThrows(IllegalArgumentException.class, () -> FingerprintUtils.fingerprint("SELECT 1 /* unterminated"));
    }

    private static void assertSameFingerprint(String left, String right, FingerprintUtils.Dialect dialect) {
        assertEquals(FingerprintUtils.fingerprint(left, dialect), FingerprintUtils.fingerprint(right, dialect),
                () -> FingerprintUtils.normalize(left, dialect) + " != " + FingerprintUtils.normalize(right, dialect));
    }

    private static void assertDifferentFingerprint(String left, String right, FingerprintUtils.Dialect dialect) {
        assertNotEquals(FingerprintUtils.fingerprint(left, dialect), FingerprintUtils.fingerprint(right, dialect));
    }
}
