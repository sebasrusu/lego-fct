#!/bin/bash

set -e

echo "Stopping o  AKS..."

# Parar o cluster (não elimina nada, apenas desliga os nós)
az aks stop \
  --resource-group cc2526 \
  --name lego-aks-cluster

echo "Stopped with success"

# chmod +x scripts/stop-cluster.sh
# ./scripts/stop-cluster.sh