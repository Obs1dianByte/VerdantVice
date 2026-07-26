package com.verdantvice.worker;

import com.verdantvice.common.BlockPosition;
import com.verdantvice.common.DomainChecks;

import java.util.Optional;
import java.util.UUID;

public record TaskTarget(String targetType, UUID targetId, Optional<BlockPosition> position) {
    public TaskTarget {
        targetType = DomainChecks.identifier(targetType, "targetType");
        DomainChecks.required(targetId, "targetId");
        position = DomainChecks.required(position, "position");
    }
}
