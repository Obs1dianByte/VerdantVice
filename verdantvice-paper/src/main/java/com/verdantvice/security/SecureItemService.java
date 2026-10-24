package com.verdantvice.security;

import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.item.CustomItemFactory;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.processing.ProcessingFailureException;
import com.verdantvice.processing.ProcessingFailureReason;
import com.verdantvice.database.SqlSession;
import com.verdantvice.database.DatabaseManager;
import com.verdantvice.database.item.ItemCasResult;
import com.verdantvice.database.item.ItemRegistryState;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Couples authenticated PDC data with durable consumption bookkeeping.
 * Optional duplicate policing is controlled independently by ItemUsePolicy;
 * compare-and-set transitions still protect each transaction in either mode.
 */
public final class SecureItemService implements ItemConsumptionGateway {
    private final CustomItemFactory factory;
    private final ItemSigner signer;
    private final ItemRegistryRepository registry;
    private final Duration reservationDuration;
    private final ProcessingItemValidator processingValidator = new ProcessingItemValidator();
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final ItemUsePolicy itemUsePolicy;
    private final Set<UUID> pendingDeliveries = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SecureItemService(
            JavaPlugin plugin,
            DatabaseManager database,
            CustomItemFactory factory,
            ItemSigner signer,
            ItemRegistryRepository registry,
            Duration reservationDuration
    ) {
        this(plugin, database, factory, signer, registry, reservationDuration, new ItemUsePolicy(true));
    }

    public SecureItemService(
            JavaPlugin plugin, DatabaseManager database, CustomItemFactory factory,
            ItemSigner signer, ItemRegistryRepository registry, Duration reservationDuration,
            ItemUsePolicy itemUsePolicy
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reservationDuration = Objects.requireNonNull(reservationDuration, "reservationDuration");
        this.itemUsePolicy = Objects.requireNonNull(itemUsePolicy, "itemUsePolicy");
        if (reservationDuration.isNegative() || reservationDuration.isZero()) {
            throw new IllegalArgumentException("reservationDuration must be positive");
        }
    }

    public ItemUsePolicy itemUsePolicy() {
        return itemUsePolicy;
    }

    /** Prepares on the server thread; the caller owns the registry transaction. */
    public CustomItemFactory.PreparedItem prepare(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            String strain
    ) {
        return factory.create(viewer, type, owner, strain);
    }

    /** Prepares a batch output before the caller begins its database transaction. */
    public CustomItemFactory.PreparedItem prepare(
            CommandSender viewer, CustomItemType type, UUID owner, ProductBatch batch
    ) {
        return factory.create(viewer, type, owner, batch);
    }

    public CompletableFuture<Optional<ItemRegistryRecord>> registryRecord(UUID instanceId) {
        return registry.find(instanceId);
    }

    public void beginDelivery(UUID instanceId) {
        pendingDeliveries.add(Objects.requireNonNull(instanceId, "instanceId"));
    }

    public void endDelivery(UUID instanceId) {
        pendingDeliveries.remove(Objects.requireNonNull(instanceId, "instanceId"));
    }

    /** A just-inserted storage item remains sealed until world save and database acknowledgement. */
    public boolean deliveryPending(ItemStack item) {
        return !pendingDeliveries.isEmpty() && item != null
                && signer.read(item).map(data -> pendingDeliveries.contains(data.uniqueInstanceId())).orElse(false);
    }

    /** Recreates an already registered non-batch identity without a second insert. */
    public ItemStack recreate(CommandSender viewer, SignedItemData data) {
        return factory.create(viewer, data).stack();
    }

    /** Recreates an already registered batch identity for durable delivery. */
    public ItemStack recreate(CommandSender viewer, SignedItemData data, ProductBatch batch) {
        return factory.create(viewer, data, batch).stack();
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
        return onMain(() -> factory.create(viewer, type, owner, batchId, strain, quality))
                .thenCompose(this::registerPrepared);
    }

    public CompletableFuture<ItemStack> issue(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            ProductBatch batch
    ) {
        return onMain(() -> factory.create(viewer, type, owner, batch))
                .thenCompose(this::registerPrepared);
    }

    /** Idempotently recreates the same signed physical item after a restart. */
    public CompletableFuture<ItemStack> issueDeterministic(
            CommandSender viewer,
            CustomItemType type,
            UUID owner,
            ProductBatch batch,
            UUID instanceId,
            Instant createdAt
    ) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!owner.equals(batch.ownerUuid())) {
            throw new IllegalArgumentException(
                    "Deterministic item owner does not match product batch");
        }
        String itemId = switch (type) {
            case FRESH_BATCH -> "fresh-batch";
            case DRY_BATCH -> "dry-batch";
            case CURED_BATCH -> "cured-batch";
            case GROUND_PRODUCT -> "ground-product";
            case MIXTURE -> "mixture";
            case PACKAGE -> "package";
            default -> throw new IllegalArgumentException(
                    "Deterministic issue is limited to durable batch items");
        };
        String strain = batch.strainComposition().keySet().stream()
                .sorted()
                .findFirst()
                .orElse("");
        SignedItemData data = new SignedItemData(
                type,
                itemId,
                instanceId,
                batch.batchId(),
                owner,
                batch.qualityScore(),
                strain,
                createdAt,
                1);
        return onMain(() -> factory.create(viewer, data, batch))
                .thenCompose(this::registerPrepared);
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
            if (result.record().state() != ItemRegistryState.ACTIVE) {
                return CompletableFuture.failedFuture(new ProcessingFailureException(
                        ProcessingFailureReason.ITEM_ALREADY_USED));
            }
            return onMain(prepared::stack);
        });
    }

    public CompletableFuture<ItemStack> register(ItemStack signedItem) {
        Objects.requireNonNull(signedItem, "signedItem");
        return onMain(() -> {
            ItemStack snapshot = signedItem.clone();
            ItemSigner.Verification verification = signer.verify(snapshot);
            if (!verification.valid()) {
                throw new IllegalArgumentException("Item is not authentically signed");
            }
            return new CustomItemFactory.PreparedItem(snapshot, verification.data(),
                    signer.payloadHash(verification.data()));
        }).thenCompose(this::registerPrepared);
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
        Set<CustomItemType> acceptedSnapshot = Set.copyOf(accepted);
        return validateActive(item, acceptedSnapshot, actor).thenCompose(validated ->
                database.transaction(session -> consume(session, validated.identity(),
                        acceptedSnapshot, actor, operationId, Instant.now())));
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
        Set<CustomItemType> acceptedSnapshot = Set.copyOf(accepted);
        return onMain(() -> {
            ItemStack snapshot = item.clone();
            ItemSigner.Verification verification = signer.verify(snapshot);
            if (!verification.valid()) {
                throw new ProcessingFailureException(ProcessingFailureReason.INVALID_SIGNATURE);
            }
            SignedItemData data = verification.data();
            processingValidator.validateData(data, acceptedSnapshot, actor);
            return new ValidatedItem(snapshot, data, signer.payloadHash(data));
        }).thenCompose(validated -> registry.find(validated.data().uniqueInstanceId()).thenApply(optional -> {
            itemUsePolicy.validateRecord(optional.orElse(null), validated.data(), validated.payloadHash(), actor);
            return validated;
        }));
    }

    @Override
    public ConsumedItem consume(
            SqlSession session,
            ValidatedIdentity validated,
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
        ItemRegistryRecord record = itemUsePolicy.prepareConsumption(
                session, registry, data, validated.payloadHash(), actor, now);
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

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(action.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        };
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            try {
                plugin.getServer().getScheduler().runTask(plugin, task);
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        }
        return future;
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

        public ValidatedIdentity identity() {
            return new ValidatedIdentity(data, payloadHash);
        }

        @Override
        public ItemStack snapshot() {
            return snapshot.clone();
        }
    }

    public record ValidatedIdentity(SignedItemData data, String payloadHash) {
        public ValidatedIdentity {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(payloadHash, "payloadHash");
        }
    }

    public record ConsumedItem(SignedItemData data, String operationId) {
    }
}
