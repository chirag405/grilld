package com.grilld.backend.billing;

/**
 * MVP's two purchasable, one-time Lemon Squeezy products
 * (product-and-architecture.md §10's "Free credits + Lemon Squeezy top-up" -
 * decisions-and-technical-architecture.md §11 kickoff plan Phase 7 gate).
 * The recurring Builder/Pro/Team subscription tiers in the same table are a
 * deliberate post-MVP deferral - see LEARNING.md's Phase 7 task 2 note.
 */
public enum CreditPackage {
    STARTER(60),
    TOPUP(50);

    private final int credits;

    CreditPackage(int credits) {
        this.credits = credits;
    }

    public int credits() {
        return credits;
    }
}
