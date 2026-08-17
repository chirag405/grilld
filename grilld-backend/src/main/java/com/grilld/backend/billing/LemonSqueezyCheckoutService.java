package com.grilld.backend.billing;

import com.grilld.backend.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds a Lemon Squeezy hosted-checkout URL - no API call, no purchase
 * happens here. The user's browser navigates to this URL and completes the
 * purchase entirely on Lemon Squeezy's own page (Grilld's Spring backend
 * never touches card details, matching decisions-and-technical-architecture.md
 * §11.2's "Spring owns canonical state" split - billing state changes only
 * enter Spring through the webhook, after Lemon Squeezy confirms payment).
 * <p>
 * {@code checkout[custom][user_id]} rides along in the URL and comes back
 * unchanged in the webhook's {@code meta.custom_data.user_id}
 * (docs.lemonsqueezy.com/help/checkout/passing-custom-data) - it's how the
 * webhook knows which Grilld account to credit. It is NOT trusted for the
 * credit *amount*; see LemonSqueezyProductCatalog for why.
 */
@Service
public class LemonSqueezyCheckoutService {

    private final String storeSubdomain;
    private final LemonSqueezyProductCatalog productCatalog;

    public LemonSqueezyCheckoutService(@Value("${grilld.lemonsqueezy.store-subdomain:}") String storeSubdomain,
                                        LemonSqueezyProductCatalog productCatalog) {
        this.storeSubdomain = storeSubdomain;
        this.productCatalog = productCatalog;
    }

    public String buildCheckoutUrl(User user, CreditPackage creditPackage) {
        if (storeSubdomain.isBlank()) {
            throw new IllegalStateException(
                    "grilld.lemonsqueezy.store-subdomain is not configured - cannot build a checkout URL");
        }
        String variantId = productCatalog.variantIdFor(creditPackage);
        return UriComponentsBuilder
                .fromUriString("https://" + storeSubdomain + ".lemonsqueezy.com/buy/" + variantId)
                .queryParam("checkout[custom][user_id]", user.getId())
                .queryParam("checkout[email]", user.getEmail())
                .encode()
                .build()
                .toUriString();
    }
}
