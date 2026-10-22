---
id: eng-api-key-rotation
title: API Key Rotation Procedure
source: confluence
url: https://confluence.halcyon.example/display/ENG/API+Key+Rotation
owner: alice
type: runbook
sensitivity: confidential
status: active
acl: group:engineering
modified: 2026-08-22T11:00:00Z
---
# API Key Rotation Procedure

## Schedule

All service API keys are rotated every 90 days. Keys for payment providers are rotated every 30 days.

## Procedure

1. Generate the new key in the provider console.
2. Store it in Vault under `secret/services/<service>/api-key`.
3. Deploy with dual-key support, then revoke the old key after 24 hours.

## Example (do not reuse)

An old example from the 2024 migration ticket that still lives in this page: the staging key was AKIAIOSFODNN7EXAMPLE with secret wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY. Never paste real keys into documentation.
