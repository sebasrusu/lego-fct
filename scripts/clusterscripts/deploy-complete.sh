#!/bin/bash

set -e

RESOURCE_GROUP="cc2526"
CLUSTER_NAME="lego-aks-cluster"
ACR_NAME="cc2526acr"
IMAGE_NAME="lego-webapp"
IMAGE_TAG="v1"

echo "=========================================="
echo " DEPLOY COMPLETO, Viva o Benfica"
echo "=========================================="

echo ""
echo "Raising the clusters"
az aks start \
  --resource-group $RESOURCE_GROUP \
  --name $CLUSTER_NAME

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
  --resource-group $RESOURCE_GROUP \
  --name $CLUSTER_NAME \
  --overwrite-existing

echo ""
echo "Compilling the App"
mvn clean package -f webapp/pom.xml -DskipTests

echo "Building docker img and compose"
docker build -t $IMAGE_NAME:$IMAGE_TAG .

echo "ACR Login for container management"
az acr login --name $ACR_NAME

echo "Tag and Push to Azure ACR"
docker tag $IMAGE_NAME:$IMAGE_TAG $ACR_NAME.azurecr.io/$IMAGE_NAME:$IMAGE_TAG
docker push $ACR_NAME.azurecr.io/$IMAGE_NAME:$IMAGE_TAG

echo ""
echo "Creating secret"
kubectl create secret generic azure-secrets --from-env-file=.env 2>/dev/null || echo "  (Secret já existe)"

echo ""
echo "Sync ACR ao AKS..."
az aks update \
  --name $CLUSTER_NAME \
  --resource-group $RESOURCE_GROUP \
  --attach-acr $ACR_NAME

echo ""
echo "deploying kubernetes"
kubectl apply -f k8s/deployment.yaml

echo ""
echo "Get pods ready"
kubectl rollout status deployment/lego-webapp --timeout=5m

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
EXTERNAL_IP=$(kubectl get svc lego-webapp-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "   http://$EXTERNAL_IP"

echo ""
echo " Para parar o cluster:"
echo "   ./scripts/stop-cluster.sh"
echo ""

#pvc test and deploy
kubectl apply -f k8s7pvc-media.yaml
kubectl get pvc media-pvc --watch
kubectl get pvc
#  Restart do deployment
kubectl rollout restart deployment/lego-webapp
kubectl get pods

kubectl describe deploy lego-webapp | grep -i image

kubectl get svc lego-webapp-service

# chmod +x scripts/deploy-complete.sh
# ./scripts/deploy-complete.sh

#test ip external endpoints:
#kubectl get svc lego