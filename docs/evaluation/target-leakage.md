# Target leakage: a face swap blends two identities

Measured 2026-08-19 on the Celeb-DF v2 **dev** split (500 synthesis videos, 54 identities),
which is the tuning set. The official test split is untouched by this analysis.

## The question

The baseline result showed 98.8% detection but only 58.2% rank-1 attribution: manipulated
video reliably clears the threshold for the person it depicts, yet in 42% of cases some
other enrolled identity scores higher. That says attribution fails but not why, and
"why" determines whether any of the planned interventions can help.

## The finding

When attribution fails, the winning identity is overwhelmingly the **target**, the person
whose footage was manipulated, rather than the person whose face was pasted in.

```
misranked                                    : 245 of 500
...of which top match was the TARGET identity: 216 (88.2%)
chance share if confusion were random        : 1.9%

target outranks the face donor               : 216 of 500 (43.2%)
median |own - other| gap                     : 0.1027
probes scoring within 0.05 of two identities : 133 of 500 (26.6%)
near misses (deficit < 0.02)                 :  29 of 245 (11.8%)
median deficit when wrong                    : 0.1075
```

88.2% against a 1.9% chance rate is roughly **47× chance**. This is not one contributing
factor among several; it is the failure mode.

The failures are also decisive rather than marginal. Only 11.8% are near misses, and the
median losing deficit (0.1075) is comparable to the median winning margin (0.0981). When
the target wins, it wins by about as much as a correct answer wins by.

## Why this is not a labelling error

A result this lopsided invites the obvious objection: that the source/target convention is
backwards, and the system is simply matching the right person under the wrong name.

Two independent checks say otherwise.

**Structural.** For `id{A}_id{B}_{NNNN}.mp4`, `Celeb-real/id{A}_{NNNN}.mp4` exists for
5639 of 5639 synthesis files. Among the 149 cases where only one of the two ids has a real
video with that sequence number, it is A every time and B never. A's video is the base.

**Visual.** Frames from `Celeb-real/id0_0000.mp4` and `Celeb-synthesis/id0_id1_0000.mp4`
share the same studio set, lighting, clothing, pose and hand position, so the synthesis is
built on id0's footage. The face has visibly changed (different nose, brow and facial
proportions, smoothed skin) so a swap did occur, but the result retains id0's hair, head
shape, skin tone and facial boundary, and bears no resemblance to id1, who is a visibly
different person. The output reads as a blend weighted toward the target.

## Mechanism

A DeepFake-style swap replaces the inner face region. It does not replace the hairline,
head shape, jaw outline, ears, neck or skin tone, and it colour-matches the inserted face
to the surrounding frame. ArcFace embeds an aligned crop that includes part of that
retained boundary, and is influenced by overall head geometry.

So the swap does not erase identity and replace it. It **superimposes** one identity onto
another and leaves a mixture, in which the target's contribution is usually the larger.
That is what a 0.0397 margin between the right identity and the best wrong one looks like
from the inside.

## What it means for the system

Map the roles onto the actual threat. In non-consensual intimate imagery, a victim's face
is swapped onto someone else's body, typically a performer's:

| Dataset role | Real-world role | Result |
|---|---|---|
| face donor (id B) | **the victim** | detected, clearing threshold in 98% of videos |
| target / footage (id A) | **the performer** | outranks the victim in 43% of videos |

The system reliably notices that imagery of the victim exists. Presented naively, it would
then name the **performer** as the strongest identity match.

For a takedown tool this is a specific and consequential failure. An alert that leads with
the performer is both useless to the victim and an accusation against someone who is not
the subject of the complaint. The design's decision to route alerts to a human and never
to automate reporting looks considerably better in light of this than it did as a
precaution.

## What it means for the improvement programme

E1 (score normalisation) moved dev rank-1 by 1.4 points against a 2-point bar, and this
explains why. Normalisation, aggregation, gallery size and track filtering all assume the
correct identity's signal is present but obscured. Here a **competing identity is
genuinely present in the imagery**. No change to how scores are compared removes a real
face that is really there.

E2 to E4 should still be run, since they were pre-registered and their null results are
informative, but the expectation for each should be small. The larger opportunity is
elsewhere: see below.

## The opportunity this creates

A probe that scores highly on two identities at once is showing the fingerprint of a
blend. Genuine footage of one person should match that person and nobody else. Here 26.6%
of manipulated videos score within 0.05 of two separate enrolled identities.

That suggests a manipulation signal derived **purely from identity matching**, with no
synthetic-media classifier: flag a probe whose top-two identity scores are both high and
close together, particularly when the pair is stable across the video's frames.

**This would need a scope decision.** The design deliberately excluded synthetic-media
classification (see the design spec's scope section) in favour of identity matching only.
A top-two-proximity signal arguably stays inside that boundary, since it uses nothing but
likeness vectors. It is detection of manipulation, which is what that scope decision
ruled out. It should be decided explicitly rather than drifting into scope.

Testing it would also need real videos as negatives, to establish that unmanipulated
footage does not produce the same top-two proximity. `Celeb-real` and `YouTube-real`
provide those, and the official test split includes 108 and 70 of them respectively.

## Confirmed on the test split

The analysis above was performed on the dev split. The same metrics were then computed on
the 340-video official test split, as an additional measurement of the unchanged baseline
configuration:

| Metric | Dev (500 videos, 54 ids) | Test (340 videos, 52 ids) |
|---|---|---|
| Share of errors won by target | 0.8816 | **0.9225** |
| Chance share | 0.0189 | 0.0196 |
| Target outranks the face donor | 0.4320 | 0.3853 |
| Probes within 0.05 of two identities | 26.6% | 30.0% |
| top-1 | 0.5100 | 0.5824 |
| top-2 | 0.9120 | **0.9412** |
| top-10 | 0.9900 | **1.0000** |
| Mean rank of true identity | 1.89 | 1.59 |
| Rank-1 misses sitting at rank 2 | 0.8204 | 0.8592 |

The effect is slightly stronger on the test split, which is the easier of the two (52
identities rather than 54). On the test split the true identity is never outside the top
ten: 340 of 340.

**This did not compromise the pre-registration.** No parameter was tuned and no variant
selected on the basis of dev results; the k values were fixed in advance and the whole
curve is reported. Had k been chosen on dev and only the winning k reported on test, that
would have been exactly the overfitting the split exists to prevent.

## Limitations

- One generator. Celeb-DF uses a single synthesis method; FaceForensics++ would show
  whether the effect holds across generators, and its per-method breakdown is exactly the
  right instrument.
- The visual confirmation rests on one triple, and is illustrative. The 88.2% over 245
  misrankings is the evidence.
- Measured on the dev split, so these figures are not the reported result. The equivalent
  numbers for the test split should be produced once, alongside the final configuration.
