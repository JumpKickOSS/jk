"""A run's estimated API cost from its token buckets and prices.toml.

Every row gets ``cost_est_usd`` (None when its model has no price row) and ``price_key``, the row of
prices.toml it was priced by. The estimate is the API list price of the tokens the CLI reported, whether
the run was billed per token or ran on a subscription; ``cost_usd`` stays what the CLI itself reported.
"""
from __future__ import annotations

import functools
import tomllib
from pathlib import Path

PRICES = Path(__file__).resolve().parents[1] / "prices.toml"
# Drivers whose reasoning_tokens are reported apart from output_tokens (agy's, when its total says so);
# every other driver's output count already holds them, so adding them would bill them twice.
REASONING_APART = {"agy"}
# Drivers whose prompt cache is written for an hour: Claude Code's own total_cost_usd prices its cache
# writes at the 1-hour rate.
CACHE_1H = {"claude-code"}


@functools.cache
def prices() -> dict:
    return tomllib.loads(PRICES.read_text())["models"]


def price_key(model: str | None) -> str | None:
    """The longest prices.toml key the model id starts with."""
    if not model:
        return None
    hits = [k for k in prices() if model == k or model.startswith(k + "-")]
    return max(hits, key=len) if hits else None


def estimate(row: dict) -> dict:
    key = price_key(row.get("model"))
    if key is None or row.get("tokens") is None:
        return {"cost_est_usd": None, "price_key": key}
    p = prices()[key]
    output = int(row.get("output_tokens") or 0)
    write = p.get("cache_write", p["input"])
    if row.get("driver") in CACHE_1H:
        write = p.get("cache_write_1h", write)
    if row.get("driver") in REASONING_APART:
        output += int(row.get("reasoning_tokens") or 0)
    usd = (int(row.get("input_tokens") or 0) * p["input"]
           + int(row.get("cache_read_tokens") or 0) * p.get("cached_input", p["input"])
           + int(row.get("cache_write_tokens") or 0) * write
           + output * p["output"]) / 1_000_000
    return {"cost_est_usd": round(usd, 5), "price_key": key}
