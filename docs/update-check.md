# Check for updates — the two times the program touches the internet

**Status: built in 1.8.0-beta, 2026-10-31. This is now history**, the plan the feature was built
from, kept because it records why each decision went the way it did. Written 2026-10-31 against
1.7.1-beta after a design conversation. The three open points in §11 were decided as leaned: the
old AppImage is deleted, the release body is the CHANGELOG section, the proxy flag is on. Where the
build departed from the text below:

- **Windows unpacks in-process.** §4.3's helper called `Expand-Archive`; the program now expands
  the zip itself with `java.util.zip` before it exits, so `omega-update.cmd` is plain `cmd` — wait,
  copy, start, clean up — and needs no PowerShell.
- **Save before launch.** §4.2 started the new AppImage and then exited through the normal
  shutdown, which saves the session; the new process would have read a session the old one was
  still writing. The installer's last step now runs inside the shutdown, after the save and the
  device release and before the log closes, on both platforms. The child AppImage also gets the
  runtime's own variables (`APPIMAGE`, `APPDIR`, `OWD`, `ARGV0`) removed from its environment.
- **Download-only has a destination.** Where the copy cannot replace itself the button reads
  Download, the verified file goes to the user's Downloads folder (else home), and the line names
  it with the reason.
- **A developer override.** `-Domega.update.source=<folder>` answers the two requests and the
  download from canned files, so the whole path can be driven against a release that does not
  exist. Nothing shipped sets it; it never touches the network.
- **The Linux swap is unverified on hardware** at the time of shipping; `known-issues.md` §8.

The idea in one paragraph. The About dialog gets a **Check for updates** button. Pressing it asks
GitHub for the newest published release and says either "you have the newest version" or shows
the new version, its notes and an **Update** button. Pressing that downloads the bundle for this
platform, verifies it, replaces the installed program and relaunches it. **Those two presses are
the only times Omega connects to anything**, and that promise is written where the user can read
it — on the dialog, in README.md — and pinned by a test, so it stays true.

---

## 1. What the user sees

Open ☰ → About. Under the version and the licence lines there is one button, **Check for
updates**, and a line of small text beside it:

> Omega never connects to the internet on its own. This button, and the Update button it may
> show, are the only two times it does. It fetches the release list from github.com and sends
> nothing about you.

Press it. The button greys and the line reads "Asking GitHub…". Within a second or two one of
these replaces it:

- **"You have the newest version, 1.8.0-beta."** Nothing else appears.
- **"1.8.1-beta is available."** followed by the release notes (a few lines, scrollable if
  longer) and an **Update** button. Beside it a second small line: "Downloads
  `Omega-Plugin-1.8.1-beta-Windows.zip` (61 MB) from github.com, checks it, and replaces this
  program. Your settings and presets are kept."
- **"Could not reach GitHub."** with the reason in plain words — no network, a proxy that
  refused, GitHub busy (rate limited), a response that did not parse. No retry on its own.
- **"This copy is newer than the newest release."** for a dev build run from a jar that is ahead
  of main. Nothing else; the dialog is honest rather than clever.
- **"Version unknown."** when run from loose classes (`gradlew run`), where the jar manifest is
  absent and there is nothing to compare. The line offers the Releases page URL as text.

Press **Update**. If a stream is running, a `ConfirmDialog` says so first — "Updating stops the
sound and closes Omega. Continue?" — because the apply step ends the process. Then the button
greys and the line shows progress, "Downloading… 23 MB of 61 MB", then "Checking…", then
"Restarting…", and the window closes. A moment later the new version opens with the same
session, because the session, settings and presets were never inside the program folder.

If anything fails after the download starts, the download is deleted, the installed program is
untouched, and the line says what happened and what to do instead: "Could not write to the
program folder. Download the new version from the Releases page and unzip it over this one."

The dialog is otherwise what it was. `Licences.about` still answers "what is this and which
version"; the button is below those lines, not among them.

## 2. The promise, and the four places it lives

The claim is: **no network traffic except on those two presses.** Not at startup, not weekly, not
when a stream fails, not to fetch a logo. That is a stronger promise than most desktop software
makes and it is the right one for this audience (`omega-audience-is-musicians`): a guitarist
plugging in at a gig should never wonder what the effects box is doing with the Wi-Fi.

It is stated in four places, each for a different reader:

1. **On the About dialog**, in the line under the button — the user deciding whether to press.
2. **In README.md**, a short section titled **Network**, placed after "Get it" — the user
   deciding whether to download. It replaces the current "Updating is manual on both" paragraph
   and adds the one honest caveat: the request goes to github.com, which sees the requesting IP
   address the way any download does; Omega adds no identifier, no settings, nothing about the
   machine beyond the user agent string GitHub's API requires, which is the program name and
   version.
3. **In CLAUDE.md and CONTRIBUTING.md**, as a rule — the contributor: *no code outside
   `org.example.update` may open a socket or import `java.net` beyond URI parsing*, and the update
   package itself only acts on a user's press.
4. **In a test**, mechanically — `NoNetworkOutsideUpdaterTest` walks `src/main/kotlin`, and fails
   on any file outside `org/example/update/` that mentions `java.net.http`, `HttpURLConnection`,
   `URL.openConnection`, `Socket` or `URLConnection`. Crude, and that is fine: the point is that
   nobody adds a quiet fetch without a test turning red and a reviewer asking why.

## 3. The check

### 3.1 The request

One HTTPS GET to GitHub's REST API:

    https://api.github.com/repos/Sam-GN/Omega-Plugin/releases?per_page=10

with the headers GitHub requires: `Accept: application/vnd.github+json`,
`X-GitHub-Api-Version: 2022-11-28`, and `User-Agent: Omega-Plugin/<version>` — GitHub refuses a
request with no user agent, and the version is the only thing of ours it carries. Unauthenticated,
which allows 60 requests an hour per address; a button nobody presses sixty times an hour.

**The releases list, not `releases/latest`.** The `latest` endpoint skips pre-releases, and every
Omega release is a beta. The list endpoint returns published releases newest first, pre-releases
included, and **never drafts** — an unauthenticated caller cannot see a draft. That is exactly the
behaviour wanted: the maintainer publishing the draft is the moment users are told, and a draft
that fails review is never announced.

Ten entries rather than one, because the newest *published* release is chosen by comparing
versions, not by trusting list order; a hot-fix published for an older line, or a release
published out of order, must not win.

### 3.2 Parsing

`util/Json.parse` already turns a JSON document into maps and lists; nothing new is needed. From
each entry take `tag_name`, `prerelease`, `draft` (always false here, checked anyway), `body`,
`html_url` and the `assets` list — each asset's `name`, `size` and `browser_download_url`.

### 3.3 Versions

`Versions.parse("v1.8.1-beta")` → numbers `[1, 8, 1]` and suffix `beta`. Compare the numbers
first; equal numbers, no suffix beats a suffix (a final beats its beta); two suffixes compare as
text. The leading `v` is optional. A tag that does not parse is skipped, not fatal. The running
version is the jar's `Implementation-Version`, which `build.gradle.kts` already writes and
`TitleBar.showAbout` already reads; the only new consumer is the comparison.

### 3.4 Choosing the asset

By platform, by name, from the chosen release's assets:

| Platform | Asset | Checksum entry |
| --- | --- | --- |
| Windows | `Omega-Plugin-<version>-Windows.zip` | same name in `SHA256SUMS.txt` |
| Linux | `Omega-Plugin-<version>-Linux.AppImage` | same name in `SHA256SUMS.txt` |

`LinuxPlatform.isLinux` decides which, as everywhere else. A release with no asset for this
platform — a Windows-only hot-fix, say — reports "1.8.1-beta is available for Windows only" and
offers no Update button. `SHA256SUMS.txt` is a new release asset; see §6.

### 3.5 The notes

`body` is shown as the notes. Today the workflow has `generate_release_notes: true`, so the body is
GitHub's list of commit titles, which is fine for a developer and noise for a guitarist. §6 changes
the workflow to put the version's own CHANGELOG section there instead, so the release page and the
dialog show the same human-written text. The dialog caps the notes at a dozen lines and offers
"Read more on GitHub" as text.

## 4. The update

The check is a day's work; this is the rest. The two platforms need different mechanics because a
running program can replace itself on Linux and cannot on Windows.

### 4.1 Download and verify, both platforms

- Stream the asset to a `.part` file with one reused 64 KB buffer, reporting progress from
  `Content-Length`. **Where** differs: on Linux, beside the running AppImage, because the final
  step is a rename and a rename must stay on one filesystem; on Windows, in `%TEMP%`, because the
  program folder may not be writable and the helper copies from there.
- Fetch `SHA256SUMS.txt` from the same release, find the asset's line, and compare with the
  file's SHA-256 (`java.security.MessageDigest`, in `java.base`). A mismatch deletes the file and
  reports "The download did not match what the build produced" — there is no retry button on
  purpose; a corrupt or tampered asset is a reason to go and look, not to press again.
- HTTPS to GitHub is the trust anchor and the checksum is the proof that what arrived is what CI
  built; the download URL redirects to `objects.githubusercontent.com` and `HttpsURLConnection`
  follows the redirect itself.

### 4.2 Linux: swap the AppImage

The AppImage runtime sets `APPIMAGE` to the running image's path and `APPDIR` to its mount.
When `APPIMAGE` is set:

1. The `.part` is beside it. Mark it executable, rename it to its final name, which is the
   versioned filename and so **differs** from the old one.
2. Start the new image with `ProcessBuilder`, output to `DISCARD`, not inherited — the child must
   not hold the parent's descriptors open.
3. Delete the old image. Unlinking a running AppImage is safe: the kernel keeps the inode mapped
   until the process exits, which is what every AppImage updater relies on.
4. Exit through the normal shutdown path, which saves the session.

When `APPIMAGE` is **not** set — run from the jar, from `gradlew run`, or from a hand-extracted
AppDir — the Update button does the download and verify only and then says where the file is. No
guessing.

The README's manual recipe for Linux, "download, chmod +x, delete the old one", is exactly what
this automates, so the README paragraph becomes a sentence.

### 4.3 Windows: a helper that waits

The exe bundle is one folder: `OmegaPlugin.exe`, `app\`, `runtime\`, and the zip wraps it in
`OmegaPlugin-<version>\`. While the program runs, the exe and the runtime's DLLs are locked, so
nothing in-process can replace them. The pattern is a helper script that runs after the process
exits:

1. Find the installed folder: the `jpackage.app-path` system property is the launcher's path when
   run from the exe. Absent, this is a jar or `gradlew run`, and Update does download-and-verify
   only, as on Linux.
2. Before downloading, **prove the folder is writable** by creating and deleting a scratch file in
   it. A folder under `Program Files` fails here, and the dialog says so and offers the download
   only; asking for elevation is out of scope for this version and probably for ever.
3. Write `%TEMP%\omega-update.cmd` that: waits for this process id to exit (a `tasklist` loop or
   PowerShell's `Wait-Process`); expands the zip to a temp folder (`Expand-Archive`); deletes
   `app\*.jar` in the install folder, because the jar is versioned by name and the launcher's
   `.cfg` names the new one, so a stale jar would be dead weight; copies the new folder's contents
   over the install folder with `robocopy /E`; starts the new `OmegaPlugin.exe`; deletes the zip,
   the temp folder and itself.
4. Start the helper detached (`cmd /c start /min`), then exit through the normal shutdown path.

**In place, into the user's folder, not beside it.** The alternative — extract a new
`OmegaPlugin-1.8.1-beta\` beside the old one and delete the old — is how the README tells people
to do it by hand, but it moves the exe and breaks every shortcut and taskbar pin the user made.
Keeping the folder and replacing its contents keeps those working, which is what "update" means
to the user.

SmartScreen's warning on an unsigned exe appears on the new exe's first run exactly as it does
today; this changes nothing there and the dialog should not pretend otherwise.

### 4.4 The stream and the shutdown

Check and download may run while the stream runs: both are on a plain worker thread, allocate
only their one buffer, and never touch the engine. Apply does not: it goes through the same
shutdown as the ✕ on the title bar — stop the stream, save the session and config, then exit —
because a process replaced underneath a running stream is undefined behaviour with a speaker
attached. The confirm dialog in §1 is what makes that visible.

## 5. Code shape

A fourth package, `org.example.update`, which `ui` calls and which imports nothing from `audio`
or `dsp`:

| Type | Job |
| --- | --- |
| `Versions` | parse and compare tags. Pure. |
| `Release`, `Asset` | data read out of the JSON. |
| `ReleasePicker` | from parsed JSON to "the newest published release and this platform's asset, or why not". Pure. |
| `Checksums` | parse `SHA256SUMS.txt`, hash a file, compare. Pure apart from the file read. |
| `UpdateSource` | the seam: `fetch(url): String` and `download(url, to, progress)`. |
| `GitHubSource` | the real one, on `HttpsURLConnection`. The only type that opens a socket. |
| `Installer` | `canApply(): String?` (null, or the reason it cannot) and `apply(file)`. |
| `AppImageInstaller`, `WindowsInstaller` | §4.2 and §4.3. |
| `Updater` | the orchestration the dialog drives: a worker thread, state reported through `invokeLater`. |

`HttpsURLConnection` rather than `java.net.http.HttpClient`, deliberately: the client lives in its
own module, `java.net.http`, and would be a second addition to the jlink list for no gain on two
requests and one download.

The dialog: `ConfirmDialog.show` today takes lines and an OK button. About needs a variant with a
status line and one action button below the lines, built from the same `PushButton` and
`Theme` pieces so it is dark like the rest (the "opt in by name" rule in CLAUDE.md). The headless
side gets a flag, `--check-update`, which prints what the dialog would show and exits; §6 uses it.

The JVM honours the system proxy only when told: set `java.net.useSystemProxies=true` in the
launchers' flags so a machine behind a school or office proxy can reach GitHub without a setting
Omega would otherwise have to grow.

## 6. Build and workflow changes

Three, and the first is the one that bites.

1. **`portableModules` gains `jdk.crypto.ec`.** On Java 21 the elliptic-curve TLS code is a module
   of its own (it folded into `java.base` in 22), and GitHub's certificates and key exchange need
   it. Without it the check works under `gradlew run` and fails inside every shipped bundle with a
   handshake error that reads like a network fault. The comment above the list currently says
   "nothing here opens a socket" and must change to say what does and why the module is there.
   `jdk.localedata` stays out. The `cacerts` trust store is in `java.base` and already ships.
2. **The release publishes `SHA256SUMS.txt`** beside the two bundles: the publish job runs
   `sha256sum` over `dist/*` and adds the file to `files:`. One line per asset, the standard
   format, so a user can check by hand too.
3. **The release body is the CHANGELOG section**, not generated notes: `generate_release_notes`
   off, `body_path` pointing at a file the version job extracts from `CHANGELOG.md` — the text
   between the version's `## [` heading and the next `---`. The CHANGELOG is written for the
   release page's reader already; this stops the two from disagreeing.

And the smoke test grows one line: after the AppImage's `--list-backends`, run
`--check-update` and require only that it got as far as an HTTP status from GitHub — not that a
release exists, not a particular answer, because CI must not fail on GitHub's rate limit or on an
empty release list. That one line is what pins trap 1 mechanically: a bundle that cannot complete
a TLS handshake fails there and nowhere else.

## 7. Tests

All offline, as every test in the suite is. Through the seam, against canned responses in
`src/test/resources/update/`:

- **`VersionsTest`** — ordering, the beta suffix, `v` optional, a tag that is not a version, equal
  versions.
- **`ReleasePickerTest`** — a captured releases JSON: picks the newest by version, not by position;
  skips an unparseable tag; finds this platform's asset and reports a release without one; reports
  "newer than newest" for a dev version; ignores a draft entry if one ever appeared.
- **`ChecksumsTest`** — parses the sums file, matches a known file, rejects a changed byte, reports
  an asset missing from the list.
- **`NoNetworkOutsideUpdaterTest`** — §2 item 4.
- **`AppImageInstallerTest`** — the rename-and-delete sequence against a temp directory with a
  fake `APPIMAGE`, without launching anything (the launch is injected).
- **`WindowsInstallerTest`** — the generated helper script's text against a golden copy, and the
  writability probe against a read-only temp folder.

Nothing Swing, nothing on a real socket. The real socket is exercised by `--check-update` in CI and
by pressing the button.

## 8. Documents that change when this ships

- **README.md** — the "Updating is manual" paragraph goes; a **Network** section (§2 item 2)
  arrives, and the Windows "unzip over" and Linux "chmod" recipes shrink to the fallback for when
  Update cannot apply.
- **CLAUDE.md** — a paragraph under Architecture: the promise, the rule, `jdk.crypto.ec`, the
  draft-invisibility point, the in-place Windows decision. The docs table row for this file
  changes from "plan" to "history".
- **CONTRIBUTING.md** — the no-network rule, one line.
- **`docs/WhatsDone.md`** — the delivered entry.
- **`docs/known-issues.md`** — one entry if the `Program Files` fallback ships as a limitation
  rather than being solved, which §4.3 says it does.

## 9. Failure modes and their words

Every failure is one sentence a musician can act on, and none of them retries by itself.

| What happened | What the line says |
| --- | --- |
| No route, DNS failure, timeout | "Could not reach GitHub. Check the connection and press again." |
| HTTP 403 with a rate-limit header | "GitHub is busy. Try again in an hour." |
| HTTP 404 or malformed JSON | "GitHub answered, but not with a release list." |
| No asset for this platform | "1.8.1-beta is available, but not for Linux yet." |
| Checksum mismatch | "The download did not match what the build produced, so it was deleted." |
| Program folder not writable | "Could not write to the program folder. Download it from the Releases page and unzip it over this one." |
| Not run from a bundle | "Downloaded to … — this copy was not installed from a bundle, so it cannot replace itself." |
| Running version unknown | "Version unknown, so there is nothing to compare. Releases: github.com/…" |

## 10. Order of work

1. `Versions`, `ReleasePicker`, `Checksums` with their tests. Pure; half a day.
2. `GitHubSource`, `Updater`, the `--check-update` flag. Run it from the jar against the real
   repo; first contact with the rate limit and the user agent rule.
3. `jdk.crypto.ec`, then run `--check-update` from the **bundle** on both platforms. This is the
   step that is not optional.
4. The About dialog's button, status line and notes.
5. `AppImageInstaller`, tested on the laptop with a real previous release.
6. `WindowsInstaller` and its helper script; test from a folder under the user profile and from
   one under `Program Files` to see both branches.
7. Workflow: checksums, CHANGELOG body, smoke-test line. Verify on a release of 1.8.0-beta
   itself — which means the first version that can update is the one *after* 1.8.0, and 1.8.0's
   notes should say so.
8. README, CLAUDE.md, CONTRIBUTING.md, WhatsDone.

## 11. Open, and only these

- **Keep the old AppImage?** §4.2 deletes it. Keeping one previous version beside the new one is
  a cheap undo; it is also a second 90 MB file the user did not ask for. Lean: delete, as the
  README has always told them to.
- **Notes: CHANGELOG section or generated?** §6 item 3 says CHANGELOG. If the maintainer would
  rather write release notes on the release page by hand, drop the `body_path` change and the
  dialog shows whatever the page shows.
- **Proxy flag default.** `java.net.useSystemProxies=true` is harmless when there is no proxy and
  the difference between working and not on a managed network. Lean: on.

Everything else above is decided.
