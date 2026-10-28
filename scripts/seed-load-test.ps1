# Seed 200 users + 3-5 bookings/room/day over 20 days (UTF-8 safe for Windows)
docker compose cp scripts/seed-load-test.sql db:/tmp/seed-load-test.sql
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U roombooking -d roombooking -f /tmp/seed-load-test.sql
