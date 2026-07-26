package com.verdantvice.genetics;

import com.verdantvice.security.SignedItemData;

public interface SeedGeneticsResolver {
    GeneticProfile profileForSeed(SignedItemData seed);
}
