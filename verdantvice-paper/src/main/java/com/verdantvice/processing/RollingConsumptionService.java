package com.verdantvice.processing;

import com.verdantvice.database.DatabaseManager;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemConsumptionGateway;
import com.verdantvice.security.SecureItemService;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RollingConsumptionService {
    private static final Set<CustomItemType> PAPER = Set.of(CustomItemType.ROLLING_PAPER);
    private static final Set<CustomItemType> FILTER = Set.of(CustomItemType.FILTER);
    private static final Set<CustomItemType> PRODUCT = Set.of(
            CustomItemType.GROUND_PRODUCT,
            CustomItemType.MIXTURE
    );

    private final DatabaseManager database;
    private final ItemConsumptionGateway items;

    public RollingConsumptionService(DatabaseManager database, ItemConsumptionGateway items) {
        this.database = Objects.requireNonNull(database, "database");
        this.items = Objects.requireNonNull(items, "items");
    }

    public CompletableFuture<Void> consume(
            SecureItemService.ValidatedItem paper,
            SecureItemService.ValidatedItem filter,
            SecureItemService.ValidatedItem product,
            UUID actor,
            UUID rollingSessionId,
            Instant now
    ) {
        Objects.requireNonNull(paper, "paper");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(product, "product");
        return consumeIdentities(
                paper.identity(),
                filter.identity(),
                product.identity(),
                actor,
                rollingSessionId,
                now
        );
    }

    CompletableFuture<Void> consumeIdentities(
            SecureItemService.ValidatedIdentity paper,
            SecureItemService.ValidatedIdentity filter,
            SecureItemService.ValidatedIdentity product,
            UUID actor,
            UUID rollingSessionId,
            Instant now
    ) {
        Objects.requireNonNull(paper, "paper");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rollingSessionId, "rollingSessionId");
        Objects.requireNonNull(now, "now");
        return database.transaction(session -> {
            String operationPrefix = "roll:" + rollingSessionId;
            items.consume(session, paper, PAPER, actor, operationPrefix + ":paper", now);
            items.consume(session, filter, FILTER, actor, operationPrefix + ":filter", now);
            items.consume(session, product, PRODUCT, actor, operationPrefix + ":product", now);
            return null;
        });
    }
}
