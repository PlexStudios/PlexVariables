package dev.plex.plexvariables.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.plex.plexvariables.api.PlexVariablesApi.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    @Test
    void contextCopiesMetadataAndValidatesInput() {
        Map<String, String> metadata = new HashMap<>(Map.of("operation", "reward"));
        var context = new MutationContext("ExamplePlugin", Optional.of("request"), metadata);
        metadata.put("operation", "changed");
        assertEquals("reward", context.metadata().get("operation"));
        assertThrows(UnsupportedOperationException.class, () -> context.metadata().clear());
        assertTrue(new MutationContext("ExamplePlugin").metadata().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new MutationContext(" "));
        metadata.put("invalid", null);
        assertThrows(NullPointerException.class, () -> new MutationContext("ExamplePlugin", Optional.empty(), metadata));
    }

    @Test
    void changeValidatesScopeAndResultValidatesStatus() {
        var change = new VariableChange("score", Scope.PLAYER, Optional.of(UUID.randomUUID()),
                Optional.empty(), Optional.of("1"), Optional.empty());
        assertTrue(new MutationResult(Status.SUCCESS, Optional.of(change)).success());
        assertTrue(new MutationResult(Status.NO_CHANGE, Optional.of(change)).success());
        assertFalse(new MutationResult(Status.PERSISTENCE_FAILED, Optional.empty()).success());
        assertThrows(IllegalArgumentException.class, () -> new MutationResult(Status.SUCCESS, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new MutationResult(Status.NON_NUMERIC, Optional.of(change)));
        assertThrows(IllegalArgumentException.class, () -> new VariableChange("score", Scope.PLAYER,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new VariableChange("score", Scope.GLOBAL,
                Optional.of(UUID.randomUUID()), Optional.empty(), Optional.empty(), Optional.empty()));
    }
}
