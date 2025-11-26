#!/bin/bash

set -e

echo "Raising o cluster AKS..."

# Levantar o cluster
az aks start \
  --resource-group cc2526 \
  --name lego-aks-cluster

echo "Getting up."
kubectl get nodes -w

echo "Cluster lauched with success"

# chmod +x scripts/start-cluster.sh
# ./scripts/start-cluster.sh