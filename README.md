# Cash Drive product source

Android AAOS debug app, Godot renderer and local sandbox reward server. Real OAuth, ad supply, external rewards and vehicle validation remain unverified.

Companion planning/tooling repository: https://github.com/JongGakSeo/pleos-aaos-cash-drive-master

Read AGENTS.md and the companion docs/COLLABORATION.md before editing. Every session needs its own checkout/branch. This initial local snapshot is on codex/setup-20261107; no remote commits were present at setup.

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
