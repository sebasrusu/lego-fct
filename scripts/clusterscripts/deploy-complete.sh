#!/bin/bash

set -e

RESOURCE_GROUP="cc2526"
CLUSTER_NAME="lego-aks-cluster"
ACR_NAME="cc2526acr"
IMAGE_NAME="lego-webapp"
IMAGE_TAG="latest"

# Diretório onde está este script (scripts/clusterscripts)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"


echo "=========================================="
echo " DEPLOY COMPLETO, Viva o Benfica"
echo "=========================================="
echo ""
echo "SCRIPT_DIR = $SCRIPT_DIR"
echo "ROOT_DIR   = $ROOT_DIR"
echo ""

echo "Raising the clusters"
az aks start \
  --resource-group "$RESOURCE_GROUP" \
  --name "$CLUSTER_NAME"

echo "waiting for registration."
for i in {1..30}; do
  READY=$(kubectl get nodes 2>/dev/null | grep -c "Ready" || echo "0")
  if [ "$READY" -ge 2 ]; then
    echo "Nodes are done!"
    break
  fi
  echo "  [$i/30] Nós ready: $READY/2"
  sleep 10
done

echo ""
echo "Connecting to cluster"
az aks get-credentials \
  --resource-group "$RESOURCE_GROUP" \
  --name "$CLUSTER_NAME" \
  --overwrite-existing

echo ""
echo "To guarantee connection of AKS to ACR"
az aks update -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --attach-acr "$ACR_NAME"

echo ""
echo "Compilling the App"
cd "$ROOT_DIR"
mvn clean package -f webapp/pom.xml -DskipTests

echo "Building docker img and compose"
docker build -t "$ACR_NAME.azurecr.io/$IMAGE_NAME:$IMAGE_TAG" "$ROOT_DIR"

echo "ACR Login for container management"
az acr login -n "$ACR_NAME"

echo "Tag and Push to Azure ACR"
docker push "$ACR_NAME.azurecr.io/$IMAGE_NAME:$IMAGE_TAG"

echo ""
echo "Creating secret"
kubectl create secret generic azure-secrets --from-env-file="$ROOT_DIR/.env" 2>/dev/null || echo "  (Secret já existe)"

echo ""
echo "Aplicar PVC + Deployment + Service"
kubectl apply -f "$ROOT_DIR/k8s/pvc-media.yaml"
kubectl apply -f "$ROOT_DIR/k8s/app-deployment.yaml"
kubectl apply -f "$ROOT_DIR/k8s/app-service.yaml"

echo ""
echo "Sync ACR ao AKS..."
az aks update \
  --name "$CLUSTER_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --attach-acr "$ACR_NAME"

echo ""
echo "Get pods ready"
kubectl rollout status deployment/legoapp --timeout=5m

echo ""
echo "=========================================="
echo "DEPLOY COMPLETO COM SUCESSO!"
echo "=========================================="
echo ""
echo " Status dos Pods:"
kubectl get pods

echo ""
echo " Status dos Serviços:"
kubectl get svc

echo ""
echo " IP Externo (LoadBalancer):"
EXTERNAL_IP=$(kubectl get svc legoapp-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "   http://$EXTERNAL_IP"

echo ""
echo " Para parar o cluster:"
echo "   ./scripts/clusterscripts/stop-cluster.sh"
echo ""
