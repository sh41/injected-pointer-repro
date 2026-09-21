# Injected smart pointer restores into the wrong injected file

Minimal reproduction for [IJPL-18265](https://youtrack.jetbrains.com/issue/IJPL-18265): when two injections of different
languages cover the same host range, a smart pointer into one of them cannot be restored.

Built from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template),
targeting IntelliJ IDEA 2026.1.5.

## Run

```shell
./gradlew test
```

`InjectedPointerRestoreTest` registers a `MultiHostInjector` for the test's duration, injects over the whole text of an
XML text node, and creates a smart pointer to the first leaf of each injected file. In unit-test mode
`SmartPsiElementPointerImpl.createElementInfo` restores every new pointer immediately and logs `Cannot restore ...` if the
result differs, so pointer creation alone exercises the restore path.

- `testPointerIntoTheOnlyInjectionRestores` — one XML injection. Passes.
- `testPointersIntoTwoInjectionsOverTheSameHostRangeEachRestoreIntoTheirOwnLanguage` — XML and plain-text injections over
  the same range. Fails on every run: one of the two pointers logs
  `Cannot restore ... from injected{...}; restored=null`. Which language loses varies from JVM to JVM.

## Cause

`InjectedSelfElementInfo.getInjectedFileIn` collects the candidate injected files with `ContainerUtil.map2SetNotNull`, a
`HashSet`, and assigns its result for every candidate whose host range contains the pointer's range, so the last file in
hash order wins. It never compares the candidate's language with the root language the pointer recorded in its
`Identikit`, so both pointers resolve to the same file and one of them cannot find its element there.
