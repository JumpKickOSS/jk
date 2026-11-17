#!/bin/sh
# Birthday cards for the front desk: each pet of the first three owners with its birth date.
set -eu
for owner in 1 2 3; do
  curl -fsS "http://localhost:8080/api/owners/$owner/pets" | jq -r '.[] | "\(.name)\t\(.birthDate)"'
done
