# Contributing to Amor Fati

Amor Fati is a research system whose accounting invariants, empirical
provenance, and reproducibility are part of its public contract. Contributions
are welcome when they preserve that contract and make their evidence boundary
explicit.

## Before You Start

Open an issue before substantial work on model mechanisms, calibration,
accounting semantics, public interfaces, or repository architecture. Small
documentation corrections and narrowly scoped bug fixes may go directly to a
pull request.

Keep pull requests focused. Separate mechanical refactoring from behavioral or
empirical changes so reviewers can identify what changed in model meaning.

## Development Environment

The supported baseline is documented in [operations](docs/operations.md). The
closest match to CI is the Nix development shell:

```bash
nix develop
sbt scalafmtCheckAll
sbt test
bash scripts/check-generated-outputs.sh
```

Run focused tests while developing, then run the relevant broader suites before
requesting review. Pull-request CI remains authoritative and also checks the
Nix flake, documentation hygiene, heavy tests, integration tests, and coverage.

## Research and Model Changes

Changes to equations, decision rules, schedules, institutions, or accounting
flows must include the evidence needed to review their semantics. Depending on
the change, this normally includes:

- focused tests for the mechanism and affected invariants;
- updates to the model specification, equations, ODD, model card, or
  architecture documentation;
- explicit calibration provenance, assumptions, units, and transformations;
- deterministic seeds and run manifests for empirical or behavioral claims;
- regenerated repository artifacts when their source definitions change.

Do not present tuning as empirical calibration or simulation output as observed
data. Do not weaken exact stock-flow consistency checks to accommodate a desired
result.

## Data Contributions

Do not commit raw data unless redistribution is clearly permitted and the data
are required as a small, reviewable fixture. Record the provider, source URL,
dataset or table identifier, vintage, access date, transformation, units, and
license or reuse note in the appropriate source manifest.

Never commit credentials, confidential data, personal data, proprietary client
material, or generated runtime directories.

## Contributor License Agreement

The repository is licensed under `AGPL-3.0-only`. External contributions also
require acceptance of the [Amor Fati Contributor License Agreement](CLA.md).
The CLA is a copyright license, not a copyright assignment: contributors retain
ownership of their work while granting BoomBustGroup the rights needed to
maintain the public project and offer the contribution under additional terms.

The repository's CLA status check records acceptance for the GitHub account
submitting a pull request. External contributions must not be merged until that
check passes. Until the status check is configured, external contributions may
be reviewed but must not be merged.

If a contribution is owned by an employer, university, or another legal entity,
the person accepting the CLA must have authority to bind that entity. Identify
third-party material and its license in the pull request; do not submit it until
a maintainer confirms that it can be accepted.

Contributions already owned by BoomBustGroup do not require a separate CLA.
Maintainers may also exempt trusted automated dependency updates or changes
that do not contain copyrightable authorship.

## Pull Requests

A reviewable pull request:

- explains the problem and the chosen approach;
- identifies model, accounting, data, and compatibility consequences;
- lists the commands used for verification;
- updates user-facing and scientific documentation where behavior changed;
- contains no unrelated formatting, generated files, or refactoring;
- passes CI and the CLA status check when the CLA applies.

By submitting a pull request, you confirm that the contribution is your original
work or that you have identified every third-party component and have the right
to submit it.
