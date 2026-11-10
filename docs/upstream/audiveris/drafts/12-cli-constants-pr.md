# Draft pull request (not posted): CLI `-constant` ignored for Main's constants

**Branch:** `vilpter/audiveris` `fix/cli-constants` (87a019426), one commit on `development` at c91b1c456.
**Title:** Apply -constant values to constants registered before the CLI is parsed

---

Since 1bd9a633a ("Another step on i18n"), `Main` reads its `locale` constant in a static initializer:

```java
private static final Constants constants = new Constants();

static {
    readLocale();
}
```

Reading one constant registers all of `Main`'s constants. At that point the command line hasn't been parsed: `Main.getCli()` is still null. So `ConstantManager.addConstant` finds no CLI value for them, and any `-constant org.audiveris.omr.Main.<name>=<value>` is silently ignored.

**To reproduce,** run any single page with a one-second step limit:

```
Audiveris -batch -transcribe -export -constant org.audiveris.omr.Main.sheetStepTimeOut=1 -- page.png
```

- **5.11.0:** stops at the first step that takes longer than a second (`Timeout 1 seconds for step BEAMS`).
- **`development`:** runs all 20 steps under the default 120 s limit and exports.

**The change:**
- `ConstantManager.applyCliConstants()` gives the constants that are already registered their CLI values.
- `Main.processCli` calls it right after `cli.parseParameters(args)`.
- A constant registered later still gets its CLI value in `addConstant`, as before.
- Only constants named on the command line are touched. A name that matches no registered constant is ignored, as before.

**Checked:**
- With the change, the command above times out at BEAMS again, as 5.11.0 does.
- Without `-constant`, the same page exports identical MusicXML on `development` and on this branch (apart from the encoding date).
- `./gradlew test` passes.
