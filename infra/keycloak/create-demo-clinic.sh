#!/bin/bash
# Creates one demo Organization ("Demo Clinic") and adds every clinic-scoped
# demo user (clinic_admin/provider/front_desk/pharmacist/accountant - not
# patient or platform_admin, neither of which is tied to a clinic) as a
# member. realm-export.json's plain "users" array creates the accounts and
# their realm roles on import, but Organization membership isn't part of
# that same import (see the reliability note below) - this script is the
# other half, meant to be run once right after `docker compose up` finishes
# importing the realm.
#
# NOTE ON RELIABILITY: the Organizations REST API is newer than the rest of Keycloak's
# admin API and its exact payload shape can vary slightly between Keycloak versions.
# This script is a solid starting point, not a guaranteed-correct one for every version -
# after running it, open the Admin Console (Organizations section, left nav) and confirm
# the org and membership look right before wiring application code against them.
set -e

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="clinic"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
ORG_ALIAS="demo-clinic"

echo "Authenticating as admin..."
TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASSWORD" \
  -d "grant_type=password" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo "Creating organization 'Demo Clinic'..."
ORG_RESPONSE=$(curl -s -i -X POST "$KEYCLOAK_URL/admin/realms/$REALM/organizations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
        \"name\": \"Demo Clinic\",
        \"alias\": \"$ORG_ALIAS\",
        \"enabled\": true,
        \"domains\": [{ \"name\": \"democlinic.example\", \"verified\": true }]
      }")

ORG_ID=$(echo "$ORG_RESPONSE" | grep -i "^location:" | sed -E 's#.*/organizations/([a-f0-9-]+).*#\1#' | tr -d '\r')

if [ -z "$ORG_ID" ]; then
  # Most likely a re-run against an environment that already has this org
  # (a 409 on the alias, no Location header) - idempotent fallback: look it
  # up by alias instead of failing outright. `?search=` on this endpoint
  # hasn't proven reliable in practice, so list every org and match by
  # alias rather than relying on server-side filtering.
  echo "No new org created (already exists?) - looking up '$ORG_ALIAS' by alias instead..."
  ORG_ID=$(curl -s -X GET "$KEYCLOAK_URL/admin/realms/$REALM/organizations" \
    -H "Authorization: Bearer $TOKEN" \
    | python3 -c "import sys,json; orgs=json.load(sys.stdin); m=[o for o in orgs if o.get('alias')=='$ORG_ALIAS']; print(m[0]['id']) if m else print('')")
fi

if [ -z "$ORG_ID" ]; then
  echo "Could not create or find the '$ORG_ALIAS' organization - create it manually in the Admin Console instead. Original create response:"
  echo "$ORG_RESPONSE"
  exit 1
fi
echo "Using organization: $ORG_ID"

# Every realm-export.json demo user tied to one clinic - clinic_admin,
# provider, front_desk, pharmacist, accountant. Not demo-patient (patients
# aren't Organization members at all) or demo-platform-admin (acts across
# every tenant, deliberately not scoped to one org).
CLINIC_STAFF_USERS=(demo-clinic-admin demo-provider demo-front-desk demo-pharmacist demo-accountant)

for USERNAME in "${CLINIC_STAFF_USERS[@]}"; do
  echo "Looking up $USERNAME user id..."
  USER_ID=$(curl -s -X GET "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$USERNAME&exact=true" \
    -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id']) if d else print('')")

  if [ -z "$USER_ID" ]; then
    echo "  $USERNAME not found in this realm - skipping (realm-export.json may not have been imported, or the user was renamed)."
    continue
  fi

  echo "Adding $USERNAME ($USER_ID) as a member of the organization..."
  curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/organizations/$ORG_ID/members" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "\"$USER_ID\""
  echo ""
done

echo ""
echo "Done. Organization id (Keycloak-internal, not what goes in the app's db): $ORG_ID"
echo ""
echo "Now: create a matching row in the app's 'clinics' table with keycloak_org_id = the org ALIAS,"
echo "not the id above. Keycloak's built-in oidc-organization-membership-mapper (used by the"
echo "'organization' client scope) puts the alias, not the id, in the token's organization claim -"
echo "see the comment on TenantContextFilter.extractOrgId."
echo "  e.g. INSERT INTO clinics (keycloak_org_id, name) VALUES ('$ORG_ALIAS', 'Demo Clinic');"
