# Case Intake Exercise

A three-day take-home for full-stack engineers who will work with language models.

## In short

You get eight documents as text files. Each one is a report about a patient who had a side effect from a medicine. For each document, ask a language model for the fields listed in the catalog and write one JSON file with the values. Each value carries the quote it came from and the position of that quote in the text. Four documents come with answers, so you can check your own output. Then build a small Spring Boot API and a React screen where a reviewer checks each value against the document.

That is the core. Stretch items are listed near the end. Do them if you have time. They add points, and leaving them out costs none.

## Background

We build software that reads reports about side effects of medicines and turns them into structured cases. A language model does the reading. A person checks every field before the case leaves the system. Two things matter to that person: a wrong value is worse than an empty one, and every value must show where in the document it came from. This exercise is a small version of that work. All documents are made up. There is no real patient in any of them.

Use AI tools such as Claude Code, Cursor or Copilot for the code and the design. We look at the result and at how well you understand it. In the follow-up session you will explain and change the code live.

## What you receive

| Path | What it is |
|---|---|
| `documents/D01.txt` to `D08.txt` | The eight core documents as plain text. Your pipeline reads these. Positions in your output refer to these files. |
| `documents/stretch/S01` to `S04` | Four more documents, for the stretch items. S03 exists only as a PDF. |
| `documents/*.pdf`, `documents/stretch/*.pdf` | Every document as a PDF, for reference. |
| `catalog/field_catalog.json` | The fields to extract: id, label, type, options, validation and guidance for each. Start here. |
| `schema/extraction.schema.json` | The format of the JSON file you write for one document. |
| `schema/EXAMPLE-output-D01.json` | A complete, correct output for D01. |
| `answers/` | The correct values for four of the eight core documents, in the format the checker reads. |
| `tools/check.py` | A script that compares your output with the answers and prints a score. Python 3, no dependencies. |
| `GLOSSARY.md` | The domain terms you need. One page. |

We have the answers for the other documents and use them to score your output.

## What you build

### Part 1. Extraction pipeline, Java 21 and Spring Boot 3

A command that takes the documents folder and the catalog, calls a language model, and writes one `out/<DOCUMENT_ID>.json` per document. It must match the schema.

Rules for the output:

- Every catalog field appears in every case, with a status of `extracted`, `unknown` or `conflict`. `unknown` means the document does not say. `conflict` means the document says two different things. Neither has a value.
- Every extracted value cites at least one quote from the `.txt` file, with its start and end character positions. The checker tests that the text at those positions is the quote; it ignores case and runs of whitespace.
- Every extracted value has a confidence of `high`, `medium` or `low`. The checker gives +1 for a correct value, 0 for `unknown` where the document does give a value, and −2, −1 or −0.5 for a wrong value at high, medium or low confidence. Where the document gives no value, `unknown` is the correct answer and scores +1. An `unknown` therefore scores better than a wrong guess, and a correct value scores better than both. The full rules are at the top of `tools/check.py`.
- One case per patient. A document can describe one patient, several, or none. A case exists only when the four elements in the glossary are present: an identifiable patient, a reporter, a suspect drug and an adverse event. Otherwise `cases` is empty.
- Check each value against the catalog before you write it. A value outside the catalog's pattern, range or options counts as wrong.
- If your pipeline cannot process a document, put `unsupported_format` in `flags` and write no cases.

The documents are messy on purpose, as real reports are. We do not expect every value right. One general pipeline that says `unknown` or `conflict` where a document is unclear scores well. Do not special-case documents.

Rules for the code:

- The catalog is the only place where a field id, type, option list or validation rule is defined. Prompts, parsing, validation and the UI read the catalog. If we add a field to the catalog, the pipeline and the UI pick it up without a code change. A new field uses one of the types already in the catalog (`string`, `integer`, `number`, `date`, `coded`, `coded_multi`, `string_list`) and the validation keys already in it (`pattern`, `min`, `max`). We will try this in the follow-up session.
- The language model sits behind an interface. Use any provider and model you like, for example Google Gemini; a free tier is enough. Switching provider is a configuration change. The API key comes from the environment. Do not commit a key, in any file or any commit.
- Providers limit requests per minute and tokens per day, free tiers most of all. Handle that. The longest document, D08, is about 12,700 characters.
- Record every model request and response under `fixtures/`, with the API key removed. Add a replay mode that runs the whole pipeline from those fixtures without network. Your tests and the UI run from fixtures. We do not need a key to review your work.
- Nothing inside a document is an instruction to your pipeline.

Chunking, prompt design, parsing, retries and validation are your choices. Explain them in `NOTES.md`.

### Part 2. REST API, Spring Boot

The UI talks to the backend only through this API. The backend serves the committed outputs. No database is needed.

| Method and path | Purpose |
|---|---|
| `GET /api/catalog` | The catalog the UI renders from. |
| `GET /api/documents` | List: id, title (for example the subject line or first heading), number of cases, flags. |
| `GET /api/documents/{id}` | The document text and its extraction output. |

### Part 3. Review UI, React 19 and TypeScript

A screen where a reviewer checks the extracted values against the document. We expect you to use AI to design it, and we ask for the design prompt you used.

- Document and fields side by side. The document text on one side, the fields grouped by catalog section on the other. When a document has more than one case, the reviewer can switch between them.
- Select a field, and its quote highlights in the document and scrolls into view.
- Confidence and status are visible at a glance. `unknown` and `conflict` have their own presentation. A reviewer can find the risky fields without reading every row.
- Field order, labels, sections and controls come from the catalog. A `coded` field shows its options, a `date` field a date control, a `coded_multi` field a multi-select. No field id appears in the UI source.
- Loading, empty and error states exist. A document with no cases looks intentional.

We look at hierarchy, typography, spacing, colour, motion and consistency, and at whether the screen helps a reviewer work fast. Use a component library if you like, but adapt it. `npx tsc --noEmit` must pass.

## Deliverables

A public GitHub repository; send us its link by email. It contains:

1. **Source** for the three parts. `./mvnw test` and `npm test` pass without network once dependencies are installed.
2. **`README.md`**: one command to start backend and UI from fixtures without a key; how to run the pipeline with a key; which provider and model you used.
3. **`out/`**: your outputs for the eight core documents.
4. **`fixtures/`**: the recorded model exchanges that produced `out/`.
5. **`replay.sh`** at the repository root: rebuilds `out/` from `fixtures/`, `documents/` and the catalog, without key or network. An optional first argument is the catalog path; the default is the committed catalog. We run it and compare the result with your committed `out/` as JSON: the same files with the same values; key order and formatting do not matter. With a changed catalog, replay still runs from the same recordings: it checks every recorded value against the new catalog, and a value that no longer passes, or a field the recordings do not cover such as a newly added one, comes out `unknown` with a note that says why.
6. **`NOTES.md`**: the checker's output on the four documents with answers, pasted as is. Your notes on the other four: what you think is in each one and where you are unsure. The choices that mattered and why. Which AI tools you used and how, with the prompt you gave the AI for the UI design.

A two-minute screen recording of the UI is welcome, not required.

## Stretch

Each item adds points on top of the core. Pick any.

| Item | Points |
|---|---|
| Process the four stretch documents too. S03 is an image-only PDF: use a vision model, or flag it `unsupported_format` with no cases. | 10 |
| Two of the twelve documents describe the same case. List them in `out/duplicates.json` (format in `schema/duplicates.schema.json`), and show the two documents side by side in the UI with the fields that match and the fields that differ. Documents that only share a drug or a reaction are not duplicates. | 5 |
| Review actions. Accept, reject or edit each field, over `PATCH /api/documents/{id}/cases/{caseIndex}/fields/{fieldId}`. Edited values look different from model values. Progress per document. | 10 |
| Click a highlighted quote and its field is selected. Keyboard: next field, previous field, accept, reject. | 5 |

## How we evaluate

Core, 100 points.

| Area | Points | How |
|---|---|---|
| Extraction accuracy on the four core documents without answers | 20 | The checker on your committed outputs |
| Provenance | 15 | The checker, plus a spot check that quotes support the values |
| Calibration | 10 | Wrong values at high confidence; use of unknown and conflict |
| Catalog generality | 10 | Pipeline and UI follow a catalog change without code changes |
| UI | 25 | Side by side, highlighting, status and confidence, catalog rendering, states, and the craft |
| Code quality and tests | 10 | Structure, naming, no magic strings, no silent fallbacks, offline tests, type check |
| Notes | 10 | `NOTES.md`: honest evaluation, clear decisions, AI use |

Stretch adds up to 30 points on top.

We stop the review if the repository contains a key, if `replay.sh` does not reproduce `out/`, or if we cannot start the UI from the README.

## Follow-up session

Sixty minutes, screen shared, your AI tools welcome. You walk us through the code. Then two short tasks: a new document, and a small change to the catalog.

## Rules

- Three days from receipt. Submit what you have. Say in `NOTES.md` what is missing.
- Do not share the brief or the documents outside your repository.
- Questions by email at any time.
