package com.verdantvice.security;

import com.verdantvice.database.item.CasStatus;
import com.verdantvice.database.item.ItemRegistration;
import com.verdantvice.database.item.ItemRegistryRecord;
import com.verdantvice.database.item.ItemRegistryRepository;
import com.verdantvice.database.item.ItemRegistryState;
import com.verdantvice.database.item.RegistrationStatus;
import com.verdantvice.item.CustomItemFactory;
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
public final class SecureItemService {
    private final CustomItemFactory factory;
    private final ItemSigner signer;
    private final ItemRegistryRepository registry;
    private final Duration reservationDuration;

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
        CustomItemFactory.PreparedItem prepared =
                factory.create(viewer, type, owner, batchId, strain, quality);
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

    public record ConsumedItem(SignedItemData data, String operationId) {
    }
}
