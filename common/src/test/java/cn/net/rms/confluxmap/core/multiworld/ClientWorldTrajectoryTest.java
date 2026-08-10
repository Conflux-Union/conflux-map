package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientWorldTrajectoryTest {
    @Test
    void keepsOnlyTheConfiguredRecentHistory() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory(3);
        for (int index = 0; index < 5; index++) {
            trajectory.append(sample(index * 100L, index, 0.0D, index));
        }

        assertEquals(3, trajectory.samples().size());
        assertEquals(2.0D, trajectory.samples().get(0).x());
        assertEquals(4.0D, trajectory.latest().x());
    }

    @Test
    void rejectsOutOfOrderSamplesByStartingANewSegment() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(100L, 0, 0.0D, 1L));
        final ClientWorldTrajectory.AppendResult result = trajectory.append(sample(90L, 1, 0.0D, 2L));

        assertEquals(ClientWorldTrajectory.DiscontinuityReason.OUT_OF_ORDER, result.discontinuity());
        assertEquals(1, trajectory.samples().size());
    }

    @Test
    void cutsHistoryOnDimensionChangeAndImpossibleJump() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(0L, 0, 0.0D, 1L));
        final ClientWorldTrajectory.AppendResult dimension = trajectory.append(
            new ClientWorldTrajectorySample(1, 64, 1, 0, 0, 0, 0, 50, 1, "minecraft:the_nether", 2,
                ClientWorldTrajectorySample.NO_SERVER_ACK,
                ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED)
        );
        assertEquals(ClientWorldTrajectory.DiscontinuityReason.DIMENSION_CHANGE, dimension.discontinuity());
        trajectory.append(sample(100L, 0, 0.0D, 3L));
        final ClientWorldTrajectory.AppendResult jump = trajectory.append(sample(200L, 1_000, 0.0D, 4L));

        assertEquals(ClientWorldTrajectory.DiscontinuityReason.POSITION_JUMP, jump.discontinuity());
        assertEquals(1, trajectory.samples().size());
        assertEquals(
            ClientWorldTrajectory.DiscontinuityReason.NONE,
            trajectory.append(sample(300L, 1_001, 0.0D, 5L)).discontinuity()
        );
    }

    @Test
    void nearestDistanceUsesHistoryAndFiniteForwardProjection() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(0L, 0, 0.0D, 1L));
        trajectory.append(sample(1_000L, 100, 0.0D, 2L));

        assertEquals(0.0D, trajectory.nearestDistance(new ClientWorldPosition(50, 64, 0), 1_000L, 1_000L));
        assertEquals(0.0D, trajectory.nearestDistance(new ClientWorldPosition(150, 64, 0), 2_000L, 1_000L));
        assertTrue(trajectory.nearestDistance(new ClientWorldPosition(150, 64, 10), 2_000L, 1_000L) > 0.0D);
    }

    @Test
    void acknowledgementAgeAndUncertaintyGrowWithoutServerPackets() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(new ClientWorldTrajectorySample(
            0, 64, 0, 1, 0, 0, 0, 100, 1, "minecraft:overworld", 1, 100,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        ));
        trajectory.append(new ClientWorldTrajectorySample(
            1, 64, 0, 1, 0, 0, 0, 200, 2, "minecraft:overworld", 2, 100,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        ));

        assertEquals(900L, trajectory.acknowledgementAgeMs(1_000L));
        assertTrue(trajectory.uncertaintyRadius(5_000L, 6.0D)
            > trajectory.uncertaintyRadius(1_000L, 6.0D));
        assertFalse(trajectory.samples().isEmpty());
    }

    @Test
    void connectionGenerationStartsANewTrajectorySegment() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(100L, 0, 0.0D, 1L, 1L));

        final ClientWorldTrajectory.AppendResult result = trajectory.append(
            sample(200L, 1, 0.0D, 2L, 2L)
        );

        assertEquals(ClientWorldTrajectory.DiscontinuityReason.CONNECTION_CHANGE, result.discontinuity());
        assertEquals(1, trajectory.samples().size());
        assertEquals(2L, trajectory.latest().connectionGeneration());
    }

    @Test
    void restoredHistorySurvivesReconnectWithoutBridgingConnectionGenerations() {
        final ClientWorldTrajectory restored = ClientWorldTrajectory.fromHistoricalSamples(
            List.of(sample(100L, 0, 0.0D, 1L, 1L), sample(1_000L, 100, 0.0D, 2L, 1L)),
            ClientWorldTrajectory.DEFAULT_CAPACITY
        );

        final ClientWorldTrajectory.AppendResult boundary = restored.append(
            sample(2_000L, 1_000, 0.0D, 3L, 2L)
        );

        assertEquals(ClientWorldTrajectory.DiscontinuityReason.CONNECTION_CHANGE, boundary.discontinuity());
        assertEquals(3, restored.samples().size());
        assertEquals(400.0D, restored.nearestDistance(
            new ClientWorldPosition(500, 64, 0), 2_000L, 0L
        ));
    }

    @Test
    void restoredHistoryAllowsSequenceToRestartInANewConnectionGeneration() {
        final ClientWorldTrajectory restored = ClientWorldTrajectory.fromHistoricalSamples(
            List.of(sample(100L, 0, 0.0D, 50L, 7L), sample(1_000L, 1_000, 0.0D, 1L, 8L)),
            ClientWorldTrajectory.DEFAULT_CAPACITY
        );

        assertEquals(2, restored.samples().size());
        assertEquals(500.0D, restored.nearestDistance(
            new ClientWorldPosition(500, 64, 0), 1_000L, 0L
        ));
    }

    @Test
    void staleTrajectoryConfidenceExpiresOutsideTheBoundedWindow() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(sample(100L, 0, 0.0D, 1L));

        assertTrue(trajectory.hasUsableContinuity("minecraft:overworld", 5_000L, 5_000L));
        assertFalse(trajectory.hasUsableContinuity("minecraft:overworld", 5_101L, 5_000L));
    }

    @Test
    void corridorConfidenceIsZeroAtTheRawOneThousandTwentyFourBlockCutoff() {
        final ClientWorldTrajectory trajectory = trajectory(point(1_000L, 0, 0, 1L));

        assertEquals(0.0D, trajectory.corridorConfidence(
            new ClientWorldPosition(1_024, 64, 0), "minecraft:overworld", 1_000L, 48.0D, 0L
        ));
    }

    @Test
    void nearestApproachFindsCrossingHistoricalPaths() {
        final ClientWorldTrajectory horizontal = trajectory(
            point(0L, 0, 0, 1L), point(1_000L, 10, 0, 2L)
        );
        final ClientWorldTrajectory vertical = trajectory(
            point(0L, 5, -5, 1L), point(1_000L, 5, 5, 2L)
        );

        assertEquals(0.0D, horizontal.nearestApproachDistance(vertical, 1_000L, 0L));
    }

    @Test
    void nearestApproachMeasuresParallelAndDisjointCollinearPaths() {
        final ClientWorldTrajectory baseline = trajectory(
            point(0L, 0, 0, 1L), point(1_000L, 10, 0, 2L)
        );
        final ClientWorldTrajectory parallel = trajectory(
            point(0L, 0, 4, 1L), point(1_000L, 10, 4, 2L)
        );
        final ClientWorldTrajectory disjoint = trajectory(
            point(0L, 20, 0, 1L), point(1_000L, 30, 0, 2L)
        );

        assertEquals(4.0D, baseline.nearestApproachDistance(parallel, 1_000L, 0L));
        assertEquals(10.0D, baseline.nearestApproachDistance(disjoint, 1_000L, 0L));
    }

    @Test
    void forwardProjectionStopsAfterFiveSecondsButHistoryRemainsComparable() {
        final ClientWorldTrajectory trajectory = trajectory(
            point(0L, 0, 0, 1L), point(1_000L, 10, 0, 2L)
        );

        assertEquals(0.0D, trajectory.nearestDistance(
            new ClientWorldPosition(30, 64, 0), 3_000L, 5_000L
        ), 1.0e-9D);
        assertEquals(20.0D, trajectory.nearestDistance(
            new ClientWorldPosition(30, 64, 0), 7_000L, 5_000L
        ));
        assertEquals(0.0D, trajectory.nearestDistance(
            new ClientWorldPosition(5, 64, 0), 60_000L, 5_000L
        ), 1.0e-9D);
    }

    @Test
    void sharpTurnUsesTheLatestLocalVelocityWithoutDiscardingObservedHistory() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(new ClientWorldTrajectorySample(
            0, 64, 0, 10, 0, -90, 0,
            0L, 0L, "minecraft:overworld", 1L,
            ClientWorldTrajectorySample.NO_SERVER_ACK, 1L,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        ));
        trajectory.append(new ClientWorldTrajectorySample(
            10, 64, 0, -10, 0, 90, 0,
            1_000L, 20L, "minecraft:overworld", 2L,
            ClientWorldTrajectorySample.NO_SERVER_ACK, 1L,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        ));

        assertEquals(0.0D, trajectory.nearestDistance(
            new ClientWorldPosition(-10, 64, 0), 3_000L, 5_000L
        ), 1.0e-9D);
        assertEquals(2, trajectory.samples().size());
    }

    @Test
    void serverConfirmationCutsPredictionAndRecordsAcknowledgement() {
        final ClientWorldTrajectory trajectory = trajectory(
            point(0L, 0, 0, 1L), point(1_000L, 10, 0, 2L)
        );

        trajectory.reset(ClientWorldTrajectory.DiscontinuityReason.SERVER_CORRECTION);
        trajectory.append(ClientWorldTrajectorySample.confirmed(
            2, 64, 3, 0, 0, 0, 0, 2_000L, 40L,
            "minecraft:overworld", 3L, 0L
        ));

        assertEquals(1, trajectory.samples().size());
        assertEquals(ClientWorldTrajectorySample.EvidenceSource.SERVER_CONFIRMED,
            trajectory.latest().evidenceSource());
        assertEquals(2_000L, trajectory.lastServerAckTimeMs());
        assertEquals(3L, trajectory.lastServerAckSequence());
    }

    @Test
    void causalCorridorIgnoresAnEarlierHistoricalPathIntersection() {
        final ClientWorldTrajectory candidate = trajectory(
            point(0L, 0, 0, 1L), point(1_000L, 10, 0, 2L)
        );
        final ClientWorldTrajectory current = trajectory(
            new ClientWorldTrajectorySample(
                5, 64, -5, 0, 0, 0, 0, 2_000L, 40L, "minecraft:overworld", 1L,
                ClientWorldTrajectorySample.NO_SERVER_ACK, 1L,
                ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
            ),
            new ClientWorldTrajectorySample(
                5, 64, 5, 0, 0, 0, 0, 3_000L, 60L, "minecraft:overworld", 2L,
                ClientWorldTrajectorySample.NO_SERVER_ACK, 1L,
                ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
            )
        );

        assertEquals(0.0D, candidate.nearestApproachDistance(current, 3_000L, 0L));
        final ClientWorldTrajectory.CausalCorridor corridor = candidate.causalCorridorTo(current, 1_000L);
        assertTrue(corridor.pointDistance() > 7.0D);
        assertTrue(corridor.lateralDistance() > 7.0D);
    }

    @Test
    void stationaryCausalCorridorIsExactlyAnEndpointToEntryPointComparison() {
        final ClientWorldTrajectory candidate = trajectory(new ClientWorldTrajectorySample(
            22, 76, -154, 0, 0, 0, 0, 1_000L, 20L, "minecraft:overworld", 1L,
            ClientWorldTrajectorySample.NO_SERVER_ACK, 0L,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        ));
        final ClientWorldTrajectory current = trajectory(new ClientWorldTrajectorySample(
            22, 76, -154, 0, 0, 0, 0, 2_000L, 40L, "minecraft:overworld", 1L,
            ClientWorldTrajectorySample.NO_SERVER_ACK, 1L,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        ));

        final ClientWorldTrajectory.CausalCorridor corridor = candidate.causalCorridorTo(current, 5_000L);
        assertEquals(0.0D, corridor.pointDistance());
        assertEquals(0.0D, corridor.alongDistance());
        assertEquals(0.0D, corridor.lateralDistance());
    }

    @Test
    void causalCorridorSeparatesDecayAlongTheLineFromLateralDecay() {
        final ClientWorldTrajectory candidate = trajectory(
            movingPoint(0L, 0, 0, 1L, 0L), movingPoint(1_000L, 10, 0, 2L, 0L)
        );
        final ClientWorldTrajectory current = trajectory(
            movingPoint(2_000L, 20, 4, 1L, 1L)
        );

        final ClientWorldTrajectory.CausalCorridor corridor = candidate.causalCorridorTo(current, 5_000L);
        assertEquals(10.0D, corridor.predictedLength(), 1.0e-9D);
        assertEquals(10.0D, corridor.alongDistance(), 1.0e-9D);
        assertEquals(4.0D, corridor.lateralDistance(), 1.0e-9D);
    }

    @Test
    void missingAcknowledgementsExpandUncertaintyAndReduceConfidenceMonotonically() {
        final ClientWorldTrajectory trajectory = trajectory(point(1_000L, 0, 0, 1L));

        assertTrue(trajectory.uncertaintyRadius(3_000L, 48.0D)
            < trajectory.uncertaintyRadius(7_000L, 48.0D));
        assertEquals(trajectory.uncertaintyRadius(7_000L, 48.0D),
            trajectory.uncertaintyRadius(60_000L, 48.0D));
        assertTrue(ClientWorldProfileResolver.confirmationConfidence(trajectory, 3_000L)
            > ClientWorldProfileResolver.confirmationConfidence(trajectory, 20_000L));
    }

    private static ClientWorldTrajectory trajectory(final ClientWorldTrajectorySample... samples) {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        for (final ClientWorldTrajectorySample sample : samples) {
            trajectory.append(sample);
        }
        return trajectory;
    }

    private static ClientWorldTrajectorySample point(
        final long time,
        final double x,
        final double z,
        final long sequence
    ) {
        return new ClientWorldTrajectorySample(
            x, 64, z, 10, 0, -90, 0, time, time / 50L, "minecraft:overworld",
            sequence, ClientWorldTrajectorySample.NO_SERVER_ACK, 0L,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        );
    }

    private static ClientWorldTrajectorySample sample(
        final long time,
        final double x,
        final double z,
        final long sequence
    ) {
        return sample(time, x, z, sequence, 0L);
    }

    private static ClientWorldTrajectorySample sample(
        final long time,
        final double x,
        final double z,
        final long sequence,
        final long connectionGeneration
    ) {
        return new ClientWorldTrajectorySample(
            x, 64, z, 100, 0, 0, 0, time, time / 50L, "minecraft:overworld", sequence,
            ClientWorldTrajectorySample.NO_SERVER_ACK, connectionGeneration,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        );
    }

    private static ClientWorldTrajectorySample movingPoint(
        final long time,
        final double x,
        final double z,
        final long sequence,
        final long connectionGeneration
    ) {
        return new ClientWorldTrajectorySample(
            x, 64, z, 0.5D, 0, -90, 0, time, time / 50L, "minecraft:overworld", sequence,
            ClientWorldTrajectorySample.NO_SERVER_ACK, connectionGeneration,
            ClientWorldTrajectorySample.EvidenceSource.CLIENT_OBSERVED
        );
    }
}
