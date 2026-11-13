# THERAGENX

THERAGENX reads medicine safety reports and prepares a structured case for a reviewer. The reviewer can check the extracted information against the original report, correct it, and accept or reject each case.

This README is a short guide to running and using the project. The `demo` folder contains files for trying the main features.

## What the application does

1. Reads a report in PDF or TXT format.
2. Splits longer reports into smaller parts before sending them to the language model.
3. Collects the model's answers and joins information that belongs to the same patient case.
4. Checks the extracted fields and keeps evidence from the original report.
5. Shows the result beside the original report so a reviewer can check it.
6. Lets the reviewer edit, accept or reject cases.

The field catalog controls which fields are extracted and how they are described. Catalog changes are used for later extractions; they do not silently rewrite results that have already been created.

## Main folders

* `backend/THERAGENX\_api` – Java and Spring Boot application.
* `frontend` – React application shown in the browser.
* `documents` – source reports, including uploaded reports.
* `catalog/field\_catalog.json` – field definitions used during extraction.
* `out` – generated extraction results shown in the application.
* `fixtures` – saved model responses used for offline replay.

The backend paths in `application.properties` are relative paths. Start the backend with the backend project directory as its working directory, as configured in the supplied project.

## What you need

* Java 21
* Maven (or the included Maven Wrapper, `mvnw.cmd` on Windows)
* Node.js and npm
* A Groq API key for live extraction

## Start the application

### 1\. Backend

Set the `GROQ\_API\_KEY` environment variable before starting the backend. In Windows PowerShell:

```powershell
$env:GROQ\_API\_KEY="your-groq-api-key"
```

Open a terminal in `backend/THERAGENX\_api` and run:

```powershell
.\\mvnw.cmd spring-boot:run
```

If Maven is installed on your machine, `mvn spring-boot:run` can be used instead.

### 2\. Frontend

Open another terminal in `frontend` and run:

```bash
npm install
npm run dev
```

Open the local address printed by Vite in the terminal (usually `http://localhost:5173`).

## Normal use

1. Open the application.
2. Select a document from the document list, or upload a PDF/TXT report.
3. If a duplicate is found, check the matching document shown by the application. Choose to cancel or save anyway.
4. Run extraction for the document.
5. Select each case and compare its fields with the original report.
6. Click evidence to move to the related text in the source report.
7. Edit, accept or reject the case as needed.
8. Use document-level review actions only when they are appropriate for all cases in that document.

## Catalog changes

Open the catalog editor and add, edit or remove fields using the form. Save and activate the catalog. Use a new extraction to see the changed catalog in the generated result. Existing results remain as they were until you explicitly run extraction again.

Keep a copy of the catalog before experimenting. The `demo/catalog\_examples` folder contains a sample field you can add and then remove.

## Fixture recording and replay

For a live run, set this in `backend/THERAGENX\_api/src/main/resources/application.properties`:

```properties
app.llm.provider=groq
```

Run extraction normally. The application records the responses for the chunks in `fixtures/<documentId>.json`. A later re-extraction is stored as another run in that document's fixture file.

To replay a saved run without calling Groq, change the setting to:

```properties
app.llm.provider=fixture
```

Optionally select a particular run:

```properties
app.fixtures.run-id=YOUR\_RUN\_ID
```

Restart the backend and run extraction for the same document. Replay checks the saved document content, chunk count and prompts. If the document or extraction request has changed, replay stops rather than using responses that may no longer match. Change back to `groq` for a live extraction.

## Important notes

* Scanned PDFs with no selectable text need OCR; OCR is not included in the current PDF duplicate check.
* Exact file duplicates can still be detected by file hash. Text-based duplicate checks need extractable PDF text.
* Fixture replay is intended for the same document and matching extraction setup that created the fixture.

