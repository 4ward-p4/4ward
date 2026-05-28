# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

Dependency override rationale lives next to the overrides in `MODULE.bazel`.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Establish user-facing documentation

`docs/` currently serves developers working on 4ward. As the project
approaches upstream integration into sonic-pins, API consumers (DVaaS
integrators, sonic-pins developers) need their own documentation: API
reference, configuration guide, integration cookbook. Decide on format
(mdbook, docusaurus, plain markdown) when scope is clearer.
`docs/TYPE_TRANSLATION.md` is a first step toward user-facing reference
material.
