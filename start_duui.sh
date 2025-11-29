#!/bin/bash
docker start duui-sentiment duui-hatecheck duui-factchecking
sleep 5
echo "✓ All DUUI containers started and ready"
docker ps | grep duui