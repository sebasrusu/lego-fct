#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ===== CONFIGURATION =====
# Docker Compose
ENV_FILE="$ROOT_DIR/.env.compose"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"

# Azure Config
RESOURCE_GROUP="cc2526"
CLUSTER_NAME="lego-aks-cluster"
ACR_NAME="cc2526acr"
IMAGE_NAME="lego-webapp"
IMAGE_TAG="latest"
ACR_URL="${ACR_NAME}.azurecr.io"
K8S_NAMESPACE="default"
K8S_DIR="$ROOT_DIR/k8s"
ENV_K8S_FILE="$ROOT_DIR/.env"

# Detect docker compose
if command -v docker compose &> /dev/null; then
  DOCKER_COMPOSE="docker compose"
elif command -v docker-compose &> /dev/null; then
  DOCKER_COMPOSE="docker-compose"
else
  echo "ERROR: docker compose is not installed!"
  exit 1
fi

# ===== FUNCTIONS =====
show_menu() {
  echo ""
  echo "=========================================="
  echo " LEGO APP - CENTRAL DEPLOY"
  echo "=========================================="
  echo ""
  echo "Choose an option:"
  echo ""
  echo "LOCAL (Docker Compose):"
  echo "  1) Deploy LOCAL (Docker Compose)"
  echo "  2) Stop/Clean LOCAL"
  echo ""
  echo "KUBERNETES (Azure AKS):"
  echo "  3) Deploy to KUBERNETES"
  echo "  4) Stop KUBERNETES"
  echo ""
  echo "UTILITIES:"
  echo "  5) View status all"
  echo "  6) View logs LOCAL"
  echo "  7) View logs KUBERNETES"
  echo "  8) Exit"
  echo ""
  read -p "Option [1-8]: " choice
}

# ===== STAGE 1: BUILD =====
build_app() {
  echo ""
  echo "=========================================="
  echo "Building application"
  echo "=========================================="
  echo ""
  
  cd "$ROOT_DIR"
  mvn clean package -f webapp/pom.xml -DskipTests
  
  if [ ! -f "webapp/target/cc2526-webapp-1.0.war" ]; then
    echo "ERROR: WAR was not generated!"
    return 1
  fi
  echo "SUCCESS: WAR generated successfully"
  return 0
}

# ===== DEPLOY LOCAL =====
deploy_local() {
  echo ""
  echo "=========================================="
  echo "LOCAL DEPLOY - DOCKER COMPOSE"
  echo "=========================================="
  echo ""
  
  # Build app
  if ! build_app; then
    return 1
  fi
  
  # Validate .env.compose
  if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: File .env.compose not found!"
    echo "Creating .env.compose with default values..."
    cat > "$ENV_FILE" << 'EOF'
DB_NAME=ccdb
DB_USER=admin
DB_PASSWORD=senhaSegura123
SPRING_PROFILES_ACTIVE=prod
EOF
    echo "SUCCESS: .env.compose created. Edit if necessary!"
  fi
  
  # Cleanup old environment
  echo ""
  echo "Cleaning up previous environment..."
  $DOCKER_COMPOSE -f "$COMPOSE_FILE" down --remove-orphans -v 2>/dev/null || true
  sleep 2
  docker container prune -f 2>/dev/null || true
  
  # Build images
  echo ""
  echo "Building Docker images..."
  $DOCKER_COMPOSE -f "$COMPOSE_FILE" build --no-cache
  
  # Start with retry
  echo ""
  echo "Starting containers..."
  MAX_RETRIES=3
  RETRY=0
  
  while [ $RETRY -lt $MAX_RETRIES ]; do
    if $DOCKER_COMPOSE -f "$COMPOSE_FILE" up -d; then
      echo "SUCCESS: Containers started successfully!"
      break
    else
      RETRY=$((RETRY + 1))
      if [ $RETRY -lt $MAX_RETRIES ]; then
        echo "WARNING: Attempt $RETRY failed. Waiting 3s..."
        sleep 3
      fi
    fi
  done
  
  if [ $RETRY -eq $MAX_RETRIES ]; then
    echo "ERROR: Failed to start containers"
    return 1
  fi
  
  # Verify
  echo ""
  echo "Waiting for services to be ready..."
  sleep 5
  
  echo ""
  echo "Container status:"
  $DOCKER_COMPOSE -f "$COMPOSE_FILE" ps
  
  echo ""
  echo "=========================================="
  echo "LOCAL DEPLOY SUCCESSFUL!"
  echo "=========================================="
  echo ""
  echo "Endpoints:"
  echo "   WebApp:   http://localhost:8080"
  echo "   Redis:    localhost:6379"
  echo "   MongoDB: localhost:27017"
  echo ""
  echo "Useful commands:"
  echo "   View logs:     $DOCKER_COMPOSE -f $COMPOSE_FILE logs -f webapp"
  echo "   View mongo: $DOCKER_COMPOSE -f $COMPOSE_FILE logs -f mongo"
  echo "   View redis:    $DOCKER_COMPOSE -f $COMPOSE_FILE logs -f redis"
  echo ""
  return 0
}

# ===== STOP LOCAL =====
stop_local() {
  echo ""
  echo "=========================================="
  echo "STOPPING DOCKER COMPOSE"
  echo "=========================================="
  echo ""
  
  $DOCKER_COMPOSE -f "$COMPOSE_FILE" down -v
  echo "SUCCESS: Docker Compose stopped and cleaned"
  echo ""
}

# ===== DEPLOY KUBERNETES =====
deploy_kubernetes() {
  echo ""
  echo "=========================================="
  echo "KUBERNETES DEPLOY - AZURE AKS"
  echo "=========================================="
  echo ""
  
  # Build app
  if ! build_app; then
    return 1
  fi
  
  # Docker build & push
  echo ""
  echo "=========================================="
  echo "BUILD AND PUSH TO AZURE ACR"
  echo "=========================================="
  echo ""
  
  FULL_IMAGE_NAME="$ACR_URL/$IMAGE_NAME:$IMAGE_TAG"
  echo "Building Docker image..."
  echo "   Image: $FULL_IMAGE_NAME"
  
  docker build -t "$FULL_IMAGE_NAME" "$ROOT_DIR" \
    --build-arg BUILD_DATE="$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
    --build-arg VCS_REF="$(git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
  
  echo "SUCCESS: Image built"
  
  echo ""
  echo "Logging in to Azure ACR..."
  az acr login --name "$ACR_NAME" || {
    echo "ERROR: Failed to login to ACR"
    return 1
  }
  
  echo "Pushing image to ACR..."
  docker push "$FULL_IMAGE_NAME"
  echo "SUCCESS: Image pushed"
  
  # Kubernetes setup
  echo ""
  echo "=========================================="
  echo "KUBERNETES PREPARATION"
  echo "=========================================="
  echo ""
  
  echo "Starting AKS cluster..."
  az aks start \
    --resource-group "$RESOURCE_GROUP" \
    --name "$CLUSTER_NAME" || {
    echo "WARNING: Cluster was already running"
  }
  
  echo "Waiting for nodes to be ready..."
  for i in {1..30}; do
    READY=$(kubectl get nodes 2>/dev/null | grep -c "Ready" || echo "0")
    if [ "$READY" -ge 2 ]; then
      echo "SUCCESS: Nodes ready!"
      break
    fi
    echo "  [$i/30] Nodes ready: $READY/2"
    sleep 10
  done
  
  echo "Connecting to AKS cluster..."
  az aks get-credentials \
    --resource-group "$RESOURCE_GROUP" \
    --name "$CLUSTER_NAME" \
    --overwrite-existing
  
  echo "Configuring AKS to ACR access..."
  az aks update \
    --resource-group "$RESOURCE_GROUP" \
    --name "$CLUSTER_NAME" \
    --attach-acr "$ACR_NAME" 2>/dev/null || {
    echo "WARNING: ACR was already configured"
  }
  
  # Deploy Kubernetes
  echo ""
  echo "=========================================="
  echo "DEPLOYING TO KUBERNETES"
  echo "=========================================="
  echo ""
  
  # Validate files
  if [ ! -d "$K8S_DIR" ]; then
    echo "ERROR: Directory $K8S_DIR does not exist!"
    return 1
  fi
  
  # Create namespace
  echo "Creating namespace..."
  kubectl create namespace "$K8S_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
  
  # Delete old secret and create new one
  if [ -f "$ENV_K8S_FILE" ]; then
    echo "Updating secret..."
    kubectl delete secret azure-secrets -n "$K8S_NAMESPACE" 2>/dev/null || true
    sleep 1
    kubectl create secret generic azure-secrets \
      --from-env-file="$ENV_K8S_FILE" \
      --namespace="$K8S_NAMESPACE" 2>/dev/null || {
      echo "WARNING: Secret creation had issues. Continuing..."
    }
    echo "SUCCESS: Secret created/updated"
  fi
  
  # Apply manifests in order
  echo ""
  echo "Applying Kubernetes manifests..."
  
  [ -f "$K8S_DIR/pvc-media.yaml" ] && {
    echo "  + Media PVC..."
    kubectl apply -f "$K8S_DIR/pvc-media.yaml" -n "$K8S_NAMESPACE"
  }
  
  [ -f "$K8S_DIR/mongodb.yaml" ] && {
    echo "  + MongoDB..."
    kubectl apply -f "$K8S_DIR/mongodb.yaml" -n "$K8S_NAMESPACE"
  }
  
  [ -f "$K8S_DIR/redis.yaml" ] && {
    echo "  + Redis..."
    kubectl apply -f "$K8S_DIR/redis.yaml" -n "$K8S_NAMESPACE"
  }
  
  [ -f "$K8S_DIR/app-deployment.yaml" ] && {
    echo "  + App Deployment..."
    kubectl apply -f "$K8S_DIR/app-deployment.yaml" -n "$K8S_NAMESPACE"
  } || [ -f "$K8S_DIR/deployment.yaml" ] && {
    echo "  + App Deployment..."
    kubectl apply -f "$K8S_DIR/deployment.yaml" -n "$K8S_NAMESPACE"
  }
  
  [ -f "$K8S_DIR/app-service.yaml" ] && {
    echo "  + Service..."
    kubectl apply -f "$K8S_DIR/app-service.yaml" -n "$K8S_NAMESPACE"
  }
  
  echo "SUCCESS: Manifests applied"
  
  # Verification
  echo ""
  echo "=========================================="
  echo "DEPLOY VERIFICATION"
  echo "=========================================="
  echo ""
  
  echo "Waiting for rollout..."
  for DEPLOY in legoapp lego-webapp; do
    if kubectl get deployment "$DEPLOY" -n "$K8S_NAMESPACE" &>/dev/null; then
      kubectl rollout status deployment/"$DEPLOY" -n "$K8S_NAMESPACE" --timeout=5m
      break
    fi
  done
  
  echo ""
  echo "Pods:"
  kubectl get pods -n "$K8S_NAMESPACE"
  
  echo ""
  echo "Services:"
  kubectl get svc -n "$K8S_NAMESPACE"
  
  # Wait for IP
  echo ""
  echo "Waiting for external IP (1-2 minutes)..."
  for i in {1..30}; do
    for SVC in legoapp-service lego-webapp-service; do
      EXTERNAL_IP=$(kubectl get svc "$SVC" -n "$K8S_NAMESPACE" \
        -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
      
      if [ -n "$EXTERNAL_IP" ] && [ "$EXTERNAL_IP" != "null" ]; then
        echo ""
        echo "=========================================="
        echo "KUBERNETES DEPLOY SUCCESSFUL!"
        echo "=========================================="
        echo ""
        echo "SUCCESS: LoadBalancer IP: $EXTERNAL_IP"
        echo "   Access: http://$EXTERNAL_IP"
        echo ""
        return 0
      fi
    done
    
    echo "  [$i/30] Waiting for IP..."
    sleep 4
  done
  
  echo ""
  echo "WARNING: IP not yet available. Try later:"
  echo "   kubectl get svc -n $K8S_NAMESPACE"
  echo ""
  return 0
}

# ===== STOP KUBERNETES =====
stop_kubernetes() {
  echo ""
  echo "=========================================="
  echo "STOPPING KUBERNETES"
  echo "=========================================="
  echo ""
  
  read -p "Clean all resources in $K8S_NAMESPACE? (y/n): " confirm
  if [ "$confirm" = "y" ]; then
    echo "Deleting resources..."
    kubectl delete all --all -n "$K8S_NAMESPACE" 2>/dev/null || true
    kubectl delete pvc --all -n "$K8S_NAMESPACE" 2>/dev/null || true
    kubectl delete secret azure-secrets -n "$K8S_NAMESPACE" 2>/dev/null || true
    echo "SUCCESS: Resources deleted"
  fi
  
  echo ""
  read -p "Stop AKS cluster? (y/n): " confirm
  if [ "$confirm" = "y" ]; then
    az aks stop \
      --resource-group "$RESOURCE_GROUP" \
      --name "$CLUSTER_NAME"
    echo "SUCCESS: Cluster stopped"
  fi
  echo ""
}

# ===== STATUS =====
show_status() {
  echo ""
  echo "=========================================="
  echo "OVERALL STATUS"
  echo "=========================================="
  echo ""
  
  echo "LOCAL - Docker Compose:"
  $DOCKER_COMPOSE -f "$COMPOSE_FILE" ps 2>/dev/null || echo "   No containers running"
  
  echo ""
  echo "KUBERNETES:"
  if kubectl cluster-info &>/dev/null; then
    echo "   Pods:"
    kubectl get pods -n "$K8S_NAMESPACE" 2>/dev/null || echo "   No pods"
    echo ""
    echo "   Services:"
    kubectl get svc -n "$K8S_NAMESPACE" 2>/dev/null || echo "   No services"
  else
    echo "   Kubernetes not available"
  fi
  echo ""
}

# ===== LOGS =====
show_logs_local() {
  echo ""
  read -p "Which service? (webapp/mongo/redis/all): " service
  case "$service" in
    all)
      $DOCKER_COMPOSE -f "$COMPOSE_FILE" logs -f
      ;;
    *)
      $DOCKER_COMPOSE -f "$COMPOSE_FILE" logs -f "$service"
      ;;
  esac
}

show_logs_k8s() {
  echo ""
  echo "Available pods:"
  kubectl get pods -n "$K8S_NAMESPACE"
  echo ""
  read -p "Pod name: " pod
  kubectl logs -f "$pod" -n "$K8S_NAMESPACE"
}

# ===== MAIN LOOP =====
while true; do
  show_menu
  
  case $choice in
    1)
      deploy_local
      ;;
    2)
      stop_local
      ;;
    3)
      deploy_kubernetes
      ;;
    4)
      stop_kubernetes
      ;;
    5)
      show_status
      ;;
    6)
      show_logs_local
      ;;
    7)
      show_logs_k8s
      ;;
    8)
      echo "Goodbye!"
      exit 0
      ;;
    *)
      echo "ERROR: Invalid option!"
      ;;
  esac
done