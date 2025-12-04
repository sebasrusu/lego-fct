#!/bin/bash

set -e

# Configurações
RESOURCE_GROUP="cc2526"
CLUSTER_NAME="lego-aks-cluster"
DEPLOYMENT_NAME="legoapp" 

# Caminhos
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

# Função para iniciar o cluster
function start_cluster() {
    echo ""
    echo "🚀 Raising o cluster AKS..."
    az aks start \
      --resource-group "$RESOURCE_GROUP" \
      --name "$CLUSTER_NAME"

    # Envia o .env para o cluster assim que ele liga
    echo "🔄 Sending .env to cluster secrets..."
    if [ -f "$ENV_FILE" ]; then
        kubectl create secret generic lego-app-secrets \
            --from-env-file="$ENV_FILE" \
            --dry-run=client -o yaml | kubectl apply -f -
        echo "✅ Secrets synced."
    else
        echo "⚠️ Warning: .env not found at $ENV_FILE"
    fi

    echo "Getting up..."
    kubectl get nodes
    echo "✅ Cluster launched with success"
}

# Função para atualizar .env e reiniciar (Hot Reload)
function update_env_restart() {
    echo ""
    echo "🔄 Updating Secrets from .env..."
    
    if [ -f "$ENV_FILE" ]; then
        # 1. Atualiza o segredo no Kubernetes
        kubectl create secret generic lego-app-secrets \
            --from-env-file="$ENV_FILE" \
            --dry-run=client -o yaml | kubectl apply -f -
        
        echo "✅ Secrets updated."
        echo "🔄 Restarting deployment ($DEPLOYMENT_NAME) to apply changes..."
        
        # 2. Reinicia o deployment para ler as novas variáveis
        kubectl rollout restart deployment "$DEPLOYMENT_NAME"
        
        echo "⏳ Waiting for rollout..."
        kubectl rollout status deployment/"$DEPLOYMENT_NAME"
        
        echo "✅ Update complete!"
    else
        echo "❌ Error: .env file not found at $ENV_FILE"
    fi
}

# Menu Principal
while true; do
    echo ""
    echo "=========================================="
    echo " 🧱 LEGO CLUSTER MANAGER"
    echo "=========================================="
    echo "1) Start Cluster (Ligar + Enviar .env)"
    echo "2) Update .env & Restart Pods (Hot Reload)"
    echo "3) Exit"
    echo "=========================================="
    read -p "Select an option [1-3]: " choice

    case $choice in
        1)
            start_cluster
            ;;
        2)
            update_env_restart
            ;;
        3)
            echo "Exiting..."
            exit 0
            ;;
        *)
            # CORREÇÃO AQUI: Fechei as aspas e removi o caractere inválido
            echo "Invalid option. Please select 1, 2 or 3."
            ;;
    esac
done