java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8006 -Dlog4j.configurationFile=`pwd`/config.xml -Xmx2G -jar mods/fabric-server-mc.1.21-loader.0.16.5-launcher.1.0.1.jar  --nogui
