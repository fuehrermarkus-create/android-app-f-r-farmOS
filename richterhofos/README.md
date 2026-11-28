# richterhofOS

Zusatzmodul für farmOS 4 (läuft auch mit 3.5): einfache, moderne Oberfläche für den Richterhof.

- **Hof-Übersicht** (`/hof`, neue Startseite): Anzahl Tiere, Felder, Maschinen, letzte Arbeiten,
  Listen aus der App mit Fortschritt, neueste Fotos, Schnellknöpfe zum Anlegen.
- **Menü** nur mit Hof-Übersicht, Tieren, Feldern, Maschinen, Einträgen, Fotos und Personen.
  Pläne, Mengen, Standorte, Berichte und andere Arten werden ausgeblendet (die Daten bleiben).
- **Listen** (`/listen`): Listen anlegen, Felder auswählen (nach aktueller Frucht gruppiert, „Alle“ je Gruppe),
  abhaken, ändern, abschließen. Gespeichert wie in der App – beide sehen dieselben Listen.
- **Was wächst wo** (`/kulturen`): jedes Feld mit der Frucht, die gerade darauf steht, Filter je Frucht, druckbar.
- **Blühflächen**: 🌼-Spalte und Filter in „Was wächst wo“ (per Klick markieren/entfernen), eigene Gruppe beim
  Anlegen von Listen, Kachel auf der Hof-Übersicht. Gleiche Markierung (`bluehflaeche`) wie in der App;
  das Modul legt sie selbst an, falls sie fehlt.
- **Feldarbeit erfassen** (`/feldarbeit`, Knopf „+ Feldarbeit“): Düngung, Pflanzenschutz, Aussaat oder Ernte für mehrere
  Felder auf einmal; Menge je ha oder gesamt (wird nach Fläche aufgeteilt); Kontrolltabelle je Feld mit änderbarer
  Fläche, kg N/ha; übernimmt N-Gehalt und Zulassungsnummer vom letzten Mal. Speichert wie die App (ein Eintrag je Feld).
- **Ernte**: Erntegut, Schnitt, Zähleinheit (Fuhren, Ballen, t, dt, kg, m³) mit Gewicht je Fuhre/Ballen; Menge je
  Feld in der Kontrolltabelle, daraus t und t/ha; Kultur auf Wunsch beenden. Jahresübersicht „Ernte und Erträge“
  (`/aufzeichnungen/ernte`) mit Summen je Erntegut/Schnitt, druckbar und als CSV.
- **Aufzeichnungen** (`/aufzeichnungen`, Knöpfe auf der Hof-Übersicht): Pflanzenschutz-Aufzeichnung und
  Düngeaufzeichnung je Jahr, druckbar (A4 quer, als PDF speicherbar) und als CSV für Excel. Pflichtangaben,
  die fehlen, sind rot markiert. Die App (ab 1.11) speichert die Angaben maschinenlesbar im Log-Feld `data`.
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
