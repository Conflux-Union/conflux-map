package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrossWorldSeedObservationsTest {
    private static CrossWorldSeedObservations fromJson(final String json) {
        return new Gson().fromJson(json, CrossWorldSeedObservations.class);
    }

    @Test
    void copyLeavesTheOriginalUntouched() {
        final CrossWorldSeedObservations observations = fromJson("""
            {"servers": {" ": {"spawn": {"seedHash": 7, "nameKeys": ["spawn"]}},
                         "play.example.net": {"lobby": {"seedHash": 123, "nameKeys": ["lobby", "old"]}}}}
            """);
        final CrossWorldSeedObservations copy = observations.copy();

        copy.normalize();

        assertTrue(copy.observations(" ").isEmpty());
        assertEquals(123L, copy.observations("play.example.net").get("lobby").seedHash());
        assertEquals(123L, observations.observations("play.example.net").get("lobby").seedHash());
        assertEquals(7L, observations.observations(" ").get("spawn").seedHash());
    }

    @Test
    void normalizeSkipsBlankServerAndWorldKeys() {
        final CrossWorldSeedObservations observations = fromJson("""
            {"servers": {"": {"lobby": {"seedHash": 1, "nameKeys": ["lobby"]}},
                         " ": {"survival": {"seedHash": 2, "nameKeys": ["survival"]}},
                         "play.example.net": {"": {"seedHash": 3, "nameKeys": [""]},
                                              "ghost": null,
                                              "lobby": {"seedHash": 123, "nameKeys": ["lobby"]}},
                         "legacy.example.net": {"": {"seedHash": 4, "nameKeys": [""]}}}}
            """);

        observations.normalize();

        assertTrue(observations.observations("").isEmpty());
        assertTrue(observations.observations(" ").isEmpty());
        assertEquals(123L, observations.observations("play.example.net").get("lobby").seedHash());
        assertTrue(observations.observations("legacy.example.net").isEmpty());
    }

    @Test
    void normalizeRepairsEachEntryInPlace() {
        final CrossWorldSeedObservations observations = fromJson("""
            {"servers": {"play.example.net": {"lobby": {"seedHash": 123,
                                                        "nameKeys": [null, "", " ", "lobby", "old", "lobby"]}}}}
            """);
        final CrossWorldSeedObservations copy = observations.copy();

        observations.normalize();

        assertEquals(List.of("lobby", "old"), observations.observations("play.example.net").get("lobby").nameKeys());
        assertEquals(List.of("lobby", "old"), copy.observations("play.example.net").get("lobby").nameKeys());
    }

}
