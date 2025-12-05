#!/bin/bash

# Para o script se houver qualquer erro
set -e

# --- Configurações ---
RESOURCE_GROUP="cc2526"
CLUSTER_NAME="lego-aks-cluster"
DEPLOYMENT_NAME="legoapp"

# --- Caminhos ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

# --- Funções ---

# Função para iniciar o cluster
function start_cluster() {
    echo ""
    echo " A iniciar o cluster AKS ($CLUSTER_NAME)..."

    # Inicia o cluster. O '|| true' impede que o script pare se o cluster já estiver a correr
    az aks start \
      --resource-group "$RESOURCE_GROUP" \
      --name "$CLUSTER_NAME" || echo "Cluster pode já estar a correr."

    # CRITICO: Garante que o kubectl está a apontar para este cluster
    echo "A obter credenciais do cluster..."
    az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

    # Envia o .env para o cluster
    echo " A enviar .env para os secrets..."
    if [ -f "$ENV_FILE" ]; then
        # Apaga o secret se existir para recriar limpo (opcional, mas evita erros de merge) ou usa o dry-run apply
        kubectl create secret generic lego-app-secrets \
            --from-env-file="$ENV_FILE" \
            --dry-run=client -o yaml | kubectl apply -f -
        echo "Secrets sincronizados."
    else
        echo " Aviso: .env não encontrado em $ENV_FILE"
    fi

    echo " Estado dos nós:"
    kubectl get nodes
    echo " Cluster lançado com sucesso."
}

# Função para atualizar .env e reiniciar (Hot Reload)
function update_env_restart() {
    echo ""
    echo " A atualizar Secrets a partir do .env..."

    # Garante contexto antes de tentar update (caso tenhas mudado de terminal)
    az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

    if [ -f "$ENV_FILE" ]; then
        # 1. Atualiza o segredo no Kubernetes
        kubectl create secret generic lego-app-secrets \
            --from-env-file="$ENV_FILE" \
            --dry-run=client -o yaml | kubectl apply -f -

        echo " Secrets atualizados."
        echo "A reiniciar deployment ($DEPLOYMENT_NAME) para aplicar alterações..."

        # 2. Reinicia o deployment para ler as novas variáveis
        kubectl rollout restart deployment "$DEPLOYMENT_NAME"

        echo " A aguardar pelo rollout..."
        kubectl rollout status deployment/"$DEPLOYMENT_NAME"

        echo " Atualização completa!"
    else
        echo " Erro: ficheiro .env não encontrado em $ENV_FILE"
    fi
}

# --- Execução Principal ---

# Verifica se o argumento é "update"
if [ "$1" == "update" ]; then
    update_env_restart
else
    start_cluster
fi