# Design QA

- Source visual truth paths:
  - `/workspace/scratch/935faf66eda7/upload/02-3862.jpg`
  - `/workspace/scratch/935faf66eda7/upload/03-3866.jpg`
  - `/workspace/scratch/935faf66eda7/upload/04-3865.jpg`
  - `/workspace/scratch/935faf66eda7/upload/05-3868.jpg`
  - `/workspace/scratch/935faf66eda7/upload/06-3869.jpg`
- Implementation screenshot path: unavailable in this workspace; the target is a native Android APK and no Android emulator/device capture is available.
- Reference viewport: 720 × 1544 and 690 × 1536 source screenshots at device density.
- Implementation viewport / density: device-dependent Android layout in dp/sp; not capturable here.
- State: main list, new message, message composer, settings, blocking settings, and about.

**Full-view comparison evidence**

The source images were opened and used to define the visible hierarchy, spacing, colors, radii, titles, cards, search field, FAB, recipient chips, composer, settings groups, and blocking groups. A matching rendered implementation screenshot could not be captured in this environment.

**Focused region comparison evidence**

Blocked for the same reason. The native APK must be installed on the target Huawei device before typography, status-bar insets, keyboard resizing, switch rendering, and manufacturer font metrics can be compared reliably.

**Findings**

- [P1] Native visual comparison is not available.
  - Location: all target screens.
  - Evidence: source screenshots are available, but no same-state implementation screenshots exist yet.
  - Impact: compile success does not prove pixel-level fidelity on EMUI / HarmonyOS.
  - Fix: install build 1004, capture the six matching screens, then compare and tighten dimensions/colors.

**Required fidelity surfaces**

- Fonts and typography: implemented with Android system typography and reference-sized sp values; device comparison pending.
- Spacing and layout rhythm: implemented in dp from the reference proportions; device comparison pending.
- Colors and visual tokens: implemented with fixed cyan, blue, green, gray, white, and text tokens sampled/inferred from the references; device comparison pending.
- Image quality and asset fidelity: the screens use contact photos and existing vector icon-library resources; device comparison pending.
- Copy and content: requested Chinese labels and removals are implemented.

**Primary interactions checked**

- Kotlin/resource compilation passed in GitHub Actions.
- APK archive integrity passed.
- Runtime taps, keyboard behavior, and console/logcat errors require a physical Android device.

**Comparison history**

- Initial implementation: no rendered native capture available.
- Compile iteration 1: corrected resource namespaces and color imports.
- Compile iteration 2: corrected the common person-icon resource namespace.
- Final compile: passed; visual comparison remains blocked.

final result: blocked
