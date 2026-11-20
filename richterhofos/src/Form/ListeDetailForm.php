<?php

declare(strict_types=1);

namespace Drupal\richterhofos\Form;

use Drupal\Core\Form\FormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\Core\Link;
use Drupal\Core\Url;
use Drupal\richterhofos\Hof;
use Drupal\taxonomy\TermInterface;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * Eine Liste ansehen und Felder abhaken.
 */
class ListeDetailForm extends FormBase {

  public function getFormId(): string {
    return 'richterhofos_liste_detail_form';
  }

  public function buildForm(array $form, FormStateInterface $form_state, ?TermInterface $taxonomy_term = NULL): array {
    if (!$taxonomy_term || !str_starts_with((string) $taxonomy_term->label(), Hof::LISTE)) {
      throw new NotFoundHttpException();
    }
    $form_state->set('liste', $taxonomy_term->id());
    $info = Hof::listenInfo($taxonomy_term);
    $eintraege = Hof::listenEintraege($taxonomy_term);
    $kulturen = Hof::kulturenJeFeld();
    $darf = Url::fromRoute('richterhofos.liste_bearbeiten', ['taxonomy_term' => $taxonomy_term->id()])->access();

    $form['#attached']['library'][] = 'richterhofos/listen';
    $form['#cache']['tags'] = ['log_list', 'taxonomy_term_list'];

    $gesamt = count($eintraege);
    $fertig = count(array_filter($eintraege, fn($l) => $l->get('status')->value !== 'pending'));
    $prozent = $gesamt ? (int) round($fertig * 100 / $gesamt) : 0;
    $form['kopf'] = [
      '#type' => 'inline_template',
      '#template' => '<div class="rh-listenkopf">'
        . '{% if arbeit %}<span class="rh-marke">{{ arbeit }}</span>{% endif %}'
        . '<span class="rh-listenkopf__zahl">{{ fertig }} von {{ gesamt }} erledigt</span>'
        . '<span class="rh-balken"><span style="width: {{ prozent }}%"></span></span>'
        . '{% if notiz %}<p class="rh-hinweis">{{ notiz }}</p>{% endif %}</div>',
      '#context' => [
        'arbeit' => $info['arbeit'] ? Hof::arbeiten()[$info['arbeit']][0] : '',
        'fertig' => $fertig,
        'gesamt' => $gesamt,
        'prozent' => $prozent,
        'notiz' => $info['notiz'],
      ],
    ];

    // Nach Feldnamen sortieren.
    $zeilen = [];
    foreach ($eintraege as $schluessel => $log) {
      $feld = NULL;
      if (!str_starts_with((string) $schluessel, 'log-')) {
        $feld = \Drupal::entityTypeManager()->getStorage('asset')->load($schluessel);
      }
      $zeilen[(string) $schluessel] = [$feld, $log];
    }
    uasort($zeilen, fn($a, $b) => strnatcasecmp(
      (string) ($a[0] ? $a[0]->label() : $a[1]->label()),
      (string) ($b[0] ? $b[0]->label() : $b[1]->label())
    ));

    $form['felder'] = [
      '#type' => 'table',
      '#header' => ['Erledigt', 'Feld', 'Frucht', 'Erledigt am'],
      '#empty' => 'Diese Liste hat noch keine Felder.',
      '#tree' => TRUE,
      '#attributes' => ['class' => ['rh-tabelle', 'rh-abhaken']],
    ];
    foreach ($zeilen as $schluessel => [$feld, $log]) {
      $erledigt = $log->get('status')->value !== 'pending';
      $arten = $feld ? array_unique(array_column($kulturen[$feld->id()] ?? [], 'art')) : [];
      $form['felder'][$schluessel]['erledigt'] = [
        '#type' => 'checkbox',
        '#title' => 'erledigt',
        '#title_display' => 'invisible',
        '#default_value' => $erledigt,
        '#disabled' => !$darf,
        '#return_value' => 1,
        '#log' => $log->id(),
      ];
      $titel = $feld ? $feld->label() : $log->label();
      $url = Hof::link($feld ?: $log);
      $form['felder'][$schluessel]['feld'] = $url
        ? Link::fromTextAndUrl($titel, Url::fromUserInput($url))->toRenderable()
        : ['#plain_text' => $titel];
      $form['felder'][$schluessel]['frucht'] = ['#plain_text' => $arten ? implode(', ', $arten) : '–'];
      $form['felder'][$schluessel]['datum'] = ['#plain_text' => $erledigt ? Hof::datum((int) $log->get('timestamp')->value) : ''];
      if ($erledigt) {
        $form['felder'][$schluessel]['#attributes']['class'][] = 'is-erledigt';
      }
    }

    if ($darf) {
      $form['aktionen'] = ['#type' => 'actions'];
      $form['aktionen']['speichern'] = [
        '#type' => 'submit',
        '#value' => 'Häkchen speichern',
        '#button_type' => 'primary',
      ];
      $form['aktionen']['alle'] = [
        '#type' => 'submit',
        '#value' => 'Alle erledigt',
        '#name' => 'alle',
      ];
      $form['aktionen']['bearbeiten'] = Link::fromTextAndUrl(
        'Felder / Name ändern',
        Url::fromRoute('richterhofos.liste_bearbeiten', ['taxonomy_term' => $taxonomy_term->id()])
      )->toRenderable() + ['#attributes' => ['class' => ['button']]];
      $form['aktionen']['abschliessen'] = [
        '#type' => 'submit',
        '#value' => 'Liste abschließen',
        '#name' => 'abschliessen',
        '#attributes' => ['class' => ['button--danger']],
      ];
    }
    $form['zurueck'] = Link::fromTextAndUrl('← Alle Listen', Url::fromRoute('richterhofos.listen'))->toRenderable()
      + ['#prefix' => '<p class="rh-zurueck">', '#suffix' => '</p>'];
    return $form;
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    $speicher = \Drupal::entityTypeManager()->getStorage('log');
    $knopf = $form_state->getTriggeringElement()['#name'] ?? '';
    $liste = \Drupal::entityTypeManager()->getStorage('taxonomy_term')->load($form_state->get('liste'));

    if ($knopf === 'abschliessen') {
      $name = Hof::listenInfo($liste)['name'];
      $liste->set('name', Hof::ARCHIV . $name)->save();
      $this->messenger()->addStatus('Liste „' . $name . '“ abgeschlossen. Die Einträge bleiben in farmOS erhalten.');
      $form_state->setRedirect('richterhofos.listen');
      return;
    }

    $jetzt = \Drupal::time()->getRequestTime();
    $geaendert = 0;
    $werte = (array) $form_state->getValue('felder');
    foreach (Hof::listenEintraege($liste) as $schluessel => $log) {
      $soll = $knopf === 'alle' ? TRUE : !empty($werte[(string) $schluessel]['erledigt']);
      $ist = $log->get('status')->value !== 'pending';
      if ($soll === $ist) {
        continue;
      }
      $log->set('status', $soll ? 'done' : 'pending');
      if ($soll) {
        $log->set('timestamp', $jetzt);
      }
      $log->save();
      $geaendert++;
    }
    $this->messenger()->addStatus($geaendert ? $geaendert . ' Felder aktualisiert.' : 'Keine Änderungen.');
  }

}
