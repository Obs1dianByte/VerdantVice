package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

public record MixtureComponent(GroundProduct product, double amount) {
    public MixtureComponent {
        DomainChecks.required(product, "product");
        amount = DomainChecks.positive(amount, "amount");
        if (amount > product.weight()) {
            throw new IllegalArgumentException("amount exceeds available ground product");
        }
    }
}
