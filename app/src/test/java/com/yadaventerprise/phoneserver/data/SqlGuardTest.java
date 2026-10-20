package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the static SQL analysis that runs before any statement reaches
 * SQLite: statement splitting, write controls, and blocked dangerous commands.
 */
public class SqlGuardTest {

    @Test
    public void readStatementsAreAllowed() {
        assertOk("SELECT * FROM users");
        assertOk("  select 1  ");
        assertOk("WITH x AS (SELECT 1) SELECT * FROM x");
        assertOk("PRAGMA table_info(users)");
        assertOk("EXPLAIN SELECT 1");
    }

    @Test
    public void writeStatementsRespectReadOnlyCredential() {
        assertEquals(SqlGuard.Verdict.OK, SqlGuard.inspect("INSERT INTO t VALUES(1)", true).verdict);
        assertEquals(SqlGuard.Verdict.WRITE_DISALLOWED,
                SqlGuard.inspect("INSERT INTO t VALUES(1)", false).verdict);
        assertEquals(SqlGuard.Verdict.WRITE_DISALLOWED,
                SqlGuard.inspect("DELETE FROM t", false).verdict);
        assertEquals(SqlGuard.Verdict.WRITE_DISALLOWED,
                SqlGuard.inspect("DROP TABLE t", false).verdict);
    }

    @Test
    public void multipleStatementsAreRejected() {
        SqlGuard.Result result = SqlGuard.inspect("SELECT 1; SELECT 2");
        assertEquals(SqlGuard.Verdict.MULTIPLE_STATEMENTS, result.verdict);
        assertEquals(";", result.keyword);
    }

    @Test
    public void semicolonInsideLiteralIsNotASeparator() {
        assertOk("SELECT ';' AS a");
        assertOk("SELECT * FROM t WHERE name = 'drop;table'");
        assertOk("SELECT 1;");
    }

    @Test
    public void commentsAreIgnoredDuringSplitting() {
        assertOk("SELECT 1 -- ; DROP TABLE t");
        assertOk("SELECT 1 /* ; DELETE FROM t */");
    }

    @Test
    public void dangerousStatementsAreForbidden() {
        assertForbidden("ATTACH DATABASE 'x.db' AS other");
        assertForbidden("DETACH DATABASE other");
        assertForbidden("SELECT load_extension('lib.so')");
        assertForbidden("PRAGMA writable_schema = 1");
        assertForbidden("BEGIN TRANSACTION");
        assertForbidden("COMMIT");
        assertForbidden("ROLLBACK");
    }

    @Test
    public void unsupportedStatementIsForbidden() {
        assertForbidden("VACUUM");
        assertForbidden("REINDEX");
    }

    @Test
    public void emptyInputIsReportedAsEmpty() {
        assertEquals(SqlGuard.Verdict.EMPTY, SqlGuard.inspect(null).verdict);
        assertEquals(SqlGuard.Verdict.EMPTY, SqlGuard.inspect("   ").verdict);
        assertEquals(SqlGuard.Verdict.EMPTY, SqlGuard.inspect(";;;").verdict);
    }

    @Test
    public void boundParameterNamesDoNotLookLikeKeywords() {
        assertOk("SELECT * FROM t WHERE name = :name");
        assertOk("SELECT * FROM t WHERE name = @name AND id = ?");
    }

    @Test
    public void isReadOnlyKeywordClassifiesKeywords() {
        assertTrue(SqlGuard.isReadOnlyKeyword("select"));
        assertTrue(SqlGuard.isReadOnlyKeyword("WITH"));
        assertFalse(SqlGuard.isReadOnlyKeyword("insert"));
        assertFalse(SqlGuard.isReadOnlyKeyword(null));
    }

    private static void assertOk(String sql) {
        SqlGuard.Result result = SqlGuard.inspect(sql);
        assertEquals("expected OK for: " + sql + " (" + result.reason + ")",
                SqlGuard.Verdict.OK, result.verdict);
    }

    private static void assertForbidden(String sql) {
        assertEquals("expected FORBIDDEN for: " + sql,
                SqlGuard.Verdict.FORBIDDEN, SqlGuard.inspect(sql).verdict);
    }
}
