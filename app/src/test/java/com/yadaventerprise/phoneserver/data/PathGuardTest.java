package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * Pure-JVM tests for path/name sanitisation. These are the first line of
 * defence against path traversal in uploaded file names and folder paths.
 */
public class PathGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void normalizeRelative_handlesNullAndRootForms() {
        assertEquals("", PathGuard.normalizeRelative(null));
        assertEquals("", PathGuard.normalizeRelative(""));
        assertEquals("", PathGuard.normalizeRelative("/"));
        assertEquals("", PathGuard.normalizeRelative("///"));
        assertEquals("a/b", PathGuard.normalizeRelative("/a/b/"));
        assertEquals("a/b", PathGuard.normalizeRelative("a\\b"));
    }

    @Test
    public void normalizeRelative_rejectsTraversalAndEmptySegments() {
        assertNull(PathGuard.normalizeRelative(".."));
        assertNull(PathGuard.normalizeRelative("../etc"));
        assertNull(PathGuard.normalizeRelative("a/../../b"));
        assertNull(PathGuard.normalizeRelative("a/../b"));
        assertNull(PathGuard.normalizeRelative("a/./b"));
        assertNull(PathGuard.normalizeRelative("a//b"));
    }

    @Test
    public void isValidSegment_enforcesLengthCharactersAndReservedNames() {
        assertTrue(PathGuard.isValidSegment("holiday photo.jpg"));
        assertFalse(PathGuard.isValidSegment(null));
        assertFalse(PathGuard.isValidSegment(""));
        assertFalse(PathGuard.isValidSegment("."));
        assertFalse(PathGuard.isValidSegment(".."));
        assertFalse(PathGuard.isValidSegment("a/b"));
        assertFalse(PathGuard.isValidSegment("a\\b"));
        assertFalse(PathGuard.isValidSegment("a:b"));
        assertFalse(PathGuard.isValidSegment("line\nbreak"));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 256; i++) longName.append('a');
        assertFalse(PathGuard.isValidSegment(longName.toString()));
    }

    @Test
    public void sanitizeFileName_stripsDirectoriesAndReservedCharacters() {
        assertEquals("passwd", PathGuard.sanitizeFileName("../../etc/passwd"));
        assertEquals("passwd", PathGuard.sanitizeFileName("C:\\windows\\passwd"));
        assertEquals("a_b_c_.txt", PathGuard.sanitizeFileName("a:b*c?.txt"));
        assertEquals("file", PathGuard.sanitizeFileName(null));
        assertEquals("file", PathGuard.sanitizeFileName("   "));
        assertEquals("file", PathGuard.sanitizeFileName(".."));
        assertEquals("file", PathGuard.sanitizeFileName("/"));
    }

    @Test
    public void isHidden_matchesDotFiles() {
        assertTrue(PathGuard.isHidden(".git"));
        assertTrue(PathGuard.isHidden(".nomedia"));
        assertFalse(PathGuard.isHidden("readme.txt"));
        assertFalse(PathGuard.isHidden(null));
    }

    @Test
    public void safeResolve_keepsPathsInsideRoot() throws Exception {
        File root = tmp.newFolder("root");
        File inside = PathGuard.safeResolve(root, "sub/file.txt");
        assertNotNull(inside);
        assertTrue(PathGuard.isInside(root, inside));

        assertNull(PathGuard.safeResolve(root, "../escape.txt"));
        assertNull(PathGuard.safeResolve(root, "sub/../../escape.txt"));
        assertNull(PathGuard.safeResolve(null, "anything"));
    }

    @Test
    public void isInside_detectsContainment() throws Exception {
        File root = tmp.newFolder("root");
        File child = new File(root, "a/b.txt");
        assertTrue(PathGuard.isInside(root, child));
        assertTrue(PathGuard.isInside(root, root));
        assertFalse(PathGuard.isInside(root, tmp.getRoot()));
        assertFalse(PathGuard.isInside(null, child));
    }
}
