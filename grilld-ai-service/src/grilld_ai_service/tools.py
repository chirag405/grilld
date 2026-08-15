"""Shared tools for the specialist roster (Phase 5,
docs/product-and-architecture.md §3.2). One Tavily-backed web_search tool,
reused by every agent whose roster row lists `web_search`/`web_fetch` -
Market Analyst, Competition Analyst, Tech Architect, Infra Agent.
"""

from __future__ import annotations

from langchain_tavily import TavilySearch

# max_results kept small - specialists synthesize a handful of good sources
# into a doc, not a research dump. TAVILY_API_KEY is read from the
# environment automatically by the underlying client.
web_search = TavilySearch(max_results=5)
