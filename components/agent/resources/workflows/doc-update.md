---
workflow_id: doc-update
name: Documentation Update Workflow
description: Update existing documentation — scope the change → draft prose → verify examples → review-gate → publish. Lighter than feature-launch (no implementation stage, no announcement by default).
defaults: {hitl: gates, max_stage_attempts: 2, sub_lm: claude-haiku-4-5-20251001}
---

# Acceptance
- [ ] a1 — Doc changes match the user's stated intent
- [ ] a2 — Prose is clear and consistent with existing style
- [ ] a3 — Any code examples / commands are verified accurate
- [ ] a4 — Changes committed (and merged if PR-bound)

# Stages
- [ ] s1 scope — Clarify which files / sections change and confirm the requested intent. {agent: research-agent, gate: none, focus: [a1]}
- [ ] s2 draft — Author the prose changes via edit-agent (or exec-agent for multi-file edits). {agent: edit-agent, gate: none, focus: [a1, a2]}
- [ ] s3 verify-examples — Run / spot-check any code, shell, or CLI examples in the new prose. {agent: exec-agent, gate: none, focus: [a3]}
- [ ] s4 review — Surface the diff for human review before committing. {agent: coact-agent, gate: user, focus: [a1, a2, a3]}
- [ ] s5 publish — Commit the changes (and open a PR when the repo workflow requires one). {agent: exec-agent, gate: none, focus: [a4]}
