"""A run's estimated API cost from its token buckets and the price table built into JumpKick.

The table is the product's own prices.toml (shared/client-io, cc/jumpkick/prices), so the bench and
the engine price tokens from one file. Every row gets ``cost_est_usd`` (None when its model has no
price row) and ``price_key``, the row of prices.toml it was priced by. The estimate is the API list
price of the tokens the CLI reported, whether the run was billed per token or ran on a subscription;
``cost_usd`` stays what the CLI itself reported.
"""
from __future__ import annotations

import functools
import tomllib
from pathlib import Path

PRICES = (Path(__file__).resolve().parents[3]
          / "shared/client-io/src/main/resources/cc/jumpkick/prices/prices.toml")
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


# The token kinds a vendor prices apart, in the order a price row lists them.
KINDS = ("input", "output", "cache-read", "cache-write")


def kinds(row: dict) -> dict[str, int] | None:
    """A run's tokens by the kind each is priced as; None when the run reported no tokens."""
    if row.get("tokens") is None:
        return None
    output = int(row.get("output_tokens") or 0)
    if row.get("driver") in REASONING_APART:
        output += int(row.get("reasoning_tokens") or 0)
    return {"input": int(row.get("input_tokens") or 0), "output": output,
            "cache-read": int(row.get("cache_read_tokens") or 0),
            "cache-write": int(row.get("cache_write_tokens") or 0)}


def usd(model: str | None, driver: str | None, tokens: dict[str, float]) -> float | None:
    """The API list price of tokens by kind on model, as driver's CLI is billed; None without a price row."""
    key = price_key(model)
    if key is None:
        return None
    p = prices()[key]
    write = p.get("cache_write", p["input"])
    if driver in CACHE_1H:
        write = p.get("cache_write_1h", write)
    return (tokens["input"] * p["input"] + tokens["cache-read"] * p.get("cached_input", p["input"])
            + tokens["cache-write"] * write + tokens["output"] * p["output"]) / 1_000_000


def estimate(row: dict) -> dict:
    key = price_key(row.get("model"))
    tokens = kinds(row)
    if key is None or tokens is None:
        return {"cost_est_usd": None, "price_key": key}
    return {"cost_est_usd": round(usd(row.get("model"), row.get("driver"), tokens), 5), "price_key": key}
