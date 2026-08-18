"use client";

import Link from "next/link";
import { TextEffect } from "@/components/ui/text-effect";
import { GlowEffect } from "@/components/ui/glow-effect";
import { Spotlight } from "@/components/ui/spotlight";
import { AuroraBackground } from "@/components/ui/aurora-background";
import { Button } from "@/components/ui/button";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export default function LandingPage() {
  return (
    <AuroraBackground className="min-h-dvh justify-between overflow-hidden py-6">
      <Spotlight className="-top-32 left-0 md:-top-20 md:left-40" fill="var(--color-accent)" />

      <header className="relative z-10 flex w-full items-center justify-between px-6 sm:px-10">
        <span className="text-lg font-semibold tracking-tight text-ink">grilld</span>
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
          <Button asChild size="lg" className="relative px-7 py-6 text-base">
            <Link href={`${BACKEND_URL}/oauth2/authorization/google`} className="gap-3">
              <GoogleMark />
              Sign in with Google
            </Link>
          </Button>
        </div>
      </div>

      <footer className="relative z-10 px-6 pb-8 text-center font-mono text-[11px] text-ink-soft/70 sm:px-10">
        No card required &middot; 60 free credits on signup &middot; one full blueprint
      </footer>
    </AuroraBackground>
  );
}

function GoogleMark() {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
      <path
        fill="#EA4335"
        d="M12 10.2v3.9h5.5c-.24 1.4-1.68 4.1-5.5 4.1-3.3 0-6-2.7-6-6.1s2.7-6.1 6-6.1c1.9 0 3.15.8 3.87 1.5l2.64-2.55C16.86 3.4 14.63 2.4 12 2.4 6.9 2.4 2.7 6.6 2.7 12s4.2 9.6 9.3 9.6c5.37 0 8.93-3.78 8.93-9.1 0-.6-.07-1.06-.15-1.5H12z"
      />
    </svg>
  );
}
