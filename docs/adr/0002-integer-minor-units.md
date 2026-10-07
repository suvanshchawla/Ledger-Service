# 0002: Integer minor units instead of BigDecimal

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

Money must be exact. The ledger adds and subtracts amounts millions of times, and a one-cent discrepancy is a defect. The representation chosen here runs through the domain code, the database and the JSON API.

## Options considered

1. **`double` / `float`.** Binary floating point cannot represent most decimal fractions: in Java, `0.1 + 0.2` is `0.30000000000000004`. Errors accumulate and amounts stop balancing. Rejected outright.
2. **`BigDecimal` (`NUMERIC` in PostgreSQL).** Exact, and the standard answer for money. But every operation needs an explicit scale and rounding mode, `equals` distinguishes `2.50` from `2.5`, and it is heavier to compute, store and serialize. Workable, but more surface for mistakes than this service needs.
3. **`long` minor units (cents) wrapped in a `Money` type.** **Chosen.**

## Decision

Represent every amount as a `long` count of minor units, wrapped in a `Money` record. `double` and `BigDecimal` never appear in the domain.

- Arithmetic uses `Math.addExact`, `subtractExact` and `negateExact`, so an overflow throws instead of silently wrapping into a wrong balance.
- `Money` is signed: a posting is negative for money leaving and positive for money arriving, so the postings of an entry can sum to zero.
- Amounts are stored as `BIGINT` columns (`balance_minor`, `amount_minor`).
- The API carries `{ "value": 2500, "currency": "CAD" }`, where `value` is whole minor units. Jackson's `ACCEPT_FLOAT_AS_INT` is turned off, so `"value": 12.5` is a 400 instead of being silently truncated to 12.
- `Money` carries no currency. The service is CAD-only; the currency lives on the account and in the API's `MoneyDto`.

## Consequences

**Positive**

- Exact and simple: integer addition, no rounding rules, no scale to forget.
- Fast, and it maps directly to `BIGINT`.
- Overflow is caught, and a fractional amount cannot slip in through the API.

**Negative and risks**

- Amounts smaller than a minor unit (exchange rates, interest accrual) cannot be represented. If they ever matter, `BigDecimal` belongs at that boundary, with an explicit rounding step to minor units.
- Currencies differ in how many minor units make a unit (JPY has none, some have three), so conversion between display amounts and minor units must be per currency and happen at the edge.
- Because `Money` has no currency, going multi-currency changes `Money` and every call site. At that point `Money` could carry the currency directly and `MoneyDto` would no longer be needed.
- The range is about plus or minus 9.2 quintillion minor units, far beyond any real balance; `addExact` makes the limit a loud failure instead of a silent one.

## References

- `Money`, `MoneyDto`; `MoneyTest` (including the overflow cases); `ACCEPT_FLOAT_AS_INT` in `application.yml` with the fractional-amount test in `TransferEndpointTest`
