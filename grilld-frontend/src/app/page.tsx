"use client";

import Link from "next/link";
import { FileText, Lightbulb, MessagesSquare, Users2 } from "lucide-react";
import { TextEffect } from "@/components/ui/text-effect";
import { GlowEffect } from "@/components/ui/glow-effect";
import { Spotlight } from "@/components/ui/spotlight";
import { AuroraBackground } from "@/components/ui/aurora-background";
import { Button } from "@/components/ui/button";
import { InView } from "@/components/ui/in-view";
import { Pricing1, type PricingPlan } from "@/components/ui/pricing-1";
import { AgentPipelineDiagram } from "@/components/AgentPipelineDiagram";
import { Logo } from "@/components/Logo";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";
const SIGN_IN_URL = `${BACKEND_URL}/oauth2/authorization/google`;

const STEPS = [
  {
    icon: Lightbulb,
    title: "Describe your idea",
    body: "One or two sentences is enough - a freelancer invoicing tool, an internal dashboard, whatever it is.",
  },
  {
    icon: MessagesSquare,
    title: "Grilld interrogates it",
    body: "A handful of sharp, targeted questions - not a form. It pushes back on vague answers and stops once it has enough.",
  },
  {
    icon: Users2,
    title: "Specialist agents take over",
    body: "Market, tech architecture, infra, roadmap, and more - ten agents work through your brief in parallel.",
  },
  {
    icon: FileText,
    title: "Get a real blueprint",
    body: "Architecture docs, diagrams, a phased roadmap, and a ready-to-run agent kit - all as markdown you own.",
  },
] as const;

const PLANS: PricingPlan[] = [
  {
    id: "STARTER",
    title: "Starter",
    description: "For your first idea.",
    price: "$12",
    priceSuffix: "one-time",
    buttonText: "Sign in to buy",
    features: [
      { text: "60 credits" },
      { text: "One full blueprint run (50 credits)" },
      { text: "All ten specialist agents" },
      { text: "Markdown docs, diagrams, and agent kit" },
    ],
  },
  {
    id: "TOPUP",
    title: "Top-up",
    description: "When you're back for another one.",
    price: "$10",
    priceSuffix: "one-time",
    buttonText: "Sign in to buy",
    isPopular: true,
    features: [
      { text: "50 credits" },
      { text: "Exactly one more full blueprint run" },
      { text: "Same ten agents, same output" },
      { text: "No subscription, buy only when you need it" },
    ],
  },
];

const fadeUp = {
  hidden: { opacity: 0, y: 24 },
  visible: { opacity: 1, y: 0 },
};

export default function LandingPage() {
  return (
    <div className="bg-paper">
      <AuroraBackground className="min-h-dvh justify-between overflow-hidden py-6">
        <Spotlight className="-top-32 left-0 md:-top-20 md:left-40" fill="var(--color-accent)" />

        <header className="relative z-10 flex w-full items-center justify-between px-6 sm:px-10">
          <Logo />
          <span className="font-mono text-xs uppercase tracking-widest text-ink-soft">
            idea &rarr; blueprint
          </span>
        </header>

        <div className="relative z-10 mx-auto flex w-full max-w-3xl flex-1 flex-col items-center justify-center gap-8 px-6 py-16 text-center sm:px-10">
          <p className="font-mono text-xs font-medium uppercase tracking-[0.3em] text-accent-ink">
            an interrogation, not a chat
          </p>

          <TextEffect
            as="h1"
            per="word"
            preset="fade-in-blur"
            className="text-balance text-4xl font-semibold leading-[1.08] tracking-tight text-ink sm:text-6xl"
          >
            Grill your idea until it&rsquo;s a project you can actually build.
          </TextEffect>

          <TextEffect
            as="p"
            per="line"
            preset="fade"
            delay={0.4}
            className="max-w-xl text-balance text-base leading-relaxed text-ink-soft sm:text-lg"
          >
            Grilld asks the questions a sharp technical cofounder would ask, then turns your answers
            into an architecture, an infra plan, and a phased build order.
          </TextEffect>

          <div className="relative mt-2">
            <GlowEffect
              colors={["#D97706", "#FBBF24", "#D97706"]}
              mode="pulse"
              blur="soft"
              duration={4}
              className="opacity-70"
            />
            <Button
              asChild
              variant="outline"
              size="lg"
              className="relative border-line bg-paper-raised px-7 py-6 text-base text-ink shadow-sm hover:bg-paper-raised/90"
            >
              <Link href={SIGN_IN_URL} className="gap-3">
                <GoogleMark />
                Sign in with Google
              </Link>
            </Button>
          </div>
        </div>

        <footer className="relative z-10 px-6 pb-8 text-center font-mono text-[11px] text-ink-soft/70 sm:px-10">
          Pay only for what you generate &middot; no subscription, ever
        </footer>
      </AuroraBackground>

      <section className="border-t border-line px-6 py-24 sm:px-10">
        <div className="mx-auto flex w-full max-w-[1240px] flex-col gap-14">
          <InView variants={fadeUp} viewOptions={{ once: true, margin: "-80px" }}>
            <div className="flex flex-col gap-3 text-center">
              <p className="font-mono text-xs uppercase tracking-[0.3em] text-accent-ink">how it works</p>
              <h2 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
                From one sentence to a build-ready plan
              </h2>
            </div>
          </InView>

          <div className="relative grid grid-cols-1 gap-10 sm:grid-cols-2 lg:grid-cols-4">
            <div
              aria-hidden
              className="absolute left-0 right-0 top-6 hidden h-px bg-line lg:block"
              style={{ marginInline: "12.5%" }}
            />
            {STEPS.map((step, index) => (
              <InView
                key={step.title}
                variants={fadeUp}
                transition={{ delay: index * 0.1 }}
                viewOptions={{ once: true, margin: "-80px" }}
                className="relative flex flex-col gap-4"
              >
                <div className="relative z-10 flex h-12 w-12 items-center justify-center rounded-full border border-line bg-paper text-accent-ink">
                  <step.icon className="h-5 w-5" />
                </div>
                <div className="flex flex-col gap-1.5">
                  <p className="font-mono text-xs text-ink-soft">Step {index + 1}</p>
                  <h3 className="text-base font-semibold text-ink">{step.title}</h3>
                  <p className="text-sm leading-relaxed text-ink-soft">{step.body}</p>
                </div>
              </InView>
            ))}
          </div>

          <InView variants={fadeUp} viewOptions={{ once: true, margin: "-80px" }} className="flex flex-col gap-3">
            <p className="text-center text-xs text-ink-soft">
              Ten specialist agents, fanned out from one orchestrator and converging on one blueprint - scroll to see the whole thing.
            </p>
            <AgentPipelineDiagram />
          </InView>
        </div>
      </section>

      <section className="border-t border-line bg-paper-raised/40 px-6 py-24 sm:px-10">
        <div className="mx-auto flex w-full max-w-5xl flex-col items-center gap-14">
          <InView variants={fadeUp} viewOptions={{ once: true, margin: "-80px" }}>
            <div className="flex flex-col gap-3 text-center">
              <p className="font-mono text-xs uppercase tracking-[0.3em] text-accent-ink">pricing</p>
              <h2 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
                No subscription. Pay for the runs you use.
              </h2>
              <p className="mx-auto max-w-md text-sm text-ink-soft">
                A full blueprint run costs 50 credits, start to finish - ten real agents doing real
                research and writing.
              </p>
            </div>
          </InView>

          <InView
            variants={fadeUp}
            viewOptions={{ once: true, margin: "-80px" }}
            className="w-full"
          >
            <Pricing1
              plans={PLANS}
              onSelect={() => {
                // SIGN_IN_URL is the backend's own OAuth endpoint, a different
                // origin in production - a hard navigation, not an internal route.
                // eslint-disable-next-line @next/next/no-location-assign-relative-destination
                window.location.href = SIGN_IN_URL;
              }}
            />
          </InView>
        </div>
      </section>
    </div>
  );
}

function GoogleMark() {
  return (
    <svg viewBox="0 0 18 18" width="18" height="18" aria-hidden="true">
      <path
        fill="#4285F4"
        d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.258h2.908c1.702-1.567 2.684-3.874 2.684-6.615z"
      />
      <path
        fill="#34A853"
        d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332C2.438 15.983 5.482 18 9 18z"
      />
      <path
        fill="#FBBC05"
        d="M3.964 10.71c-.18-.54-.282-1.117-.282-1.71s.102-1.17.282-1.71V4.958H.957C.347 6.173 0 7.55 0 9s.348 2.827.957 4.042l3.007-2.332z"
      />
      <path
        fill="#EA4335"
        d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0 5.482 0 2.438 2.017.957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z"
      />
    </svg>
  );
}
