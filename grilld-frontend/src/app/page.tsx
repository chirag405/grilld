import Link from "next/link";

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export default function LandingPage() {
  return (
    <main className="blueprint-sheet relative flex min-h-dvh flex-col overflow-hidden">
      <header className="flex items-center justify-between px-6 py-6 sm:px-10">
        <span className="font-display text-lg font-semibold tracking-tight text-ink">
          grilld
        </span>
        <span className="font-mono text-xs uppercase tracking-widest text-ink-soft">
          idea &rarr; blueprint
        </span>
      </header>

      <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col items-center justify-center gap-12 px-6 py-16 sm:px-10">
        <div className="animate-fade-up flex flex-col items-center gap-6 text-center">
          <p className="font-mono text-xs uppercase tracking-[0.3em] text-blueprint">
            an interrogation, not a chat
          </p>
          <h1 className="max-w-3xl text-balance font-display text-4xl font-semibold leading-[1.05] tracking-tight text-ink sm:text-6xl">
            Grill your idea until it&rsquo;s a project you can actually build.
          </h1>
          <p className="max-w-xl text-balance text-base leading-relaxed text-ink-soft sm:text-lg">
            Grilld asks the questions a sharp technical cofounder would ask &mdash;
            then turns your answers into an architecture, an infra plan, and a
            phased build order. Not another chat window.
          </p>
        </div>

        <Link
          href={`${BACKEND_URL}/oauth2/authorization/google`}
          className="animate-fade-up group inline-flex items-center gap-3 rounded-md bg-ink px-6 py-3.5 font-display text-sm font-medium text-paper shadow-[3px_3px_0_0_var(--color-rust)] transition-transform hover:-translate-y-0.5 hover:shadow-[4px_4px_0_0_var(--color-rust)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-rust"
          style={{ animationDelay: "80ms" }}
        >
          <GoogleMark />
          Sign in with Google
        </Link>

        <TitleBlock />
      </div>

      <footer className="px-6 pb-8 text-center font-mono text-[11px] text-ink-soft/70 sm:px-10">
        No card required &middot; 60 free credits on signup &middot; one full blueprint
      </footer>
    </main>
  );
}

/**
 * The signature element (frontend-design skill: "the single unique element
 * this page will be remembered by"). A literal engineering title block -
 * every real blueprint has one in a bottom corner - standing in here for
 * what Grilld actually produces. Recurs, filled in with real data, on the
 * live brief panel and the Run Report canvas.
 */
function TitleBlock() {
  return (
    <div
      className="animate-fade-up w-full max-w-md border border-ink/20 bg-paper font-mono text-[11px] text-ink-soft"
      style={{ animationDelay: "160ms" }}
    >
      <div className="grid grid-cols-2 divide-x divide-ink/20 border-b border-ink/20">
        <Field label="project">your next idea</Field>
        <Field label="scale">T0&ndash;T3</Field>
      </div>
      <div className="grid grid-cols-2 divide-x divide-ink/20 border-b border-ink/20">
        <Field label="drawn by">you</Field>
        <Field label="reviewed by">10 specialist agents</Field>
      </div>
      <div className="grid grid-cols-2 divide-x divide-ink/20">
        <Field label="rev">00</Field>
        <Field label="status">not yet interrogated</Field>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="px-3 py-2">
      <div className="uppercase tracking-widest text-ink-soft/60">{label}</div>
      <div className="mt-0.5 text-ink">{children}</div>
    </div>
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
