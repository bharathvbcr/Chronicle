# Attachments (capture-workflow support)

Two separate attachment pipelines exist — don't conflate them.

## Journal capture media (app-owned)

Images/audio attached to a journal entry go through the app (phone capture or PC upload),
land under `_attachments/yyyy/MM/<id>_<n>.jpg|.m4a`, and are referenced from the entry JSON
`images`/`audio` fields. Do not hand-place files here — use the capture flow.

## Knowledge-note attachments (manual / agent-placed)

For a reference file attached to a `30-Knowledge/`, `10-Work/`, `20-Personal/`, or `00-Inbox/`
note (a PDF, doc, or image dropped in manually):

1. Copy the binary under `_attachments/notes/<area>/YYYY-MM-DD-description.ext`
   (e.g. `_attachments/notes/10-Work/2026-07-09-vendor-contract.pdf`).
2. Create or update the companion note in the right PARA area: embed the file
   (`![[...]]`), summarize its contents as searchable text (read the PDF / describe the
   image), and list the path under an `attachments:` frontmatter key.
3. `kb/files/` is legacy, Mac-pipeline-owned, read-only reference — do not write there.
