package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DatabaseManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;
    private String projectId;

    @Before
    public void setUp() throws Exception {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        projectId = ProjectStore.create(context, "Alpha", "", 0, 0).id;
    }

    @Test
    public void createDatabaseRegistersMetadataAndFile() throws Exception {
        Records.DbRecord record = DatabaseManager.create(context, projectId, "main");

        assertEquals("main", record.name);
        assertEquals("main.db", record.fileName);
        File file = DatabaseManager.databaseFile(context, projectId, "main");
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        List<Records.DbRecord> all = DatabaseManager.list(context, projectId);
        assertEquals(1, all.size());
        assertEquals("main", all.get(0).name);
    }

    @Test
    public void duplicateAndInvalidDatabaseNamesAreRejected() throws Exception {
        DatabaseManager.create(context, projectId, "main");

        DataException dup = assertThrows(DataException.class,
                () -> DatabaseManager.create(context, projectId, "main"));
        assertEquals(409, dup.httpStatus());

        assertThrows(DataException.class, () -> DatabaseManager.create(context, projectId, "../evil"));
        assertThrows(DataException.class, () -> DatabaseManager.create(context, projectId, "system"));
        assertThrows(DataException.class, () -> DatabaseManager.create(context, projectId, "a b"));
        assertThrows(DataException.class,
                () -> DatabaseManager.create(context, "nonexistent-project", "main"));
    }

    @Test
    public void crudRoundTripThroughParameterisedQueries() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)",
                null, null, null, null, true);

        for (int i = 1; i <= 3; i++) {
            DatabaseManager.execute(context, projectId, "main",
                    "INSERT INTO users (name, age) VALUES(?, ?)",
                    Arrays.asList("user" + i, 20 + i), null, null, null, true);
        }

        Records.QueryResult all = DatabaseManager.execute(context, projectId, "main",
                "SELECT name FROM users ORDER BY id", null, null, null, null, true);
        assertTrue(all.isSelect);
        assertEquals(3, all.rows.size());

        Records.QueryResult one = DatabaseManager.execute(context, projectId, "main",
                "SELECT age FROM users WHERE name = ?", Arrays.asList("user2"),
                null, null, null, true);
        assertEquals(1, one.rows.size());
        assertEquals(21L, one.rows.get(0).get(0));

        DatabaseManager.execute(context, projectId, "main",
                "UPDATE users SET age = 99 WHERE name = ?", Arrays.asList("user1"),
                null, null, null, true);
        Records.QueryResult updated = DatabaseManager.execute(context, projectId, "main",
                "SELECT age FROM users WHERE name = ?", Arrays.asList("user1"),
                null, null, null, true);
        assertEquals(99L, updated.rows.get(0).get(0));

        DatabaseManager.execute(context, projectId, "main",
                "DELETE FROM users WHERE name = ?", Arrays.asList("user3"),
                null, null, null, true);
        Records.QueryResult afterDelete = DatabaseManager.execute(context, projectId, "main",
                "SELECT COUNT(*) FROM users", null, null, null, null, true);
        assertEquals(2L, afterDelete.rows.get(0).get(0));
    }

    @Test
    public void invalidSqlAndReadonlyWritesAreRejectedWith4xx() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE t (id INTEGER PRIMARY KEY)", null, null, null, null, true);

        DataException syntax = assertThrows(DataException.class,
                () -> DatabaseManager.execute(context, projectId, "main",
                        "SELECT * FROM missing_table", null, null, null, null, true));
        assertEquals(400, syntax.httpStatus());

        DataException readonly = assertThrows(DataException.class,
                () -> DatabaseManager.execute(context, projectId, "main",
                        "INSERT INTO t (id) VALUES(1)", null, null, null, null, false));
        assertEquals(403, readonly.httpStatus());

        DataException injection = assertThrows(DataException.class,
                () -> DatabaseManager.execute(context, projectId, "main",
                        "DROP TABLE t; SELECT 1", null, null, null, null, true));
        assertEquals(403, injection.httpStatus());
    }

    @Test
    public void transactionRollsBackOnFailure() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)", null, null, null, null, true);

        List<String> statements = Arrays.asList(
                "INSERT INTO t (v) VALUES('a')",
                "INSERT INTO t (v) VALUES('b')",
                "INSERT INTO missing_table (v) VALUES('c')");
        List<List<Object>> params = new ArrayList<>();
        params.add(null);
        params.add(null);
        params.add(null);

        assertThrows(DataException.class, () -> DatabaseManager.executeTransaction(
                context, projectId, "main", statements, params, true));

        // The successful statements in the same transaction must not persist.
        Records.QueryResult result = DatabaseManager.execute(context, projectId, "main",
                "SELECT COUNT(*) FROM t", null, null, null, null, true);
        assertEquals(0L, result.rows.get(0).get(0));
    }

    @Test
    public void schemaInspectionListsTablesColumnsAndCounts() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)", null, null, null, null, true);
        DatabaseManager.execute(context, projectId, "main",
                "CREATE INDEX idx_users_name ON users(name)", null, null, null, null, true);
        DatabaseManager.execute(context, projectId, "main",
                "INSERT INTO users (name) VALUES('zoe')", null, null, null, null, true);

        List<Records.TableInfo> tables = DatabaseManager.listTables(context, projectId, "main");
        assertEquals(1, tables.size());
        assertEquals("users", tables.get(0).name);
        assertEquals(1, tables.get(0).rowCount);

        Records.TableInfo info = DatabaseManager.tableInfo(context, projectId, "main", "users");
        assertEquals(2, info.columns.size());
        assertEquals("id", info.columns.get(0).name);
        assertTrue(info.columns.get(0).primaryKey);
        assertFalse(info.indexes.isEmpty());

        assertThrows(DataException.class,
                () -> DatabaseManager.tableInfo(context, projectId, "main", "nope"));
    }

    @Test
    public void largeResultsArePaginatedAndTruncated() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE big (id INTEGER PRIMARY KEY)", null, null, null, null, true);
        for (int i = 0; i < 5; i++) {
            DatabaseManager.execute(context, projectId, "main",
                    "INSERT INTO big (id) VALUES(?)", Arrays.asList(i), null, null, null, true);
        }

        Records.QueryResult page = DatabaseManager.execute(context, projectId, "main",
                "SELECT id FROM big ORDER BY id", null, 3, 0, null, true);
        assertEquals(3, page.rows.size());
        assertTrue(page.truncated);

        Records.QueryResult offset = DatabaseManager.execute(context, projectId, "main",
                "SELECT id FROM big ORDER BY id", null, 3, 3, null, true);
        assertEquals(2, offset.rows.size());
        assertFalse(offset.truncated);
    }

    @Test
    public void databasesAreIsolatedBetweenProjects() throws Exception {
        String other = ProjectStore.create(context, "Beta", "", 0, 0).id;
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE secret (id INTEGER)", null, null, null, null, true);

        assertEquals(1, DatabaseManager.list(context, projectId).size());
        assertEquals(0, DatabaseManager.list(context, other).size());

        // The other project has no "main" database at all.
        assertThrows(DataException.class,
                () -> DatabaseManager.listTables(context, other, "main"));
    }

    @Test
    public void exportProducesAValidSqliteFileAndImportNeedsConfirmation() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        DatabaseManager.execute(context, projectId, "main",
                "CREATE TABLE t (id INTEGER PRIMARY KEY)", null, null, null, null, true);

        File dest = new File(tmp.getRoot(), "exported.db");
        DatabaseManager.exportTo(context, projectId, "main", dest);
        assertTrue(dest.exists());
        assertTrue(dest.length() > 0);
        DatabaseManager.verifySqliteFile(dest);

        DataException noConfirm = assertThrows(DataException.class,
                () -> DatabaseManager.importFrom(context, projectId, "main", dest, false));
        assertEquals(400, noConfirm.httpStatus());

        DataException notSqlite = assertThrows(DataException.class,
                () -> DatabaseManager.importFrom(context, projectId, "main",
                        tmp.newFile("garbage.db"), true));
        assertEquals(400, notSqlite.httpStatus());
    }

    @Test
    public void deletingDatabaseRemovesFileAndMetadata() throws Exception {
        DatabaseManager.create(context, projectId, "main");
        File file = DatabaseManager.databaseFile(context, projectId, "main");
        assertTrue(file.exists());

        DatabaseManager.delete(context, projectId, "main");
        assertFalse(file.exists());
        assertEquals(0, DatabaseManager.list(context, projectId).size());
        assertThrows(DataException.class,
                () -> DatabaseManager.require(context, projectId, "main"));
    }

    @Test
    public void databaseFileRejectsTraversalNames() {
        assertThrows(DataException.class,
                () -> DatabaseManager.databaseFile(context, projectId, "../../etc/passwd"));
        assertThrows(DataException.class,
                () -> DatabaseManager.databaseFile(context, projectId, "a/b"));
        assertNotNull(projectId);
    }
}
