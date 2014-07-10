#!/bin/sh

provisioner --scale 2

for i in {1..10}
do
coordinator --memberWorkerCount 2 \
	--clientWorkerCount 2 \
	--duration 1m \
	--parallel \
	../../test.properties
done

provisioner --download