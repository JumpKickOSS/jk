#!/bin/sh
# Post-deploy smoke test: the pages a front-desk user opens first must answer.
set -eu
curl -fsS -o /dev/null http://localhost:8080/
curl -fsS -o /dev/null http://localhost:8080/owners/find
curl -fsS -o /dev/null http://localhost:8080/vets.html
echo "smoke: ok"
