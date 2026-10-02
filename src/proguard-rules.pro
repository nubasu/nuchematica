# Annotation classes are optional at runtime.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# Forge discovers these classes reflectively.
-keep class mods.orca.examplemod.ExampleMod { *; }
-keep class mods.orca.examplemod.registry.RegistryHandler { *; }
-keep class mods.orca.examplemod.proxy.ClientProxy { *; }
-keep class mods.orca.examplemod.proxy.DedicatedProxy { *; }

# Suppress duplicate Kotlin class notes.
-dontnote kotlin.**

# Minify without obfuscating.
-dontobfuscate
