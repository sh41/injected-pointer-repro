# Injected smart pointer restores into the wrong injected file

Minimal reproduction for [IJPL-18265](https://youtrack.jetbrains.com/issue/IJPL-18265). Built from the
[IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template), targeting
IntelliJ IDEA 2026.1.5.

## Run

```shell
./gradlew test
```

| Test | Result | What it shows |
|---|---|---|
| [control][test-only-injection] | passes | one injection restores fine |
| [two injections][test-two-injections] | **fails** | the bug: XML + plain-text over the same range, one pointer logs `Cannot restore ...; restored=null` |
| [1000×, XML first][test-varies] | **fails** | `TEXT failed 378, XML failed 622` (local), `367/633` (CI) |
| [1000×, swapped][test-swap] | **fails** | swap the registration order, the skew swaps: `TEXT failed 626, XML failed 374` |

`./gradlew check` is red on purpose. `--rerun` an analysis test to resample it — Gradle caching is on, so a bare
re-invocation replays the same numbers.

## Cause

[`InjectedSelfElementInfo.getInjectedFileIn`][getInjectedFileIn] collects the candidate injected files with
`ContainerUtil.map2SetNotNull`, then for every candidate whose host range contains the pointer's range does an
unconditional [`result[0] = injectedPsi`][overwrite] — no tie-break, no comparison with the language the pointer's
`Identikit` already recorded. Whichever file the set iterates last wins; the other pointer restores to nothing.

[`SmartPsiElementPointerImpl.createElementInfo`][createElementInfo] only checks this
[in unit-test mode][unitTestModeGuard]. [`restoreElement`][restoreElement] itself never logs — every failure path is a
plain `return null`. So the bug is fully live in production; only the diagnostic is test-only. In production a losing
pointer just returns `null` from `getElement()` whenever something later dereferences it.

## Fix

1. **No disambiguation** — the real fix. [The overwrite][overwrite] should prefer the candidate matching the
   pointer's own `Identikit` language.
2. **An unordered set** — cosmetic on its own. Swapping in [`map2LinkedSet`][map2LinkedSet] would only make the
   first-registered injection lose deterministically, every time.

## Why the split is 62.5/37.5, not 50/50

[`map2SetNotNull`][map2SetNotNull] builds `new HashSet<>(collection.size())` — `new HashSet<>(2)` here, not a
default-capacity-16 set. That resizes to a length-**4** table on the second `add`, giving each file's identity hash
only two bits of entropy (no class in the `PsiFile` chain overrides `hashCode()`). Insertion order is registration
order ([`InjectionRegistrarImpl`'s append order][addFileToResults]), and a `HashMap` resize keeps relative order
within a bucket, so a tie goes to whichever file was registered second.

| outcome | probability | winner |
|---|---|---|
| second's bucket higher | 6/16 | second |
| same bucket (tie → insertion order) | 4/16 | second |
| second's bucket lower | 6/16 | first |

The first-registered injection's pointer fails 10/16 = **62.5%** of the time, the second's 37.5%. Predicted over 1000
runs: 625/375. Measured: 622 (local), 633 (CI), and 626 for the second-registered language once the order is swapped
— all within ~0.5σ (σ≈15.3 at n=1000).

---

**Postscript, speculation — not verified against source:** whether a losing pointer could restore to a *wrong*
element instead of `null` was checked one level: [`Identikit.ByType.findPsiElement`][findPsiElement] asks the wrong
file's own view provider for the pointer's original language via `getPsi(actualLanguage)`, which returns `null` for a
single-language injected file rather than a mismatched element from elsewhere. Not checked across every
injector/view-provider shape, so treat "fails silently, not corruptly" as a lead, not a settled claim.

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)

[createElementInfo]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L154-L172
[getInjectedFileIn]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/InjectedSelfElementInfo.java#L109-L145
[overwrite]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/InjectedSelfElementInfo.java#L120
[map2SetNotNull]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/util/src/com/intellij/util/containers/ContainerUtil.java#L2918-L2923
[addFileToResults]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/InjectionRegistrarImpl.java#L513-L518
[map2LinkedSet]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/util/src/com/intellij/util/containers/ContainerUtil.java#L2903-L2907
[unitTestModeGuard]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L159
[restoreElement]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/InjectedSelfElementInfo.java#L92-L108
[findPsiElement]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/Identikit.java#L65-L74

[test-only-injection]: https://github.com/sh41/injected-pointer-repro/blob/main/src/test/kotlin/com/github/sh41/injectedpointerrepro/InjectedPointerRestoreTest.kt#L90-L99
[test-two-injections]: https://github.com/sh41/injected-pointer-repro/blob/main/src/test/kotlin/com/github/sh41/injectedpointerrepro/InjectedPointerRestoreTest.kt#L101-L111
[test-varies]: https://github.com/sh41/injected-pointer-repro/blob/main/src/test/kotlin/com/github/sh41/injectedpointerrepro/InjectedPointerRestoreTest.kt#L113-L131
[test-swap]: https://github.com/sh41/injected-pointer-repro/blob/main/src/test/kotlin/com/github/sh41/injectedpointerrepro/InjectedPointerRestoreTest.kt#L133-L155
