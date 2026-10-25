# Generated format fixtures

Created by this repository's `FormatFixtures`, with no customer or downloaded third-party documents.
Tests load these committed bytes to keep content hashes identical to HTTP acceptance samples.

The ordinary PDF has two text-layer pages; the scanned PDF has only an embedded invoice bitmap.
DOCX includes a real Heading1 style, Chinese text, and a rectangular table.
PPTX has two slides and a table; PPT exercises the legacy Tika Office route.
Both Excel files contain two sheets, formulas, formatted numbers, Chinese/blank cells and a gap before row 5.
CSV includes a quoted newline, empty middle field and a literal formula string. PNG/JPEG show INVOICE 42.

VLM/MinerU responses in tests are explicit protocol fixtures, not actual recognition results.
