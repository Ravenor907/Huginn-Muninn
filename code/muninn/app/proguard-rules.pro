# Muninn: zxing is used directly; nothing reflective.
-dontwarn com.google.zxing.**

# WorkManager creates the inbox worker by name.
-keep class io.github.ravenor907.muninn.net.InboxWorker { *; }

# Ultra privacy: Tor (tor-android) runs through JNI, which reads and writes TorService's fields by name, and jtorctl
# talks to its control socket.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn org.torproject.jni.**
-dontwarn net.freehaven.tor.control.**

# Read aloud: the speech engine (sherpa-onnx) reads its config classes' fields by name from native code and calls back
# into Kotlin; WorkManager creates the voice download's worker by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-keep class io.github.ravenor907.muninn.net.ReadAloud$VoiceWorker { *; }
