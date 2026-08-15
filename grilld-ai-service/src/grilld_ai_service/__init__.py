"""Grilld's AI service - Deep Agents + LangGraph.

Windows-only fix applied at package import time: psycopg's async mode (used by
AsyncPostgresSaver, app.py) cannot run under Windows' default ProactorEventLoop
- it raises psycopg.InterfaceError immediately on connect. Switching the event
loop policy here, before anything creates a loop, is what makes `langgraph dev`
and `uv run pytest` both work locally on Windows without every caller needing
to know about this. No-op on Linux/macOS (where this service actually deploys),
since the default loop there is already compatible.
"""

import asyncio
import sys

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
