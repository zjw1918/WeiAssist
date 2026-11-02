# Kotlin's multifile facades (e.g. kotlin.collections.CollectionsKt) are a chain of package-private
# classes (CollectionsKt__IterablesKt ← CollectionsKt__MutableCollectionsJVMKt ← …). If R8 repackages
# one part but not its parent, the app crashes at start with IllegalAccessError. Keep them together.
-keep,allowshrinking,allowoptimization class kotlin.**.*Kt__* { }
