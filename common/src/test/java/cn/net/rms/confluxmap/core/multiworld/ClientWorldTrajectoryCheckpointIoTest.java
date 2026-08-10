package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientWorldTrajectoryCheckpointIoTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripKeepsHistoryAndUsesAHashedServerFile() throws Exception {
        final ClientWorldTrajectoryCheckpointIo io = new ClientWorldTrajectoryCheckpointIo(
            tempDir.resolve("trajectory"), LogManager.getLogger("trajectory-checkpoint-test")
        );
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(1_000L, 0.0D, 1L));
        trajectory.append(sample(1_100L, 12.0D, 2L));

        assertTrue(io.save("proxy.example.net:25565", OptionalLong.of(11L), trajectory).saved());

        final ClientWorldTrajectoryCheckpointIo.Checkpoint checkpoint = io.load(
            "proxy.example.net:25565"
        );
        assertNotNull(checkpoint);
        assertTrue(checkpoint.hasSeed());
        assertEquals(11L, checkpoint.seedHash());
        assertEquals(2, checkpoint.samples().size());
        assertEquals(12.0D, checkpoint.trajectory().latest().x());
        try (var files = Files.list(tempDir.resolve("trajectory"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void malformedSamplesAreIgnoredWithoutQuarantiningTheCheckpoint() throws Exception {
        final ClientWorldTrajectoryCheckpointIo io = new ClientWorldTrajectoryCheckpointIo(
            tempDir.resolve("trajectory"), LogManager.getLogger("trajectory-checkpoint-test")
        );
        final String key = ClientWorldSignalHasher.hash("proxy.example.net:25565");
        Files.createDirectories(tempDir.resolve("trajectory"));
        Files.writeString(tempDir.resolve("trajectory").resolve(key + ".json"), """
            {
              "schemaVersion": 1,
              "serverKey": "%s",
              "hasSeed": false,
              "savedAtEpochMs": 10,
              "samples": [
                {"x": 1, "y": 64, "z": 2, "horizontalVelocityX": 0, "horizontalVelocityZ": 0,
                 "yawDegrees": 0, "pitchDegrees": 0, "clientTimeMs": 100,
                 "clientTick": 1, "dimensionId": "minecraft_overworld", "sequence": 1,
                 "serverAckTimeMs": -1, "connectionGeneration": 0,
                 "evidenceSource": "CLIENT_OBSERVED"},
                {"x": "bad"}
              ]
            }
            """.formatted(key));

        final ClientWorldTrajectoryCheckpointIo.Checkpoint checkpoint = io.load(
            "proxy.example.net:25565"
        );
        assertNotNull(checkpoint);
        assertEquals(1, checkpoint.samples().size());
        assertEquals(1.0D, checkpoint.samples().get(0).x());
    }

    @Test
    void wrongServerCannotReuseAnotherServersCheckpoint() {
        final ClientWorldTrajectoryCheckpointIo io = new ClientWorldTrajectoryCheckpointIo(
            tempDir.resolve("trajectory"), LogManager.getLogger("trajectory-checkpoint-test")
        );
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(1_000L, 0.0D, 1L));
        io.save("proxy.example.net:25565", OptionalLong.empty(), trajectory);

        assertNull(io.load("other.example.net:25565"));
        assertTrue(io.clear("proxy.example.net:25565").saved());
        assertNull(io.load("proxy.example.net:25565"));
    }

    private static ClientWorldTrajectorySample sample(
        final long time,
        final double x,
        final long sequence
    ) {
        return ClientWorldTrajectorySample.observed(
            x, 64.0D, 0.0D, 12.0D, 0.0D, -90.0D, 0.0D,
            time, time / 50L, "minecraft_overworld", sequence,
            ClientWorldTrajectorySample.NO_SERVER_ACK, 1L
        );
    }
}
