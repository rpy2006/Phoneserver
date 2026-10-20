package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ProjectStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
    }

    @Test
    public void createGeneratesIdAndIsListed() throws Exception {
        Records.ProjectRecord project = ProjectStore.create(context, "Alpha", "demo", 1024, 0);

        assertNotNull(project.id);
        assertEquals("Alpha", project.name);
        assertTrue(project.id.matches("[A-Za-z0-9_-]+"));

        List<Records.ProjectRecord> all = ProjectStore.list(context);
        assertEquals(1, all.size());
        assertEquals(project.id, all.get(0).id);
        assertEquals(project.id, ProjectStore.get(context, project.id).id);
        assertEquals(1, ProjectStore.globalStats(context).projectCount);
    }

    @Test
    public void duplicateNameIsRejected() throws Exception {
        ProjectStore.create(context, "Alpha", "", 0, 0);
        DataException error = assertThrows(DataException.class,
                () -> ProjectStore.create(context, "Alpha", "", 0, 0));
        assertEquals(409, error.httpStatus());
        assertEquals(1, ProjectStore.list(context).size());
    }

    @Test
    public void updateRenamesAndConflictsWithOtherNames() throws Exception {
        Records.ProjectRecord a = ProjectStore.create(context, "Alpha", "first", 0, 0);
        ProjectStore.create(context, "Beta", "", 0, 0);

        Records.ProjectRecord renamed = ProjectStore.update(context, a.id, "Alpha2", null, null, null);
        assertEquals("Alpha2", renamed.name);

        DataException conflict = assertThrows(DataException.class,
                () -> ProjectStore.update(context, a.id, "Beta", null, null, null));
        assertEquals(409, conflict.httpStatus());
    }

    @Test
    public void deleteRequiresExactConfirmationAndIsIsolated() throws Exception {
        Records.ProjectRecord a = ProjectStore.create(context, "Alpha", "", 0, 0);
        Records.ProjectRecord b = ProjectStore.create(context, "Beta", "", 0, 0);

        DataException wrong = assertThrows(DataException.class,
                () -> ProjectStore.delete(context, a.id, "wrong-name"));
        assertEquals(400, wrong.httpStatus());
        assertEquals(2, ProjectStore.list(context).size());

        ProjectStore.delete(context, a.id, "Alpha");
        List<Records.ProjectRecord> remaining = ProjectStore.list(context);
        assertEquals(1, remaining.size());
        assertEquals(b.id, remaining.get(0).id);
        assertNull(ProjectStore.get(context, a.id));
        assertNotNull(ProjectStore.get(context, b.id));
    }

    @Test
    public void invalidNamesAreRejected() {
        assertThrows(DataException.class, () -> ProjectStore.create(context, null, "", 0, 0));
        assertThrows(DataException.class, () -> ProjectStore.create(context, "   ", "", 0, 0));
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < ProjectStore.MAX_NAME_LENGTH + 1; i++) tooLong.append('x');
        assertThrows(DataException.class, () -> ProjectStore.create(context, tooLong.toString(), "", 0, 0));
        assertThrows(DataException.class, () -> ProjectStore.create(context, "bad\nname", "", 0, 0));
    }

    @Test
    public void projectDataSurvivesSimulatedRestart() throws Exception {
        Records.ProjectRecord project = ProjectStore.create(context, "Alpha", "persist", 0, 0);

        // Simulate an app restart: the helper singleton is torn down and rebuilt
        // against the same data root and system database.
        DataTestSupport.resetSystemDatabase();

        Records.ProjectRecord after = ProjectStore.get(context, project.id);
        assertNotNull(after);
        assertEquals("Alpha", after.name);
        assertEquals("persist", after.description);
        assertEquals(1, ProjectStore.list(context).size());
    }

    @Test
    public void globalAndPerProjectStatsTrackCountsAndBytes() throws Exception {
        Records.ProjectRecord a = ProjectStore.create(context, "Alpha", "", 0, 0);
        Records.ProjectRecord b = ProjectStore.create(context, "Beta", "", 0, 0);

        ProjectStore.GlobalStats global = ProjectStore.globalStats(context);
        assertEquals(2, global.projectCount);
        assertEquals(0, global.fileCount);

        ProjectStore.Stats statsA = ProjectStore.stats(context, a.id);
        ProjectStore.Stats statsB = ProjectStore.stats(context, b.id);
        assertEquals(0, statsA.fileCount);
        assertEquals(0, statsB.fileCount);
        assertNotEquals(a.id, b.id);
    }
}
