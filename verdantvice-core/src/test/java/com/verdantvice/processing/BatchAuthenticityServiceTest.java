package com.verdantvice.processing;

import com.verdantvice.test.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BatchAuthenticityServiceTest {
    private static final byte[] SECRET =
            "a-test-secret-that-is-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsAndVerifiesUnmodifiedMetadata() {
        BatchAuthenticityService authenticity = new BatchAuthenticityService();
        ProductBatch signed = authenticity.sign(TestFixtures.driedBatch(), SECRET);
        assertFalse(signed.signature().isBlank());
        assertTrue(authenticity.verify(signed, SECRET));
        assertFalse(authenticity.verify(signed,
                "a-different-secret-that-is-at-least-32b".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void detectsQualityAndWeightTampering() {
        BatchAuthenticityService authenticity = new BatchAuthenticityService();
        ProductBatch signed = authenticity.sign(TestFixtures.driedBatch(), SECRET);
        ProductBatch tampered = new ProductBatch(
                signed.batchId(), signed.ownerUuid(), signed.strainComposition(), signed.geneticOrigin(),
                signed.freshWeight(), signed.dryWeight() - 1.0, signed.qualityScore() + 1.0,
                QualityGrade.fromScore(signed.qualityScore() + 1.0), signed.contamination(),
                signed.freshness(), signed.harvestTiming(), signed.dryingData(), signed.curingData(),
                signed.createdAt(), signed.signature(), signed.state());
        assertFalse(authenticity.verify(tampered, SECRET));
    }

    @Test
    void refusesWeakSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> new BatchAuthenticityService().sign(TestFixtures.freshBatch(), new byte[8]));
    }
}
