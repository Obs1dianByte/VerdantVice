package com.verdantvice.command;

import com.verdantvice.farm.StorageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class FarmStorageCommandRoute {
    private FarmStorageCommandRoute() {
    }

    static List<String> arguments() {
        List<String> values = new ArrayList<>(StorageType.arguments());
        values.add("unlink");
        return List.copyOf(values);
    }

    static Result resolve(String[] args) {
        if (args == null || args.length != 3
                || !"farm".equalsIgnoreCase(args[0])
                || !"storage".equalsIgnoreCase(args[1])) {
            return new Result(Action.INVALID_USAGE, Optional.empty());
        }
        if ("unlink".equalsIgnoreCase(args[2])) {
            return new Result(Action.UNLINK, Optional.empty());
        }
        Optional<StorageType> type = StorageType.parse(args[2]);
        return type.map(value -> new Result(Action.LINK, Optional.of(value)))
                .orElseGet(() -> new Result(Action.INVALID_TYPE, Optional.empty()));
    }

    enum Action {
        LINK,
        UNLINK,
        INVALID_USAGE,
        INVALID_TYPE
    }

    record Result(Action action, Optional<StorageType> type) {
    }
}
