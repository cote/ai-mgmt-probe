# Changelog

## 2.0 - 2026-07-01

Complete rewrite as an instructions-first skill.

- Removed all Python scripts. The AI assistant assembles the JSON trees and HTML directly from captured data by following `SKILL.md`.
- No hardcoded capture data anywhere. All examples use dummy placeholders (`example.com`, `EXAMPLE-USER-GUID`, `example-user`).
- `references/CALL_TREE_SCHEMA.md` documents the tree shape with a fully-annotated dummy example.
- `references/VIEWER_TEMPLATE.html` is a self-contained page with an embedded JS renderer. The AI injects data into a `<script id="trace-data">` block; no build step required.
- `references/CAPTURE_WORKFLOW.md` is the long-form runbook with per-IdP JWKS URL examples and gotchas.

## 1.x - withdrawn

Earlier iterations shipped hardcoded example data pulled from a specific capture. Withdrawn to avoid leaking operator-specific identifiers into a redistributable skill.
