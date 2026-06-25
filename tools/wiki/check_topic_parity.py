#!/usr/bin/env python3
"""Verify every wiki page in wiki/docs/players/ has valid YAML frontmatter with
required fields (title, audience, topic, summary, keywords, related, updated).

Exit code 1 on missing/invalid frontmatter; prints all issues before exiting.

NOTE: This is a frontmatter validator. Topic parity (cross-referencing wiki topics
against an in-game help registry) is not yet implemented — EnthusiaMarket does not
have a HelpTopics registry like LumaGuilds. Rename or extend this script when one is added.
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PLAYERS_DIR = REPO_ROOT / "wiki/docs/players"
REQUIRED_FIELDS = {"title", "audience", "topic", "summary", "keywords", "related", "updated"}

# Pages intentionally excluded from parity checks:
WIKI_ONLY = {"how-do-i"}


def check_frontmatter(path: Path) -> list[str]:
    """Check a single .md file for required frontmatter fields. Returns list of issues."""
    issues = []
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---"):
        return ["missing YAML frontmatter (must start with ---)"]

    # Extract YAML block between first two --- markers
    parts = text.split("---", 2)
    if len(parts) < 3:
        return ["malformed YAML frontmatter"]

    frontmatter = parts[1]
    fields_found = set()
    for line in frontmatter.strip().split("\n"):
        if ":" in line:
            key = line.split(":", 1)[0].strip()
            fields_found.add(key)

    missing = REQUIRED_FIELDS - fields_found
    if missing:
        issues.append(f"missing fields: {', '.join(sorted(missing))}")

    return issues


def main() -> int:
    if not PLAYERS_DIR.is_dir():
        print(f"SKIP — {PLAYERS_DIR} not found.", file=sys.stderr)
        return 0

    player_pages = list(PLAYERS_DIR.glob("*.md"))
    errors = 0

    for page in player_pages:
        slug = page.stem
        issues = check_frontmatter(page)
        if issues:
            print(f"{slug}.md:", file=sys.stderr)
            for issue in issues:
                print(f"  - {issue}", file=sys.stderr)
            errors += 1

    if errors:
        print(f"\n{errors} page(s) with frontmatter issues.", file=sys.stderr)
        return 1

    print(f"OK — {len(player_pages)} player wiki pages with valid frontmatter.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
