package ncii.enrolment

import ncii.core.Embedding
import ncii.store.EnrolmentOutcome

enum EnrolmentResult:
  case Accepted(selected: Seq[Embedding])
  case Rejected(outcome: EnrolmentOutcome, reason: String)
