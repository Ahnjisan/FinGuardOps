# Issue #380 local E2E evidence (2026-10-10 UTC)

This run used only synthetic customers and transactions. The unique Compose project was
`finguardops-380-i380mv1nb67s`. The Backend, FastAPI, PostgreSQL, External Risk mock,
and JWT fixture were real connected local services. Each successful stage below exited
with code 0. A stage that failed is listed separately and was not counted as a pass.

| Stage | Exit | Transaction ID | Detection result ID | Case ID | Observation |
| --- | ---: | --- | --- | --- | --- |
| Public behavior intake | 0 | `f43c4835-2d24-41e2-83a3-dcae5d346967` | — | — | 12 unique preceding events; duplicate event returned 200 without a new row |
| Public transaction intake | 0 | `f43c4835-2d24-41e2-83a3-dcae5d346967` | `649a1f15-674c-45b9-8651-a5195a059b5d` | `0258ce02-6949-4032-8c27-3cf2c76cbf10` | External Risk, Rule v2, ML call deltas each 1; HELD |
| Public transaction, case, adopted detection detail plus DB | 0 | `f43c4835-2d24-41e2-83a3-dcae5d346967` | `649a1f15-674c-45b9-8651-a5195a059b5d` | `0258ce02-6949-4032-8c27-3cf2c76cbf10` | Rule 85 + ML 35, capped final 100 CRITICAL; RULE/ML Evidence 4/1, Audit 4; cutoff, model, feature, policy, SHA matched DB/API |
| Same-key same-body replay and different-body conflict | 0 | `f43c4835-2d24-41e2-83a3-dcae5d346967` | same | same | 201/409; no external calls or new transaction, detection, case, Audit rows |
| Four concurrent completed replays | 0 | `f43c4835-2d24-41e2-83a3-dcae5d346967` | same | same | Four 201 responses, no external calls or new rows |
| Event after transaction cutoff | 0 | `f43c4835-2d24-41e2-83a3-dcae5d346967` | same | same | Late event did not change adopted result |
| Wrong model hash, real fault service path | 0 | `c9abd926-1a46-47e7-86e2-b75d61536821` | failed analysis row | none | Public 503 DEPENDENCY_UNAVAILABLE; transaction/detection/idempotency FAILED with ML_MODEL_HASH_MISMATCH; no adopted score, Evidence, case, risk response or Audit; replay made zero calls |
| Historical cutoff after model replacement | 0 | `245df96b-4eec-4f04-ae1b-dc3b25aad6d4` | `3100fd9b-98bd-4245-90f5-ec7d27d94b27` | `a20a663e-d34b-4ad0-bbc9-6f5edb45b7b5` | Cutoff selected immutable v1 model/hash; old completed replay made zero calls |

The final normal row used `fraud-logistic-v2`, `fraud-feature-v1`,
`rule-ml-policy-v1`, SHA-256
`42344d398008babdd6a0404c750b24f1f27f1260aeac65851195d81500a876af`.
The historical cutoff row used v1 SHA-256
`93a91797f6a652a08871191e87385ced6edd1a560abd6ee9b2782411bb19c286`.

One later diagnostic invocation failed before completing its transaction check because
the previous fault overlay still directed Backend ML traffic to the wrong model. Its
transaction `ae9d5abb-4cac-4c9d-9686-77dcc8ba2b98` became FAILED. A second
diagnostic invocation failed at behavior intake while the Backend network namespace
was being recreated. Neither was counted as a normal E2E pass. The Backend and both
network-sharing fixtures were then recreated with the normal overlay; the complete
`f43c4835-…` run above passed.

Unit, integration, frontend, lint, and build results are recorded in the task report.
The official Keycloak Browser Gate was not run: its documented prerequisite is a
clean committed worktree, and this task keeps all changes uncommitted.

Resource inventory at the final normal run: host free RAM 1,466,300 KiB, Docker
allocation 8,161,280,000 bytes, C: free 97,977,569,280 bytes. Lowest observed host
free RAM during the run was 1,384,192 KiB. Owned containers had no OOMKilled flag
or service restart. The PostgreSQL anonymous volume
`f121bb0d58f4c9b12d8ca255985765703062245022d7526f199be1e7e5e4a2aa`
held 12 synthetic transactions, 12 detections, 30 Evidence, 6 cases, 24 Audit,
and 77 behavior events before cleanup. Its data is retained for a preservation decision.
