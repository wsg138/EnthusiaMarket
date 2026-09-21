#!/usr/bin/env python3
"""Validate YAML front-matter on every wiki/docs/**/*.md page.

Required fields: title, audience, topic, summary, keywords, related, updated.
audience must be one of: player, admin, dev.
topic must be a lowercase-hyphenated slug matching the filename stem.
summary must be <=140 chars.

Exit code 1 on any failure; prints all failures before exiting.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("PyYAML is required. Install with: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCS_ROOT = REPO_ROOT / "wiki" / "docs"

REQUIRED_FIELDS = {"title", "audience", "topic", "summary", "keywords", "related", "updated"}
VALID_AUDIENCES = {"player", "admin", "dev"}
SLUG_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
SUMMARY_MAX = 140

# Files whose `topic` slug intentionally does not match the filename stem.
SLUG_EXEMPT = {
    Path("index.md"),
}


def parse_frontmatter(text: str) -> dict | None:
    # Normalize line endings so CRLF-committed files don't fail
    text = text.replace('\r\n', '\n')
    if not text.startswith("---\n"):
        return None
    end = text.find("\n---", 4)
    if end == -1:
        return None
    try:
        parsed = yaml.safe_load(text[4:end])
    except yaml.YAMLError:
        return None
    if not isinstance(parsed, dict):
        return None
    return parsed


def missing_field_errors(rel: Path, frontmatter: dict) -> list[str]:
    missing = REQUIRED_FIELDS - frontmatter.keys()
    if not missing:
        return []
    return [f"{rel}: missing required fields: {sorted(missing)}"]


def audience_errors(rel: Path, frontmatter: dict) -> list[str]:
    if "audience" not in frontmatter:
        return []
    if frontmatter["audience"] in VALID_AUDIENCES:
        return []
    return [f"{rel}: audience={frontmatter['audience']!r} not in {VALID_AUDIENCES}"]


def topic_errors(rel: Path, path: Path, frontmatter: dict) -> list[str]:
    if "topic" not in frontmatter:
        return []

    slug = str(frontmatter["topic"])
    errors: list[str] = []
    if not SLUG_RE.match(slug):
        errors.append(f"{rel}: topic={slug!r} not a lowercase-hyphenated slug")
    if rel not in SLUG_EXEMPT and path.stem != slug:
        errors.append(f"{rel}: topic={slug!r} does not match filename stem {path.stem!r}")
    return errors


def summary_errors(rel: Path, frontmatter: dict) -> list[str]:
    if "summary" not in frontmatter or not isinstance(frontmatter["summary"], str):
        return []

    summary = frontmatter["summary"]
    if len(summary) <= SUMMARY_MAX:
        return []
    return [f"{rel}: summary is {len(summary)} chars (max {SUMMARY_MAX})"]


def list_field_errors(rel: Path, frontmatter: dict, field: str) -> list[str]:
    if field not in frontmatter or isinstance(frontmatter[field], list):
        return []
    return [f"{rel}: {field} must be a list"]


def lint(path: Path) -> list[str]:
    rel = path.relative_to(DOCS_ROOT)
    text = path.read_text(encoding="utf-8")
    frontmatter = parse_frontmatter(text)
    if frontmatter is None:
        return [f"{rel}: missing or malformed YAML front-matter"]

    return [
        *missing_field_errors(rel, frontmatter),
        *audience_errors(rel, frontmatter),
        *topic_errors(rel, path, frontmatter),
        *summary_errors(rel, frontmatter),
        *list_field_errors(rel, frontmatter, "keywords"),
        *list_field_errors(rel, frontmatter, "related"),
    ]


def main() -> int:
    failures: list[str] = []
    pages = sorted(DOCS_ROOT.rglob("*.md"))
    if not pages:
        print(f"No markdown pages found under {DOCS_ROOT}", file=sys.stderr)
        return 1
    for page in pages:
        failures.extend(lint(page))
    if failures:
        for line in failures:
            print(line, file=sys.stderr)
        print(f"\n{len(failures)} front-matter problem(s) across {len(pages)} pages.", file=sys.stderr)
        return 1
    print(f"OK -- {len(pages)} pages validated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
