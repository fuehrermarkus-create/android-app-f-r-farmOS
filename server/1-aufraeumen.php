<?php
/**
 * farmOS Hof – farmOS auf das Nötige reduzieren.
 *
 * Behalten werden: Tiere, Flächen, Kulturen, Gebäude, Maschinen sowie die Einträge
 * Arbeit, Beobachtung, Behandlung, Ausbringung/Gabe, Ernte, Aussaat, Wartung, Geburt.
 * Entfernt wird nur, was aktiv ist, KEINE Einträge hat und von nichts Behaltenem gebraucht wird.
 */
$entfernen = [
  // Arten von Dingen, die du nicht brauchst
  'farm_compost', 'farm_group', 'farm_product', 'farm_sensor', 'farm_sensor_listener',
  'farm_water', 'farm_seed',
  // Arten von Einträgen, die du nicht brauchst
  'farm_lab_test', 'farm_purchase', 'farm_sale', 'farm_transplanting',
  // Mengenarten / Bestandsberechnung (von der App nicht genutzt)
  'farm_quantity_price', 'farm_quantity_test', 'farm_inventory',
];

$mh = \Drupal::moduleHandler();
$etm = \Drupal::entityTypeManager();
$liste = \Drupal::service('extension.list.module');
$installer = \Drupal::service('module_installer');
$aktiv = array_keys($mh->getModuleList());

// Wie viele Einträge hängen an der Art, die ein Modul bereitstellt?
$eintraege = function (string $modul) use ($etm): int {
  $n = 0;
  $paare = [
    'asset' => preg_replace('/^farm_/', '', $modul),
    'log' => preg_replace('/^farm_/', '', $modul),
    'quantity' => preg_replace('/^farm_quantity_/', '', $modul),
  ];
  foreach ($paare as $typ => $bundle) {
    if (!$etm->hasDefinition($typ) || !$etm->hasDefinition($typ . '_type')) {
      continue;
    }
    if ($etm->getStorage($typ . '_type')->load($bundle)) {
      $n += (int) $etm->getStorage($typ)->getQuery()->accessCheck(FALSE)
        ->condition('type', $bundle)->count()->execute();
    }
  }
  return $n;
};

$kandidaten = [];
foreach ($entfernen as $m) {
  if (!in_array($m, $aktiv, TRUE)) {
    continue;
  }
  $n = $eintraege($m);
  if ($n > 0) {
    echo "BEHALTEN  $m – enthält $n Einträge\n";
    continue;
  }
  $kandidaten[] = $m;
}

// Nur entfernen, was von keinem behaltenen Modul gebraucht wird
do {
  $geaendert = FALSE;
  foreach ($kandidaten as $i => $m) {
    $info = $liste->get($m);
    $abhaengig = array_intersect(array_keys($info->required_by ?? []), $aktiv);
    $blockiert = array_diff($abhaengig, $kandidaten);
    if ($blockiert) {
      echo "BEHALTEN  $m – wird gebraucht von: " . implode(', ', $blockiert) . "\n";
      unset($kandidaten[$i]);
      $geaendert = TRUE;
    }
  }
  $kandidaten = array_values($kandidaten);
} while ($geaendert);

if ($kandidaten) {
  echo "ENTFERNE  " . implode(', ', $kandidaten) . "\n";
  try {
    $ok = $installer->uninstall($kandidaten, FALSE);
    echo $ok ? "          erledigt\n" : "          nicht möglich (Abhängigkeiten)\n";
  }
  catch (\Throwable $e) {
    echo "          FEHLER: " . $e->getMessage() . "\n";
  }
}
else {
  echo "Nichts zu entfernen.\n";
}

// ---------------------------------------------------------------- Deutsch
$sprache = array_values(array_filter(
  ['language', 'locale', 'config_translation', 'farm_l10n'],
  fn($m) => $liste->exists($m) && !\Drupal::moduleHandler()->moduleExists($m)
));
if ($sprache) {
  $installer->install($sprache);
  echo "Sprachmodule aktiviert: " . implode(', ', $sprache) . "\n";
}
if (!\Drupal\language\Entity\ConfigurableLanguage::load('de')) {
  \Drupal\language\Entity\ConfigurableLanguage::createFromLangcode('de')->save();
  echo "Sprache Deutsch hinzugefügt\n";
}
\Drupal::configFactory()->getEditable('system.site')->set('default_langcode', 'de')->save();
foreach (\Drupal::entityTypeManager()->getStorage('user')->loadMultiple() as $u) {
  if ((int) $u->id() === 0) {
    continue;
  }
  $u->set('preferred_langcode', 'de');
  if ($u->hasField('preferred_admin_langcode')) {
    $u->set('preferred_admin_langcode', 'de');
  }
  $u->save();
}
echo "Standardsprache und Benutzer: Deutsch\n";

// ---------------------------------------------------------------- Markierung Blühfläche
$etm = \Drupal::entityTypeManager();
$etm->clearCachedDefinitions();
if ($etm->hasDefinition('flag')) {
  $flags = $etm->getStorage('flag');
  if (!$flags->load('bluehflaeche')) {
    $flags->create([
      'id' => 'bluehflaeche',
      'label' => 'Blühfläche',
      'entity_types' => ['asset' => ['land', 'plant']],
    ])->save();
    echo "Markierung „Blühfläche“ angelegt\n";
  }
}
