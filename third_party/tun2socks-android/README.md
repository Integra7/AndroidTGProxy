# universal-android-tun2socks
An universal (badvpn)tun2socks for Android, with easy usage, just need call Java APIs...

## Build aar
`./gradlew tun2socks:assembleRelease`

## Use in flutter
* `./gradlew tun2socks:assembleRelease`
* Copy so file you need from `tun2socks/build/intermediates/stripped_native_libs/release/out/lib` to flutter project's `android/src/main/jniLibs/`
* Copy `tun2socks/src/main/java/com/LondonX/tun2socks/Tun2Socks.java` in to flutter project's `android/src/main/kotlin/com/LondonX/tun2socks/Tun2Socks.java`
