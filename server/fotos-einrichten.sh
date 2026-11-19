#!/bin/bash
# Legt in farmOS die Seite „Fotos“ (/fotos) an.  Aufruf: bash fotos-einrichten.sh [Containername]
set -e
C="${1:-farmos}"
DIR="$(cd "$(dirname "$0")" && pwd)"
BASE="https://raw.githubusercontent.com/fuehrermarkus-create/android-app-f-r-farmOS/diagnose/server"
for f in fotos-view.yml fotos-einrichten.php; do
  [ -f "$DIR/$f" ] || curl -fsSL "$BASE/$f" -o "$DIR/$f"
  sudo docker cp "$DIR/$f" "$C:/tmp/$f"
done
DRUSH="drush"
sudo docker exec -u www-data "$C" sh -c 'command -v drush' >/dev/null 2>&1 || DRUSH="/opt/drupal/vendor/bin/drush"
sudo docker exec -u www-data "$C" $DRUSH php:script /tmp/fotos-einrichten.php
sudo docker exec -u www-data "$C" $DRUSH cr
echo "Fertig: http://<farmOS-Adresse>/fotos im Browser öffnen."
