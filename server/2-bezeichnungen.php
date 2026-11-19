<?php
/**
 * farmOS Hof – einfache deutsche Bezeichnungen für die Arten von Dingen und Einträgen.
 * (Wird nach dem Laden der Übersetzungen ausgeführt, damit diese nicht überschreiben.)
 */
$etm = \Drupal::entityTypeManager();
$lm = \Drupal::languageManager();
$namen = [
  'asset_type' => [
    'animal' => 'Tier', 'land' => 'Fläche', 'plant' => 'Kultur',
    'structure' => 'Gebäude', 'equipment' => 'Maschine',
  ],
  'log_type' => [
    'activity' => 'Arbeit', 'observation' => 'Beobachtung', 'medical' => 'Behandlung',
    'input' => 'Ausbringung / Gabe', 'harvest' => 'Ernte', 'seeding' => 'Aussaat',
    'maintenance' => 'Wartung', 'birth' => 'Geburt',
  ],
];
foreach ($namen as $typ => $liste) {
  if (!$etm->hasDefinition($typ)) {
    continue;
  }
  foreach ($liste as $id => $label) {
    $e = $etm->getStorage($typ)->load($id);
    if (!$e) {
      continue;
    }
    $e->set('label', $label)->save();
    if (method_exists($lm, 'getLanguageConfigOverride')) {
      $lm->getLanguageConfigOverride('de', $e->getConfigDependencyName())->set('label', $label)->save();
    }
    echo "$id → $label\n";
  }
}
