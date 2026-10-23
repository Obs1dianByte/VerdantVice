package com.verdantvice.command;

import com.verdantvice.database.DatabaseUnavailableException;
import com.verdantvice.storage.ConcurrentPlantModificationException;
import com.verdantvice.storage.StoragePersistenceException;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class CommandFailureMapper {
    private CommandFailureMapper() {
    }

    static FailureMapping map(Throwable failure) {
        Throwable cause = failure == null
                ? new IllegalStateException("Operation returned no result")
                : unwrap(failure);
        if (cause instanceof DatabaseUnavailableException) {
            return new FailureMapping("errors.database-unavailable", true, cause);
        }
        if (cause instanceof ConcurrentPlantModificationException) {
            return new FailureMapping("errors.database-conflict", false, cause);
        }
        if (cause instanceof StoragePersistenceException storage) {
            String key = switch (storage.status()) {
                case ALREADY_LINKED -> "farm.storage.already-linked";
                case NOT_LINKED -> "farm.storage.not-linked";
                case SALARY_STORAGE_ALREADY_EXISTS -> "farm.storage.salary-exists";
                case OTHER_FARM -> "farm.storage.other-farm";
                default -> "errors.internal";
            };
            return new FailureMapping(key, key.equals("errors.internal"), cause);
        }
        String message = Objects.toString(cause.getMessage(), "");
        String key = switch (message) {
            case "farm_region_overlap", "Farm region overlaps an existing farm" ->
                    "errors.farm-region-overlap";
            case "manager_table_blocked" -> "errors.manager-table-blocked";
            case "manager_table_outside_farm" -> "errors.manager-table-outside-farm";
            case "farm_region_too_large" -> "errors.farm-region-too-large";
            case "farm_name_required" -> "errors.farm-name-required";
            case "invalid_farm_name", "invalid_name" -> "errors.invalid-name";
            case "farm_limit", "limit_reached", "chunk_limit" -> "errors.limit-reached";
            case "region_denied", "protected_region" -> "errors.protected-region";
            case "farm_permission_denied", "farm_permission" -> "errors.farm-permission";
            case "no_permission", "permission_denied", "missing_permission" ->
                    "errors.no-permission";
            case "invalid_or_wrong_item", "invalid_item", "same_parent_strain" ->
                    "errors.invalid-item";
            case "not_a_container" -> "farm.storage.not-container";
            case "storage_already_linked", "Storage position is already assigned" ->
                    "farm.storage.already-linked";
            case "Storage must be inside the farm" -> "farm.storage.outside-farm";
            case "Farm already has a salary storage" -> "farm.storage.salary-exists";
            case "unknown_farm" -> "farm.storage.no-owned-farm";
            case "item_already_used_or_modified", "item_reservation_failed",
                 "item_consumption_failed" -> "errors.duplicate-item";
            case "wrong_owner" -> "errors.wrong-owner";
            case "busy" -> "errors.busy";
            default -> "errors.internal";
        };
        return new FailureMapping(key, key.equals("errors.internal"), cause);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    record FailureMapping(String translationKey, boolean logStackTrace, Throwable cause) {
        FailureMapping {
            Objects.requireNonNull(translationKey, "translationKey");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
