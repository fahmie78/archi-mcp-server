# Validation Findings Reference

How to read and act on the findings returned by the `get-validation-findings` tool, which runs
Archi's built-in model Validator (the same checks as **Tools → Validate Model** and the Issues
view) over the currently loaded model.

## Severity semantics

| Severity | Meaning | Typical response |
|---|---|---|
| `error` | The model violates an ArchiMate rule — the finding names something the specification does not permit. | Fix before or alongside any further modelling; errors poison downstream analysis. |
| `warning` | Likely a modelling hygiene problem — legal, but suspicious or incomplete. | Investigate; fix when the model is meant to be complete. |
| `advice` | Informational — legal and often intentional, worth a look. | Triage; many are acceptable as-is. |

The Validator respects the user's **Validate preferences** in Archi (each check can be toggled in
the preferences dialog). A disabled check simply contributes no findings.

## What each finding type means and how to remediate

| Finding name | Severity | What it means | Remediation |
|---|---|---|---|
| Illegal relation | `error` | A relationship connects concepts the ArchiMate relationship rules do not permit (e.g. an Assignment whose source is not an active-structure element). | Inspect the relationship via its `targetId`, then either re-type it (`update-relationship`) to a permitted relationship or delete it (`delete-relationship`) and model the intent with a valid one. |
| Junction relationships | `error` | A Junction combines relationships of different types, or a Junction relationship chain is inconsistent (e.g. mixing Flow and Triggering through one Junction). | Split the Junction so each one combines a single relationship type, or align the relationship types flowing through it. |
| Unused Element | `warning` | The element appears in no view. | Either place it on a view (`add-to-view`) where it belongs, or delete it (`delete-element`) if it is dead content. |
| Unused Relation | `warning` | The relationship appears in no view. | Draw it (`add-connection-to-view`) where relevant, or delete it (`delete-relationship`). |
| Possible duplicate | `warning` | Two elements share the same type and a very similar name. | Compare both via `get-element`; merge into one (re-point relationships, then `delete-element` the duplicate) or rename to disambiguate (`update-element`). |
| Concept in Viewpoint | `warning` | A view contains a concept its viewpoint does not include. | Move the concept to a suitable view, or change the view's viewpoint (`update-view`) if the viewpoint choice was wrong. |
| Empty View | `advice` | A view contains no objects. | Populate it or delete it (`delete-view`). |
| Visual Nesting | `advice` | An element is drawn inside another element on a view, but no nesting relationship (Composition / Aggregation / Assignment, depending on the types) exists between them in the model. | Either create the implied relationship (`create-relationship`) so the diagram matches the model, or unnest the visual objects. |

## Using findings as agent input knowledge

- **Baseline before changing anything.** Call `get-validation-findings` at the start of a task so
  pre-existing findings are not mistaken for damage your changes caused.
- **Verify after mutating.** Findings are computed live at call time. Re-run the tool after a
  mutation batch and compare counts: a remediation should lower them, and no new `error` findings
  should appear.
- **Follow the target.** Every finding carries `targetId` / `targetName` / `targetType` /
  `targetKind` for the concept it is about (diagram-object findings resolve to their underlying
  ArchiMate concept). Pass `targetId` to `get-element`, or `find-concept-usage` to see every view
  that would be affected by deleting or re-typing it.
- **Page large result sets.** Findings are ordered `error` → `warning` → `advice`. Use `severity`
  to focus (e.g. `error` first) and `limit` to bound the response; the per-severity counts always
  describe the full run so you know what remains.
