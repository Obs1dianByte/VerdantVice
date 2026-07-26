package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * HMAC protection for persistent batch metadata. The secret remains owned by the Paper layer.
 */
public final class BatchAuthenticityService {
    private static final String ALGORITHM = "HmacSHA256";

    public ProductBatch sign(ProductBatch batch, byte[] secret) {
        DomainChecks.required(batch, "batch");
        return batch.withSignature(calculate(batch, secret));
    }

    public boolean verify(ProductBatch batch, byte[] secret) {
        DomainChecks.required(batch, "batch");
        if (batch.signature().isBlank()) return false;
        byte[] expected = calculate(batch, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = batch.signature().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String calculate(ProductBatch batch, byte[] secret) {
        DomainChecks.required(secret, "secret");
        if (secret.length < 32) throw new IllegalArgumentException("HMAC secret must contain at least 32 bytes");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.clone(), ALGORITHM));
            byte[] signature = mac.doFinal(canonical(batch).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM does not provide " + ALGORITHM, exception);
        }
    }

    private static String canonical(ProductBatch batch) {
        StringBuilder value = new StringBuilder(512)
                .append("v1|").append(batch.batchId()).append('|').append(batch.ownerUuid()).append('|');
        batch.strainComposition().forEach((id, share) ->
                value.append(id).append('=').append(HexFormat.of().toHexDigits(Double.doubleToLongBits(share)))
                        .append(','));
        value.append('|');
        batch.geneticOrigin().stream().sorted(Comparator.comparing(java.util.UUID::toString))
                .forEach(id -> value.append(id).append(','));
        value.append('|').append(Double.doubleToLongBits(batch.freshWeight()))
                .append('|').append(Double.doubleToLongBits(batch.dryWeight()))
                .append('|').append(Double.doubleToLongBits(batch.qualityScore()))
                .append('|').append(batch.qualityGrade())
                .append('|').append(Double.doubleToLongBits(batch.contamination()))
                .append('|').append(Double.doubleToLongBits(batch.freshness()))
                .append('|').append(batch.harvestTiming())
                .append('|').append(batch.createdAt().toEpochMilli())
                .append('|').append(batch.state());
        batch.dryingData().ifPresent(data -> value.append("|d:")
                .append(data.stage()).append(':').append(data.startedAt().toEpochMilli())
                .append(':').append(data.targetDuration().toMillis())
                .append(':').append(Double.doubleToLongBits(data.rackCleanliness()))
                .append(':').append(Double.doubleToLongBits(data.rackStability())));
        batch.curingData().ifPresent(data -> value.append("|c:")
                .append(data.stage()).append(':').append(data.startedAt().toEpochMilli())
                .append(':').append(data.targetDuration().toMillis())
                .append(':').append(Double.doubleToLongBits(data.containerCleanliness()))
                .append(':').append(Double.doubleToLongBits(data.containerStability())));
        return value.toString();
    }
}
