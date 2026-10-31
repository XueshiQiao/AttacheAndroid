# vendor/maven

A Maven repository holding exactly one module: `org.connectbot:termlib`, built
from the `attache-ime` branch of a termlib fork (local checkout:
`~/Code/termlib-attache`, branched from upstream tag `0.3.14`).
`build.gradle.kts` lets only that module resolve from here. The branch's
commits are also kept as `vendor/termlib-patches/*.patch`, so the fork can be
rebuilt from upstream `0.3.14` with `git am` if the checkout is gone.

## Why

Compose mode (the IME key) mirrors the IME's composing text in a local
overlay buffer. Upstream 0.3.14 left text there once the IME considered it
final in two ways — a CJK IME accepting a candidate with
`finishComposingText()`, and a Space the IME sends as a key event — while the
Space or Enter that followed went to the shell ahead of it. The text looked
typed but nothing reached the shell until compose mode was toggled off.
`ComposeModeImeCommitTest` in the fork reproduces each case (4 of 7 failed
before the fix); the full suite, 647 tests, passes after it.

## Rebuilding

```sh
cd ~/Code/termlib-attache
PATH=$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH \
  ./gradlew :lib:publishToMavenLocal -PattacheUnsigned \
  -Pversion=0.3.14-attache.N \
  -Dmaven.repo.local=$HOME/Code/AttacheAndroid/vendor/maven
```

then bump `termlib` in `gradle/libs.versions.toml` to the same version.
