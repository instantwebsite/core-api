#! /usr/bin/env bash
set -e

HOST="176.9.1.25"
PORT="9125"

if [[ -z "$ANNOTATE_API_TOKEN" ]]; then
    echo "Must provide ANNOTATE_API_TOKEN to deploy" 1>&2
    exit 1
fi

echo "## Cleaning"
rm -rf target/
lein clean
echo "## Compiling"
lein uberjar
echo "## Uploading"
scp -P $PORT target/uberjar/instant-website-0.1.0-SNAPSHOT-standalone.jar root@$HOST:/instant-website-backend/instant-website.jar
echo "## Restarting"
ssh root@$HOST -p $PORT systemctl restart instant-website

REV=$(git rev-parse HEAD)

curl -XPOST https://instantwebsite.grafana.net/api/annotations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ANNOTATE_API_TOKEN" \
  --data @- << EOF
  {
    "text": "Deployed backend@$REV",
    "tags": [
      "deployment", "backend"
    ]
  }
EOF
