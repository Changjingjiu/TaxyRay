# Engineering rules

1. Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
2. Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
3. Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity.
4. Keep components modular and concerns clearly separated.
5. Prefer established, well-maintained libraries when they reduce overall complexity or improve reliability. Do not reimplement common functionality without a clear reason.
6. Lean on the dependencies already in the project before writing your own implementation or adding packages. Do not assume a library lacks a capability without checking its documentation and types.
7. Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.

## TaxRay invariants

- Amounts use exact decimal input and integer cents; rates use integer basis points. Floating-point values are allowed only for rendering geometry.
- AI outputs are untrusted drafts until explicit user confirmation. No remote service may write to the ledger.
- Preserve the offline manual workflow. Do not add telemetry, account requirements, proxy backends, or background requests.
- Never commit API keys, personal receipts, database files, signing secrets, or machine-specific configuration.
- Keep algorithm and policy documentation consistent with the implementation. Tax estimates must not be described as statutory tax-payment proof.
- Run the relevant tests, `lintDebug`, and build checks before reporting a change as complete. Device or API claims require corresponding evidence.
