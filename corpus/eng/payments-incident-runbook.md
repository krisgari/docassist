---
id: eng-payments-runbook
title: Payments Service Incident Runbook
source: confluence
url: https://confluence.halcyon.example/display/ENG/Payments+Runbook
owner: alice
type: runbook
sensitivity: internal
status: active
acl: group:engineering
modified: 2026-09-18T16:30:00Z
---
# Payments Service Incident Runbook

## Severity levels

| Severity | Definition | Response time | Who is paged |
| --- | --- | --- | --- |
| SEV1 | Checkout failing for more than 5% of requests | 5 minutes | Payments on-call and incident commander |
| SEV2 | Elevated latency (p95 above 2 s) or a single PSP down | 15 minutes | Payments on-call |
| SEV3 | Degraded non-critical feature (refund emails, receipts) | Next business day | Ticket only |

## First response for SEV1

1. Acknowledge the page in PagerDuty within 5 minutes.
2. Open an incident channel named #inc-payments-<date>.
3. Check the PSP status dashboard. If the primary PSP (Stripe) error rate is above 10%, fail over to the secondary PSP (Adyen) with the feature flag `payments.psp.failover`.
4. Post a status update every 30 minutes until resolved.

## Rollback

Deployments to the payments service are rolled back with `deployctl rollback payments --to previous`. Rollback takes about 4 minutes and does not require approval during a SEV1.

## Post-incident

A blameless postmortem is due within 5 business days for every SEV1 and SEV2.
