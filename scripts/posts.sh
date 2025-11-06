```bash
#!/usr/bin/env bash
# Smoke tests contra https://cc2526.azurewebsites.net/rest
# Uso: ./smoke-azure.sh
set -u

BASE="https://cc2526.azurewebsites.net/rest"
TMP_IMG="$(mktemp /tmp/lego-smoke.XXXX.jpg)"
# criar ficheiro simples para enviar como image/jpeg
printf "lego-smoke" > "$TMP_IMG"

uuidgen_cmd() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    python3 - <<'PY'
import uuid,sys
print(uuid.uuid4())
PY
  fi
}

echo; echo "=== BASE: $BASE ==="; echo

echo "==== 1) /ctrl/version ===="
curl -i "$BASE/ctrl/version" || true
echo; echo

U_ID=$(uuidgen_cmd)
U_NAME="smoke_${U_ID:0:6}"
U_PWD="pass123"

echo "==== 2) Create user POST /user ===="
curl -i -X POST "$BASE/user" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"$U_ID\",\"name\":\"$U_NAME\",\"pwd\":\"$U_PWD\",\"photoId\":\"\",\"legoIds\":[]}" || true
echo; echo

echo "==== 3) GET /user/{id} ===="
curl -i "$BASE/user/$U_ID" || true
echo; echo

echo "==== 4) LIST /user ===="
curl -i "$BASE/user" || true
echo; echo

echo "==== 5) POST /media (upload) ===="
MEDIA_RESP=$(curl -i -s -X POST "$BASE/media" -H "Content-Type: image/jpeg" --data-binary @"$TMP_IMG") || true
echo "$MEDIA_RESP"
# tentar extrair id (se retornado no body entre "...")
MEDIA_ID=$(echo "$MEDIA_RESP" | sed -n '1,200p' | tr -d '\r' | awk 'NR>1{print}' | tr -d '\n' | sed -E 's/.*"([^"]+)".*/\1/' || true)
echo "Extracted MEDIA_ID: $MEDIA_ID"
echo; echo

if [[ -n "$MEDIA_ID" ]]; then
  echo "==== 6) HEAD GET /media/{id} ===="
  curl -i -I "$BASE/media/$MEDIA_ID" || true
  echo; echo
fi

echo "==== 7) POST /legoset ===="
LS_ID=$(uuidgen_cmd)
LS_NAME="smoke_ls_${LS_ID:0:6}"
curl -i -X POST "$BASE/legoset" -H "Content-Type: application/json" \
  -d "{\"id\":\"$LS_ID\",\"name\":\"$LS_NAME\",\"codeNumber\":\"000\",\"description\":\"smoke\",\"photoId\":\"$MEDIA_ID\",\"ownerId\":\"$U_ID\"}" || true
echo; echo

echo "==== 8) GET /legoset/{id} ===="
curl -i "$BASE/legoset/$LS_ID" || true
echo; echo

echo "==== 9) GET /legoset ===="
curl -i "$BASE/legoset" || true
echo; echo

echo "==== 10) POST /auction ===="
AUC_ID=$(uuidgen_cmd)
CLOSE_DATE_MS=$(( $(date +%s) * 1000 + 3600000 ))
curl -i -X POST "$BASE/auction" -H "Content-Type: application/json" \
  -d "{\"id\":\"$AUC_ID\",\"legoSetId\":\"$LS_ID\",\"sellerId\":\"$U_ID\",\"basePrice\":10,\"closeDate\":$CLOSE_DATE_MS}" || true
echo; echo

echo "==== 11) GET /auction?legoSetId= ===="
curl -i "$BASE/auction?legoSetId=$LS_ID" || true
echo; echo

echo "==== 12) POST /auction/{id}/bid ===="
curl -i -X POST "$BASE/auction/$AUC_ID/bid" -H "Content-Type: application/json" \
  -d "{\"auctionId\":\"$AUC_ID\",\"userId\":\"$U_ID\",\"amount\":15}" || true
echo; echo

echo "==== 13) POST /user/auth ===="
AUTH_RESP=$(curl -i -s -X POST "$BASE/user/auth" -H "Content-Type: application/json" -d "{\"user\":\"$U_ID\",\"pwd\":\"$U_PWD\"}") || true
echo "$AUTH_RESP"
COOKIE=$(echo "$AUTH_RESP" | awk -F': ' '/Set-Cookie/ {print $2; exit}' | tr -d '\r' || true)
echo "Cookie: $COOKIE"
echo; echo

echo "==== 14) GET /user/me (with cookie) ===="
if [[ -n "$COOKIE" ]]; then
  curl -i -b "$COOKIE" "$BASE/user/me" || true
else
  echo "No cookie from auth, skipping /user/me"
fi
echo; echo

echo "==== 15) Cleanup DELETE /legoset and /user ===="
curl -i -X DELETE "$BASE/legoset/$LS_ID" || true
curl -i -X DELETE "$BASE/user/$U_ID" || true
echo; echo

rm -f "$TMP_IMG"
echo "=== Done ==="
```