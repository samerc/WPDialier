# libphonenumber loads its metadata from bundled resources; no reflection.
# osmdroid instantiates tile source modules directly; keep its config class
# names readable in crash logs but let R8 shrink normally.

# Notification RemoteViews layouts reference view classes from XML — kept by
# AAPT; nothing extra needed.

# Keep crash stack traces readable (file names + line numbers survive R8).
-keepattributes SourceFile,LineNumberTable

# osmdroid references some optional/legacy APIs that aren't on modern Android.
-dontwarn org.osmdroid.**
