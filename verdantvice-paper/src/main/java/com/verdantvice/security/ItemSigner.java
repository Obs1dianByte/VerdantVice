package com.verdantvice.security;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * HMAC protects the canonical domain payload. Display name and lore are
 * intentionally excluded so localization cannot invalidate an authentic item.
 */
public final class ItemSigner {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final NamespacedKey itemType;
    private final NamespacedKey itemId;
    private final NamespacedKey instanceId;
    private final NamespacedKey batchId;
    private final NamespacedKey ownerId;
    private final NamespacedKey quality;
    private final NamespacedKey strain;
    private final NamespacedKey createdAt;
    private final NamespacedKey schemaVersion;
    private final NamespacedKey signature;
    private final NamespacedKey usesRemaining;

    public ItemSigner(JavaPlugin plugin, byte[] secret) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("HMAC secret must contain at least 32 bytes");
        }
        this.secret = secret.clone();
        this.itemType = new NamespacedKey(plugin, "item_type");
        this.itemId = new NamespacedKey(plugin, "item_id");
        this.instanceId = new NamespacedKey(plugin, "instance_id");
        this.batchId = new NamespacedKey(plugin, "batch_id");
        this.ownerId = new NamespacedKey(plugin, "owner_uuid");
        this.quality = new NamespacedKey(plugin, "quality");
        this.strain = new NamespacedKey(plugin, "strain");
        this.createdAt = new NamespacedKey(plugin, "created_at");
        this.schemaVersion = new NamespacedKey(plugin, "schema_version");
        this.signature = new NamespacedKey(plugin, "signature");
        this.usesRemaining = new NamespacedKey(plugin, "uses_remaining");
    }

    public void sign(ItemStack item, SignedItemData data) {
        if (item.getAmount() != 1) {
            throw new IllegalArgumentException("Signed stateful items must have amount 1");
        }
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemType, PersistentDataType.STRING, data.itemType().name());
        pdc.set(itemId, PersistentDataType.STRING, data.itemId());
        pdc.set(instanceId, PersistentDataType.STRING, data.uniqueInstanceId().toString());
        setNullable(pdc, batchId, data.batchId());
        setNullable(pdc, ownerId, data.ownerUuid());
        pdc.set(quality, PersistentDataType.DOUBLE, data.quality());
        pdc.set(strain, PersistentDataType.STRING, data.strain());
        pdc.set(createdAt, PersistentDataType.LONG, data.createdAt().toEpochMilli());
        pdc.set(schemaVersion, PersistentDataType.INTEGER, data.schemaVersion());
        pdc.set(signature, PersistentDataType.STRING, signature(data));
        item.setItemMeta(meta);
    }

    public Verification verify(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Verification.invalid("empty");
        }
        if (item.getAmount() != 1) {
            return Verification.invalid("invalid_amount");
        }
        Optional<SignedItemData> parsed = read(item);
        if (parsed.isEmpty()) {
            return Verification.invalid("missing_or_malformed_data");
        }
        SignedItemData data = parsed.get();
        String actual = item.getItemMeta().getPersistentDataContainer()
                .get(signature, PersistentDataType.STRING);
        if (actual == null) {
            return Verification.invalid("missing_signature");
        }
        byte[] expectedBytes = signature(data).getBytes(StandardCharsets.US_ASCII);
        byte[] actualBytes = actual.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            return Verification.invalid("signature_mismatch");
        }
        return Verification.valid(data);
    }

    public Optional<SignedItemData> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        try {
            String typeValue = pdc.get(itemType, PersistentDataType.STRING);
            String idValue = pdc.get(itemId, PersistentDataType.STRING);
            String instanceValue = pdc.get(instanceId, PersistentDataType.STRING);
            Double qualityValue = pdc.get(quality, PersistentDataType.DOUBLE);
            String strainValue = pdc.get(strain, PersistentDataType.STRING);
            Long createdValue = pdc.get(createdAt, PersistentDataType.LONG);
            Integer schemaValue = pdc.get(schemaVersion, PersistentDataType.INTEGER);
            if (typeValue == null || idValue == null || instanceValue == null
                    || qualityValue == null || strainValue == null
                    || createdValue == null || schemaValue == null) {
                return Optional.empty();
            }
            return Optional.of(new SignedItemData(
                    CustomItemType.valueOf(typeValue),
                    idValue,
                    UUID.fromString(instanceValue),
                    uuid(pdc.get(batchId, PersistentDataType.STRING)),
                    uuid(pdc.get(ownerId, PersistentDataType.STRING)),
                    qualityValue,
                    strainValue,
                    Instant.ofEpochMilli(createdValue),
                    schemaValue
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public boolean hasVerdantData(ItemStack item) {
        return item != null
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(itemType, PersistentDataType.STRING);
    }

    public String payloadHash(SignedItemData data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical(data).getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public void setUsesRemaining(ItemStack item, int uses) {
        if (uses < 0) {
            throw new IllegalArgumentException("uses cannot be negative");
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
                usesRemaining,
                PersistentDataType.INTEGER,
                uses
        );
        item.setItemMeta(meta);
    }

    public int usesRemaining(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(usesRemaining, PersistentDataType.INTEGER, 0);
    }

    private String signature(SignedItemData data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical(data).getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to compute item signature", exception);
        }
    }

    private String canonical(SignedItemData data) {
        return "v=" + data.schemaVersion()
                + "&type=" + encode(data.itemType().name())
                + "&item=" + encode(data.itemId())
                + "&instance=" + data.uniqueInstanceId()
                + "&batch=" + nullable(data.batchId())
                + "&owner=" + nullable(data.ownerUuid())
                + "&quality=" + Long.toUnsignedString(Double.doubleToLongBits(data.quality()))
                + "&strain=" + encode(data.strain())
                + "&created=" + data.createdAt().toEpochMilli();
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String nullable(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private void setNullable(PersistentDataContainer pdc, NamespacedKey key, UUID value) {
        if (value == null) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.STRING, value.toString());
        }
    }

    public record Verification(boolean valid, SignedItemData data, String reason) {
        public static Verification valid(SignedItemData data) {
            return new Verification(true, data, "");
        }

        public static Verification invalid(String reason) {
            return new Verification(false, null, reason);
        }
    }
}
