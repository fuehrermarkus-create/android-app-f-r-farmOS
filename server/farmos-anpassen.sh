#!/bin/bash
# farmOS für die App „farmOS Hof“ anpassen:
#  - entfernt ungenutzte Bereiche (nur wenn dort keine Einträge sind)
#  - stellt farmOS auf Deutsch um und vergibt einfache Bezeichnungen
#  - legt die Markierung „Blühfläche“ an
# Aufruf auf der Docker-VM:  bash farmos-anpassen.sh [Containername]
set -e
C="${1:-farmos}"
DIR="$(cd "$(dirname "$0")" && pwd)"
BASE="https://raw.githubusercontent.com/fuehrermarkus-create/android-app-f-r-farmOS/diagnose/server"

echo "farmOS-Container: $C"
if ! sudo docker ps --format '{{.Names}}' | grep -qx "$C"; then
  echo "Container „$C“ läuft nicht. Name prüfen mit: sudo docker ps"; exit 1
fi
read -r -p "Hast du vorher einen Snapshot der VM in Proxmox gemacht? (j/n) " a
[ "$a" = "j" ] || { echo "Bitte zuerst einen Snapshot machen, dann erneut starten."; exit 1; }

# PHP-Dateien holen, falls sie nicht neben dem Skript liegen
for f in 1-aufraeumen.php 2-bezeichnungen.php; do
  [ -f "$DIR/$f" ] || curl -fsSL "$BASE/$f" -o "$DIR/$f"
  sudo docker cp "$DIR/$f" "$C:/tmp/$f"
done

DRUSH="drush"
sudo docker exec -u www-data "$C" sh -c 'command -v drush' >/dev/null 2>&1 || DRUSH="/opt/drupal/vendor/bin/drush"
D() { sudo docker exec -u www-data "$C" $DRUSH "$@"; }

echo; echo "== 1/3 Aufräumen und Deutsch aktivieren =="
D php:script /tmp/1-aufraeumen.php

echo; echo "== 2/3 Deutsche Übersetzungen laden (kann ein paar Minuten dauern) =="
D locale:check || true
D locale:update || true

echo; echo "== 3/3 Einfache Bezeichnungen =="
D php:script /tmp/2-bezeichnungen.php
D cr
echo; echo "Fertig. farmOS im Browser neu laden (ggf. ab- und wieder anmelden)."
