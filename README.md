<div align="center">

# Grim — fork ottimizzata

[![Build](https://github.com/MathsAnalysis/Grim/actions/workflows/gradle-publish.yml/badge.svg?branch=2.0)](https://github.com/MathsAnalysis/Grim/actions/workflows/gradle-publish.yml)
[![Release](https://img.shields.io/github/v/release/MathsAnalysis/Grim?include_prereleases&label=release)](https://github.com/MathsAnalysis/Grim/releases)
[![License](https://img.shields.io/github/license/MathsAnalysis/Grim)](LICENSE)

</div>

Questo repository è un fork del ramo `2.0` di [GrimAnticheat/Grim](https://github.com/GrimAnticheat/Grim),
l'anticheat open source per Minecraft 1.8–26.3. Il fork si concentra su tre cose:

- **performance**: meno lavoro sul thread Netty per pacchetto e per tick, meno allocazioni, stato per giocatore
  ripulito alla disconnessione;
- **nuovi check** per il combat, il misc, il timer e il breaking, alcuni ancora sperimentali;
- **correzioni di falsi positivi** emerse dal traffico reale (AutoArmor, KnockbackHandler, prediction, teleport).

Il progetto upstream resta la fonte di riferimento per documentazione e supporto. Questo fork non è affiliato
al progetto GrimAnticheat. Questa versione è distribuita solo su GitHub, tramite gli artifact del CI e le GitHub Release.
Non è presente su Modrinth, Hangar o SpigotMC.

## Stato della build

Il badge in alto mostra lo stato dell'ultimo run del workflow
[`gradle-publish.yml`](.github/workflows/gradle-publish.yml) sul ramo `2.0`.

| Evento | Workflow | Cosa produce |
| --- | --- | --- |
| Push su `2.0` con modifiche a codice o build | `gradle-publish.yml` (dev) | jar `grimac-bukkit` e `grimac-fabric` come artifact del run, changelog generato con git-cliff |
| Release pubblicata su GitHub | `release.yml` | build di release, jar allegati alla GitHub Release dal job `attach-jars-to-release` |
| Avvio manuale (`workflow_dispatch`) | `gradle-publish.yml` | build con opzioni: build scan, pubblicazione on/off, runner |

Su questo fork il CI non pubblica su Modrinth né sull'API di aggiornamento, perché i secret `MODRINTH_ID`,
`MODRINTH_TOKEN`, `BUILD_UPDATE_TOKEN` e `BUILD_UPDATE_URL` non sono configurati. I jar sono quindi disponibili
solo come artifact del run e, per le release, come asset della GitHub Release.

### Scaricare un jar

- **Build di sviluppo:** apri la scheda *Actions*, scegli l'ultimo run verde sul ramo `2.0` e scarica
  l'artifact `grimac-bukkit` o `grimac-fabric`. Gli artifact restano disponibili per un periodo limitato.
- **Release:** apri la scheda *Releases* e scarica i jar allegati alla release.

## Cosa cambia rispetto all'upstream

### Ottimizzazioni

- Il dispatch dei pacchetti usa l'elenco dei tipi gestiti da ciascun check, così un check riceve solo quello che
  gli serve.
- Lo stato per giocatore viene ripulito alla disconnessione, e i tracciamenti di keepalive e crystal hanno un
  limite.
- La risoluzione dei pistoni alloca meno oggetti, con una cache di primitive.
- Il manifest dei messaggi verbose viene memorizzato invece di ricostruito a ogni heartbeat.
- Il lavoro per le entità nascoste viene saltato quando nessuna entità è nascosta, e la copia dei set di rimbalzo
  degli slime viene saltata quando non ci sono rimbalzi pendenti.
- Il contatore dei flag usa un `AtomicInteger` invece di un monitor globale.

Non abbiamo misurato guadagni di performance su un server di produzione. Le ottimizzazioni sono verificate con
la compilazione e la suite di test, non con benchmark dal vivo.

### Correzioni

- Prediction: niente cascata di setback mentre un setback è in attesa, e gli offset non vengono azzerati
  prematuramente.
- Velocity: falsi positivi di AntiKB vicino a scaffolding e danni da caduta; rilevamento della salita.
- Teleport: ripristino degli offset di knockback ed esplosione quando il secondo passaggio di prediction viene scartato.
- Breaking e scaffolding: buffer per i mancati RotationBreak e RotationPlace, con target espanso di una soglia di movimento.
- KillAura: guardia sul range di attacco nullo.
- Floodgate e Geyser: API non inizializzate gestite senza eccezioni.

### Check nuovi

"Sperimentale" indica un check marcato come tale nel codice. Nel fork gli esperimenti sono abilitati di default (commit `88517b7be`), quindi questi check possono generare avvisi anche in produzione.

| Check | Categoria | Cosa rileva | Stato |
| --- | --- | --- | --- |
| `AutoArmor` | combat | Equipaggiamento automatico dell'armatura | stabile |
| `AutoTotem` | combat | Uso automatico del totem dell'immortalità | stabile |
| `AutoTool` | misc | Cambio di slot e ritorno nello stesso tick attorno a uno scavo | stabile |
| `AutoCrystalA` | combat | Attacco a un end crystal non ancora confermato dal server o con ID predetto | sperimentale |
| `AutoCrystalB` | combat | Timing place-break automatico o jitter artificiale | sperimentale |
| `AimAI` (alias `CombatAI`) | combat | Punteggio di pattern di mira e rotazione con un piccolo modello | sperimentale |
| `KillAura` | combat | Attacco attraverso blocchi, con un contenitore aperto o con mira bloccata sul bersaglio | sperimentale |
| `GhostHand` | breaking | Rottura di un blocco attraverso un blocco solido | sperimentale |
| `BlinkA`, `BlinkB` | timer | Pacchetti di movimento oltre il tempo trascorso, o mancato reminder di posizione | sperimentale |
| `ESP` | movement | Tracciamento di un giocatore in movimento o accovacciato attraverso blocchi solidi | sperimentale |

### Limiti noti

- Nessuna delle modifiche è stata verificata su un server reale. I check sperimentali possono ancora produrre falsi
  positivi, in particolare `AutoCrystalA`/`AutoCrystalB` e `AimAI`.
- Sei test di `EnderPearlTeleportSimulationTest` sono disabilitati: lo snapshot di PacketEvents usato dal
  progetto non inizializza i dati degli item nell'harness di test. Vanno riattivati quando PacketEvents o il
  setup del test vengono sistemati.
- I riferimenti alla documentazione (wiki, FAQ, Discord) puntano ancora all'upstream.

## Requisiti

- **Server:** Spigot, Paper, Folia o Fabric. Java 17 o superiore.
- **Proxy (Velocity, BungeeCord):** Floodgate e ViaVersion devono essere installati sul server backend dove gira
  Grim, come nell'upstream.
- **Build:** JDK 25 (come nel CI). Il modulo `common` usa il toolchain 21 con target 17.

## Compilare da sorgente

```bash
git clone https://github.com/MathsAnalysis/Grim.git
cd Grim
./gradlew build
```

I jar finiscono in `bukkit/build/libs` e `fabric/build/libs`. I test del modulo comune:

```bash
./gradlew :common:test
```

## Contribuire

Vedi [CONTRIBUTING.md](CONTRIBUTING.md). Le pull request verso questo fork vengono revisionate prima del merge,
con particolare attenzione ai falsi positivi.

## Licenza e crediti

Il progetto è distribuito sotto licenza GNU GPLv3, come l'upstream. Le modifiche di questo fork sono
riconoscibili nella storia dei commit. Grazie a GrimAnticheat e ai contributor dell'upstream per il lavoro di base.
