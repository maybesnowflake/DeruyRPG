#!/bin/bash
cd /tmp/srv && pkill -f paper.jar; pkill -f "sleep 900"; sleep 1
rm -rf world/dimensions/minecraft/deruyrpg_dungeon
rm -f in.fifo && mkfifo in.fifo && (sleep 900 > in.fifo &)
(nohup /tmp/jdk25/bin/java -Xmx3G -jar paper.jar --nogui < in.fifo > log.txt 2>&1 &)
for i in $(seq 1 90); do grep -q "Done (" log.txt && break; sleep 2; done
echo "dg start jungle" > in.fifo; sleep 60
echo "save-all flush" > in.fifo; sleep 8; echo "stop" > in.fifo; sleep 15
pkill -f "sleep 900"; grep -i "exception\|at com.fsixel" log.txt | head -5; echo done
