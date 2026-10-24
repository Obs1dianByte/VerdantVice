package com.verdantvice.security;

import com.verdantvice.database.SqlSession;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ItemConsumptionGateway {
    CompletableFuture<SecureItemService.ValidatedItem> validateActive(
            ItemStack item,
            Set<CustomItemType> accepted,
            UUID actor
    );

    SecureItemService.ConsumedItem consume(
            SqlSession session,
            SecureItemService.ValidatedIdentity validated,
            Set<CustomItemType> accepted,
            UUID actor,
            String operationId,
            Instant now
    ) throws SQLException;
}
