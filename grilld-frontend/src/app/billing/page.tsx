"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiClient } from "@/lib/api-client";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { SlidingNumber } from "@/components/ui/sliding-number";
import { Pricing1, type PricingPlan } from "@/components/ui/pricing-1";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { UserMenu } from "@/components/UserMenu";
import { Logo } from "@/components/Logo";
import { ApiError, type BillingBalance, type CheckoutUrlResponse, type CreditPackage } from "@/lib/types";

const PACKAGES: (PricingPlan & { id: CreditPackage })[] = [
  {
    id: "STARTER",
    title: "Starter",
    description: "One full blueprint, plus room to iterate.",
    price: "$12",
    priceSuffix: "one-time",
    buttonText: "Buy",
    features: [{ text: "60 credits" }, { text: "One full blueprint run (50 credits)" }, { text: "10 credits left over" }],
  },
  {
    id: "TOPUP",
    title: "Top-up",
    description: "Exactly one more full blueprint run.",
    price: "$10",
    priceSuffix: "one-time",
    buttonText: "Buy",
    isPopular: true,
    features: [{ text: "50 credits" }, { text: "One more full blueprint run" }, { text: "No subscription" }],
  },
];

export default function BillingPage() {
  const [balance, setBalance] = useState<BillingBalance | null>(null);
  const [loading, setLoading] = useState(true);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);
  const [buying, setBuying] = useState<CreditPackage | null>(null);

  useEffect(() => {
    apiClient<BillingBalance>("/billing/balance")
      .then(setBalance)
      .finally(() => setLoading(false));
  }, []);

  async function buy(creditPackage: CreditPackage) {
    setBuying(creditPackage);
    setCheckoutError(null);
    try {
      const result = await apiClient<CheckoutUrlResponse>(
        `/billing/checkout-url?creditPackage=${creditPackage}`,
      );
      // Full-page navigation to Lemon Squeezy's own hosted checkout, not a
      // state mutation the React Compiler needs to track.
      // eslint-disable-next-line react-hooks/immutability
      window.location.href = result.checkoutUrl;
    } catch (e) {
      setCheckoutError(
        e instanceof ApiError
          ? e.message
          : "Couldn't start checkout. Try again.",
      );
      setBuying(null);
    }
  }

  return (
    <main className="min-h-dvh bg-paper px-6 py-10 sm:px-10">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
        <header className="flex items-center justify-between">
          <Link href="/interview">
            <Logo />
          </Link>
          <div className="flex items-center gap-4">
            <Link href="/interview" className="font-mono text-xs uppercase tracking-widest text-ink-soft hover:text-ink">
              back to interview
            </Link>
            <UserMenu />
          </div>
        </header>

        <div>
          <h1 className="text-2xl font-semibold text-ink">Billing</h1>
          <p className="mt-1 text-sm text-ink-soft">
            A full blueprint run costs 50 credits. Every credit is purchased - no free tier.
          </p>
        </div>

        <Card className="gap-3">
          <CardHeader className="px-5">
            <CardDescription>Current balance</CardDescription>
            <CardTitle className="flex items-baseline gap-2 text-3xl">
              {loading ? (
                <Skeleton className="h-9 w-20" />
              ) : (
                <>
                  <SlidingNumber value={balance?.creditsBalance ?? 0} />
                  <span className="text-sm font-normal text-ink-soft">credits</span>
                </>
              )}
            </CardTitle>
          </CardHeader>
        </Card>

        <Pricing1
          className="mx-auto max-w-none"
          plans={PACKAGES.map((pkg) => ({
            ...pkg,
            buttonText: buying === pkg.id ? "Redirecting…" : pkg.buttonText,
          }))}
          disabled={buying !== null}
          onSelect={(plan) => buy(plan.id as CreditPackage)}
        />

        {checkoutError && (
          <Alert variant="destructive">
            <AlertDescription>{checkoutError}</AlertDescription>
          </Alert>
        )}

        <div>
          <h2 className="mb-3 text-sm font-medium text-ink">Recent activity</h2>
          {balance && balance.recentTransactions.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>When</TableHead>
                  <TableHead>Reason</TableHead>
                  <TableHead className="text-right">Change</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {balance.recentTransactions.map((t, i) => (
                  <TableRow key={i}>
                    <TableCell className="text-ink-soft">
                      {new Date(t.createdAt).toLocaleDateString()}
                    </TableCell>
                    <TableCell>{t.reason}</TableCell>
                    <TableCell className={`text-right font-mono ${t.delta >= 0 ? "text-ok" : "text-ink"}`}>
                      {t.delta >= 0 ? `+${t.delta}` : t.delta}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            !loading && <p className="text-sm text-ink-soft">No activity yet.</p>
          )}
        </div>
      </div>
    </main>
  );
}
