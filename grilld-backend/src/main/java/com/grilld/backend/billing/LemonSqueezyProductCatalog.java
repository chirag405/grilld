package com.grilld.backend.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one trusted, server-side mapping between a Lemon Squeezy variant id and
 * how many credits it's worth. Deliberately the only place that mapping
 * exists: the webhook handler must never trust a credit amount supplied by
 * the client (custom_data is embedded in a checkout URL the browser can
 * edit), so it looks up the *variant actually paid for* here instead.
 */
@Component
public class LemonSqueezyProductCatalog {

    private final String starterVariantId;
    private final String topupVariantId;

    public LemonSqueezyProductCatalog(
            @Value("${grilld.lemonsqueezy.starter-variant-id:}") String starterVariantId,
            @Value("${grilld.lemonsqueezy.topup-variant-id:}") String topupVariantId) {
        this.starterVariantId = starterVariantId;
        this.topupVariantId = topupVariantId;
    }

    public Optional<CreditPackage> packageForVariant(String variantId) {
        if (variantId == null) {
            return Optional.empty();
        }
        if (variantId.equals(starterVariantId)) {
            return Optional.of(CreditPackage.STARTER);
        }
        if (variantId.equals(topupVariantId)) {
            return Optional.of(CreditPackage.TOPUP);
        }
        return Optional.empty();
    }

    public String variantIdFor(CreditPackage creditPackage) {
        String variantId = switch (creditPackage) {
            case STARTER -> starterVariantId;
            case TOPUP -> topupVariantId;
        };
        if (variantId.isBlank()) {
            throw new IllegalStateException(
                    "No Lemon Squeezy variant id configured for " + creditPackage
                            + " - set grilld.lemonsqueezy." + creditPackage.name().toLowerCase() + "-variant-id");
        }
        return variantId;
    }
}
