#!/usr/bin/env python3
"""Convert raw GH Archive hourly .json.gz files into a pair of keyed TSVs for the join on repo_id.

Left  = pushes.tsv : join_key=repo_id, a0=hour(0-23), a1=weekday(1-7), a2=default_branch(0/1), a3=bot(0/1)
Right = events.tsv : join_key=repo_id, a0=type_id (see TYPE_IDS), a1=hour, a2=weekday, a3=bot(0/1)
The join is many-to-many: a repo has many pushes and many other events.
Usage: python scripts/prepare_gharchive.py <raw_dir> <output_dir>
"""

from __future__ import annotations

import argparse
import gzip
import json
import time
from datetime import date
from pathlib import Path

TYPE_IDS = {
    "WatchEvent": 1,
    "IssuesEvent": 2,
    "PullRequestEvent": 3,
    "ForkEvent": 4,
    "IssueCommentEvent": 5,
    "PullRequestReviewEvent": 6,
    "PullRequestReviewCommentEvent": 7,
    "CreateEvent": 8,
    "DeleteEvent": 9,
    "ReleaseEvent": 10,
    "GollumEvent": 11,
    "CommitCommentEvent": 12,
    "MemberEvent": 13,
    "PublicEvent": 14,
}
OTHER_TYPE = 15


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    files = sorted(args.raw_dir.glob("*.json.gz"))
    if not files:
        raise SystemExit(f"no .json.gz files in {args.raw_dir}")
    pushes = 0
    events = 0
    skipped = 0
    start = time.time()
    weekday_cache: dict[str, int] = {}
    with (args.output_dir / "pushes.tsv").open("w", encoding="utf-8", newline="") as left, \
            (args.output_dir / "events.tsv").open("w", encoding="utf-8", newline="") as right:
        left.write("repo_id\thour\tweekday\tdefault_branch\tbot\n")
        right.write("repo_id\ttype_id\thour\tweekday\tbot\n")
        for path in files:
            with gzip.open(path, "rt", encoding="utf-8", errors="replace") as source:
                for line in source:
                    try:
                        event = json.loads(line)
                        repo_id = event["repo"]["id"]
                        created = event["created_at"]  # ISO timestamp, e.g. 2026-06-01T15:00:00Z
                        event_type = event["type"]
                    except (KeyError, TypeError, json.JSONDecodeError):
                        skipped += 1
                        continue
                    day = created[:10]
                    weekday = weekday_cache.get(day)
                    if weekday is None:
                        weekday = date.fromisoformat(day).isoweekday()
                        weekday_cache[day] = weekday
                    hour = int(created[11:13])
                    actor = event.get("actor", {}).get("login", "")
                    bot = 1 if actor.endswith("[bot]") or actor.endswith("-bot") else 0
                    if event_type == "PushEvent":
                        ref = event.get("payload", {}).get("ref") or ""
                        default_branch = 1 if ref.endswith("/main") or ref.endswith("/master") else 0
                        left.write(f"{repo_id}\t{hour}\t{weekday}\t{default_branch}\t{bot}\n")
                        pushes += 1
                    else:
                        type_id = TYPE_IDS.get(event_type, OTHER_TYPE)
                        right.write(f"{repo_id}\t{type_id}\t{hour}\t{weekday}\t{bot}\n")
                        events += 1
            print(f"{path.name}: pushes={pushes:,} events={events:,} ({time.time() - start:.0f}s)", flush=True)
    print(f"done: pushes={pushes:,} events={events:,} skipped={skipped:,}", flush=True)


if __name__ == "__main__":
    main()
