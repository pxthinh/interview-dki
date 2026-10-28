# Seed 200 users + 3-5 bookings/room/day over 20 days (UTF-8 safe for Windows)
# Usage: .\scripts\seed-load-test.ps1          (seed only)
#        .\scripts\seed-load-test.ps1 -Sync    (also enqueue calendar_outbox CREATE rows, see step 4 in the SQL)
param([switch]$Sync)
$ErrorActionPreference = 'Stop'

docker compose cp scripts/seed-load-test.sql db:/tmp/seed-load-test.sql
if ($LASTEXITCODE -ne 0) { Write-Error "docker compose cp failed (exit $LASTEXITCODE)"; exit 1 }

$psqlArgs = @('-v', 'ON_ERROR_STOP=1')
if ($Sync) { $psqlArgs += @('-v', 'SEED_SYNC=1') }
docker compose exec -T db psql @psqlArgs -U roombooking -d roombooking -f /tmp/seed-load-test.sql
if ($LASTEXITCODE -ne 0) { Write-Error "psql seed failed (exit $LASTEXITCODE)"; exit 1 }
