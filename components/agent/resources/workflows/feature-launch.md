---
workflow_id: feature-launch
name: Feature Launch Workflow
description: End-to-end feature delivery — research feasibility → plan → implement → test → release notes → announce. Each stage is a recommendation; the workflow-agent adapts shape (skip/insert/re-run/reorder) to the actual work.
defaults: {hitl: gates, max_stage_attempts: 3, sub_lm: claude-haiku-4-5-20251001}
---

# Acceptance
- [ ] a1 — Feature meets the user-stated success criteria
- [ ] a2 — Implementation merged to main
- [ ] a3 — Tests added and passing
- [ ] a4 — Release notes published
- [ ] a5 — Stakeholders notified

# Stages
- [ ] s1 research-feasibility — Validate the feature is worth doing and surface unknowns. {agent: research-agent, gate: user, focus: [a1]}
- [ ] s2 plan-design — Author a concrete implementation plan with acceptance criteria. {agent: plan-agent, gate: none, focus: [a1, a2]}
- [ ] s3 implement — Spawn a todo from the plan and drive items to completion. {agent: exec-agent, gate: none, focus: [a2, a3]}
- [ ] s4 test — Run / extend tests; capture failures and re-run implement on regressions. {agent: exec-agent, gate: none, focus: [a3]}
- [ ] s5 release-notes — Draft user-facing release notes citing the merged change. {agent: coact-agent, gate: none, focus: [a4]}
- [ ] s6 announce — Notify stakeholders via configured channels (Slack / email / Linear). {agent: mcp-agent, gate: user, focus: [a5]}
