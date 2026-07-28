package com.verdantvice.storage;

import com.verdantvice.database.SqlSession;
import com.verdantvice.processing.ProductBatch;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BatchPersistence {
    CompletableFuture<List<ProductBatch>> loadAll();

    CompletableFuture<Optional<ProductBatch>> find(UUID batchId);

    CompletableFuture<Void> create(ProductBatch batch);

    CompletableFuture<Void> save(ProductBatch batch);

    Optional<StoredBatch> find(SqlSession session, UUID batchId, boolean forUpdate)
            throws SQLException;

    void save(SqlSession session, StoredBatch expected, ProductBatch replacement, Instant now)
            throws SQLException;

    record StoredBatch(ProductBatch batch, long version) {
    }
}
