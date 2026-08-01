package cn.net.rms.confluxmap.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionQualityCorpusTest {
    @Test
    void generatedCorpusIsReproducibleUniqueAndDimensionBalanced() {
        final List<PredictionQualityCorpus.Sample> samples = PredictionQualityCorpus.generate(
            0x5EED_C0DEL,
            3,
            2,
            64
        );

        assertEquals(samples, PredictionQualityCorpus.generate(0x5EED_C0DEL, 3, 2, 64));
        assertEquals(5, samples.size());
        assertEquals(3, samples.stream().filter(sample -> sample.dimension().equals(DimensionId.OVERWORLD)).count());
        assertEquals(2, samples.stream().filter(sample -> sample.dimension().equals(DimensionId.END)).count());
        assertEquals(5, new HashSet<>(samples).size());
        assertTrue(samples.stream().allMatch(sample -> Math.abs(sample.tileX()) <= 64 && Math.abs(sample.tileZ()) <= 64));
        assertTrue(samples.stream().noneMatch(sample -> Math.max(Math.abs(sample.tileX()), Math.abs(sample.tileZ())) <= 2));
    }

    @Test
    void rejectsCountsThatCannotFitInTheRequestedRadius() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PredictionQualityCorpus.generate(1L, 25, 0, 3)
        );
    }
}
