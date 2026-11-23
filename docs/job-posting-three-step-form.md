# Three-step Job Posting Form

## Step 1 — Basic Information
- Job Title
- Budget (USD)
- Skills (comma-separated)
- Description

## Step 2 — Job Requirements
- Category
- Experience Level: Beginner, Intermediate, Expert
- Project Duration / Deadline: number of days (1–3650)
- Number of Freelancers Needed (1–100)

## Step 3 — Review & Publish
- Preview all submitted fields
- Edit basic information or requirements
- Confirm & Publish

The Android form sends the four additional requirements fields with the existing job payload. The matching database migration adds validated columns to `public.jobs`. The migration is committed for review and is not applied to Production by this change.
