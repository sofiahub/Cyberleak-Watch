# Deepfake dataset access

The match-rate experiment needs manipulated videos with known source identities.
Both standard datasets require an access request, and turnaround is measured in
days to weeks, so **submit these before starting implementation**. They are the
longest-lead item in the project.

### Celeb-DF (v2)

Real and synthesised videos of celebrities, with source and target identity recoverable
from the filenames. This is the primary corpus for the experiment.

Request via the Google form linked from `yuezunli/celeb-deepfakeforensics` at <https://forms.gle/2jYBby6y1FBU3u6q9>. A Tencent mirror is available at <https://wj.qq.com/s2/8540155/b5d9/>. The download link is sent once the form is accepted. Questions: deepfakeforensics@gmail.com

### FaceForensics++

Several manipulation methods over the same source videos, which is what allows the
writeup to report match rate broken down by generator rather than as one number.

Request via the Google form linked from `ondyari/FaceForensics`:
<https://docs.google.com/forms/d/e/1FAIpQLSdRRR3L5zAv6tQ_CKxmK4W96tAab_pfBu2EKAgQbeDVhmXagg/viewform>.
Once accepted they send a link to a download script rather than the data directly.
Questions: faceforensics@googlegroups.com

Their README notes that if no response arrives within a week, the reply is probably
bouncing, so use an address that reliably accepts external mail, and check spam. A
university address is the sensible choice for an academic request in any case.

### Before requesting

Confirm whether the university requires ethics approval for use of these datasets. It is
usually straightforward for public research corpora, but it can gate access and is
cheaper to resolve before the data arrives than after. Both datasets carry terms of use
that restrict redistribution: the downloads must stay off this repository and off any
shared drive that is not covered by the agreement.

### Choosing what to download

FaceForensics++ offers raw, c23 and c40 compressions across several methods. The raw set
runs to terabytes. Take **c23** (the standard "high quality" setting used in most reported
results) and only the methods being compared; that keeps the download to a manageable size
and remains comparable to published work. Celeb-DF v2 is smaller, so take `Celeb-real`,
`Celeb-synthesis` and `YouTube-real`.

`Celeb-real` is the natural source of gallery images: those are genuine recordings of the
subject, so frames from them are real photographs of that person. `Celeb-synthesis` is the
manipulated set and must never supply gallery images. See the warning below.

## Celeb-DF filename convention: determined empirically

`Celeb-synthesis` files are named `id{A}_id{B}_{NNNN}.mp4`. Neither the repository README
nor the paper abstract documents which id is which, and the ordering is easy to read
backwards, so it was established structurally over all 5639 synthesis files:

    Celeb-real/id{A}_{NNNN}.mp4 exists : 5639 / 5639  (100%)
    Celeb-real/id{B}_{NNNN}.mp4 exists : 5490 / 5639  (97.4%, coincidental)
    files where only A's real video exists : 149
    files where only B's real video exists : 0

Sequence numbers repeat across identities, so B's real video usually exists for unrelated
reasons, so that 97.4% is noise. The signal is the 149-to-0 asymmetry among the cases that
can discriminate.

**Therefore: A is the TARGET.** Its real video is the footage that was manipulated.
**B supplied the FACE** that appears in the synthesised output.

So a synthesised video belongs to identity **B** for the purposes of this experiment: B is
the person being impersonated, and B is who a likeness matcher should flag. Its gallery is
built from `Celeb-real/id{B}_*.mp4`.

Filing fakes under A instead would measure whether a swap resembles the person it
*replaced* rather than the person it *impersonates*. That produces a near-zero match rate
which reads as a substantive finding, namely that identity does not survive face swapping, when
it is only a labelling error. Do not reverse this without re-running the check.

This test uses filenames alone, deliberately: determining the direction by embedding faces
and seeing which gallery scores higher would be circular, since whether a swapped face
embeds near an identity is precisely what the experiment sets out to measure.

## Preparing the data

Arrange the downloads as:

    data/deepfake/<identity-name>/gallery/*.jpg   real photographs
    data/deepfake/<identity-name>/fake/*.mp4      manipulated videos of that person

Gallery images must be *real photographs of the source identity*, not frames taken
from the manipulated videos. Using frames from the fakes as the gallery measures
whether the system can match a video to itself, which is not the question.

## Interpreting the result

The reported figure is the fraction of manipulated videos whose best track score
exceeds the operating threshold derived from LFW at FAR = 1e-3 (measured: 0.2524).

FAR = 1e-3 rather than the 1e-4 originally planned, because a false-accept rate can
be resolved no more finely than one impostor pair out of the total, and LFW's 6000-pair
protocol supplies only 3000 impostor pairs, a floor of 1/3000 = 3.33e-4. Resolving
1e-4 would need at least 10,000 impostor pairs, so an all-pairs (BLUFR-style) impostor
set over LFW's 13,233 images is the route to that operating point if it is wanted.

A high match rate supports the project's premise. A low one is a more interesting
finding, not a failure: it would show that identity-based detection degrades against
synthetic media, which is a substantive result worth reporting and a direction for
further work. Report the number either way, broken down by generator where
FaceForensics++ makes that possible.
