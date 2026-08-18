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
          <Button
            asChild
            variant="outline"
            size="lg"
            className="relative border-line bg-paper-raised px-7 py-6 text-base text-ink shadow-sm hover:bg-paper-raised/90"
          >
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
