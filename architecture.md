# BlockchainIST — Overall Architecture

## Component Diagram (High Level)

```plantuml
@startuml BlockchainIST_Components
!theme plain
skinparam defaultFontName Segoe UI
skinparam componentStyle rectangle
skinparam packageStyle frame
skinparam ArrowColor #444
skinparam componentBackgroundColor #FAFAFA

left to right direction

title BlockchainIST — Component Overview

' ─── RUNTIME TIERS ─────────────────────────────────────────────────────────────

actor "User" as user

rectangle "**client**" as CLIENT #EEF4FF {
  component "CommandProcessor" as CmdProc
  component "ClientNodeService\n<<gRPC stub>>" as ClientSvc
  component "RetryObserver" as Retry
  component "MessageObserver" as MsgObs
}

rectangle "**node**  (N instances)" as NODE #EEF4FF {
  component "NodeServiceImpl\n<<gRPC server>>" as NodeSvc
  component "NodeSequencerService\n<<gRPC stub>>" as NodeSeqSvc
  component "NodeState\n<<in-memory ledger>>" as NodeState
  component "DelayInterceptor" as DelayInter
}

rectangle "**sequencer**" as SEQ #EEF4FF {
  component "SequencerServiceImpl\n<<gRPC server>>" as SeqSvc
  component "SequencerState\n<<ordered block store>>" as SeqState
}

' ─── SHARED FOUNDATION ──────────────────────────────────────────────────────────

rectangle "**contract**  (protobuf / gRPC stubs)" as CONTRACT #FFF8EE {
  interface "NodeService" as NodeIface
  interface "SequencerService" as SeqIface
}

rectangle "**common**" as COMMON #F0FFF0 {
  component "Transaction" as TxDomain
  component "Utils\n(proto↔domain)" as Utils
  component "DuplicateException" as DupEx
}

' ─── INTER-MODULE ARROWS ────────────────────────────────────────────────────────

user          --> CmdProc      : CLI commands
ClientSvc     --> NodeIface    : gRPC  [createWallet / transfer / ...]
ClientSvc     ..> Retry        : on failure
ClientSvc     ..> MsgObs       : async responses

NodeIface     <|..  NodeSvc   : implements
NodeSvc        --> NodeSeqSvc  : submit tx, poll blocks
NodeSvc        --> NodeState   : read/write state

NodeSeqSvc    --> SeqIface     : gRPC  [broadcast / deliverBlock / ...]

SeqIface      <|..  SeqSvc    : implements
SeqSvc         --> SeqState    : cut & deliver blocks

NODE           ..>  COMMON     : uses
SEQ            ..>  COMMON     : uses
CLIENT         ..>  COMMON     : uses

@enduml
```


---

## Key Data Flow — Write Operation (e.g. Transfer)

```plantuml
@startuml BlockchainIST_WriteFlow
!theme plain
skinparam defaultFontName Segoe UI

title Write Operation Flow (Transfer / CreateWallet / DeleteWallet)

actor User
participant "ClientNodeService\n+ RetryObserver" as C
participant "NodeServiceImpl" as N
participant "NodeSequencerService" as NS
participant "SequencerServiceImpl" as S
participant "SequencerState" as SS
participant "NodeState" as ND

User -> C : transfer(srcUser, srcWallet, dstWallet, amount)
C -> N : gRPC Transfer(TransferRequest)
activate N

alt node is still syncing
  N -> N : ensureReady() throws IllegalStateException
  N --> C : Status.UNAVAILABLE\n"Node is syncing, try again later"
  deactivate N
  note over C : RetryObserver catches UNAVAILABLE\nretries request on a different node
else node is ready
  N -> NS : broadcast(tx)
  NS -> S : gRPC Broadcast(BroadcastRequest)
  S -> SS : broadcast(tx) → assigns seqNum
  SS --> S : seqNum
  S --> NS : BroadcastResponse(seqNum)
  NS --> N : BroadcastResponse

  N -> ND : waitForSequence(seqNum)\n[blocks until heartbeat delivers block]

  note right of ND
    Background Heartbeat Thread:
    DeliverBlock(next) → applyBlock()
    notifies waiting threads
  end note

  ND --> N : (resumes — tx applied)
  N --> C : TransferResponse
  deactivate N
  C --> User : success / error
end

@enduml
```

---

## Key Data Flow — Read Operation (ReadBalance / GetBlockchainState)

```plantuml
@startuml BlockchainIST_ReadFlow
!theme plain
skinparam defaultFontName Segoe UI

title Read Operation Flow (ReadBalance / GetBlockchainState)

actor User
participant "ClientNodeService\n+ RetryObserver" as C
participant "NodeServiceImpl" as N
participant "NodeState" as ND

User -> C : readBalance(walletId)
C -> N : gRPC ReadBalance(ReadBalanceRequest)
activate N

alt node is still syncing
  N -> N : ensureReady() throws IllegalStateException
  N --> C : Status.UNAVAILABLE\n"Node is syncing, try again later"
  deactivate N
  note over C : RetryObserver catches UNAVAILABLE\nretries request on a different node
else node is ready
  N -> ND : readBalance(walletId)
  ND --> N : balance
  N --> C : ReadBalanceResponse(balance)
  deactivate N
  C --> User : balance
end

@enduml
```

---

## Key Data Flow — Node Startup Sync

```plantuml
@startuml BlockchainIST_SyncFlow
!theme plain
skinparam defaultFontName Segoe UI

title Node Startup — Sync with Sequencer

participant "Heartbeat Thread\n(NodeServiceImpl)" as HB
participant "SequencerServiceImpl" as S
participant "SequencerState" as SS
participant "NodeState" as ND

note over HB : Node starts → syncing=true

loop until syncing=false
  HB -> S : gRPC lastBlockNumber()
  S -> SS : getLastBlockNumber()
  SS --> S : sequencerCurrent
  S --> HB : LastBlockNumberResponse(sequencerCurrent)

  alt next > sequencerCurrent
    note over HB : Fully synced!\nsyncing=false\nNode now accepts client requests
  else next <= sequencerCurrent
    HB -> S : gRPC deliverBlock(next)
    S -> SS : deliverBlock(next) [waits if not ready]
    SS --> S : block (list of tx)
    S --> HB : DeliverBlockResponse
    HB -> ND : applyBlock(block)
  end
end

@enduml
```

---

## Block Cutting Flow (Sequencer)

```plantuml
@startuml BlockchainIST_BlockCut
!theme plain
skinparam defaultFontName Segoe UI

title Sequencer — Block Cutting (size-triggered vs timer-triggered)

participant "NodeServiceImpl\n(any node)" as N
participant "SequencerServiceImpl" as S
participant "SequencerState" as SS
participant "ScheduledExecutorService\n(timer)" as Timer

== Size-triggered cut ==

N -> S : gRPC Broadcast(tx)
S -> SS : broadcast(tx)
activate SS
SS -> SS : pendingBlock.add(tx)\nglobalSequenceNumber++
SS -> SS : pendingBlock.size() >= maxBlockTransactions ?
SS -> SS : cutBlock()\npendingBlock → blocks\npendingBlock.clear()\nnotifyAll()
SS -> Timer : resetTimer()
SS --> S : seqNum
deactivate SS
S --> N : BroadcastResponse(seqNum)

== Timer-triggered cut (no new transactions) ==

Timer -> SS : scheduleAtFixedRate fires\n(maxBlockTimeout elapsed)
activate SS
SS -> SS : pendingBlock.isEmpty() ?
note right : if not empty → cut\nif empty → skip
SS -> SS : cutBlock()\npendingBlock → blocks\npendingBlock.clear()\nnotifyAll()
SS -> Timer : resetTimer()
deactivate SS

@enduml
```

---

## Node State Machine

```plantuml
@startuml BlockchainIST_NodeStateMachine
!theme plain
skinparam defaultFontName Segoe UI

title Node — Lifecycle State Machine

[*] --> SYNCING : NodeMain starts\nsequencer connection established\nheartbeat thread launched

SYNCING : Rejects all client requests\nwith Status.UNAVAILABLE
SYNCING : Heartbeat fetches blocks from sequencer\nand applies them to NodeState

SYNCING --> READY : next > sequencerCurrent\n(node has caught up)

READY : Accepts client requests\n(createWallet / deleteWallet / transfer / readBalance)
READY : Heartbeat continues polling\nsequencer for new blocks

READY --> SYNCING : [not implemented —\nnode does not re-enter syncing\nonce ready]

note right of READY
  Client requests block on
  waitForSequence() until the
  heartbeat delivers their tx's block
end note

@enduml
```

---

## Duplicate Transaction Handling

```plantuml
@startuml BlockchainIST_Duplicate
!theme plain
skinparam defaultFontName Segoe UI

title Idempotency — Duplicate Transaction Detection

participant "Client\n(RetryObserver)" as C
participant "NodeServiceImpl" as N
participant "SequencerState" as SS
participant "NodeState" as ND

== Original request ==

C -> N : gRPC Transfer(tx1)
activate N
N -> SS : broadcast(tx1)
SS --> N : returns seqNum=5
N -> ND : waitForSequence(5, tx1Id)

note over ND : applyBlock() called by heartbeat:\n  executionErrors.containsKey(tx1Id)? → false\n  execute tx normally\n  executionErrors.put(tx1Id, null)  [null = success]\n  notifyAll()

ND --> N : resumes (tx1Id → null error)
N --> C : success response
deactivate N

== Duplicate request (same txId, different seqNum) ==

C -> N : gRPC Transfer(tx1)\n(duplicate after retry)
activate N
N -> SS : broadcast(tx1)
SS --> N : returns seqNum=9
N -> ND : waitForSequence(9, tx1Id)

note over ND : applyBlock() called by heartbeat:\n  executionErrors.containsKey(tx1Id)? → TRUE\n  throws DuplicateException\n  retrieves stored result for tx1Id\n  executionErrors.put(tx1Id, <same result>)\n  notifyAll()

ND --> N : resumes (tx1Id → stored result)

alt stored result was null (original succeeded)
  N --> C : success response
else stored result was an Exception
  N --> C : same error as original
end
deactivate N

@enduml
```

---

---

## Module Responsibilities Summary

| Module | Role |
|--------|------|
| **client** | CLI front-end; sends gRPC requests to a node; retries on node failure via `RetryObserver` |
| **node** | Mediates client ↔ sequencer; holds local blockchain state; background heartbeat fetches ordered blocks from sequencer |
| **sequencer** | Total-order broadcast: batches transactions into blocks (by count or timeout), assigns global sequence numbers, lets nodes poll blocks |
| **common** | Shared domain model ([Transaction](file:///d:/projects/uni/SD/T52-BlockchainIST-2026/contract/src/main/proto/common.proto#30-38)), proto↔domain conversion (`Utils`), idempotency helper (`DuplicateException`) |
| **contract** | Protobuf definitions for all gRPC services and messages ([NodeService](file:///d:/projects/uni/SD/T52-BlockchainIST-2026/contract/src/main/proto/client-node.proto#33-49), [SequencerService](file:///d:/projects/uni/SD/T52-BlockchainIST-2026/contract/src/main/proto/node-sequencer.proto#35-45), [Transaction](file:///d:/projects/uni/SD/T52-BlockchainIST-2026/contract/src/main/proto/common.proto#30-38), etc.) |

---

## NodeState Internals

| Field | Type | Purpose |
|-------|------|---------|
| `wallets` | `Map<walletId, Wallet(ownerId, balance)>` | Live wallet registry; starts with a `bc` bank wallet seeded with 1000 |
| `ledger` | `List<Transaction>` | Append-only log of all applied transactions |
| `executionErrors` | `Map<txId, Exception>` | Stores per-tx failures so duplicate requests get the same error back |
| `currentSequence` | `int` | Highest applied sequence number; threads [wait()](file:///d:/projects/uni/SD/T52-BlockchainIST-2026/node/src/main/java/pt/tecnico/blockchainist/node/domain/NodeState.java#199-219) on this |
| `currentBlock` | `int` | Highest applied block number; used to poll sequencer |

## SequencerState Internals

| Field | Type | Purpose |
|-------|------|---------|
| `blocks` | `List<List<Transaction>>` | Immutable ordered block store |
| `pendingBlock` | `List<Transaction>` | Accumulates incoming tx until cut |
| `globalSequenceNumber` | `int` | Monotonically increasing counter across all tx |
| `scheduler` | `ScheduledExecutorService` | Fires a timer-based block cut when `maxBlockTimeout` elapses |
