-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep interface eu.kanade.tachiyomi.source.Source { public *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.Source { public protected *; }
# Older extensions call Kotlin interface compatibility bridges directly.
-keep class eu.kanade.tachiyomi.source.**$DefaultImpls { public *; }

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }
