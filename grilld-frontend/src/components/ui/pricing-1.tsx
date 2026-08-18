import { CheckCircle2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface PricingFeature {
  text: string;
}

export interface PricingPlan {
  id: string;
  title: string;
  description: string;
  price: string;
  priceSuffix?: string;
  features: PricingFeature[];
  buttonText: string;
  isPopular?: boolean;
}

export interface Pricing1Props {
  plans: PricingPlan[];
  onSelect?: (plan: PricingPlan) => void;
  disabled?: boolean;
  className?: string;
}

/**
 * Adapted from Watermelon UI's pricing-1 block (registry.watermelon.sh) for
 * Grilld's real two-package model - flat one-time credit purchases, not a
 * subscription tier ladder, so no monthly/yearly toggle or "Enterprise,
 * contact sales" tier. Swapped its react-icons dependency for lucide-react
 * (Check icon) to match every other icon in this codebase.
 */
export function Pricing1({ plans, onSelect, disabled, className }: Pricing1Props) {
  return (
    <div className={cn("mx-auto w-full max-w-3xl", className)}>
      <div className="grid grid-cols-1 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2">
        {plans.map((plan) => (
          <div
            key={plan.id}
            className={cn(
              "relative flex flex-col p-8",
              plan.isPopular ? "bg-paper-raised" : "bg-paper",
            )}
          >
            {plan.isPopular && (
              <div className="absolute right-6 top-6">
                <Badge variant="outline" className="border-accent/20 bg-accent-soft text-accent-ink">
                  Most runs choose this
                </Badge>
              </div>
            )}

            <div className="mb-6">
              <h3 className="mb-2 text-xl font-semibold tracking-tight text-ink">{plan.title}</h3>
              <p className="min-h-[40px] pr-8 text-sm text-ink-soft">{plan.description}</p>
            </div>

            <div className="mb-6 flex items-baseline gap-2">
              <span className="text-5xl font-bold tracking-tight text-ink">{plan.price}</span>
              {plan.priceSuffix && <span className="text-sm font-medium text-ink-soft">{plan.priceSuffix}</span>}
            </div>

            <div className="mb-8 h-px w-1/3 bg-line" />

            <ul className="mb-8 flex-1 space-y-3">
              {plan.features.map((feature, i) => (
                <li key={i} className="flex items-start gap-3">
                  <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-accent-ink" />
                  <span className="text-sm text-ink-soft">{feature.text}</span>
                </li>
              ))}
            </ul>

            <Button
              size="lg"
              variant={plan.isPopular ? "default" : "outline"}
              className="mt-auto h-12"
              disabled={disabled}
              onClick={() => onSelect?.(plan)}
            >
              {plan.buttonText}
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}
