-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-keep class org.libtorrent4j.** { *; }

# SLF4J API and NOP provider — keep from R8 stripping
-keep class org.slf4j.** { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
-dontwarn org.slf4j.**
