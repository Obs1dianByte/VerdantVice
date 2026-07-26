package com.verdantvice.contract;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Contract(
        UUID contractId,
        UUID ownerUuid,
        UUID herbalistUuid,
        ContractType type,
        List<ContractRequirement> requirements,
        Instant deadline,
        ContractReward reward,
        double reputationReward,
        ContractStatus status,
        Instant createdAt,
        Optional<Instant> completedAt,
        Map<Integer, Double> deliveredWeight,
        Set<UUID> deliveredBatchIds
) {
    public Contract {
        DomainChecks.required(contractId, "contractId");
        DomainChecks.required(ownerUuid, "ownerUuid");
        DomainChecks.required(herbalistUuid, "herbalistUuid");
        DomainChecks.required(type, "type");
        requirements = List.copyOf(DomainChecks.required(requirements, "requirements"));
        if (requirements.isEmpty()) throw new IllegalArgumentException("Contract requires at least one requirement");
        DomainChecks.required(deadline, "deadline");
        DomainChecks.required(reward, "reward");
        reputationReward = DomainChecks.range(reputationReward, 0.0, 100.0, "reputationReward");
        DomainChecks.required(status, "status");
        DomainChecks.required(createdAt, "createdAt");
        if (!deadline.isAfter(createdAt)) throw new IllegalArgumentException("deadline must follow creation");
        completedAt = DomainChecks.required(completedAt, "completedAt");
        HashMap<Integer, Double> progress = new HashMap<>(DomainChecks.required(deliveredWeight,
                "deliveredWeight"));
        int requirementCount = requirements.size();
        progress.forEach((index, weight) -> {
            if (index == null || index < 0 || index >= requirementCount) {
                throw new IllegalArgumentException("Unknown contract requirement index");
            }
            DomainChecks.range(weight, 0.0, 1_000_000_000.0, "delivered weight");
        });
        deliveredWeight = Map.copyOf(progress);
        deliveredBatchIds = Set.copyOf(DomainChecks.required(deliveredBatchIds, "deliveredBatchIds"));
        if ((status == ContractStatus.COMPLETED || status == ContractStatus.CLAIMED)
                && completedAt.isEmpty()) {
            throw new IllegalArgumentException("Completed contract requires completedAt");
        }
    }

    public static Contract active(UUID id, UUID owner, UUID herbalist, ContractType type,
                                  List<ContractRequirement> requirements, Instant deadline,
                                  ContractReward reward, double reputationReward, Instant createdAt) {
        return new Contract(id, owner, herbalist, type, requirements, deadline, reward,
                reputationReward, ContractStatus.ACTIVE, createdAt, Optional.empty(), Map.of(), Set.of());
    }

    public boolean requirementsMet() {
        for (int index = 0; index < requirements.size(); index++) {
            if (deliveredWeight.getOrDefault(index, 0.0) + 1.0e-9
                    < requirements.get(index).requiredWeight()) {
                return false;
            }
        }
        return true;
    }
}
