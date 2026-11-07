# Cash Drive product source

Android AAOS debug app, Godot renderer and local sandbox reward server. Real OAuth, ad supply, external rewards and vehicle validation remain unverified.

Companion planning/tooling repository: https://github.com/JongGakSeo/pleos-aaos-cash-drive-master

User decision (2026-11-07): demonstration first, using the teammate's Park Lounge as reference. Claude owns UI/assets with the B "full-screen scene" design; Codex owns vehicle/rewards and integration. The user supplies 3D artwork. Use the companion docs/DIRECTION_REVIEW.md and COLLABORATION.md for the current decision. NC credentials use the ignored API_PLATFORM variable.

Read AGENTS.md and the companion docs/COLLABORATION.md before editing. Every session needs its own checkout/branch. The initial snapshot started on codex/setup-20261107 with no remote commits; the user authorized private main/dev initialization. Subsequent feature branches start from dev.

Build from the master checkout with CASHDRIVE_APP_ROOT pointing here and CASHDRIVE_TOOLS_ROOT pointing to the prepared local toolchain:

```sh
scripts/android.sh :core:test :app:assembleDemoDebug :app:assembleEmuDebug
sh scripts/godot-check.sh
```

Run server tests from this product root:

```sh
python3 -m unittest server.test_backend -v
```

android/local.properties and the .tools symlink are local setup, not portable source. docs/ and .agents/ are ignored local links into the master checkout. APKs under android/app/build/outputs are build outputs, not tracked binaries. Source-local README files document renderer/server contracts; broader docs and scripts are maintained in the companion repository.
