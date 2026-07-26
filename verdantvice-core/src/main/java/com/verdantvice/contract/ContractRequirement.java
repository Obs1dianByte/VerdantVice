package com.verdantvice.contract;

import com.verdantvice.common.DomainChecks;
import com.verdantvice.processing.QualityGrade;

import java.util.Optional;
import java.util.UUID;

public record ContractRequirement(
        Optional<String> strainId,
        double requiredWeight,
        QualityGrade minimumQuality,
        boolean curedRequired,
        Optional<UUID> geneticOrigin
) {
    public ContractRequirement {
        strainId = DomainChecks.required(strainId, "strainId")
                .map(id -> DomainChecks.identifier(id, "strainId"));
        requiredWeight = DomainChecks.positive(requiredWeight, "requiredWeight");
        DomainChecks.required(minimumQuality, "minimumQuality");
        geneticOrigin = DomainChecks.required(geneticOrigin, "geneticOrigin");
    }
}
