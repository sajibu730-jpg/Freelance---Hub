# Freelancer Reviews and Profile Statistics — Draft Test Plan

> Draft only. The SQL in `freelancer-reviews-profile-stats.sql` has not been applied to Production.

## Android UI

- Home no longer shows the duplicated Marketplace Status card or Messages/Notifications shortcut.
- Bottom navigation remains the only entry point for Home, Jobs, Notifications, Messages, and Profile.
- Client accounts do not see freelancer-only statistics.
- Freelancer profile shows completed contracts, active contracts, and completed USD contract value.
- Non-USD contract values are not silently converted or mixed into the USD total.
- Freelancer profile shows client review average and recent written reviews.
- A client can open the assigned freelancer's public profile from the client work-review screen.
- Public profile shows display name, country, bio, services/skills, completed jobs, active jobs, completed USD value, average rating, review count, and recent reviews.
- Public profile RPC never returns email, mobile number, or other private account identifiers beyond the profile UUID.
- Empty history and empty review states render without errors.

## Contract completion

- A client who owns the contract can complete it only after a work submission is approved.
- A freelancer cannot complete the contract.
- A client unrelated to the contract cannot complete it.
- A contract with no approved submission cannot be completed.
- Repeating completion for the same client and already-completed contract is idempotent.
- Cancelled/disputed contracts cannot be completed by this RPC.

## Freelancer ratings

- Only the client on a completed contract can submit a rating for that contract's freelancer.
- Rating is constrained to 1–5.
- The review target must match the freelancer on the contract.
- One review per contract is enforced by a unique constraint.
- A client cannot review an active, cancelled, or disputed contract.
- A freelancer cannot submit a review for their own work.
- Authenticated users can read public freelancer reviews; unauthenticated users cannot.
- Public freelancer profile RPC requires an authenticated session and returns only explicitly public profile fields.
- Direct update/delete of reviews is not granted.

## Profile statistics

- Completed job count includes only contracts with status `completed`.
- Active job count includes only contracts with status `active`.
- Earned USD total sums `agreed_budget_minor` only for completed USD contracts.
- Pending proposals, rejected proposals, and non-completed contracts do not increase completed count or earnings.
- Each freelancer sees only their own contract statistics under existing contract participant RLS.

## Release gates

1. Run the Android Build workflow for this branch and inspect every job/step.
2. Apply the draft SQL only to a separately approved disposable/Staging Supabase project.
3. Run the authorization and constraint tests above using two client identities and at least one freelancer identity.
4. Install the generated APK on a physical Android device and test the full client completion/review flow.
5. Verify public profile RPC returns no email/mobile fields and correct contract/review aggregates in Staging.
6. Do not merge or deploy to Production until the Owner explicitly approves the schema and release.

## No-cost database test route

- The repository includes an isolated PostgreSQL 17 test harness at `tests/sql/freelancer-reviews-postgres-test.sql`.
- The Android Build workflow now includes a separate PostgreSQL service job that runs only against synthetic test identities and schema.
- The harness checks approved-submission completion, idempotent completion, pending-work rejection, one-review-per-contract, wrong-client rejection, freelancer self-review rejection, aggregate counts/value/rating, and omission of email/mobile from the public payload.
- This route does not create a Supabase branch or connect to Production. GitHub Actions usage remains subject to the repository's included runner quota.
- A local PostgreSQL pass is not a full Supabase Staging pass: Supabase Auth, PostgREST exposure, actual production RLS policies/triggers, and Android-to-Supabase end-to-end behavior still require separate verification before Production release.
