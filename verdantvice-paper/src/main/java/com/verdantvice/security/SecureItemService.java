package com.verdantvice.security;

import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.item.CustomItemFactory;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.item.ItemCasResult;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Couples PDC signatures with the database registry. Stateful inputs are
 * consumed with compare-and-set transitions, so copied inventory data cannot
 * be accepted twice on two server threads or after a restart.
 */
public final class SecureItemService implements ItemConsumptionGateway {
    private final CustomItemFactory factory;
    private final ItemSigner signer;
    private final ItemRegistryRepository registry;
    private final Duration reservationDuration;
    private final ProcessingItemValidator processingValidator = new ProcessingItemValidator();

    public SecureItemService(
            CustomItemFactory factory,
            ItemSigner signer,
            ItemRegistryRepository registry,
            Duration reservationDuration
    ) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reservationDuration = Objects.requireNonNull(reservationDuration, "reservationDuration");
    }

    public CompletableFuture<ItemStack> issue(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            String strain
    ) {
        return issue(viewer, type, owner, null, strain, 0.0);
    }

    public CompletableFuture<ItemStack> issue(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            UUID batchId,
            String strain,
            double quality
    ) {
        return registerPrepared(factory.create(viewer, type, owner, batchId, strain, quality));
    }

    public CompletableFuture<ItemStack> issue(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            ProductBatch batch
    ) {
        return registerPrepared(factory.create(viewer, type, owner, batch));
    }

    private CompletableFuture<ItemStack> registerPrepared(
            CustomItemFactory.PreparedItem prepared
    ) {
        SignedItemData data = prepared.data();
        ItemRegistration registration = new ItemRegistration(
                data.uniqueInstanceId(),
                data.itemType().name(),
                data.itemId(),
                data.batchId(),
                data.ownerUuid(),
                data.quality(),
                data.strain().isBlank() ? null : data.strain(),
                data.createdAt(),
                prepared.payloadHash(),
                data.schemaVersion(),
                null
        );
        return registry.register(registration).thenCompose(result -> {
            if (result.status() == RegistrationStatus.CONFLICT) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Generated item identity conflicted with the registry")
                );
            }
            return CompletableFuture.completedFuture(prepared.stack());
        });
    }

    public CompletableFuture<ItemStack> register(ItemStack signedItem) {
        Objects.requireNonNull(signedItem, "signedItem");
        ItemStack snapshot = signedItem.clone();
        ItemSigner.Verification verification = signer.verify(snapshot);
        if (!verification.valid()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Item is not authentically signed")
            );
        }
        SignedItemData data = verification.data();
        ItemRegistration registration = new ItemRegistration(
                data.uniqueInstanceId(),
                data.itemType().name(),
                data.itemId(),
                data.batchId(),
                data.ownerUuid(),
                data.quality(),
                data.strain().isBlank() ? null : data.strain(),
                data.createdAt(),
                signer.payloadHash(data),
                data.schemaVersion(),
                null
        );
        return registry.register(registration).thenCompose(result ->
                result.status() == RegistrationStatus.CONFLICT
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("Signed item identity conflicted with registry")
                        )
                        : CompletableFuture.completedFuture(snapshot)
        );
    }

    public CompletableFuture<ConsumedItem> consume(
            ItemStack item,
            Set<CustomItemType> accepted,
            UUID actor,
            String operationId
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operationId, "operationId");
        ItemStack snapshot = item.clone();
        ItemSigner.Verification verification = signer.verify(snapshot);
        if (!verification.valid() || !accepted.contains(verification.data().itemType())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid_or_wrong_item")
            );
        }
        SignedItemData data = verification.data();
        String payloadHash = signer.payloadHash(data);
        UUID reservation = UUID.randomUUID();
        Instant now = Instant.now();
        return registry.find(data.uniqueInstanceId()).thenCompose(optional -> {
            ItemRegistryRecord record = optional.orElseThrow(() ->
                    new IllegalStateException("unregistered_item"));
            if (record.state() != ItemRegistryState.ACTIVE
                    || !constantEquals(record.signature(), payloadHash)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("item_already_used_or_modified")
                );
            }
            return registry.reserve(
                    data.uniqueInstanceId(),
                    record.version(),
                    payloadHash,
                    reservation,
                    actor.toString(),
                    now.plus(reservationDuration),
                    operationId
            );
        }).thenCompose(reserved -> {
            if (reserved.status() == CasStatus.REJECTED || reserved.record().isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("item_reservation_failed")
                );
            }
            ItemRegistryRecord record = reserved.record().orElseThrow();
            return registry.consume(
                    data.uniqueInstanceId(),
                    record.version(),
                    payloadHash,
                    reservation,
                    operationId,
                    Instant.now()
            );
        }).thenCompose(consumed -> {
            if (consumed.status() == CasStatus.REJECTED) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("item_consumption_failed")
                );
            }
            return CompletableFuture.completedFuture(new ConsumedItem(data, operationId));
        });
    }

    @Override
    public CompletableFuture<ValidatedItem> validateActive(
            ItemStack item,
            Set<CustomItemType> accepted,
            UUID actor
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actor, "actor");
        ItemStack snapshot = item.clone();
        ItemSigner.Verification verification = signer.verify(snapshot);
        if (!verification.valid()) {
            return CompletableFuture.failedFuture(new ProcessingFailureException(
                    ProcessingFailureReason.INVALID_SIGNATURE));
        }
        SignedItemData data = verification.data();
        try {
            processingValidator.validateData(data, accepted, actor);
        } catch (ProcessingFailureException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        String payloadHash = signer.payloadHash(data);
        return registry.find(data.uniqueInstanceId()).thenApply(optional -> {
            ItemRegistryRecord record = optional.orElseThrow(() ->
                    new ProcessingFailureException(
                            ProcessingFailureReason.UNREGISTERED_ITEM));
            processingValidator.validateRecord(record, data, payloadHash, actor);
            return new ValidatedItem(snapshot, data, payloadHash);
        });
    }

    @Override
    public ConsumedItem consume(
            SqlSession session,
            ValidatedItem validated,
            Set<CustomItemType> accepted,
            UUID actor,
            String operationId,
            Instant now
    ) throws java.sql.SQLException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(validated, "validated");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(now, "now");
        SignedItemData data = validated.data();
        processingValidator.validateData(data, accepted, actor);
        ItemRegistryRecord record = registry.find(
                session, data.uniqueInstanceId(), true).orElseThrow(() ->
                new ProcessingFailureException(ProcessingFailureReason.UNREGISTERED_ITEM));
        processingValidator.validateRecord(record, data, validated.payloadHash(), actor);
        UUID reservation = UUID.randomUUID();
        ItemCasResult reserved = registry.reserve(
                session, record.instanceId(), record.version(), validated.payloadHash(),
                reservation, actor.toString(), now.plus(reservationDuration), operationId, now);
        if (reserved.status() == CasStatus.REJECTED || reserved.record().isEmpty()) {
            throw new ProcessingFailureException(ProcessingFailureReason.ITEM_ALREADY_USED);
        }
        ItemRegistryRecord reservedRecord = reserved.record().orElseThrow();
        ItemCasResult consumed = registry.consume(
                session, record.instanceId(), reservedRecord.version(),
                validated.payloadHash(), reservation, operationId, now);
        if (consumed.status() == CasStatus.REJECTED) {
            throw new ProcessingFailureException(ProcessingFailureReason.ITEM_ALREADY_USED);
        }
        return new ConsumedItem(data, operationId);
    }

    public ItemSigner.Verification verify(ItemStack item) {
        return signer.verify(item);
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        );
    }

    public record ValidatedItem(
            ItemStack snapshot,
            SignedItemData data,
            String payloadHash
    ) {
        public ValidatedItem {
            snapshot = snapshot.clone();
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(payloadHash, "payloadHash");
        }

        @Override
        public ItemStack snapshot() {
            return snapshot.clone();
        }
    }

    public record ConsumedItem(SignedItemData data, String operationId) {
    }
}
