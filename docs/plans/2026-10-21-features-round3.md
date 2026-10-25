# Features round 3 — "a phone that never fills up" (2026-10-21)

At most ONE helper agent. Everything on-device. No new libraries. Nothing is deleted without Android's own
dialog; every new action is on the "ladder of regret" (most reversible first).

| # | Feature | How | Owner |
|---|---------|-----|-------|
| 1 | Storage floor: "Keep at least N GB free" | Setting (off/5/10/15/20 GB). Nightly job measures the gap and sizes its shrink batch to close it; morning notification says what to clear to get back above the floor (trash only frees space when it empties, so the app is honest about that). | agent |
| 2 | Ladder of regret + "Make room" | Pure planner: given a target, pick actions rung by rung (shrink → light copy of old photos → expired items → suggestions) until the target is met. "Make room" screen shows the plan per rung with toggles, then runs rung 1–2 as a batch and opens Quick review for the rest. | agent |
| 3 | Trip mode + low-space alert | "I need N GB by <date>": a one-off job the night before runs the ladder. Separately, when free space drops under 2 GB, one notification a day: "Running low. Make room now?" | agent (trip), me (alert) |
| 4 | Photos that cool with age | Global default: lighter copies for photos older than 1/2/3 years (off by default), favourites never; per-album rules still win. | agent |
| 5 | Contact sheets for bursts | In Duplicates, a burst group gets "Keep best + contact sheet": a single grid image of every shot is saved, then the other shots go to the trash. | me |
| 6 | Archive map | Every file moved to a computer is remembered with a tiny thumbnail, its month folder and which computer; "Moved to computer" screen lists them by month. | me |
| 7 | Expiry dates that read themselves | Tickets get an expiry from the travel date printed on them; OTP and verification-code screenshots expire after a day. They land in the existing "Expired" list. | me |
| 8 | Chat-media diet | Screen showing space per chat app folder and month, with "Review chat media older than N months". | me |
| 9 | Five questions setup | First-run (and Settings) card with five yes/no questions that become standing rules: pre-selection in Quick review and a weekly "ready to clear" nudge. | me |
| 10 | Regret button | "Recent changes": everything GallerySlim changed in the last 30 days (shrinks, clean-ups, moves) with Undo on each row, from one history log. | me |
| 11 | Honest numbers | Recent changes and Your month show "freed now" vs "waiting in trash" separately. | me |

## Status
All 11 built; build passes; unit tests pass. Trips only make light copies when the user turned on cooling
(the unattended run never makes surprise light copies). Not yet tested on the phone.
