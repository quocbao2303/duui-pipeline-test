#!/bin/bash

echo "Starting DUUI containers..."

docker run -d -p 9001:9714 --name duui-sentiment sha256:31dd7ae3ae3c0b0e84b7ce5fa504e17ca0c3a6148e624e10e4b9512e9c7f0cb8
docker run -d -p 9002:9714 --name duui-hatecheck docker.texttechnologylab.org/duui-hate-hate-check-eziisk:latest
docker run -d -p 9003:9714 --name duui-factchecking docker.texttechnologylab.org/duui-factchecking-minicheck:latest

echo "Waiting for containers to be ready (10 seconds)..."
sleep 10

echo "✓ All containers started!"
docker ps | grep duui

echo ""
echo "Ready to run: python3 duui_orchestrator.py"