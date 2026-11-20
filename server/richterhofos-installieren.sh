#!/bin/bash
# richterhofOS installieren oder aktualisieren.
# Aufruf auf der Docker-VM:  bash richterhofos-installieren.sh [Containername]
#
# Das Modul wird in den dauerhaft gespeicherten Ordner „sites/all/modules“ gelegt.
# Dadurch bleibt es auch bei farmOS-Updates erhalten, ohne dass die compose.yaml
# geändert werden muss.
set -e
C="${1:-farmos}"
REPO="https://codeload.github.com/fuehrermarkus-create/android-app-f-r-farmOS/tar.gz/refs/heads/diagnose"

echo "farmOS-Container: $C"
if ! sudo docker ps --format '{{.Names}}' | grep -qx "$C"; then
  echo "Container „$C“ läuft nicht. Name prüfen mit: sudo docker ps"; exit 1
fi

# Ordner auf der VM, der im Container /opt/drupal/web/sites ist
SITES=$(sudo docker inspect "$C" --format '{{range .Mounts}}{{if eq .Destination "/opt/drupal/web/sites"}}{{.Source}}{{end}}{{end}}')
if [ -z "$SITES" ]; then
  echo "Kein dauerhafter Ordner für /opt/drupal/web/sites gefunden – bitte Claude fragen."; exit 1
fi
echo "Sites-Ordner: $SITES"

echo "== Modul herunterladen =="
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "$REPO" | tar -xz -C "$TMP"
SRC=$(find "$TMP" -maxdepth 2 -type d -name richterhofos | head -1)
[ -n "$SRC" ] || { echo "Modul im Download nicht gefunden."; exit 1; }

echo "== Modul ablegen =="
sudo mkdir -p "$SITES/all/modules"
sudo rm -rf "$SITES/all/modules/richterhofos"
sudo cp -r "$SRC" "$SITES/all/modules/richterhofos"
sudo chmod -R a+rX "$SITES/all/modules"

DRUSH="drush"
sudo docker exec -u www-data "$C" sh -c 'command -v drush' >/dev/null 2>&1 || DRUSH="/opt/drupal/vendor/bin/drush"
D() { sudo docker exec -u www-data "$C" $DRUSH "$@"; }

D cr
if D pm:list --status=enabled --field=name 2>/dev/null | grep -qx richterhofos; then
  echo "== Aktualisieren =="
  D updb -y
else
  echo "== Einschalten =="
  D en richterhofos -y
fi
D cr
echo
echo "Fertig. farmOS im Browser neu laden – die Startseite ist jetzt /hof."
