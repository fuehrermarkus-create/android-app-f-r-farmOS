# richterhofOS

Zusatzmodul für farmOS 4 (läuft auch mit 3.5): einfache, moderne Oberfläche für den Richterhof.

- **Hof-Übersicht** (`/hof`, neue Startseite): Anzahl Tiere, Felder, Maschinen, letzte Arbeiten,
  Listen aus der App mit Fortschritt, neueste Fotos, Schnellknöpfe zum Anlegen.
- **Menü** nur mit Hof-Übersicht, Tieren, Feldern, Maschinen, Einträgen, Fotos und Personen.
  Pläne, Mengen, Standorte, Berichte und andere Arten werden ausgeblendet (die Daten bleiben).
- **Listen** (`/listen`): Listen anlegen, Felder auswählen (nach aktueller Frucht gruppiert, „Alle“ je Gruppe),
  abhaken, ändern, abschließen. Gespeichert wie in der App – beide sehen dieselben Listen.
- **Was wächst wo** (`/kulturen`): jedes Feld mit der Frucht, die gerade darauf steht, Filter je Frucht, druckbar.
- **Fotos** (`/fotos`) fest eingebaut.
- **Einfache Begriffe** wie in der App (Tier, Feld, Maschine, Arbeit, Ausbringung …).
- **Gestaltung**: runder, luftiger, grünes Richterhof-Logo, Dunkelmodus wird unterstützt.

## Installieren / aktualisieren

```bash
cd /home/markus
curl -fsSL -o richterhofos-installieren.sh https://raw.githubusercontent.com/fuehrermarkus-create/android-app-f-r-farmOS/diagnose/server/richterhofos-installieren.sh
bash richterhofos-installieren.sh farmos
```

Das Modul liegt danach in `farmos-sites/all/modules/richterhofos` und übersteht farmOS-Updates.

## Entfernen

```bash
sudo docker exec -u www-data farmos drush pm:uninstall richterhofos -y
```
Startseite und Logo gehen dann wieder auf farmOS zurück.
