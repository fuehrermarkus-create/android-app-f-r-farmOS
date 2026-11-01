# farmOS Hof – Android-App für farmOS

Native Android-App (Kotlin, Jetpack Compose) für deinen eigenen farmOS-Server.

## Funktionen

- **Tiere**: Liste mit Suche und Filter nach Tierart, Detailansicht mit Verlauf, neue Tiere anlegen
  (Name, Tierart – auch neu anlegen –, Ohrmarke, Geschlecht, Geburtsdatum, Notizen)
- **Flächen & Gebäude**: Liste und Details (Typ, Größe, welche Tiere dort stehen, Verlauf)
- **Schnell-Logs**: Beobachtung, Behandlung, Fütterung/Gabe, Aktivität, Umstallen/Weide (Bewegung), Ernte –
  mit mehreren Tieren gleichzeitig, Ort, Datum, Menge + Einheit, Notizen. Der Titel wird automatisch erzeugt.
- **Fotos mit Metadaten** (ab 1.1): über die Kamera oder aus der Galerie, mehrere pro Eintrag. Zu jedem Foto
  werden gespeichert:
  - **Zeit**: Aufnahmezeit aus dem Foto oder jetzt, Datum und Uhrzeit sind änderbar
  - **Ort**: GPS-Position des Handys oder aus dem Foto, wird als Punkt am farmOS-Log gespeichert
  - **Zuordnung**: Tiere, Feld/Gebäude, Kultur/Frucht (farmOS-„Plant“-Assets) und Kategorien
  - Titel und Notizen

  Die Fotos werden auf max. 1600 px verkleinert. Zeit, GPS und Zuordnung stehen zusätzlich in den
  EXIF-Daten der Bilddatei. In farmOS landen sie als Bilder am Log (Standard: Beobachtung), auf Wunsch
  zusätzlich direkt beim Tier oder bei der Kultur. Schnellzugriff: das Kamera-Symbol oben in den Listen
  oder „Foto“ auf der Seite eines Tiers, einer Fläche oder einer Kultur.
- **Galerie**: Vorschaubilder in Verlauf und Detailseiten, Vollbild mit Zoom, Filter „mit Fotos“
- **Offline**: Alles, was du anlegst, wird sofort lokal gespeichert und in eine Warteschlange gestellt.
  Sobald der Server erreichbar ist, überträgt die App die Einträge automatisch im Hintergrund (auch stündlich).
  Abgelehnte Einträge siehst du unter „Sync“ samt Fehlermeldung.
- **Anmeldung** per OAuth2 (Passwort wird nicht gespeichert, nur ein Token)

## 1. APK bauen (ohne eigenen PC-Aufwand, über GitHub)

1. Auf github.com ein neues **privates** Repository anlegen, z. B. `farmos-hof`.
2. Den Inhalt dieses Ordners hochladen (auf der Repo-Seite „uploading an existing file“ und alle Dateien
   und Ordner hineinziehen, **inklusive des versteckten Ordners `.github`**) – oder per Git:
   ```bash
   cd farmos-hof
   git init && git add . && git commit -m "farmOS Hof"
   git branch -M main
   git remote add origin https://github.com/DEIN-NAME/farmos-hof.git
   git push -u origin main
   ```
3. Im Repo auf **Actions** gehen → der Lauf „APK bauen“ startet automatisch (ca. 5–8 Minuten).
4. Nach dem grünen Haken den Lauf öffnen → unten unter **Artifacts** `farmOS-Hof-apk` herunterladen,
   entpacken → `app-debug.apk`.
5. Die APK aufs Handy kopieren und öffnen. Android fragt nach „Installation aus unbekannten Quellen“ – erlauben.

Falls der Build rot wird: das Fehlerprotokoll aus Actions kopieren und mir schicken.

**Alternativ mit Android Studio:** Ordner öffnen → warten bis Gradle fertig ist → ▶ Run (Handy per USB mit
aktiviertem USB-Debugging) oder *Build › Build APK(s)*.

> Die App ist mit einem festen Schlüssel (`app/farmos-hof.jks`) signiert, damit neue Versionen einfach
> über die alte installiert werden können. Das Repo daher privat halten.

## 2. farmOS vorbereiten

1. In farmOS als Admin: **Verwaltung › Erweitern/Module** – das Modul
   **„farmOS default API Consumer“** (`farm_api_default_consumer`) aktivieren.
   Es stellt den OAuth-Client `farm` bereit, den die App standardmäßig nutzt.
2. Der Benutzer, mit dem du dich anmeldest, braucht eine Rolle mit Schreibrechten (z. B. *Farm Manager*).
3. Mindestens eine **Tierart** (z. B. „Rind“) – kann auch direkt in der App angelegt werden.
4. **Kulturen** (z. B. „Winterweizen Feld 3 2026“) legst du in farmOS als *Pflanzung/Plant* an, **Kategorien**
   für Einträge (z. B. „Schaden“, „Krankheit“, „Bestandskontrolle“) unter *Taxonomie › Log-Kategorien*.
   Die App lädt beides automatisch und bietet es zur Zuordnung an.
5. **Flächen** (Weiden, Felder) und **Gebäude** (Stall) legst du am besten am PC in farmOS an, weil man dort
   auf der Karte zeichnen kann. Die App lädt sie dann.

In der App beim Anmelden die Adresse eingeben, unter der farmOS im Browser erreichbar ist,
z. B. `http://192.168.68.113` (ggf. mit Port, z. B. `http://192.168.68.113:8080`).

## 3. Zugriff von unterwegs (trotz CGNAT)

Portfreigaben gehen bei deinem Anschluss nicht. Die einfachste Lösung ist **Tailscale** (kostenlos für privat):

1. Auf der Docker-VM (Debian):
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up
   ```
   Den angezeigten Link öffnen und mit deinem Konto anmelden. `tailscale ip -4` zeigt die Adresse (100.x.y.z).
2. Die **Tailscale-App** aufs Handy, mit demselben Konto anmelden.
3. In farmOS Hof die Adresse `http://100.x.y.z` (bzw. mit Port) verwenden. Sie funktioniert dann zu Hause
   **und** unterwegs. (Die Verbindung ist durch Tailscale verschlüsselt, `http` ist hier in Ordnung.)

Falls farmOS „The provided host name is not valid for this server“ meldet: in der `settings.php` von farmOS
bei `$settings['trusted_host_patterns']` die neue Adresse ergänzen.

Bei einem bestehenden Login die Adresse wechseln: *Sync › Abmelden* und mit der neuen Adresse anmelden.

## Bekannte Grenzen (Version 1.0)

- Die Listen „Einträge“ und der Verlauf zeigen die jeweils neuesten 50 Logs je Art vom Server
  (plus alles, was du selbst erfasst hast).
- Keine Kartenansicht. Bearbeiten/Löschen bestehender Einträge nur in farmOS im Browser.
- Mengen werden mit der Einheit als Beschriftung gespeichert (ohne farmOS-Einheiten-Taxonomie).

## Projektaufbau

```
app/src/main/java/at/hof/farmos/
├── FarmApp.kt, MainActivity.kt   – Start, Navigation
├── data/FarmApi.kt               – OAuth2 + JSON:API (OkHttp)
├── data/Repository.kt            – Laden, Anlegen, Offline-Warteschlange
├── data/db/                      – Room-Datenbank (lokaler Zwischenspeicher)
├── photo/                       – Fotos verkleinern, EXIF, GPS
├── sync/SyncWorker.kt            – Hintergrund-Upload (WorkManager)
└── ui/                           – Compose-Bildschirme
```

## Update von 1.0 auf 1.1

Vor dem Update in der alten Version unter *Sync* alles übertragen: Die lokale Datenbank wird beim Update
neu angelegt. Danach einfach die neue APK über die alte installieren.
