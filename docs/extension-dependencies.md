# Extension dependency compatibility

Extensions are compiled separately. Their `compileOnly` dependencies are supplied
by the host app at runtime; Gradle cannot resolve their versions together with the
app because extension APKs are loaded dynamically. Extension API versions 1.4 and
1.6 alone do not describe the required Kotlin, coroutine or networking versions.
A source can load successfully and still throw `NoSuchMethodError` on its first
search, details or chapter request.

The reported global-search crash called
`kotlinx.coroutines.BuildersKt.runBlockingK$default`, which does not exist in
coroutines 1.10.2. Coroutines 1.11.0 provides this method and retains the older
`runBlocking$default` entry point. This is a runtime version mismatch, not a
missing Kotlin import. RxJava propagates linkage errors as fatal errors, so the
global-search boundary now handles `LinkageError` for each source. Cancellation
and fatal VM errors still propagate.

The shared dependency baseline was checked against the Keiyoushi extension
catalog at commit `10f06eb833af5990bc5470c84c5e93edc159ad4e` on 2026-10-09:

| Dependency | Previous host | Updated host |
| --- | --- | --- |
| Kotlin compiler, stdlib, reflect and plugins | 2.3.0 | 2.4.10 |
| Coroutines core, Android, Guava and tests | 1.10.2 | 1.11.0 |
| Serialization JSON, JSON/Okio and protobuf | 1.9.0 | 1.11.0 |
| OkHttp and its optional modules | 5.3.2 | 5.4.0 |
| Okio | 3.16.4 | 3.17.0 |
| Jsoup | 1.22.1 | 1.22.2 |
| R8 | 8.13.19 bundled with AGP | 9.1.56 override |

Kotlin 2.4 needs R8 9.1.29 or newer. Keep the existing AGP 8.13.2/Gradle 8.14.3
build and override R8; moving to AGP 9 requires a separate migration of the
Android multiplatform modules. The old context receivers were migrated to named
context parameters, preserving the JVM signature of extension-facing JSON
helpers.

RxJava 1.3.8 and QuickJS `547f5b1597` already match the extension catalog.
Injekt remains the existing Mihon fork; switching forks is not a version bump.
JSpecify is explicitly aligned to 1.0.1. Zstd 0.4.0 already matches through
transitive dependencies; verify its resolved version alongside explicit dependencies.

The combined manga update bridge must dispatch to the suspend detail/chapter APIs,
including sources that implement only the 1.5 methods. Calling the legacy RxJava
methods directly bypasses those overrides and can throw `IllegalStateException:
Not used`. The default bridge uses a coroutine context marker to prevent recursive
calls between individual and combined defaults while retaining 1.4 RxJava and 1.6
combined-only sources. Do not catch a source's `UnsupportedOperationException` as
an API-version signal: that replaces real source failures with misleading fallback
errors. Missing extensions must retain `SourceNotInstalledException`.

Release shrinking is another possible cause of missing methods: the shrinker
cannot see calls made only by external APKs. Keep the public `Source` interface,
its `$DefaultImpls` compatibility bridges, and the extension-facing JavaScript
and HTTP exception APIs. Unit tests check the source and coroutine entry points;
also inspect the final minified DEX because tests use unshrunk classes.

Before accepting new extension releases, run:

```text
python tools/check_extension_dependencies.py
gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
gradlew.bat :app:testDebugUnitTest :core:common:testDebugUnitTest :domain:testDebugUnitTest
gradlew.bat :app:minifyReleaseWithR8
```

The Python check compares the extension compiler's shared dependency requirements
with local catalogs. `OLD` is a failing check; `REVIEW` means a transitive
dependency or a different fork needs manual verification. Version comparisons
are an early warning, not a guarantee of binary compatibility.

References:

- [Extension compiler catalog](https://github.com/keiyoushi/extensions-source/blob/10f06eb833af5990bc5470c84c5e93edc159ad4e/gradle/libs.versions.toml)
- [Extension compile-only dependencies](https://github.com/keiyoushi/extensions-source/blob/10f06eb833af5990bc5470c84c5e93edc159ad4e/core/build.gradle.kts)
- [Android Kotlin/R8 compatibility](https://developer.android.com/build/kotlin-support)
