"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiClient } from "@/lib/api-client";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { SlidingNumber } from "@/components/ui/sliding-number";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { UserMenu } from "@/components/UserMenu";
import { ApiError, type BillingBalance, type CheckoutUrlResponse, type CreditPackage } from "@/lib/types";

const PACKAGES: { id: CreditPackage; label: string; price: string; credits: number; blurb: string }[] = [
  { id: "STARTER", label: "Starter", price: "$12", credits: 60, blurb: "One full blueprint, plus room to iterate." },
  { id: "TOPUP", label: "Top-up", price: "$10", credits: 50, blurb: "Exactly one more full blueprint run." },
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
          <Link href="/interview" className="text-lg font-semibold tracking-tight text-ink">
            grilld
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
            A full blueprint run costs 50 credits. New accounts start with 60, free.
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

        <div className="grid gap-4 sm:grid-cols-2">
          {PACKAGES.map((pkg) => (
            <Card key={pkg.id} className="gap-4">
              <CardHeader className="px-5">
                <div className="flex items-center justify-between">
                  <CardTitle>{pkg.label}</CardTitle>
                  <Badge variant="outline">{pkg.credits} credits</Badge>
                </div>
                <CardDescription>{pkg.blurb}</CardDescription>
              </CardHeader>
              <CardContent className="px-5">
                <div className="flex items-center justify-between">
                  <span className="text-2xl font-semibold text-ink">{pkg.price}</span>
                  <Button onClick={() => buy(pkg.id)} disabled={buying !== null}>
                    {buying === pkg.id ? "Redirecting…" : "Buy"}
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>

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
