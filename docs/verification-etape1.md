# Vérification de l'étape 1 (téléphone Android → PC Windows, même Wi-Fi)

Date : 2026-11-12. PC Windows 10, téléphone HUAWEI MAR-LX1A (Android 10), Wi-Fi partagé du PC (192.168.137.0/24).

## Tests automatiques

| Ensemble | Résultat |
|---|---|
| PC (pytest) | 88/88 |
| Android unitaires (JUnit) | 56/56 |
| Android sur appareil (caméra/micro WebRTC, Huawei) | 5/5 |

## Critères de réussite (spec, section 7)

| # | Critère | Résultat |
|---|---|---|
| 1 | Téléphone visible sur le PC en < 5 s | **OK** |
| 2 | Jumelage par code, puis « Voir » sans re-jumeler après redémarrage des deux apps | **OK** (app PC fermée, app téléphone fermée et « Prêt à partager » relancé, puis « Voir » sans code) |
| 3 | Vidéo + son en direct, latence < 500 ms, 30 min sans coupure | vidéo et son reçus après correction ; **latence 0,2 s** (chronomètre filmé : 52,922 à l'écran contre 52,722 dans la vue) ; 30 min à vérifier |
| 4 | Caméra / micro / avant-arrière pendant la vue | **OK** (image, son, coupure/reprise caméra et micro, bascule avant/arrière) |
| 5 | Écran du téléphone éteint : « Voir » fonctionne | **OK** sur Huawei : sans câble, sur batterie, écran éteint 5 min, image reçue (grâce à la connexion maintenue, voir problème 4). 30 min à confirmer |
| 6 | « Retirer » sur le téléphone → le PC ne peut plus voir | **OK** après correction : retrait pendant la vue → flux arrêté, message « Ce PC a été retiré sur le téléphone. Jumelez-le à nouveau. » (point d'attention 5). Détail : la dernière image reste affichée derrière le message |
| 7 | PC non jumelé : 5 codes faux puis le bon refusé | **OK** (5 codes faux, puis le bon code refusé ; nouveau code nécessaire). Défaut mineur connu : le code épuisé reste affiché sur le téléphone |
| 8 | Notification visible pendant tout partage | **OK** : « Prêt à partager » (service au premier plan + notification) et pendant la vue (« Caméra et micro partagés avec … ») |
| 9 | Deux téléphones en même temps | à vérifier (second téléphone nécessaire) |
| 10 | Critère 5 sur deux marques | à vérifier (second téléphone nécessaire) |

## Problèmes trouvés sur le vrai téléphone et corrigés

1. **Image noire.** Les images du téléphone (360×640) ont des lignes complétées par des octets de remplissage ; la conversion pour l'affichage échouait à chaque image (`BufferError`). Corrigé : recopie d'un seul bloc avant conversion. Test : `test_frame_to_qimage_handles_padded_rows`.
2. **Son haché.** Les paquets audio arrivent irrégulièrement par le Wi-Fi (écarts mesurés jusqu'à 413 ms, 95 % sous 47 ms) et étaient joués sans avance. Corrigé : réserve de 80 ms, attendue de nouveau après un trou, ramenée à 80 ms au-delà de 200 ms. Tests : `test_playback_waits_for_jitter_reserve`, `test_underrun_waits_for_reserve_again`, `test_latency_does_not_grow_past_high_water`.
3. **Écran éteint : téléphone injoignable.** Le gestionnaire d'énergie Huawei (PowerGenie) gelait Eyes quelques secondes après l'extinction de l'écran (`Pged-Freezer: Freeze process: <pid d'Eyes>`), malgré le service au premier plan et l'exemption d'optimisation de batterie. Réglage nécessaire sur Huawei : Paramètres → Batterie → Lancement d'applications → Eyes → gestion manuelle, 3 options activées. Après réglage : plus aucun gel en 4 min écran éteint (21 connexions sur 25 réussies ; les 4 échecs sont des réponses lentes du Wi-Fi en veille, > 3 s).
4. **Sur batterie, écran éteint, le Huawei refuse toute nouvelle connexion entrante** — même celle du service système de débogage (adbd), alors que le ping répond. Ce n'était donc ni Eyes ni Doze (Eyes autorisé dans la liste « dozable »), ni le gel Huawei (absent des journaux pendant ce temps). En revanche une connexion **déjà ouverte** survit : 32/32 pings en 8 min, écran éteint vérifié, puis `start` accepté et offre vidéo reçue. Corrigé côté PC : une connexion authentifiée est maintenue avec chaque téléphone jumelé (ping toutes les 15 s, rouverte dès que le téléphone répond) et « Voir » l'emprunte puis la rend (`links.py`). Tests : `tests/test_links.py`. En complément, 3 tentatives de connexion espacées d'1 s (`test_connect_retries_when_sleeping_phone_drops_first_attempt`). Limite : le PC doit avoir ouvert la connexion avant la mise en veille ; si elle tombe pendant la veille, il faut rallumer l'écran une fois.
5. **« Retirer » affichait « Connexion perdue » sur le PC** au lieu du message de retrait : le téléphone arrêtait la vidéo avant d'envoyer le refus. Corrigé : refus d'abord, puis arrêt. Test : `revokeTellsThePcBeforeStoppingTheStream`.
6. **Méthodes de test instrumenté refusées par JUnit** (elles ne renvoyaient pas `Unit`). Corrigé.

## Comportement connu

- **Le téléphone change de Wi-Fi** s'il connaît plusieurs réseaux (box et partage de connexion du PC) : le PC le retrouve à sa nouvelle adresse par l'annonce Wi-Fi, mais pas s'ils sont sur deux réseaux différents.

- **Larsen** quand le téléphone est près du PC : le micro du téléphone capte les haut-parleurs du PC. Le bruit diminue en éloignant le téléphone (vérifié). Normal pour un micro devant une enceinte ; utiliser un casque ou placer le téléphone dans une autre pièce. Avertissement possible dans une étape suivante.
