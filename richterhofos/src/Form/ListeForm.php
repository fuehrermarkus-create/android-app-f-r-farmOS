<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Form;

use Drupal\Core\Form\FormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\richterhofos\Hof;
use Drupal\taxonomy\TermInterface;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * Liste anlegen oder bearbeiten: Name, Arbeit, Notiz und Felder auswählen.
 *
 * Die Felder sind nach der Frucht gruppiert, die gerade darauf steht. So lassen
 * sich z. B. vor dem Silieren alle Wiesen mit einem Klick auswählen.
 */
class ListeForm extends FormBase {

  public function getFormId(): string {
    return 'richterhofos_liste_form';
  }

  public function buildForm(array $form, FormStateInterface $form_state, ?TermInterface $taxonomy_term = NULL): array {
    if ($taxonomy_term && !str_starts_with((string) $taxonomy_term->label(), Hof::LISTE)) {
      throw new NotFoundHttpException();
    }
    $form_state->set('liste', $taxonomy_term ? $taxonomy_term->id() : NULL);
    $info = $taxonomy_term ? Hof::listenInfo($taxonomy_term) : ['name' => '', 'arbeit' => NULL, 'notiz' => NULL];
    $vorhanden = $taxonomy_term ? Hof::listenEintraege($taxonomy_term) : [];

    $form['#attached']['library'][] = 'richterhofos/listen';
    $form['#attributes']['class'][] = 'rh-listenformular';

    $form['name'] = [
      '#type' => 'textfield',
      '#title' => 'Name der Liste',
      '#required' => TRUE,
      '#default_value' => $info['name'],
      '#placeholder' => 'z. B. Silo erster Schnitt 2026',
      '#maxlength' => 200,
    ];
    $optionen = [];
    foreach (Hof::arbeiten() as $schluessel => [$titel]) {
      $optionen[$schluessel] = $titel;
    }
    $form['arbeit'] = [
      '#type' => 'radios',
      '#title' => 'Arbeit',
      '#options' => $optionen,
      '#default_value' => $info['arbeit'] ?? 'SILAGE',
      '#attributes' => ['class' => ['rh-auswahlknoepfe']],
      '#prefix' => '<div class="rh-knopfgruppe">',
      '#suffix' => '</div>',
    ];
    $form['notiz'] = [
      '#type' => 'textarea',
      '#title' => 'Notiz',
      '#rows' => 2,
      '#default_value' => $info['notiz'],
    ];

    // Felder nach aktueller Frucht gruppieren.
    $kulturen = Hof::kulturenJeFeld();
    // Blühflächen bilden eine eigene Gruppe (z. B. zum Mulchen).
    $blueh = array_flip(Hof::bluehflaechen());
    $gruppen = [];
    foreach (Hof::felder() as $id => $feld) {
      $arten = array_unique(array_column($kulturen[$id] ?? [], 'art'));
      if (isset($blueh[(string) $id])) {
        $gruppe = '🌼 Blühflächen';
        $label = $feld->label() . ($arten ? ' (' . implode(', ', $arten) . ')' : '');
      }
      else {
        $gruppe = $arten ? implode(', ', $arten) : 'Ohne Kultur';
        $label = $feld->label();
      }
      $gruppen[$gruppe][$id] = $label;
    }
    $rang = fn($g) => $g === 'Ohne Kultur' ? 2 : ($g === '🌼 Blühflächen' ? 1 : 0);
    uksort($gruppen, fn($a, $b) => [$rang($a), $a] <=> [$rang($b), $b]);

    $form['felder'] = [
      '#type' => 'container',
      '#tree' => TRUE,
      '#attributes' => ['class' => ['rh-feldgruppen']],
      'titel' => ['#markup' => '<h3 class="rh-abschnitt">Felder auswählen</h3><p class="rh-hinweis">Nach der Frucht gruppiert, die gerade darauf steht; Blühflächen haben eine eigene Gruppe. „Alle“ wählt die ganze Gruppe.</p>'],
    ];
    $n = 0;
    foreach ($gruppen as $gruppe => $felder) {
      $schluessel = 'g' . $n++;
      $standard = array_values(array_intersect(array_map('strval', array_keys($felder)), array_map('strval', array_keys($vorhanden))));
      $form['felder'][$schluessel] = [
        '#type' => 'details',
        '#title' => $gruppe . ' (' . count($felder) . ')',
        '#open' => TRUE,
        '#attributes' => ['class' => ['rh-gruppe']],
        'auswahl' => [
          '#type' => 'checkboxes',
          '#options' => $felder,
          '#default_value' => $standard,
        ],
      ];
      // Bereits erledigte Felder lassen sich nicht mehr abwählen.
      foreach ($felder as $id => $label) {
        if (isset($vorhanden[$id]) && $vorhanden[$id]->get('status')->value !== 'pending') {
          $form['felder'][$schluessel]['auswahl'][$id] = ['#disabled' => TRUE, '#description' => 'erledigt'];
        }
      }
    }
    if (!$gruppen) {
      $form['felder']['leer'] = ['#markup' => '<p class="rh-leer">Keine Felder gefunden.</p>'];
    }

    $form['aktionen'] = ['#type' => 'actions'];
    $form['aktionen']['speichern'] = [
      '#type' => 'submit',
      '#value' => $taxonomy_term ? 'Speichern' : 'Liste anlegen',
      '#button_type' => 'primary',
    ];
    return $form;
  }

  /**
   * Alle angehakten Feld-IDs.
   */
  protected function gewaehlt(FormStateInterface $form_state): array {
    $ids = [];
    foreach ((array) $form_state->getValue('felder') as $gruppe) {
      foreach ((array) ($gruppe['auswahl'] ?? []) as $id => $wert) {
        if ($wert) {
          $ids[] = (string) $id;
        }
      }
    }
    return array_unique($ids);
  }

  public function validateForm(array &$form, FormStateInterface $form_state): void {
    $name = trim((string) $form_state->getValue('name'));
    $liste = $form_state->get('liste');
    $doppelt = \Drupal::entityTypeManager()->getStorage('taxonomy_term')->getQuery()->accessCheck(FALSE)
      ->condition('vid', 'log_category')
      ->condition('name', Hof::LISTE . $name);
    if ($liste) {
      $doppelt->condition('tid', $liste, '<>');
    }
    if ($doppelt->count()->execute()) {
      $form_state->setErrorByName('name', 'Eine Liste mit diesem Namen gibt es schon.');
    }
    if (!$liste && !$this->gewaehlt($form_state)) {
      $form_state->setErrorByName('felder', 'Bitte mindestens ein Feld auswählen.');
    }
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    $speicher = \Drupal::entityTypeManager()->getStorage('taxonomy_term');
    $name = trim((string) $form_state->getValue('name'));
    $arbeit = (string) $form_state->getValue('arbeit') ?: 'OTHER';
    $notiz = trim((string) $form_state->getValue('notiz')) ?: NULL;
    $beschreibung = ['value' => Hof::beschreibung($arbeit, $notiz), 'format' => 'default'];

    $liste = $form_state->get('liste') ? $speicher->load($form_state->get('liste')) : NULL;
    if ($liste) {
      $liste->set('name', Hof::LISTE . $name)->set('description', $beschreibung)->save();
    }
    else {
      $liste = $speicher->create(['vid' => 'log_category', 'name' => Hof::LISTE . $name, 'description' => $beschreibung]);
      $liste->save();
    }

    $vorhanden = Hof::listenEintraege($liste);
    $gewaehlt = $this->gewaehlt($form_state);
    $felder = \Drupal::entityTypeManager()->getStorage('asset')->loadMultiple($gewaehlt);

    $neu = 0;
    foreach ($felder as $id => $feld) {
      if (!isset($vorhanden[$id])) {
        Hof::eintragAnlegen($liste, $name, $arbeit, $notiz, $feld);
        $neu++;
      }
    }
    // Abgewählte, noch offene Felder entfernen.
    $weg = 0;
    foreach ($vorhanden as $id => $log) {
      if (str_starts_with((string) $id, 'log-') || in_array((string) $id, $gewaehlt, TRUE)) {
        continue;
      }
      if ($log->get('status')->value === 'pending') {
        $log->delete();
        $weg++;
      }
    }

    $text = 'Liste „' . $name . '“ gespeichert';
    if ($neu) {
      $text .= ', ' . $neu . ' Felder hinzugefügt';
    }
    if ($weg) {
      $text .= ', ' . $weg . ' entfernt';
    }
    $this->messenger()->addStatus($text . '.');
    $form_state->setRedirect('richterhofos.liste', ['taxonomy_term' => $liste->id()]);
  }

}
