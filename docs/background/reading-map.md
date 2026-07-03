# SFC + ABM Reading Map

This note is an orientation map, not a textbook primer. It is for readers who
can read code, macro models, or ABM papers, but do not yet know how stock-flow
consistent agent-based modeling is used in Amor Fati.

The short version: read this document to understand the modeling stance, then
move to the formal model and architecture documents. If you need foundations,
use the references below and come back here.

## The 10-Minute Version

Stock-flow consistent modeling starts from accounting discipline. A stock is a
balance-sheet position at a point in time: deposits, loans, reserves, bonds,
foreign assets, capital buffers, and similar quantities. A flow is something
that happens during a period: wages, taxes, interest, loan origination,
repayment, transfers, purchases, defaults, valuation changes, and other
transactions. In an SFC model, flows must have counterparties and stocks must
reconcile with the flows that changed them. A model can be behaviorally wrong,
poorly calibrated, or missing an institution, but it should not silently create
or destroy money through implementation drift.

Agent-based modeling starts from heterogeneous units and local rules. Instead
of collapsing households, firms, or banks into representative aggregates, an
ABM simulates many agents with different states, constraints, and decision
rules. Aggregate outcomes are produced by interactions among these agents and
institutions. This is useful when distribution, balance-sheet position,
network exposure, credit rationing, defaults, and nonlinear propagation matter.

SFC and ABM solve different problems. SFC supplies the accounting grammar: what
must balance, which stock changed, and who holds the counterpart claim. ABM
supplies the behavioral microstructure: which household consumes, which firm
hires, which bank tightens credit, which sector absorbs a shock, and how those
decisions aggregate. SFC without heterogeneity can become too smooth for
financial-stability and distributional questions. ABM without hard accounting
can generate plausible stories with hidden monetary leakage. Amor Fati combines
them by letting heterogeneous agents make revisable decisions under strict
ledger and SFC constraints.

## Reading Paths

Use the shortest path that fits your background.

| Starting point | Read first | Then read in Amor Fati |
| --- | --- | --- |
| New to SFC | Godley and Lavoie, especially the chapters on stocks, flows, transaction matrices, and balance sheets | [Model equations to SFC map](../model-equations-to-sfc-map.md), then [SFC matrix evidence](../sfc-matrix-evidence.md) |
| New to ABM | Dawid and Delli Gatti for macro ABM foundations; the ODD protocol references in the ODD document for model description format | [ODD / ODD+D model documentation](../odd-model-documentation.md) |
| New to SFC-ABM | Caiani et al. for a benchmark SFC-ABM integration | This document, then [model specification](../model-specification.md) |
| Already familiar | Skip the general literature pass | [Model specification](../model-specification.md), [monthly transition function](../monthly-transition-function.md), and [architecture overview](../architecture/overview.md) |
| Building a similar engine | Read the model stance here, then the code-facing path | [Architecture documentation](../architecture/index.md), especially runtime loop and state-ledger boundary |

The research documents are intentionally split. The model specification states
the implemented scientific contract. The ODD document describes ABM entities,
scheduling, submodels, and decisions. The SFC evidence documents expose
matrices, identities, runtime mechanisms, and reconciliation artifacts. The
architecture documents explain how those contracts appear in Scala packages.

## Amor Fati's Position

Amor Fati is not "an ABM with a few accounting checks." The project is a
ledger-first SFC-ABM. Supported monetary flows are emitted as named flow
mechanisms, executed through a verified ledger path, projected into supported
financial state, and validated against semantic SFC identities before a month
is accepted. This is why the strongest contract in the project is not a smooth
GDP path or a preferred impulse response. It is accounting correctness.

The main modeling choices are these:

| Choice | Meaning in this project |
| --- | --- |
| Ledger-first execution | Same-month economics decides quantities, rates, defaults, and policy responses. Accounting execution is delegated to named ledger batches rather than ad hoc balance updates. |
| Explicit month boundary | `FlowSimulation.SimState` is the public month-boundary state. A month moves from pre-boundary state through same-month economics, flow execution, next-state materialization, and SFC validation. |
| Fixed-point numerics and exact reconciliation | Core monetary and SFC paths use typed fixed-point domain values so accounting residuals are not treated as floating-point noise. |
| Poland as calibration target | The current economy is Poland, with explicit rest-of-world channels, Polish public institutions, NBP, banking-sector rows, social funds, and domestic empirical bridges. |
| Documentation as evidence routing | The docs separate model contract, ODD description, SFC evidence, calibration evidence, validation evidence, and code architecture so each claim has a clear owner. |

This produces a particular research stance. Behavioral mechanisms can be
changed when evidence or theory improves. Calibration can be revised. Sector
coverage can be expanded. But supported monetary plumbing must remain
auditable. When a flow is supported by the runtime ledger, the code should show
where it is emitted, how it is executed, what stock slice it changes, and which
SFC identity or evidence artifact observes it.

That choice makes the implementation more demanding than a lightweight ABM. A
new monetary mechanism is not just a function that changes two fields. It
usually touches flow emission, ledger ownership semantics, SFC projection,
tests, and generated evidence. The benefit is that a surprising macro outcome
can be investigated as a model result rather than first being suspected as a
bookkeeping artifact.

## Terms As Used Here

| Term | Project-local meaning |
| --- | --- |
| Stock | A point-in-time financial, real, or diagnostic state variable. Supported financial stocks live in `LedgerFinancialState` when they are part of the ledger-owned slice. |
| Flow | A within-month monetary or semantic change, often emitted as a typed `FlowMechanism` batch or represented in SFC projection evidence. |
| SFC identity | A semantic accounting invariant that must close over the implemented month transition. |
| Ledger-owned state | Financial balances whose supported owner/asset semantics are controlled by the runtime ledger contract. |
| Behavioral state | Agent, bank, market, and institutional state used for decisions, diagnostics, or unsupported metrics. |
| Month boundary | The public state surface before and after one executable monthly transition. |
| Evidence artifact | A generated or hand-maintained document that links model claims to code, matrices, runs, or validation outputs. |

## Where To Go Next

For the scientific model, start with [model-specification.md](../model-specification.md).
For ABM structure, scheduling, and entities, read
[odd-model-documentation.md](../odd-model-documentation.md). For the SFC layer,
read [model-equations-to-sfc-map.md](../model-equations-to-sfc-map.md) and
[sfc-matrix-evidence.md](../sfc-matrix-evidence.md). For implementation
boundaries, start with [architecture/index.md](../architecture/index.md).

## References

- Godley, W. and Lavoie, M., *Monetary Economics: An Integrated Approach to
  Credit, Money, Income, Production and Wealth*, Palgrave Macmillan,
  2007/2012 editions.
- Caiani, A., Godin, A., Caverzasi, E., Gallegati, M., Kinsella, S., and
  Stiglitz, J. E., "Agent Based-Stock Flow Consistent Macroeconomics: Towards
  a Benchmark Model", *Journal of Economic Dynamics and Control*, 69, 2016,
  375-408, https://doi.org/10.1016/j.jedc.2016.06.001.
- Dawid, H. and Delli Gatti, D., "Agent-Based Macroeconomics", in Hommes, C.
  and LeBaron, B. (eds.), *Handbook of Computational Economics*, Vol. 4,
  Elsevier, 2018, 63-156.
