# lz4-java chooses its implementation by class name (LZ4Factory); keep them in apps that minify.
-keep class net.jpountz.lz4.** { *; }
-keep class net.jpountz.util.** { *; }
-dontwarn net.jpountz.**
