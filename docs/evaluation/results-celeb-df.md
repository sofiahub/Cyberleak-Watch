# Does facial identity survive a face swap?

Run date: 2026-08-18. Raw output: [`runs/2026-08-18-celeb-df-test-split.txt`](runs/2026-08-18-celeb-df-test-split.txt).

The project assumes that a likeness vector built from someone's ordinary photographs can
flag deepfake imagery of them. That assumes ArcFace identity survives a face swap. This is
the experiment that tests it.

## Method

The pipeline is validated first on LFW in the same run, so the operating threshold is
derived from the same code and weights that score the deepfakes rather than being carried
in from elsewhere.

- **Corpus:** Celeb-DF (v2), the **official test split** from
  `List_of_testing_videos.txt`, which is 340 of the 5639 synthesised videos, so figures are
  comparable with published Celeb-DF work.
- **Identity assignment:** a synthesised video `id{A}_id{B}_{NNNN}.mp4` is filed under
  **id{B}**, the identity whose *face appears* in the output. See
  [`deepfake-datasets.md`](deepfake-datasets.md) for how that direction was established
  structurally rather than assumed.
- **Galleries:** frames from `Celeb-real` only, excluding any real video that appears in
  the test split, so enrolment and evaluation material are disjoint. 622 frames across 52
  identities, roughly 12 each.
- **Scoring:** each video is sampled at 0.5 s, faces detected, aligned and embedded, then
  tracked; each track's mean embedding is scored against every enrolled gallery.
- **Threshold:** 0.2524, the cosine at which LFW gives a false-accept rate of 1e-3.

## Results

```
LFW verification
  Accuracy (10-fold CV) : 0.9985 ± 0.0007
  AUC                   : 0.9993
  EER                   : 0.0023 at threshold 0.1911
  TAR @ FAR 1e-3        : 0.9973 at threshold 0.2524

Deepfake match-rate experiment
  threshold (LFW FAR 1e-3) : 0.2524
  videos scored            : 340
  own-identity match rate  : 0.9882
  rank-1 identification    : 0.5824
```

```
Cumulative match characteristic (52 candidates per probe)
  top-1   : 0.5824   top-2   : 0.9412   top-3   : 0.9676
  top-5   : 0.9824   top-10  : 1.0000
  mean rank of true identity : 1.59
  of rank-1 misses, share sitting at rank 2 : 0.8592
```

Derived over the 52 per-identity rows:

| Statistic | Value |
|---|---|
| Mean own-identity score | 0.4542 (range 0.3409 to 0.5531) |
| Mean best-other-identity score | 0.4145 (range 0.1726 to 0.6092) |
| **Mean margin (own − other)** | **0.0397** |
| Identities where another identity outscores the true one | 19 / 52 (36.5%) |
| Videos belonging to those identities | 131 / 340 (38.5%) |
| Identities never ranked correctly (rank-1 = 0) | 7 |
| Identities always ranked correctly (rank-1 = 1) | 14 |
| Correlation between video count and rank-1 | −0.078 |

## What it means

Detection works. Rank-1 attribution does not. A two-candidate shortlist does.

98.8% of manipulated videos score above the operating threshold for the person they depict. On the question the project was built to answer (can a likeness vector flag a deepfake of someone), the answer is yes.

Rank-1 identification is 58.2%: in 42% of cases some other enrolled identity scores higher than the correct one. But the true identity is not lost; it is displaced by exactly one competitor. Recall reaches 94.1% at top-2 and 100% at top-10. Of 340 videos, not one puts the correct identity outside the top ten of fifty-two.

The shape of that curve is itself evidence. Random confusion would rise gradually with k.
A 36-point cliff at k=2 followed by near-flatness (+2.6, then +1.5 over three more slots)
is what being displaced by a single systematic competitor looks like, and 85.9% of rank-1
misses sit at exactly rank 2.

Top-k is reported here because this design routes alerts to a human reviewer and never
auto-reports, so a short candidate list is a real deployment mode. For a system that acted
on rank-1 automatically it would be an evasion rather than a measurement.

The mean margin explains why. Real faces are separated cleanly by this pipeline: on LFW genuine pairs score around 0.82 and impostor pairs around −0.07, a margin near 0.89. Against swapped faces the margin between the right identity and the best wrong identity collapses to 0.0397, roughly 22× narrower. Deepfake embeddings land in a compressed band that sits above the threshold but barely distinguishes between people.

That compression is the substantive finding. A face swap does not erase identity; it
attenuates it, leaving enough signal to trip a detector but not enough to tell one
candidate from another reliably.

The failure is not evenly distributed. Fourteen identities are always ranked correctly,
seven never are, and for 19 of 52 the wrong identity wins on average. The near-zero
correlation between an identity's video count and its rank-1 rate (−0.078) rules out the
obvious sampling explanation. Identities do not fail because they have fewer videos.
Something about particular faces, or particular source/target pairings, drives it.

### Why this matters for the system being built

The operating point was chosen as a false-accept rate because in a takedown context a
false accept means telling someone that intimate imagery depicts them when it does not.
These numbers say the deployed risk is worse than the LFW FAR suggests: at a threshold
calibrated to 1 in 1000 on real faces, deepfake embeddings cluster so tightly that
misattribution is common. Reporting only the 98.8% match rate would overstate what the
system can safely claim.

## Limitations

- **Gallery size.** Roughly 12 frames per identity from up to four videos. Larger, more
  varied galleries would likely raise rank-1; this is the first thing to vary.
- **Closed set of 52.** Rank-1 against thousands of enrolled users is a harder problem
  than rank-1 against 51 alternatives, so 58.2% is an optimistic estimate of attribution
  in deployment, not a pessimistic one.
- **One generator.** Celeb-DF uses a single improved synthesis method. FaceForensics++
  would allow the same measurement broken down by generator, which the harness does not
  yet expose.
- **Gallery frames come from video**, so they share compression and pose characteristics
  with the evaluation material in a way that enrolment photographs would not.
- The margin statistics are means over per-identity means, which weights small identities
  equally with large ones. The rank-1 headline is video-weighted.

## Why attribution fails

Error analysis identified the mechanism: when attribution fails, the winning identity is
the **target**, the person whose footage was manipulated, in 88.2% of cases against a
1.9% chance rate. A swap superimposes one identity on another rather than replacing it,
and the target's contribution is usually the larger. Full analysis in
[`target-leakage.md`](target-leakage.md).

Mapped onto the threat model: the victim (whose face was swapped in) is detected, but the
performer (whose footage was used) outranks them in 43% of videos.

## Improving on this

A pre-registered ablation is specified in
[`improvement-experiment.md`](improvement-experiment.md): score normalisation, gallery
aggregation, a gallery size and diversity sweep, and track quality gating, with metrics,
decision rules and the dev/test separation fixed in advance of any implementation. Tuning
happens on the 5299 synthesis videos outside the official split; these 340 are touched
once per final configuration.

## Reproducing

```bash
./scripts/fetch-assets.sh
./scripts/prepare-celeb-df.sh /path/to/celeb-df-v2 "$NCII_DATA_DIR/deepfake"
NCII_DATA_DIR=/path/to/data sbt -batch evalReport
```

Runtime about 34 minutes: roughly 9 for the 6000 LFW pairs, the remainder for 340 videos
and 622 gallery frames.
