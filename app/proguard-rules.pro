# Shard release rules.
#
# The app has no reflection-driven serialization, so the default AndroidX and
# Material rules cover almost everything. What follows is the small set of
# things R8 cannot infer.

# Views constructed from XML need their (Context, AttributeSet) constructors.
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep the syntax highlighters: they are looked up by language name.
-keep class com.ccs.shard.editor.codeHighliter.** { *; }

# Line numbers make Play Console crash reports usable while hiding sources.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JGit also runs on desktop Java and contains optional JMX monitoring and
# Kerberos/SPNEGO authentication paths. Android has neither API; Shard uses
# HTTPS Basic/PAT credentials, so these optional branches are unreachable.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
