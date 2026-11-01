# protobuf-lite reads message fields reflectively; keep them in apps that minify.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
