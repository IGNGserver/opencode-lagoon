plugins {
  id("com.android.application") version "8.13.2" apply false
  id("org.jetbrains.kotlin.android") version "2.2.10" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
// Build output is shared under /tmp to keep the (single-disk) project volume for sources, but the
// leaf directory is namespaced by the checkout path so parallel worktrees of this repository on one
// machine cannot overwrite each other's APK and test results.
val checkoutKey = Integer.toHexString(rootDir.absolutePath.hashCode())
allprojects {
  layout.buildDirectory.set(file("/tmp/lagoon-gradle/$checkoutKey/${project.name}"))
}
