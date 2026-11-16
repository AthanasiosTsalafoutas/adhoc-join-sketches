#!/usr/bin/env python3
"""Convert the TPC-DS store_sales and catalog_sales .dat files into keyed TSVs with equality-only attributes.

Each output row is join_key, sold_date_bin, location_key, promotion_key, quantity_bin. Date and quantity are
quantized into equal-width bins (0 = null). The join is on the item key (default) or on the customer key.
Usage: python scripts/prepare_tpcds_binary.py <source_dir> <output_dir> [--join item|customer]
       [--date-bins N] [--quantity-bins N]
Writes the two TSVs and metadata.json (row counts, null counts, value ranges).
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class RelationSpec:
    """Source file, output file and 0-based .dat column indices of one relation."""
    source_name: str
    output_name: str
    join_column: int
    date_column: int
    location_column: int
    promotion_column: int
    quantity_column: int


SPECS = {
    # item key: ss_item_sk / cs_item_sk (never null)
    "item": (
        RelationSpec("store_sales.dat", "store_sales.tsv", 2, 0, 7, 8, 10),
        RelationSpec("catalog_sales.dat", "catalog_sales.tsv", 15, 0, 14, 16, 18),
    ),
    # customer key: ss_customer_sk / cs_bill_customer_sk (nullable; null-key rows are skipped)
    "customer": (
        RelationSpec("store_sales.dat", "store_sales.tsv", 3, 0, 7, 8, 10),
        RelationSpec("catalog_sales.dat", "catalog_sales.tsv", 3, 0, 14, 16, 18),
    ),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--join", choices=sorted(SPECS), default="item")
    parser.add_argument("--date-bins", type=int, default=64)
    parser.add_argument("--quantity-bins", type=int, default=64)
    return parser.parse_args()


def integer(columns: list[str], index: int) -> int | None:
    """Column as an int, or None when it is empty."""
    value = columns[index]
    return None if value == "" else int(value)


def update_domain(domain: list[int | None], value: int | None) -> None:
    """Widen the [min, max] pair in domain to include value (None is ignored)."""
    if value is None:
        return
    domain[0] = value if domain[0] is None else min(domain[0], value)
    domain[1] = value if domain[1] is None else max(domain[1], value)


def scan(spec: RelationSpec, path: Path, skip_null_keys: bool) -> dict[str, object]:
    """First pass over a .dat file: row count, null counts and the date and quantity ranges used for binning."""
    date_domain: list[int | None] = [None, None]
    quantity_domain: list[int | None] = [None, None]
    rows = 0
    skipped = 0
    nulls = {"sold_date_bin": 0, "location_key": 0, "promotion_key": 0, "quantity_bin": 0}
    required_columns = max(
        spec.join_column,
        spec.date_column,
        spec.location_column,
        spec.promotion_column,
        spec.quantity_column,
    ) + 1
    with path.open("r", encoding="utf-8", newline="") as source:
        for line_number, line in enumerate(source, 1):
            columns = line.rstrip("\r\n").split("|")
            if len(columns) < required_columns:
                raise ValueError(f"{path}:{line_number}: expected at least {required_columns} columns")
            if integer(columns, spec.join_column) is None:
                if not skip_null_keys:
                    raise ValueError(f"{path}:{line_number}: null join key")
                skipped += 1
                continue
            date = integer(columns, spec.date_column)
            quantity = integer(columns, spec.quantity_column)
            update_domain(date_domain, date)
            update_domain(quantity_domain, quantity)
            nulls["sold_date_bin"] += date is None
            nulls["location_key"] += integer(columns, spec.location_column) is None
            nulls["promotion_key"] += integer(columns, spec.promotion_column) is None
            nulls["quantity_bin"] += quantity is None
            rows += 1
    if date_domain[0] is None or quantity_domain[0] is None:
        raise ValueError(f"{path}: date or quantity column contains no values")
    return {
        "rows": rows,
        "skipped_null_join_key": skipped,
        "date_min": date_domain[0],
        "date_max": date_domain[1],
        "quantity_min": quantity_domain[0],
        "quantity_max": quantity_domain[1],
        "null_counts": nulls,
    }


def quantize(value: int | None, minimum: int, maximum: int, bins: int) -> int:
    """Equal-width bin 1..bins of value within [minimum, maximum], 0 for null."""
    if value is None:
        return 0
    width = maximum - minimum + 1
    return 1 + ((value - minimum) * bins // width)


def write_relation(
    spec: RelationSpec,
    source_path: Path,
    output_path: Path,
    stats: dict[str, object],
    date_bins: int,
    quantity_bins: int,
) -> None:
    """Second pass: write the TSV of one relation with binned date and quantity, skipping rows with a null join key."""
    with source_path.open("r", encoding="utf-8", newline="") as source, output_path.open(
        "w", encoding="utf-8", newline=""
    ) as target:
        target.write("join_key\tsold_date_bin\tlocation_key\tpromotion_key\tquantity_bin\n")
        for line in source:
            columns = line.rstrip("\r\n").split("|")
            join_key = integer(columns, spec.join_column)
            if join_key is None:
                continue
            date = quantize(
                integer(columns, spec.date_column),
                int(stats["date_min"]),
                int(stats["date_max"]),
                date_bins,
            )
            quantity = quantize(
                integer(columns, spec.quantity_column),
                int(stats["quantity_min"]),
                int(stats["quantity_max"]),
                quantity_bins,
            )
            location = integer(columns, spec.location_column) or 0
            promotion = integer(columns, spec.promotion_column) or 0
            target.write(f"{join_key}\t{date}\t{location}\t{promotion}\t{quantity}\n")


def main() -> None:
    args = parse_args()
    if args.date_bins <= 0 or args.quantity_bins <= 0:
        raise SystemExit("bin counts must be positive")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    metadata: dict[str, object] = {
        "date_bins": args.date_bins,
        "quantity_bins": args.quantity_bins,
        "null_bin": 0,
        "join": args.join,
        "relations": {},
    }
    for spec in SPECS[args.join]:
        source_path = args.source_dir / spec.source_name
        output_path = args.output_dir / spec.output_name
        stats = scan(spec, source_path, skip_null_keys=args.join != "item")
        write_relation(spec, source_path, output_path, stats, args.date_bins, args.quantity_bins)
        metadata["relations"][spec.output_name] = stats
        print(f"{spec.output_name}: rows={stats['rows']:,} output={output_path}")
    (args.output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
