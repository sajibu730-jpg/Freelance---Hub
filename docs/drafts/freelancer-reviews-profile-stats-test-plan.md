# Freelancer Reviews and Profile Statistics — Draft Test Plan

> Production migration `freelancer_reviews_profile_stats` was applied with Owner approval on 2026-11-16. The follow-up client-FK index migration `index_freelancer_review_client_fk` was also applied. Live authenticated Android-to-PostgREST verification remains pending.

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
- Each privileged RPC (`complete_contract`, `get_public_freelancer_profile`, `get_public_freelancer_reviews`, and `get_my_freelancer_review`) rejects a missing/empty `auth.uid()` even when invoked by a database role with function execution permission.
- Direct update/delete of reviews is not granted.

## Profile statistics

- Completed job count includes only contracts with status `completed`.
- Active job count includes only contracts with status `active`.
- Earned USD total sums `agreed_budget_minor` only for completed USD contracts.
- Pending proposals, rejected proposals, and non-completed contracts do not increase completed count or earnings.
- Each freelancer sees only their own contract statistics under existing contract participant RLS.

## Public profile media

- Profile-photo downloads use the authenticated Storage object endpoint. Before release, verify in a disposable/Staging environment that an authenticated user can read another user's profile-photo object while unauthenticated access and unrelated private objects remain denied.
- The repository does not currently contain a migration that defines the `profile-photos` bucket's cross-user read policy. Do not assume the existing Production bucket permits public-profile reads, and do not broaden Storage access without a separate least-privilege review.
- Cover-photo downloads accept HTTPS URLs only and enforce a 5 MB streamed-download ceiling. Verify valid HTTPS cover images, oversized responses, invalid URLs, and unavailable images fall back without crashing the profile screen.

## Release gates

1. Run the Android Build workflow for this branch and inspect every job/step.
2. Confirm the approved Production migration is callable through PostgREST with an authenticated app session; do not repeat schema deployment.
3. Run the authorization and constraint tests above using two client identities and at least one freelancer identity.
4. Install the generated APK on a physical Android device and test the full client completion/review flow.
5. Verify public profile RPC returns no email/mobile fields and correct contract/review aggregates in Staging.
6. Do not merge the Draft PR or publish a release until the Owner explicitly approves the release.

## No-cost database test route

- The repository includes an isolated PostgreSQL 17 test harness at `tests/sql/freelancer-reviews-postgres-test.sql`.
- The Android Build workflow now includes a separate PostgreSQL service job that runs only against synthetic test identities and schema.
- The harness checks approved-submission completion, idempotent completion, pending-work rejection, one-review-per-contract, wrong-client rejection, freelancer self-review rejection, aggregate counts/value/rating, and omission of email/mobile from the public payload.
- This route does not create a Supabase branch or connect to Production. GitHub Actions usage remains subject to the repository's included runner quota.
- A local PostgreSQL pass is not a full Supabase Staging pass: Supabase Auth, PostgREST exposure, actual production RLS policies/triggers, and Android-to-Supabase end-to-end behavior still require separate verification before Production release.

## Security review gate

- The applied migration exposes four authenticated RPCs implemented as `SECURITY DEFINER`; each explicitly requires `auth.uid()`, revokes execution from `PUBLIC` and `anon`, and grants execution only to `authenticated`.
- Supabase security advisors were run after deployment. They report four new authenticated SECURITY DEFINER findings for these intentionally exposed RPCs; the functions enforce `auth.uid()` and deny `anon`/`PUBLIC`. This design needs a focused least-privilege review before release. Consider moving privileged implementation functions into a non-exposed schema with tightly controlled execution, while keeping only the minimum required RPC surface exposed.
- The advisor also reports pre-existing findings for `public.get_public_profiles()`, `pg_net` in `public`, disabled leaked-password protection, and unrelated RLS/performance notices. Those predate these migrations and were not changed by this work.
