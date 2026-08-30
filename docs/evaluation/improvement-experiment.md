# Improving attribution: a pre-registered ablation

Written 2026-08-18, **before** any of the interventions below were implemented. The point
of writing it first is that every item here is a knob that could be turned until the
number improves. Fixing the protocol, the metrics and the decision rules in advance is
what separates measuring an improvement from manufacturing one.

## Baseline being improved on

From [`results-celeb-df.md`](results-celeb-df.md), Celeb-DF v2 official test split, 340
videos, 52 identities, threshold 0.2524:

```
own-identity match rate  : 0.9882
rank-1 identification    : 0.5824
mean margin own - other  : 0.0397
```

Detection is near ceiling. Attribution is not. The target is rank-1.

## Metrics and decision rules, fixed in advance

**Primary:** rank-1 identification rate, video-weighted.

**Guardrail:** own-identity match rate must not fall below **0.98**. An intervention that
buys rank-1 by sacrificing detection is a regression, not an improvement. The system's
first job is to notice the imagery at all.

**Diagnostic:** mean margin (own − best other). This explains *why* something worked, and
distinguishes a genuine increase in separation from a reshuffling of ranks.

**Decision rule:** an intervention is adopted if it improves dev-set rank-1 by **≥ 2
percentage points** while holding the guardrail. Below that it is reported as no effect.
The threshold is set now, not after seeing results.

## The multiple-comparisons problem and how it is handled

Four interventions evaluated against the same 340 test videos, keeping whichever wins,
would inflate the final number by an unknown amount. With 52 identities, some
configuration will look better by chance alone.

Celeb-DF's official split exists to prevent exactly this:

- **Development set:** the 5299 Celeb-synthesis videos **not** in
  `List_of_testing_videos.txt`. All tuning, all sweeps, all failed ideas happen here. A
  fixed random sample of 500 is used for iteration speed, drawn once with a recorded seed
  and never redrawn.
- **Test set:** the 340 videos of the official split. Touched **once per final
  configuration**, at the end. Not used to choose anything.

Galleries for the development set are built the same way as for the test set, from
`Celeb-real` videos absent from the official split, so the two never share material.

If the test-set result comes in materially below the dev-set result, that gap is itself
reportable: it measures how much of the tuning was overfitting.

## What stays fixed

Changing these would make results incomparable with the baseline:

- Corpus, official split definition, and the identity-direction convention (fake belongs
  to the second id, see [`deepfake-datasets.md`](deepfake-datasets.md)).
- Threshold derived from LFW at FAR 1e-3 in the same run, never hardcoded.
- Gallery material drawn only from `Celeb-real`, never from synthesised video, and never
  from a real video in the official split.
- Detector, aligner, embedder, and their weights (pinned in `models.sha256`).
- 0.5 s video sampling interval.

## Experiments, in order

Ordered by expected payoff per unit of work. Each is run and reported independently
against the baseline, then the surviving set is combined.

### E1: Score normalisation

**Hypothesis.** Raw cosines are not comparable across identities. Some galleries are
"hubs" that score high against everything. The worst observed best-other mean was 0.6092,
against a corpus mean of 0.4145. A score of 0.50 means something different depending on
what else that probe scores against. Normalising per probe should recover rank ordering
without changing any embedding.

**Method.** For each probe, compute scores against all N galleries, then report each as a
z-score against that distribution: `(s − μ) / σ`. Rank on the normalised value. Compare
against a second variant that normalises per *gallery* instead, over the dev set, which
targets hub identities directly.

**Why first.** It attacks the measured failure mode, meaning compression and hubness, needs no
new data or models, and is a scoring-time change that cannot affect the match rate at all
if the raw score is retained for the threshold decision. Note that explicitly: the
threshold decision must continue to use the **raw** cosine, so the guardrail is
structurally protected.

**Predicted effect.** Largest single gain of the four. If it does not help, the hubness
explanation for the 42% failure is wrong and that is worth knowing.

### E2: Gallery aggregation

**Hypothesis.** Scoring currently takes the `max` cosine over gallery images, which is
maximally sensitive to a single atypical frame. A more robust aggregate should raise
rank-1.

**Method.** Compare four aggregators on the dev set: `max` (baseline), `mean`, mean of
top-3, and a single gallery centroid via `Embedding.mean` (which renormalises). Report all
four whether or not any wins.

**Note.** The centroid variant is what most deployed face-recognition systems use, so a
null result here is informative about how unusual the deepfake regime is.

### E3: Gallery size and diversity sweep

**Hypothesis.** Effective gallery size is smaller than it appears. Twelve frames sampled
3 s apart from four videos are highly redundant; the number of genuinely independent views
may be closer to four. If the rank-1 curve is still rising at the largest size tested,
gallery construction, not the method, bounds the baseline.

**Method.** Sweep gallery size at 3, 12, 30 and 60 frames, drawing from as many *distinct*
real videos as available (up to 10 per identity) rather than more frames from the same
clip. Hold frames-per-video low and video count high, since diversity is the hypothesis
under test, not raw count. Plot rank-1 against size.

**Value even if flat.** A flat curve would show the baseline is not gallery-limited, which
strengthens rather than weakens the headline finding.

### E4: Track selection and quality gating

**Hypothesis.** The best track over an entire video currently wins, and `QualityGate` is
not applied to video frames at all. Blurry, tiny or heavily-turned faces are embedded and
can win the max, adding noise.

**Method.** Two variants: apply the existing quality gate to sampled frames before
embedding; and require a minimum track length before a track is eligible. Report the
proportion of frames and tracks discarded, since an intervention that improves rank-1 by
discarding most of the data has a cost the rank-1 number does not show.

**Interaction risk.** Aggressive gating may reduce the number of scorable videos, which
would show up in the guardrail. Report scorable-video count alongside.

## Combination and final run

After the four are measured independently on dev, combine every intervention that met the
decision rule and measure the combination on dev. Interventions can interact, since E1 and E2
both touch scoring, so the combination is measured, never assumed additive.

Then, once, run the final configuration against the 340-video official test split and
report:

- the full ablation table including every intervention that did **not** help;
- baseline and final numbers on the test split;
- the dev-to-test gap, as an estimate of residual overfitting;
- the decision rule and this document's write date, so a reader can verify the metrics
  were fixed before the results were seen.

## Results so far

### E1: Score normalisation (not adopted)

Dev split, 500 videos, 54 identities, one scoring pass reported under all three modes:

| Mode | Match rate | Rank-1 | Δ rank-1 |
|---|---|---|---|
| Raw (baseline) | 0.9800 | 0.5100 | n/a |
| ProbeZ | 0.9800 | 0.5100 | 0.0000 |
| GalleryZ | 0.9800 | 0.5240 | +1.4 pts |

GalleryZ fell short of the pre-registered 2-point bar and is recorded as no effect. It
does work where predicted (id0 0.50 to 0.70, id1 0.83 to 1.00, id16 0.60 to 0.90), so
hubness is real but not dominant.

ProbeZ produced an exactly zero difference, as the maths requires: per-probe z-scoring is
a monotonic transform of that probe's scores and cannot reorder them. It was proposed here
as the primary intervention and is incapable of moving the metric; it is retained as a
negative control, with a test asserting the invariance.

Match rate was identical across all three modes, confirming the guardrail holds by
construction.

### Redirection after error analysis

[`target-leakage.md`](target-leakage.md) establishes that 88.2% of misrankings are won by
the target identity, against 1.9% chance. E2 to E4 all assume the correct identity's
signal is present but obscured; here a competing identity is genuinely present in the
imagery. They remain worth running as pre-registered, and their null results are
informative, but expectations should be small and the larger opportunity is the
top-two-proximity signal described in that document.

### E2, E3, E4: not run (decision recorded 2026-08-19)

Gallery aggregation, the gallery size sweep and track quality gating were pre-registered
but abandoned before implementation.

**Rationale.** All three assume the correct identity's signal is present but obscured, by
an unlucky gallery frame, by too few independent views, by a noisy track. Error analysis
established that the failure is not obscured signal but a *competing identity genuinely
present in the imagery*: 92.3% of test-split misrankings are won by the target, against
2.0% chance, and 85.9% of misses sit at exactly rank 2. Nothing about how scores are
aggregated or which frames are gated removes a real face that is really in the video.

**This is a judgement call and it carries a cost.** Abandoning pre-registered experiments
after seeing results is normally a warning sign, because it is how inconvenient outcomes
get buried. Three things bound that risk here, and a reader should weigh them:

- The abandoned experiments are not ones whose results would have been unwelcome. E1, the
  one intervention that *was* run, produced a null result and is reported as such. Nothing
  is being suppressed for being negative.
- The mechanism was identified by measurement, not asserted, and predicts small effects
  for all three specifically.
- The experiments remain fully specified above. Anyone who disagrees can run them; the
  harness supports each, and the dev split and score cache are in place.

**What cannot be claimed.** That E2 to E4 would have failed. They were not run, so their
effect is unknown, and the write-up should say "not run" rather than "did not help".

## What would count as a negative result

If none of the four moves dev rank-1 by 2 points, the conclusion is that face-swap
attenuation is intrinsic to the embedding rather than an artefact of how galleries are
built or scored. That is a stronger and more interesting claim than the current
baseline. It would say the 0.0397 margin is a property of the swapped imagery, not of our
pipeline, and it must be reported with the same prominence as a positive result.

## Implementation notes

- E1 and E2 are changes to `DeepfakeProtocol.scoreTracks` and the aggregation around it.
  Both should be selectable at run time rather than replacing the baseline path, so the
  ablation table can be produced from one binary.
- E3 is a change to `scripts/prepare-celeb-df.sh` (gallery construction), parameterised on
  frames-per-video and videos-per-identity.
- E4 touches the video path in `DeepfakeProtocol.trackEmbeddings` and reuses
  `QualityGate.assess` unchanged.
- A dev-set preparation mode is needed: the same reshaping keyed on videos **not** in the
  official split, with the 500-video sample drawn from a recorded seed.
- Each experiment writes its raw output to `docs/evaluation/runs/`, as the baseline did.
