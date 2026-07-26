package com.verdantvice.genetics;

import com.verdantvice.common.DomainChecks;

import java.time.Instant;

public record CustomStrainRecord(
        String strainId,
        GeneticLine line,
        Instant updatedAt,
        long rowVersion
) {
    public CustomStrainRecord {
        strainId = DomainChecks.identifier(strainId, "strainId");
        DomainChecks.required(line, "line");
        DomainChecks.required(updatedAt, "updatedAt");
        if (rowVersion < 0L) {
            throw new IllegalArgumentException("rowVersion cannot be negative");
        }
    }
}
