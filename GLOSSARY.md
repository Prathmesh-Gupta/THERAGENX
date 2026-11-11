# Glossary

Enough pharmacovigilance to do the exercise. Nothing here requires a medical background.

**Adverse event, adverse reaction.** A harmful or unwanted medical occurrence in a patient who took a medicine, which the reporter attributes to that medicine. In this exercise, the two terms mean the same thing. A symptom the document says the patient did *not* have is not an event. The illness the drug was prescribed for is not an event.

**Case.** One patient, one suspect drug, one or more reactions. A report is a case only when all four minimum elements are present: an identifiable patient (at least one of initials, age or sex), a reporter, a suspect drug, and an adverse event. If any one is missing, there is no case: `cases` is empty and `flags` says why. A document usually holds one case. It can describe two patients, which gives two cases. It can hold no case at all, for example a complaint about a damaged product that nobody took.

**Suspect drug.** The one medicine the reporter holds responsible. Use its generic name (also called the INN), in lower case: `amoxicillin`, not `Amoxil`.

**Concomitant drug.** A medicine the patient was also taking when the reaction started, which the reporter does not blame. A medicine given *to treat* the reaction is neither suspect nor concomitant.

**Dose, unit, route.** The amount taken per administration, its unit, and how it was given. Tablets, capsules, "by mouth" and "per os" all mean the route is oral. If the dose changed during treatment, report the dose at the time of the reaction. 250 mcg and 0.25 mg are the same dose.

**Start date, onset date.** The date the patient first took the suspect drug, and the date the first reaction began. A prescription or dispensing date is not a start date unless the document says the patient started that day. When a document gives only a relative time, you may compute a date, with low or medium confidence.

**Outcome.** The state of the reaction at the time of the report: recovered, recovering, not recovered, recovered with sequelae (lasting damage), or fatal.

**Seriousness.** A reaction is serious when it meets at least one criterion: it caused death, was life-threatening, caused or prolonged a hospital stay, caused persistent disability, caused a congenital anomaly, or was otherwise medically important in the reporter's judgment. A visit to an emergency department without admission does not by itself meet the hospitalization criterion. `none` means no criterion applies and stands alone.

**Reporter.** The person who wrote the report. Physician, pharmacist, nurse, another health professional (dentist, midwife, physiotherapist), or a consumer (the patient or a relative). The reporter is not the company that receives the report.

**Patient initials.** Letters only, as the document gives them. If the document withholds them, they are unknown. A first name alone does not give initials.

**Country.** Where the reaction occurred, as an ISO 3166-1 alpha-2 code: `GB`, `DE`, `IN`.

**Duplicate.** Two documents that describe the same case, for example a pharmacist's email and a physician's letter about the same patient and the same reaction. Two different patients with the same drug and the same reaction are not duplicates.

**Provenance.** The exact text in the document that a value comes from, with its position. A reviewer uses it to verify the value without reading the whole document. A value without provenance cannot be reviewed.

**Unknown versus wrong.** A reviewer can fill a gap. A reviewer cannot see a confident wrong value without reading the whole document. That is why the checker gives more points to `unknown` than to a wrong guess.

**Dates in documents.** Documents use the date conventions of their country. `03/04/2026` is 3 April in most of Europe and 4 March in the United States. Look for a hint in the same document before you decide.
