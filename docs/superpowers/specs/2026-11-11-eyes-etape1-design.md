# Eyes — Étape 1 : caméra et micro d'un téléphone Android vus sur un PC Windows (Wi-Fi local)

Date : 2026-11-11
Statut : approuvé (2026-11-12)

## 1. Intention et pourquoi

Eyes est un logiciel personnel (pour l'utilisateur et son entourage, pas un produit commercial) qui
relie deux appareils pour partager la **caméra** et/ou le **micro** de l'un vers l'autre.

Vision complète, découpée en étapes :

| Étape | Contenu |
|---|---|
| **1 (ce document)** | Téléphone Android → PC Windows, même Wi-Fi : voir/entendre caméra + micro du téléphone dans une fenêtre sur le PC |
| 2 | Tous les sens : PC → téléphone, PC ↔ PC, téléphone ↔ téléphone |
| 3 | Connexion à distance par Internet |
| 4 | Option webcam/micro virtuels sur le PC (via OBS + VB-Cable) |
| 5 | Finitions : installateur, liste d'appareils, démarrage automatique (mode surveillance) |

Deux usages visés à terme, à parts égales : **vue à distance** (style surveillance / babyphone) et
**réutilisation du flux** sur le PC (pas forcément pour des appels vidéo). L'étape 1 sert la vue à
distance et pose les fondations (jumelage, transport WebRTC) réutilisées par les étapes suivantes.

## 2. Objectifs de l'étape 1

1. **App Android** (« Eyes ») qui :
   - affiche un **code de jumelage à 6 chiffres** pour jumeler un nouveau PC ;
   - une fois jumelée, **partage automatiquement** caméra et micro quand un PC jumelé le demande,
     sans action sur le téléphone (décision de l'utilisateur : accès automatique) ;
   - affiche **toujours** une notification visible tant qu'elle est prête à partager, et une
     notification explicite (« Caméra et micro partagés avec <nom du PC> ») pendant un partage ;
   - liste les PC jumelés et permet de **dé-jumeler** chacun (révoque immédiatement l'accès).
2. **App Windows** (« Eyes PC ») qui :
   - **découvre automatiquement** les téléphones Eyes présents sur le même Wi-Fi ;
   - permet de jumeler un téléphone en tapant son code une seule fois ;
   - bouton **« Voir »** : affiche la vidéo en direct dans la fenêtre et joue le son ;
   - commandes pendant la vue : **caméra on/off**, **micro on/off**, **caméra avant/arrière** ;
   - bouton **« Arrêter »** ;
   - **plusieurs téléphones en même temps** : l'utilisateur choisit combien de téléphones il
     regarde (décision de l'utilisateur). Chaque téléphone ouvert apparaît comme une **tuile** dans
     une grille de la fenêtre de vue, avec ses propres commandes. On ajoute ou retire une tuile à
     tout moment. Le son : un seul téléphone audible à la fois par défaut (clic sur la tuile pour
     choisir lequel), option « tout entendre ». Pas de limite fixe ; au-delà de 4, l'app prévient
     que le PC ou le Wi-Fi peuvent saturer (choix de Claude).
3. Flux **chiffré** (WebRTC, DTLS-SRTP), **direct** entre les deux appareils, sans serveur.

## 3. Non-objectifs (étape 1)

- Pas de connexion par Internet (étape 3).
- Pas de sens PC → téléphone, PC ↔ PC, téléphone ↔ téléphone (étape 2).
- Pas de webcam / micro virtuels (étape 4).
- Pas d'enregistrement vidéo, pas de captures.
- Pas d'iPhone, pas de macOS/Linux.
- Pas de comptes utilisateurs, pas de paiement, pas de publication sur le Play Store.
- Pas d'installateur Windows soigné (lancement via Python suffit ; étape 5).

## 4. Anti-objectifs (échec même si « ça marche »)

- **Filmer quelqu'un à son insu** : l'indicateur visible sur le téléphone n'est jamais désactivable
  pendant un partage.
- Un appareil **non jumelé** du même Wi-Fi qui peut accéder au flux.
- Le flux qui passe par un serveur tiers.
- Une app que l'entourage ne sait pas utiliser sans aide (jumelage en plus de 3 gestes).

## 5. Contraintes

- PC de l'utilisateur : **Windows 10** (pas 11), Python 3.13 installé.
- Téléphones : **Android** uniquement. Android Studio est installé sur le PC.
- **Android 14+** interdit de démarrer un service caméra/micro depuis l'arrière-plan. Conséquence :
  l'utilisateur ouvre l'app une fois et active « Prêt à partager » ; cela démarre un *foreground
  service* de type `camera|microphone` qui reste actif (notification permanente). Les demandes du PC
  sont servies par ce service déjà au premier plan. Si le service est arrêté (redémarrage du
  téléphone, arrêt manuel), il faut rouvrir l'app — le démarrage automatique est une étape 5.
- Les deux appareils doivent être sur le même réseau local (mêmes sous-réseau, mDNS non bloqué).

## 6. Le « comment » (choix de Claude, l'utilisateur a délégué les choix techniques)

### 6.1 Technologies
- **Android** : Kotlin, Jetpack Compose pour l'interface, `minSdk 26` (Android 8), `targetSdk 35`.
  WebRTC via `io.getstream:stream-webrtc-android`. Serveur WebSocket embarqué via Ktor (CIO).
  Découverte via `NsdManager` (mDNS).
- **Windows** : Python 3.13, interface **PySide6**, WebRTC via **aiortc**, découverte via
  **zeroconf**, son via **sounddevice**. Boucle asyncio intégrée à Qt via **qasync**.

### 6.2 Architecture

```
 Téléphone (Eyes)                                   PC (Eyes PC)
 ┌───────────────────────────────┐                 ┌──────────────────────────┐
 │ Foreground service            │  mDNS annonce   │ Découverte zeroconf      │
 │  ├ annonce _eyes._tcp ────────┼────────────────►│                          │
 │  ├ serveur WebSocket :port ◄──┼── signalisation─┤ client WebSocket         │
 │  └ WebRTC (caméra+micro) ═════╪══ média SRTP ══►│ aiortc → vidéo Qt + son  │
 └───────────────────────────────┘   (direct LAN)  └──────────────────────────┘
```

- Le **téléphone est serveur** de signalisation (WebSocket) ; le PC est client.
- ICE limité aux candidats **hôte** (pas de STUN/TURN à l'étape 1).
- Le téléphone fait l'**offre** WebRTC (il envoie les pistes) ; le PC répond (réception seule).

### 6.3 Annonce mDNS
Service `_eyes._tcp`, nom = nom de l'appareil (ex. « Pixel de Marie »). Enregistrement TXT :
`id=<identifiant unique de l'appareil>`, `v=1` (version du protocole).

### 6.4 Jumelage
1. Sur le téléphone, écran « Jumeler un PC » : génère un code aléatoire à 6 chiffres, valable
   **2 minutes**, affiché en grand. **5 essais faux** maximum puis le code est invalidé.
2. Le PC envoie `{"type":"pair","code":"123456","pcId":..., "pcName":...}`.
3. Si le code est bon, le téléphone génère un **jeton** aléatoire de 256 bits, le stocke avec
   `pcId` et `pcName`, et répond `{"type":"paired","token":...,"phoneId":...,"phoneName":...}`.
4. Le PC stocke le jeton (fichier dans `%APPDATA%\Eyes\pairings.json`).
5. Le code n'est accepté que si l'écran « Jumeler un PC » est ouvert sur le téléphone.

### 6.5 Protocole de signalisation (JSON sur WebSocket)
Chaque connexion commence par `pair` (voir ci-dessus) ou `hello` :

| Message | Sens | Contenu |
|---|---|---|
| `hello` | PC → tél | `pcId`, `token`, `v` |
| `welcome` / `denied` | tél → PC | `denied` : raison (`unknown`, `revoked`, `version`) puis fermeture |
| `start` | PC → tél | `video: bool`, `audio: bool`, `camera: "back"\|"front"` |
| `offer` / `answer` | tél ↔ PC | SDP |
| `ice` | les deux | candidat ICE |
| `set` | PC → tél | changement en cours de partage : `video`, `audio`, `camera` |
| `stop` | PC → tél | arrête le partage, libère caméra et micro |
| `error` | tél → PC | ex. `camera_busy`, `permission_missing` |

Un seul PC peut voir le téléphone à la fois ; un second reçoit `error: busy`.
Le jeton est comparé en temps constant.

### 6.5 bis Sécurité face à un espion sur le même Wi-Fi (choix de Claude)
Menace couverte : quelqu'un sur le même Wi-Fi qui **écoute** le trafic (passif). Le jeton ne doit
jamais circuler en clair.
- Le téléphone génère au premier lancement une paire de clés et un **certificat auto-signé** ;
  la signalisation passe en **WebSocket sécurisé (wss/TLS)**.
- Au jumelage, le PC accepte le certificat présenté (premier contact) et **enregistre son empreinte**
  avec le jeton. Ensuite, il **refuse** toute connexion dont l'empreinte diffère (certificat épinglé).
- Les empreintes DTLS de WebRTC transitent dans ce canal TLS authentifié, ce qui lie le flux média
  au téléphone jumelé.
- Risque résiduel accepté : un attaquant **actif** présent pendant les 2 minutes de jumelage
  pourrait s'interposer. Acceptable pour un usage familial.

### 6.5 ter Fiabilité écran éteint (choix de Claude)
Tant que « Prêt à partager » est actif, le service tient un **wake lock partiel**, un **Wi-Fi lock**
(haute performance) et un **multicast lock** (pour que mDNS réponde). Au premier « Prêt à partager »,
l'app demande **une fois** l'exemption d'optimisation de batterie (un seul geste). Si mDNS ne répond
pas, le PC tente la **dernière adresse IP connue** du téléphone jumelé.

Les téléphones de l'entourage sont de **plusieurs marques** (décision de l'utilisateur). Certaines
(Samsung, Xiaomi, Huawei, Oppo…) ferment les apps en arrière-plan malgré tout. L'app détecte la
marque et affiche, au premier « Prêt à partager », un écran court « Pour que Eyes reste actif » avec
le bouton qui ouvre le bon réglage du fabricant quand il existe (choix de Claude).

### 6.6 Qualité
- Vidéo : 1280×720, 30 i/s visés, adaptation automatique WebRTC.
- Audio : Opus, mono.
- Objectif de latence perçue : **< 500 ms** sur un Wi-Fi domestique normal.

### 6.7 Écrans
**Téléphone**
- Accueil : interrupteur « Prêt à partager », état actuel (« En attente » / « Partage avec … »),
  bouton « Jumeler un PC », liste des PC jumelés avec bouton « Retirer ».
- Demande des permissions caméra, micro et notifications au premier lancement.

**PC**
- Liste des téléphones trouvés sur le Wi-Fi (jumelés ou non). Non jumelé → bouton « Jumeler »
  (boîte de saisie du code). Jumelé → bouton « Voir ».
- Fenêtre de vue : vidéo, boutons caméra on/off, micro on/off, avant/arrière, « Arrêter ».
- Messages d'erreur en français, simples (« Le téléphone n'est pas prêt — ouvrez Eyes dessus »).

### 6.8 Organisation du dépôt
```
eyes/
  android/      projet Android Studio (Gradle, Kotlin)
  pc/           application Python (pyproject.toml, src/eyes_pc/, tests/)
  docs/
```

## 7. Critères de réussite (étape 1)

1. Sur un même Wi-Fi, le PC voit le téléphone apparaître dans la liste en moins de 5 s.
2. Jumelage en tapant le code une fois ; après redémarrage des deux apps, « Voir » fonctionne sans
   re-jumeler.
3. Vidéo et son du téléphone en direct sur le PC, latence < 500 ms, pendant 30 min sans coupure.
4. Caméra on/off, micro on/off, avant/arrière fonctionnent pendant la vue.
5. Téléphone écran éteint (service « Prêt à partager » actif) : « Voir » fonctionne quand même.
6. Après « Retirer » sur le téléphone, le PC reçoit `denied` et ne peut plus voir.
7. Un PC non jumelé ne peut ni voir ni deviner le code (5 essais max).
8. Notification visible sur le téléphone pendant tout partage.
9. Le PC affiche au moins 2 téléphones en même temps, chacun avec ses commandes.
10. Critère 5 vérifié sur au moins 2 marques de téléphones différentes.

## 8. Tests
- **PC** : tests unitaires (pytest) du protocole, du stockage des jumelages, du client de
  signalisation contre un faux serveur.
- **Android** : tests unitaires (JUnit) du protocole, du gestionnaire de jumelage (code, essais,
  expiration, jeton) ; tests sur appareil réel pour la caméra/micro.
- **Vérification manuelle** des critères de la section 7 avec un vrai téléphone.

## 9. Laissé au constructeur
- Structure interne du code, noms de classes.
- Choix du port du serveur WebSocket (port libre attribué par le système, annoncé via mDNS).
- Détails visuels des écrans, dans l'esprit « simple pour l'entourage ».
- Format de stockage des jumelages côté Android (DataStore ou équivalent).
