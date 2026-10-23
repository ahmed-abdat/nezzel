# Jackson 2.11.1 inspects runtime annotations and generic types. Preserve that
# metadata for consumers too (the app's AGP optimize defaults also include it).
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# Jackson's optional Java7SupportImpl probes these JavaBeans annotations, absent
# on Android. Java7Support catches Throwable and disables that optional helper;
# tree parsing does not require it. Suppress only these verified missing types.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# Cached 0.18.1 library/common/ffmpeg bytecode audit: Python/FFmpeg are launched
# with ProcessBuilder; no Java native methods, JNI callbacks or reflective
# bridge loading. AGP's defaults already protect native method names if added.
# aria2 is not in the locked runtime graph. Bundled yt-dlp is referenced through
# R.raw.ytdlp directly, so resource shrinking can see it.
#
# Nezzel and YoutubeDLUpdater use Jackson readTree/streaming, not POJO binding.
# JsonNodeDeserializer is selected directly. YoutubeDL.getInfo and its mapper
# VideoInfo/VideoFormat/VideoSubtitle/VideoThumbnail models are unused: allow
# them to shrink. Re-audit this boundary before adopting getInfo/readValue.
# Gson is test-tooling-only; kotlinx-serialization is absent.
#
# OkHttp Android 5.5.0 ships proguard.txt for optional TLS providers/annotations;
# use those rules. Okio JVM 3.18.2 ships no rules and needs no model keeps here.
# No application DTO is parsed via reflection; do not keep com.yausername.**,
# com.fasterxml.jackson.**, okhttp3.** or okio.** wholesale.

# youtubedl-android unpacks Python/FFmpeg with commons-compress 1.12, whose
# ExtraFieldUtils creates zip extra fields via Class.newInstance(). R8 removed
# those no-arg constructors, so first-run extraction failed and the release
# engine stayed unavailable (device, 2026-10-19). Keep only the constructors.
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField { <init>(); }

# NewPipeExtractor v0.26.5 (YouTube options; rules mirror the NewPipe app's).
# TimeAgoPatternsManager loads the per-language pattern classes by name.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# StreamInfo and its items are Serializable; keep their serial form intact if ever parcelled.
-keepclassmembers class org.schabi.newpipe.extractor.** implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
# Rhino (signature/n-param JS) binds Java members and generated classes reflectively.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
# Rhino's JSR-223 engine and optional tools reference JDK-only APIs absent on Android.
-keep class org.mozilla.javascript.engine.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# protobuf-javalite reads GeneratedMessageLite fields by name (YouTube request/response messages).
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
