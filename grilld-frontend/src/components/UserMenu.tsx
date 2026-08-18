"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiClient } from "@/lib/api-client";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { UserProfile } from "@/lib/types";

function initialsFor(email: string) {
  const name = email.split("@")[0];
  return name.slice(0, 2).toUpperCase();
}

/** The one place a logged-in user can see who they are, check their plan/credits, or sign out. */
export function UserMenu() {
  const [profile, setProfile] = useState<UserProfile | null>(null);

  useEffect(() => {
    apiClient<UserProfile>("/me").then(setProfile).catch(() => {});
  }, []);

  if (!profile) {
    return <Avatar className="h-8 w-8 animate-pulse"><AvatarFallback>?</AvatarFallback></Avatar>;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger className="rounded-full outline-none ring-accent/40 focus-visible:ring-2">
        <Avatar className="h-8 w-8 cursor-pointer">
          <AvatarFallback className="bg-ink text-xs font-medium text-paper">
            {initialsFor(profile.email)}
          </AvatarFallback>
        </Avatar>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel className="flex flex-col gap-1 font-normal">
          <span className="truncate text-sm font-medium text-ink">{profile.email}</span>
          <div className="flex items-center gap-2">
            <Badge variant="outline" className="text-[10px]">{profile.plan}</Badge>
            <span className="font-mono text-xs text-ink-soft">{profile.creditsBalance} credits</span>
          </div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem>
          <Link href="/billing" className="w-full">Billing</Link>
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Link href="/interview" className="w-full">Interview</Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <form action="/auth/signout" method="POST">
          <DropdownMenuItem className="text-danger focus:text-danger">
            <button type="submit" className="w-full text-left">
              Sign out
            </button>
          </DropdownMenuItem>
        </form>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
