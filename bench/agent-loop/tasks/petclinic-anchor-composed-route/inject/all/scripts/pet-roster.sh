#!/bin/sh
# Nightly roster for the front desk: the pets of the first three owners.
set -eu
for owner in 1 2 3; do
  curl -fsS "http://localhost:8080/api/owners/$owner/pets"
done
# The clinic's longest-standing client, checked on its own.
curl -fsS http://localhost:8080/api/owners/6/pets > /dev/null
