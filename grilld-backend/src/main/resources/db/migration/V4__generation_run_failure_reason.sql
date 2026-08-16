-- run_report_md is rewritten in place by RunReportService on every
-- agent_executions update (§10.3) and must never be clobbered by a
-- terminal-failure message - that gets its own column.
ALTER TABLE generation_runs ADD COLUMN failure_reason TEXT;
