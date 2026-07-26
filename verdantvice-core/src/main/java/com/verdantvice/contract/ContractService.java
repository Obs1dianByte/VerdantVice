package com.verdantvice.contract;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.processing.BatchState;
import com.verdantvice.processing.ProductBatch;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;

public final class ContractService {
    public ContractUpdate deliver(Contract contract, ProductBatch batch, boolean authentic, Instant now) {
        DomainChecks.required(contract, "contract");
        DomainChecks.required(batch, "batch");
        DomainChecks.required(now, "now");
        if (contract.status() != ContractStatus.ACTIVE) return rejected(contract, "contract-not-active");
        if (now.isAfter(contract.deadline())) return new ContractUpdate(expire(contract), false,
                "contract-expired", false);
        if (!authentic) return rejected(contract, "invalid-signature");
        if (!batch.ownerUuid().equals(contract.ownerUuid())) return rejected(contract, "not-owner");
        if (contract.deliveredBatchIds().contains(batch.batchId())) return rejected(contract,
                "batch-already-delivered");
        if (batch.state() != BatchState.DRIED && batch.state() != BatchState.CURED) {
            return rejected(contract, "batch-not-deliverable");
        }

        HashMap<Integer, Double> progress = new HashMap<>(contract.deliveredWeight());
        boolean matched = false;
        for (int index = 0; index < contract.requirements().size(); index++) {
            ContractRequirement requirement = contract.requirements().get(index);
            if (!batch.qualityGrade().atLeast(requirement.minimumQuality())) continue;
            if (requirement.curedRequired() && batch.state() != BatchState.CURED) continue;
            if (requirement.geneticOrigin().isPresent()
                    && !batch.geneticOrigin().contains(requirement.geneticOrigin().get())) continue;
            double contribution = requirement.strainId()
                    .map(id -> batch.dryWeight() * batch.strainComposition().getOrDefault(id, 0.0))
                    .orElse(batch.dryWeight());
            if (contribution <= 0.0) continue;
            double remaining = Math.max(0.0, requirement.requiredWeight()
                    - progress.getOrDefault(index, 0.0));
            if (remaining <= 0.0) continue;
            progress.merge(index, Math.min(remaining, contribution), Double::sum);
            matched = true;
        }
        if (!matched) return rejected(contract, "requirements-not-matched");
        HashSet<java.util.UUID> batches = new HashSet<>(contract.deliveredBatchIds());
        batches.add(batch.batchId());
        Contract updated = new Contract(contract.contractId(), contract.ownerUuid(), contract.herbalistUuid(),
                contract.type(), contract.requirements(), contract.deadline(), contract.reward(),
                contract.reputationReward(), ContractStatus.ACTIVE, contract.createdAt(), Optional.empty(),
                progress, batches);
        boolean complete = updated.requirementsMet();
        if (complete) {
            updated = new Contract(updated.contractId(), updated.ownerUuid(), updated.herbalistUuid(),
                    updated.type(), updated.requirements(), updated.deadline(), updated.reward(),
                    updated.reputationReward(), ContractStatus.COMPLETED, updated.createdAt(),
                    Optional.of(now), updated.deliveredWeight(), updated.deliveredBatchIds());
        }
        return new ContractUpdate(updated, true, "", complete);
    }

    public Contract claim(Contract contract) {
        DomainChecks.required(contract, "contract");
        if (contract.status() != ContractStatus.COMPLETED) {
            throw new IllegalStateException("Only an unclaimed completed contract can be claimed");
        }
        return new Contract(contract.contractId(), contract.ownerUuid(), contract.herbalistUuid(),
                contract.type(), contract.requirements(), contract.deadline(), contract.reward(),
                contract.reputationReward(), ContractStatus.CLAIMED, contract.createdAt(),
                contract.completedAt(), contract.deliveredWeight(), contract.deliveredBatchIds());
    }

    public Contract expire(Contract contract) {
        DomainChecks.required(contract, "contract");
        if (contract.status() != ContractStatus.ACTIVE) return contract;
        return new Contract(contract.contractId(), contract.ownerUuid(), contract.herbalistUuid(),
                contract.type(), contract.requirements(), contract.deadline(), contract.reward(),
                contract.reputationReward(), ContractStatus.EXPIRED, contract.createdAt(), Optional.empty(),
                contract.deliveredWeight(), contract.deliveredBatchIds());
    }

    private static ContractUpdate rejected(Contract contract, String reason) {
        return new ContractUpdate(contract, false, reason, false);
    }
}
