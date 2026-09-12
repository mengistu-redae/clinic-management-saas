#!/bin/bash
# Creates one demo Organization ("Demo Clinic") and adds demo-clinic-admin as a member.
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
  echo "Could not determine the new org id from the response below - create it manually in the Admin Console instead:"
  echo "$ORG_RESPONSE"
  exit 1
fi
echo "Created organization: $ORG_ID"

echo "Looking up demo-clinic-admin user id..."
USER_ID=$(curl -s -X GET "$KEYCLOAK_URL/admin/realms/$REALM/users?username=demo-clinic-admin&exact=true" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")

echo "Adding demo-clinic-admin ($USER_ID) as a member of the organization..."
curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/organizations/$ORG_ID/members" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "\"$USER_ID\""

echo ""
echo "Done. Organization id (Keycloak-internal, not what goes in the app's db): $ORG_ID"
echo ""
echo "Now: create a matching row in the app's 'clinics' table with keycloak_org_id = the org ALIAS,"
echo "not the id above. Keycloak's built-in oidc-organization-membership-mapper (used by the"
echo "'organization' client scope) puts the alias, not the id, in the token's organization claim -"
echo "see the comment on TenantContextFilter.extractOrgId."
echo "  e.g. INSERT INTO clinics (keycloak_org_id, name) VALUES ('$ORG_ALIAS', 'Demo Clinic');"
