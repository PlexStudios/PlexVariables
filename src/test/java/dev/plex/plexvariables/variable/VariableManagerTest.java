package dev.plex.plexvariables.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariableManagerTest {
    @TempDir Path temporaryDirectory;

    private final VariableManager manager = new VariableManager(Logger.getLogger("variable-test"));

    @Test
    void laterSortedValidDefinitionWinsCaseInsensitiveCollision() throws IOException {
        write("z.yml", "variables:\n  GREETING: later\n");
        write("nested/a.yml", "variables:\n  greeting: first\n  count: 42\n  active: true\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals(2, result.filesLoaded());
        assertEquals(0, result.skippedFiles());
        assertEquals("later", result.variables().get("greeting").value());
        assertEquals("z.yml", result.variables().get("greeting").sourceFile());
        assertEquals("42", result.variables().get("count").value());
        assertEquals("true", result.variables().get("active").value());
        assertEquals(VariableType.STATIC, result.variables().get("greeting").type());
        assertThrows(UnsupportedOperationException.class,
                () -> result.variables().put("new", result.variables().get("greeting")));
    }

    @Test
    void malformedFilesAndEntriesDoNotHideUnrelatedDefinitions() throws IOException {
        write("a.yml", "variables:\n  safe: good\n  bad.id: nope\n  list: [one, two]\n  typed:\n    type: unsupported\n    value: nope\n");
        write("b.yml", "variables: [broken\n");
        write("c.yml", "other: nothing\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals(Map.of("safe", new VariableDefinition("safe", "good", "a.yml", VariableType.STATIC)),
                result.variables());
        assertEquals(1, result.filesLoaded());
        assertEquals(2, result.skippedFiles());
    }

    @Test
    void eachLoadReflectsCurrentFiles() throws IOException {
        write("one.yml", "variables:\n  old: value\n");
        assertTrue(manager.load(temporaryDirectory).variables().containsKey("old"));
        Files.delete(temporaryDirectory.resolve("one.yml"));
        write("two.yml", "variables:\n  new: value\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertFalse(result.variables().containsKey("old"));
        assertTrue(result.variables().containsKey("new"));
    }

    @Test
    void missingVariableDirectoryIsFatal() {
        assertThrows(IOException.class, () -> manager.load(temporaryDirectory.resolve("missing")));
    }

    @Test
    void exactDuplicateYamlKeySkipsOnlyThatFile() throws IOException {
        write("a.yml", "variables:\n  safe: good\n");
        write("b.yml", "variables:\n  repeated: first\n  repeated: second\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals(1, result.filesLoaded());
        assertEquals(1, result.skippedFiles());
        assertEquals("good", result.variables().get("safe").value());
        assertFalse(result.variables().containsKey("repeated"));
    }

    @Test
    void caseCollisionInOneFileWarnsAndLaterValueWins() throws IOException {
        write("same.yml", "variables:\n  Name: first\n  name: second\n");
        Logger logger = Logger.getLogger("duplicate-test");
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            VariableManager.LoadResult result = new VariableManager(logger).load(temporaryDirectory);
            assertEquals("second", result.variables().get("name").value());
            assertTrue(warnings.stream().anyMatch(message -> message.contains("name") && message.contains("same.yml")
                    && message.contains("overrides")));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void dottedYamlKeyCannotBecomeAShorterPhantomId() throws IOException {
        write("dotted.yml", "variables:\n  bad.value: phantom\n  valid: kept\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals("kept", result.variables().get("valid").value());
        assertFalse(result.variables().containsKey("bad"));
        assertFalse(result.variables().containsKey("bad.value"));
    }

    @Test
    void malformedDocumentRootsAreSkippedWithoutAbortingOtherFiles() throws IOException {
        write("a.yml", "variables:\n  safe: retained\n");
        write("b.yml", "- invalid\n- root\n");
        write("c.yml", "true\n");
        write("d.yml", "variables: null\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals("retained", result.variables().get("safe").value());
        assertEquals(1, result.filesLoaded());
        assertEquals(3, result.skippedFiles());
    }

    @Test
    void unquotedNumericVariableIdIsAccepted() throws IOException {
        write("numeric.yml", "variables:\n  123: numeric id\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals("numeric id", result.variables().get("123").value());
    }

    @Test
    void rawYamlKeySpellingIsPreservedForBooleanAndLeadingZeroIds() throws IOException {
        write("scalar-ids.yml", "variables:\n  on: on value\n  off: off value\n  yes: yes value\n  no: no value\n  true: true value\n  false: false value\n  00123: leading zero\n  123: plain number\n");

        VariableManager.LoadResult result = manager.load(temporaryDirectory);

        assertEquals(1, result.filesLoaded());
        assertEquals(0, result.skippedFiles());
        assertEquals("on value", result.variables().get("on").value());
        assertEquals("off value", result.variables().get("off").value());
        assertEquals("yes value", result.variables().get("yes").value());
        assertEquals("no value", result.variables().get("no").value());
        assertEquals("true value", result.variables().get("true").value());
        assertEquals("false value", result.variables().get("false").value());
        assertEquals("leading zero", result.variables().get("00123").value());
        assertEquals("plain number", result.variables().get("123").value());
    }

    private void write(String relative, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
