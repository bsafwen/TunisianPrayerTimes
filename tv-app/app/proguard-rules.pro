# No keep rules for the app's own code: it uses no reflection. The manifest's components are kept by
# the rules AAPT writes, and kotlinx-serialization, Coil and Compose bring their own.

# Line numbers in the crash traces of the kiosk log; the release's mapping.txt turns the renamed
# classes back into names (the TV Release workflow keeps it with each version).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
