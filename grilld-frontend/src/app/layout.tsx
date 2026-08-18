import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { cn } from "@/lib/utils";
import { TooltipProvider } from "@/components/ui/tooltip";

// One real type pairing (same family, designed together) rather than several
// competing sans faces - Geist for everything, Geist Mono for data/labels
// (credit counts, slot keys, run status) where Grilld's content is genuinely
// structured/technical, not for decoration.
const geistSans = Geist({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "Grilld",
  description: "Turn a raw idea into a scoped, buildable technical blueprint.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={cn(geistSans.variable, geistMono.variable, "font-sans")}>
      <body>
        <TooltipProvider>{children}</TooltipProvider>
      </body>
    </html>
  );
}
