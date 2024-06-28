/usr/bin/xvfb-run -e /dev/stdout  --server-args "-screen 0 1024x768x24" java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8002 -Dlog4j.configurationFile=`pwd`/config.xml -Xmx2G -Xss1M -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -Djava.library.path=/home/tester/.minecraft/bin/3ad733aeb6032294b33ec0493e398208209256bd -Djna.tmpdir=/home/tester/.minecraft/bin/3ad733aeb6032294b33ec0493e398208209256bd -Dorg.lwjgl.system.SharedLibraryExtractPath=/home/tester/.minecraft/bin/3ad733aeb6032294b33ec0493e398208209256bd -Dio.netty.native.workdir=/home/tester/.minecraft/bin/3ad733aeb6032294b33ec0493e398208209256bd -Dminecraft.launcher.brand=minecraft-launcher -Dminecraft.launcher.version=2.26.2 -cp /home/tester/.minecraft/libraries/org/ow2/asm/asm/9.6/asm-9.6.jar:/home/tester/.minecraft/libraries/org/ow2/asm/asm-analysis/9.6/asm-analysis-9.6.jar:/home/tester/.minecraft/libraries/org/ow2/asm/asm-commons/9.6/asm-commons-9.6.jar:/home/tester/.minecraft/libraries/org/ow2/asm/asm-tree/9.6/asm-tree-9.6.jar:/home/tester/.minecraft/libraries/org/ow2/asm/asm-util/9.6/asm-util-9.6.jar:/home/tester/.minecraft/libraries/net/fabricmc/sponge-mixin/0.13.3+mixin.0.8.5/sponge-mixin-0.13.3+mixin.0.8.5.jar:/home/tester/.minecraft/libraries/net/fabricmc/intermediary/1.21/intermediary-1.21.jar:/home/tester/.minecraft/libraries/net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar:/home/tester/.minecraft/libraries/com/github/oshi/oshi-core/6.4.10/oshi-core-6.4.10.jar:/home/tester/.minecraft/libraries/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar:/home/tester/.minecraft/libraries/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar:/home/tester/.minecraft/libraries/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar:/home/tester/.minecraft/libraries/com/ibm/icu/icu4j/73.2/icu4j-73.2.jar:/home/tester/.minecraft/libraries/com/mojang/authlib/6.0.54/authlib-6.0.54.jar:/home/tester/.minecraft/libraries/com/mojang/blocklist/1.0.10/blocklist-1.0.10.jar:/home/tester/.minecraft/libraries/com/mojang/brigadier/1.2.9/brigadier-1.2.9.jar:/home/tester/.minecraft/libraries/com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar:/home/tester/.minecraft/libraries/com/mojang/logging/1.2.7/logging-1.2.7.jar:/home/tester/.minecraft/libraries/com/mojang/patchy/2.2.10/patchy-2.2.10.jar:/home/tester/.minecraft/libraries/com/mojang/text2speech/1.17.9/text2speech-1.17.9.jar:/home/tester/.minecraft/libraries/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar:/home/tester/.minecraft/libraries/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar:/home/tester/.minecraft/libraries/commons-logging/commons-logging/1.2/commons-logging-1.2.jar:/home/tester/.minecraft/libraries/io/netty/netty-buffer/4.1.97.Final/netty-buffer-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-codec/4.1.97.Final/netty-codec-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-common/4.1.97.Final/netty-common-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-handler/4.1.97.Final/netty-handler-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-resolver/4.1.97.Final/netty-resolver-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-transport-classes-epoll/4.1.97.Final/netty-transport-classes-epoll-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-transport-native-epoll/4.1.97.Final/netty-transport-native-epoll-4.1.97.Final-linux-aarch_64.jar:/home/tester/.minecraft/libraries/io/netty/netty-transport-native-epoll/4.1.97.Final/netty-transport-native-epoll-4.1.97.Final-linux-x86_64.jar:/home/tester/.minecraft/libraries/io/netty/netty-transport-native-unix-common/4.1.97.Final/netty-transport-native-unix-common-4.1.97.Final.jar:/home/tester/.minecraft/libraries/io/netty/netty-transport/4.1.97.Final/netty-transport-4.1.97.Final.jar:/home/tester/.minecraft/libraries/it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar:/home/tester/.minecraft/libraries/net/java/dev/jna/jna-platform/5.14.0/jna-platform-5.14.0.jar:/home/tester/.minecraft/libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar:/home/tester/.minecraft/libraries/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar:/home/tester/.minecraft/libraries/org/apache/commons/commons-compress/1.26.0/commons-compress-1.26.0.jar:/home/tester/.minecraft/libraries/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar:/home/tester/.minecraft/libraries/org/apache/httpcomponents/httpclient/4.5.13/httpclient-4.5.13.jar:/home/tester/.minecraft/libraries/org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.jar:/home/tester/.minecraft/libraries/org/apache/logging/log4j/log4j-api/2.22.1/log4j-api-2.22.1.jar:/home/tester/.minecraft/libraries/org/apache/logging/log4j/log4j-core/2.22.1/log4j-core-2.22.1.jar:/home/tester/.minecraft/libraries/org/apache/logging/log4j/log4j-slf4j2-impl/2.22.1/log4j-slf4j2-impl-2.22.1.jar:/home/tester/.minecraft/libraries/org/jcraft/jorbis/0.0.17/jorbis-0.0.17.jar:/home/tester/.minecraft/libraries/org/joml/joml/1.10.5/joml-1.10.5.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-freetype/3.3.3/lwjgl-freetype-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-freetype/3.3.3/lwjgl-freetype-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-jemalloc/3.3.3/lwjgl-jemalloc-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-jemalloc/3.3.3/lwjgl-jemalloc-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-openal/3.3.3/lwjgl-openal-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-openal/3.3.3/lwjgl-openal-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-stb/3.3.3/lwjgl-stb-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-stb/3.3.3/lwjgl-stb-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-tinyfd/3.3.3/lwjgl-tinyfd-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl-tinyfd/3.3.3/lwjgl-tinyfd-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar:/home/tester/.minecraft/libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar:/home/tester/.minecraft/libraries/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.jar:/home/tester/.minecraft/libraries/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar:/home/tester/.minecraft/versions/fabric-loader-0.15.11-1.21/fabric-loader-0.15.11-1.21.jar net.fabricmc.loader.impl.launch.knot.KnotClient -DFabricMcEmu= net.minecraft.client.main.Main --username SingularityNet --gameDir /home/tester/.minecraft --assetsDir /home/tester/.minecraft/assets --assetIndex 17 --accessToken 5 --userType msa --versionType release --version fabric-loader-0.15.11-1.21
pid="$!"
echo process $pid
wait $pid
