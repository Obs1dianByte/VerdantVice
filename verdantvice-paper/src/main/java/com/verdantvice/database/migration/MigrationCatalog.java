package com.verdantvice.database.migration;

import com.verdantvice.database.DatabaseType;

import java.util.ArrayList;
import java.util.List;

public final class MigrationCatalog {
    private MigrationCatalog() {
    }

    public static List<SchemaMigration> forDatabase(DatabaseType type) {
        return List.of(
                identityFarmAndPlantSchema(type),
                processingWorkerAndEconomySchema(type),
                antiDupeAndOperationJournalSchema(type)
        );
    }

    private static SchemaMigration identityFarmAndPlantSchema(DatabaseType type) {
        String json = json(type);
        String real = real(type);
        List<String> sql = new ArrayList<>();

        sql.add(table(type, "players", """
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                locale VARCHAR(16) NULL,
                reputation INTEGER NOT NULL DEFAULT 0,
                emerald_balance BIGINT NOT NULL DEFAULT 0,
                settings_json %s NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                last_seen_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0
                """.formatted(json)));

        sql.add(table(type, "player_skills", """
                player_uuid VARCHAR(36) NOT NULL,
                skill_type VARCHAR(32) NOT NULL,
                skill_level INTEGER NOT NULL DEFAULT 0,
                experience BIGINT NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid, skill_type),
                CONSTRAINT fk_skills_player FOREIGN KEY (player_uuid)
                    REFERENCES players(player_uuid) ON DELETE CASCADE
                """));

        sql.add(table(type, "player_tolerance", """
                player_uuid VARCHAR(36) NOT NULL,
                effect_id VARCHAR(64) NOT NULL,
                tolerance %s NOT NULL DEFAULT 0,
                last_used_at BIGINT NULL,
                decay_checkpoint_at BIGINT NOT NULL,
                metadata_json %s NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (player_uuid, effect_id),
                CONSTRAINT fk_tolerance_player FOREIGN KEY (player_uuid)
                    REFERENCES players(player_uuid) ON DELETE CASCADE
                """.formatted(real, json)));

        sql.add(table(type, "farms", """
                farm_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                farm_name VARCHAR(64) NOT NULL,
                world_uuid VARCHAR(36) NOT NULL,
                table_x INTEGER NOT NULL,
                table_y INTEGER NOT NULL,
                table_z INTEGER NOT NULL,
                farm_level INTEGER NOT NULL DEFAULT 1,
                reputation INTEGER NOT NULL DEFAULT 0,
                settings_json %s NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_farms_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT uq_farm_table UNIQUE (world_uuid, table_x, table_y, table_z)
                """.formatted(json)));
        sql.add("CREATE INDEX idx_farms_owner ON farms(owner_uuid)");

        sql.add(table(type, "farm_members", """
                farm_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                member_role VARCHAR(32) NOT NULL,
                permissions_json %s NULL,
                joined_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (farm_id, player_uuid),
                CONSTRAINT fk_members_farm FOREIGN KEY (farm_id)
                    REFERENCES farms(farm_id) ON DELETE CASCADE,
                CONSTRAINT fk_members_player FOREIGN KEY (player_uuid)
                    REFERENCES players(player_uuid) ON DELETE CASCADE
                """.formatted(json)));
        sql.add("CREATE INDEX idx_farm_members_player ON farm_members(player_uuid)");

        sql.add(table(type, "farm_storages", """
                storage_id VARCHAR(36) NOT NULL PRIMARY KEY,
                farm_id VARCHAR(36) NOT NULL,
                storage_type VARCHAR(32) NOT NULL,
                world_uuid VARCHAR(36) NOT NULL,
                block_x INTEGER NOT NULL,
                block_y INTEGER NOT NULL,
                block_z INTEGER NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_storages_farm FOREIGN KEY (farm_id)
                    REFERENCES farms(farm_id) ON DELETE CASCADE,
                CONSTRAINT uq_storage_block UNIQUE (world_uuid, block_x, block_y, block_z)
                """));
        sql.add("CREATE INDEX idx_farm_storages_farm_type ON farm_storages(farm_id, storage_type)");

        sql.add(table(type, "genetic_profiles", """
                genetic_profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NULL,
                genetic_class VARCHAR(32) NOT NULL,
                traits_json %s NOT NULL,
                parent_profiles_json %s NULL,
                signature CHAR(64) NOT NULL,
                schema_version INTEGER NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_genetics_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE SET NULL
                """.formatted(json, json)));
        sql.add("CREATE INDEX idx_genetic_profiles_owner ON genetic_profiles(owner_uuid)");

        sql.add(table(type, "custom_strains", """
                strain_id VARCHAR(64) NOT NULL PRIMARY KEY,
                creator_uuid VARCHAR(36) NOT NULL,
                strain_name VARCHAR(64) NOT NULL,
                parent_strains_json %s NOT NULL,
                dominant_traits_json %s NOT NULL,
                colors_json %s NULL,
                effects_json %s NULL,
                generation INTEGER NOT NULL,
                stability %s NOT NULL,
                approved SMALLINT NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_strains_creator FOREIGN KEY (creator_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT
                """.formatted(json, json, json, json, real)));
        sql.add("CREATE INDEX idx_custom_strains_creator ON custom_strains(creator_uuid)");
        sql.add("CREATE INDEX idx_custom_strains_name ON custom_strains(strain_name)");

        sql.add(table(type, "plants", """
                plant_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                world_uuid VARCHAR(36) NOT NULL,
                block_x INTEGER NOT NULL,
                block_y INTEGER NOT NULL,
                block_z INTEGER NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                strain_id VARCHAR(64) NOT NULL,
                genetic_profile_id VARCHAR(36) NOT NULL,
                growth_stage VARCHAR(32) NOT NULL,
                growth_progress %s NOT NULL,
                health %s NOT NULL,
                hydration %s NOT NULL,
                nutrition %s NOT NULL,
                light_level INTEGER NOT NULL,
                stress %s NOT NULL,
                cleanliness %s NOT NULL,
                disease_id VARCHAR(64) NULL,
                pest_level %s NOT NULL,
                resistance %s NOT NULL,
                yield_potential %s NOT NULL,
                quality_potential %s NOT NULL,
                planted_at BIGINT NOT NULL,
                last_updated_at BIGINT NOT NULL,
                last_watered_at BIGINT NULL,
                harvest_window_start BIGINT NULL,
                harvest_window_end BIGINT NULL,
                farm_id VARCHAR(36) NULL,
                worker_assigned VARCHAR(36) NULL,
                state_json %s NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_plants_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT fk_plants_genetics FOREIGN KEY (genetic_profile_id)
                    REFERENCES genetic_profiles(genetic_profile_id) ON DELETE RESTRICT,
                CONSTRAINT fk_plants_farm FOREIGN KEY (farm_id)
                    REFERENCES farms(farm_id) ON DELETE SET NULL,
                CONSTRAINT uq_plant_block UNIQUE (world_uuid, block_x, block_y, block_z)
                """.formatted(real, real, real, real, real, real, real, real, real, real, json)));
        sql.add("CREATE INDEX idx_plants_owner ON plants(owner_uuid)");
        sql.add("CREATE INDEX idx_plants_farm ON plants(farm_id)");
        sql.add("CREATE INDEX idx_plants_chunk ON plants(world_uuid, chunk_x, chunk_z)");
        sql.add("CREATE INDEX idx_plants_update_stage ON plants(last_updated_at, growth_stage)");
        sql.add("CREATE INDEX idx_plants_worker ON plants(worker_assigned)");

        sql.add(table(type, "plant_events", """
                event_id %s,
                plant_uuid VARCHAR(36) NOT NULL,
                event_type VARCHAR(64) NOT NULL,
                event_data_json %s NULL,
                actor_uuid VARCHAR(36) NULL,
                idempotency_key VARCHAR(128) NULL,
                created_at BIGINT NOT NULL,
                CONSTRAINT fk_events_plant FOREIGN KEY (plant_uuid)
                    REFERENCES plants(plant_uuid) ON DELETE CASCADE,
                CONSTRAINT uq_plant_event_idempotency UNIQUE (idempotency_key)
                """.formatted(autoIncrement(type), json)));
        sql.add("CREATE INDEX idx_plant_events_plant_time ON plant_events(plant_uuid, created_at)");

        return new SchemaMigration(1, "player farm plant and genetics schema", sql);
    }

    private static SchemaMigration processingWorkerAndEconomySchema(DatabaseType type) {
        String json = json(type);
        String real = real(type);
        List<String> sql = new ArrayList<>();

        sql.add(table(type, "product_batches", """
                batch_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                strain_composition_json %s NOT NULL,
                genetic_origin_json %s NOT NULL,
                fresh_weight %s NOT NULL,
                dry_weight %s NULL,
                quality_score %s NOT NULL,
                quality_grade VARCHAR(32) NOT NULL,
                contamination %s NOT NULL,
                freshness %s NOT NULL,
                harvest_timing VARCHAR(32) NOT NULL,
                drying_data_json %s NULL,
                curing_data_json %s NULL,
                batch_state VARCHAR(32) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                signature CHAR(64) NOT NULL,
                schema_version INTEGER NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_batches_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT
                """.formatted(json, json, real, real, real, real, real, json, json)));
        sql.add("CREATE INDEX idx_batches_owner_state ON product_batches(owner_uuid, batch_state)");
        sql.add("CREATE INDEX idx_batches_created ON product_batches(created_at)");

        sql.add(table(type, "drying_jobs", """
                drying_job_id VARCHAR(36) NOT NULL PRIMARY KEY,
                batch_id VARCHAR(36) NOT NULL,
                owner_uuid VARCHAR(36) NOT NULL,
                rack_id VARCHAR(36) NOT NULL,
                drying_stage VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                started_at BIGINT NOT NULL,
                last_updated_at BIGINT NOT NULL,
                completes_at BIGINT NULL,
                process_data_json %s NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_drying_batch FOREIGN KEY (batch_id)
                    REFERENCES product_batches(batch_id) ON DELETE RESTRICT,
                CONSTRAINT fk_drying_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT uq_drying_batch UNIQUE (batch_id)
                """.formatted(json)));
        sql.add("CREATE INDEX idx_drying_due ON drying_jobs(status, last_updated_at)");
        sql.add("CREATE INDEX idx_drying_rack ON drying_jobs(rack_id, status)");

        sql.add(table(type, "curing_jobs", """
                curing_job_id VARCHAR(36) NOT NULL PRIMARY KEY,
                batch_id VARCHAR(36) NOT NULL,
                owner_uuid VARCHAR(36) NOT NULL,
                container_id VARCHAR(36) NOT NULL,
                status VARCHAR(32) NOT NULL,
                started_at BIGINT NOT NULL,
                last_updated_at BIGINT NOT NULL,
                completes_at BIGINT NULL,
                process_data_json %s NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_curing_batch FOREIGN KEY (batch_id)
                    REFERENCES product_batches(batch_id) ON DELETE RESTRICT,
                CONSTRAINT fk_curing_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT uq_curing_batch UNIQUE (batch_id)
                """.formatted(json)));
        sql.add("CREATE INDEX idx_curing_due ON curing_jobs(status, last_updated_at)");
        sql.add("CREATE INDEX idx_curing_container ON curing_jobs(container_id, status)");

        sql.add(table(type, "herbalists", """
                herbalist_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                entity_uuid VARCHAR(36) NOT NULL,
                owner_uuid VARCHAR(36) NULL,
                world_uuid VARCHAR(36) NOT NULL,
                block_x INTEGER NOT NULL,
                block_y INTEGER NOT NULL,
                block_z INTEGER NOT NULL,
                herbalist_level INTEGER NOT NULL DEFAULT 1,
                experience BIGINT NOT NULL DEFAULT 0,
                reputation INTEGER NOT NULL DEFAULT 0,
                offers_json %s NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_herbalists_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE SET NULL,
                CONSTRAINT uq_herbalist_entity UNIQUE (entity_uuid)
                """.formatted(json)));
        sql.add("CREATE INDEX idx_herbalists_owner ON herbalists(owner_uuid)");

        sql.add(table(type, "workers", """
                worker_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                entity_uuid VARCHAR(36) NOT NULL,
                farm_id VARCHAR(36) NOT NULL,
                owner_uuid VARCHAR(36) NOT NULL,
                worker_name VARCHAR(64) NOT NULL,
                specialization VARCHAR(32) NOT NULL,
                worker_state VARCHAR(32) NOT NULL,
                morale %s NOT NULL DEFAULT 100,
                experience BIGINT NOT NULL DEFAULT 0,
                worker_level INTEGER NOT NULL DEFAULT 1,
                daily_wage INTEGER NOT NULL DEFAULT 4,
                unpaid_days INTEGER NOT NULL DEFAULT 0,
                current_task_id VARCHAR(36) NULL,
                schedule_json %s NOT NULL,
                worker_data_json %s NULL,
                home_world_uuid VARCHAR(36) NULL,
                home_x %s NULL,
                home_y %s NULL,
                home_z %s NULL,
                last_world_uuid VARCHAR(36) NULL,
                last_x %s NULL,
                last_y %s NULL,
                last_z %s NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_workers_farm FOREIGN KEY (farm_id)
                    REFERENCES farms(farm_id) ON DELETE CASCADE,
                CONSTRAINT fk_workers_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT uq_worker_entity UNIQUE (entity_uuid)
                """.formatted(real, json, json, real, real, real, real, real, real)));
        sql.add("CREATE INDEX idx_workers_farm_state ON workers(farm_id, worker_state)");
        sql.add("CREATE INDEX idx_workers_owner ON workers(owner_uuid)");

        sql.add(table(type, "worker_tasks", """
                task_id VARCHAR(36) NOT NULL PRIMARY KEY,
                farm_id VARCHAR(36) NOT NULL,
                worker_uuid VARCHAR(36) NULL,
                task_type VARCHAR(32) NOT NULL,
                priority INTEGER NOT NULL,
                target_json %s NOT NULL,
                reservation_token VARCHAR(36) NULL,
                reservation_expires_at BIGINT NULL,
                timeout_at BIGINT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                task_status VARCHAR(32) NOT NULL,
                idempotency_key VARCHAR(128) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_tasks_farm FOREIGN KEY (farm_id)
                    REFERENCES farms(farm_id) ON DELETE CASCADE,
                CONSTRAINT fk_tasks_worker FOREIGN KEY (worker_uuid)
                    REFERENCES workers(worker_uuid) ON DELETE SET NULL,
                CONSTRAINT uq_worker_task_idempotency UNIQUE (idempotency_key)
                """.formatted(json)));
        sql.add("CREATE INDEX idx_tasks_farm_queue ON worker_tasks(farm_id, task_status, priority, created_at)");
        sql.add("CREATE INDEX idx_tasks_worker_status ON worker_tasks(worker_uuid, task_status)");
        sql.add("CREATE INDEX idx_tasks_reservation ON worker_tasks(reservation_expires_at, task_status)");

        sql.add(table(type, "worker_payments", """
                payment_id VARCHAR(36) NOT NULL PRIMARY KEY,
                worker_uuid VARCHAR(36) NOT NULL,
                payer_uuid VARCHAR(36) NOT NULL,
                amount BIGINT NOT NULL,
                currency VARCHAR(32) NOT NULL,
                period_key VARCHAR(64) NOT NULL,
                payment_status VARCHAR(32) NOT NULL,
                operation_id VARCHAR(128) NOT NULL,
                created_at BIGINT NOT NULL,
                paid_at BIGINT NULL,
                CONSTRAINT fk_payments_worker FOREIGN KEY (worker_uuid)
                    REFERENCES workers(worker_uuid) ON DELETE CASCADE,
                CONSTRAINT fk_payments_payer FOREIGN KEY (payer_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT uq_worker_payment_period UNIQUE (worker_uuid, period_key),
                CONSTRAINT uq_worker_payment_operation UNIQUE (operation_id)
                """));
        sql.add("CREATE INDEX idx_worker_payments_status ON worker_payments(worker_uuid, payment_status)");

        sql.add(table(type, "contracts", """
                contract_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                herbalist_uuid VARCHAR(36) NOT NULL,
                requirements_json %s NOT NULL,
                deadline BIGINT NOT NULL,
                reward_json %s NOT NULL,
                reputation_reward INTEGER NOT NULL,
                contract_status VARCHAR(32) NOT NULL,
                reward_claimed SMALLINT NOT NULL DEFAULT 0,
                reward_operation_id VARCHAR(128) NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                completed_at BIGINT NULL,
                row_version BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_contracts_owner FOREIGN KEY (owner_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT fk_contracts_herbalist FOREIGN KEY (herbalist_uuid)
                    REFERENCES herbalists(herbalist_uuid) ON DELETE RESTRICT,
                CONSTRAINT uq_contract_reward_operation UNIQUE (reward_operation_id)
                """.formatted(json, json)));
        sql.add("CREATE INDEX idx_contracts_owner_status ON contracts(owner_uuid, contract_status, deadline)");
        sql.add("CREATE INDEX idx_contracts_herbalist ON contracts(herbalist_uuid, contract_status)");

        sql.add(table(type, "market_state", """
                strain_id VARCHAR(64) NOT NULL PRIMARY KEY,
                base_price %s NOT NULL,
                current_price %s NOT NULL,
                supply %s NOT NULL,
                demand %s NOT NULL,
                sales_volume %s NOT NULL,
                active_event_json %s NULL,
                updated_at BIGINT NOT NULL,
                row_version BIGINT NOT NULL DEFAULT 0
                """.formatted(real, real, real, real, real, json)));
        sql.add("CREATE INDEX idx_market_updated ON market_state(updated_at)");

        sql.add(table(type, "trade_history", """
                trade_id %s,
                operation_id VARCHAR(128) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                herbalist_uuid VARCHAR(36) NULL,
                batch_id VARCHAR(36) NULL,
                strain_id VARCHAR(64) NOT NULL,
                quantity %s NOT NULL,
                unit_price %s NOT NULL,
                total_price %s NOT NULL,
                trade_direction VARCHAR(16) NOT NULL,
                quality_score %s NULL,
                created_at BIGINT NOT NULL,
                CONSTRAINT fk_trades_player FOREIGN KEY (player_uuid)
                    REFERENCES players(player_uuid) ON DELETE RESTRICT,
                CONSTRAINT fk_trades_herbalist FOREIGN KEY (herbalist_uuid)
                    REFERENCES herbalists(herbalist_uuid) ON DELETE SET NULL,
                CONSTRAINT fk_trades_batch FOREIGN KEY (batch_id)
                    REFERENCES product_batches(batch_id) ON DELETE SET NULL,
                CONSTRAINT uq_trade_operation UNIQUE (operation_id)
                """.formatted(autoIncrement(type), real, real, real, real)));
        sql.add("CREATE INDEX idx_trade_player_time ON trade_history(player_uuid, created_at)");
        sql.add("CREATE INDEX idx_trade_strain_time ON trade_history(strain_id, created_at)");

        sql.add(table(type, "notifications", """
                notification_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                notification_type VARCHAR(64) NOT NULL,
                payload_json %s NOT NULL,
                channel VARCHAR(32) NOT NULL,
                dedupe_key VARCHAR(128) NULL,
                created_at BIGINT NOT NULL,
                delivered_at BIGINT NULL,
                read_at BIGINT NULL,
                CONSTRAINT fk_notifications_player FOREIGN KEY (player_uuid)
                    REFERENCES players(player_uuid) ON DELETE CASCADE,
                CONSTRAINT uq_notification_dedupe UNIQUE (player_uuid, dedupe_key)
                """.formatted(json)));
        sql.add("CREATE INDEX idx_notifications_pending ON notifications(player_uuid, delivered_at, created_at)");

        return new SchemaMigration(2, "processing workers contracts and market schema", sql);
    }

    private static SchemaMigration antiDupeAndOperationJournalSchema(DatabaseType type) {
        String json = json(type);
        String real = real(type);
        List<String> sql = new ArrayList<>();

        sql.add(table(type, "item_registry", """
                instance_id VARCHAR(36) NOT NULL PRIMARY KEY,
                item_type VARCHAR(64) NOT NULL,
                item_id VARCHAR(128) NOT NULL,
                batch_id VARCHAR(36) NULL,
                owner_uuid VARCHAR(36) NULL,
                quality %s NULL,
                strain_id VARCHAR(64) NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                signature CHAR(64) NOT NULL,
                schema_version INTEGER NOT NULL,
                registry_state VARCHAR(16) NOT NULL,
                reservation_token VARCHAR(36) NULL,
                reserved_by VARCHAR(128) NULL,
                reserved_until BIGINT NULL,
                consumed_at BIGINT NULL,
                consumed_by_operation VARCHAR(128) NULL,
                last_operation_id VARCHAR(128) NULL,
                metadata_json %s NULL,
                row_version BIGINT NOT NULL DEFAULT 0
                """.formatted(real, json)));
        sql.add("CREATE INDEX idx_item_registry_owner_state ON item_registry(owner_uuid, registry_state)");
        sql.add("CREATE INDEX idx_item_registry_batch ON item_registry(batch_id)");
        sql.add("CREATE INDEX idx_item_registry_reservation ON item_registry(registry_state, reserved_until)");
        sql.add("CREATE INDEX idx_item_registry_last_operation ON item_registry(last_operation_id)");

        sql.add(table(type, "operation_journal", """
                operation_id VARCHAR(128) NOT NULL PRIMARY KEY,
                operation_type VARCHAR(64) NOT NULL,
                actor_uuid VARCHAR(36) NULL,
                scope_key VARCHAR(191) NULL,
                request_hash CHAR(64) NOT NULL,
                operation_state VARCHAR(16) NOT NULL,
                claim_token VARCHAR(36) NULL,
                lease_until BIGINT NULL,
                result_json %s NULL,
                error_code VARCHAR(128) NULL,
                attempt_count INTEGER NOT NULL DEFAULT 1,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                completed_at BIGINT NULL,
                row_version BIGINT NOT NULL DEFAULT 0
                """.formatted(json)));
        sql.add("CREATE INDEX idx_operation_journal_state_lease ON operation_journal(operation_state, lease_until)");
        sql.add("CREATE INDEX idx_operation_journal_actor_time ON operation_journal(actor_uuid, created_at)");
        sql.add("CREATE INDEX idx_operation_journal_scope ON operation_journal(scope_key, operation_type)");

        return new SchemaMigration(3, "atomic item registry and idempotent operation journal", sql);
    }

    private static String table(DatabaseType type, String name, String body) {
        String suffix = type == DatabaseType.SQLITE
                ? ""
                : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        return "CREATE TABLE " + name + " (\n" + body.strip() + "\n)" + suffix;
    }

    private static String json(DatabaseType type) {
        return type == DatabaseType.SQLITE ? "TEXT" : "LONGTEXT";
    }

    private static String real(DatabaseType type) {
        return type == DatabaseType.SQLITE ? "REAL" : "DOUBLE";
    }

    private static String autoIncrement(DatabaseType type) {
        return type == DatabaseType.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
    }
}
