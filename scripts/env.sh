# source 这个文件后再跑 gradle / adb;工具链刻意不进全局 PATH
export JAVA_HOME="$HOME/Library/Java/jdk-17/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$HOME/Library/Gradle/gradle-8.14.5/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
