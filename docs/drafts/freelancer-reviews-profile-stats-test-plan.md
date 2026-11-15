# Freelancer Reviews and Profile Statistics — Draft Test Plan

> Draft only. The SQL in `freelancer-reviews-profile-stats.sql` has not been applied to Production.

## Android UI

- Home no longer shows the duplicated Marketplace Status card or Messages/Notifications shortcut.
- Bottom navigation remains the only entry point for Home, Jobs, Notifications, Messages, and Profile.
- Client accounts do not see freelancer-only statistics.
- Freelancer profile shows completed contracts, active contracts, and completed USD contract value.
- Non-USD contract values are not silently converted or mixed into the USD total.
- Freelancer profile shows client review average and recent written reviews.
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
5. Do not merge or deploy to Production until the Owner explicitly approves the schema and release.
