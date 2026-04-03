# DVRMS - Distributed Vehicle Reservation Management System
## COMP 6231 Winter 2026 — Project 2

Fault-tolerant and highly available distributed vehicle reservation system using active replication with CORBA + UDP.

## Team
| Member | Module | Student ID |
|---|---|---|
| Hugo Xu | Front End (FE) | 40064100 |
| Derin Akay | Sequencer | 40294984 |
| Titouan Sablé | Replica Manager (RM) | 40179062 |
| Thach Pham | Test Cases | 40351367 |

## Project Structure
```
dvrms-project/
├── src/
│   ├── main/java/com/dvrms/
│   │   ├── common/             # Shared config, constants, message formats
│   │   │   └── common.Config.java
│   │   ├── frontend/           # Hugo — FE (CORBA server + UDP client)
│   │   ├── sequencer/          # Derin — Sequencer (UDP server)
│   │   │   ├── Sequencer.java
│   │   │   ├── ReplicaTarget.java
│   │   │   ├── ReliableUDPSender.java
│   │   │   └── RMNotifier.java
│   │   ├── replicamanager/     # Titouan — RM (failure detection + recovery)
│   │   └── replica/            # Each member's replica implementation
│   └── test/java/com/dvrms/
│       └── sequencer/
│           └── SequencerTest.java
├── README.md
└── .gitignore
```

## How to Build & Run

### Prerequisites
- Java 8+ (with CORBA support, or use GlassFish ORB for Java 11+)

### Compile
```bash
javac -d out src/main/java/com/dvrms/common/*.java src/main/java/com/dvrms/sequencer/*.java src/test/java/com/dvrms/sequencer/*.java
```

### Run the Sequencer
```bash
java -cp out com.dvrms.sequencer.Sequencer
```

### Run the Sequencer Test (in a separate terminal)
```bash
java -cp out com.dvrms.sequencer.SequencerTest
```

## Communication Protocol

All inter-component communication uses **UDP** with pipe-delimited ASCII messages.

### Message Formats
| Message | Direction | Format |
|---|---|---|
| CLIENT_REQUEST | FE → Sequencer | `SEQ_REQ\|<msgID>\|<feHost>\|<fePort>\|<method>\|<args...>` |
| SEQUENCED_REQUEST | Sequencer → Replicas | `REQ\|<msgID>\|<seqNum>\|<feHost>\|<fePort>\|<method>\|<args...>` |
| ACK | Replica → Sequencer | `ACK\|<msgID>` |
| UPDATE_TARGETS | RM → Sequencer | `UPDATE\|<oldReplicaID>\|<newHost>\|<newPort>` |
| FAULT_REPORT | FE → RM | `FAULT\|<replicaID>\|<seqNum>` |
| CRASH_SUSPECT | FE/Sequencer → RM | `CRASH\|<replicaID>` |
| RESULT | Replica → FE | `RESULT\|<requestID>\|<replicaID>\|<result>` |

### Reliable UDP
- ACK-based retransmission: 500ms timeout, 5 retries per replica
- Per-replica independent channels (slow replica doesn't block others)
- Duplicate detection at replicas via sequence number

## Port Assignments
| Component | Port |
|---|---|
| Sequencer | 5000 |
| Front End | 5001 |
| Replica 1 | 6001 |
| Replica 2 | 6002 |
| Replica 3 | 6003 |
| Replica 4 | 6004 |
| RM 1 | 7001 |
| RM 2 | 7002 |
| RM 3 | 7003 |
| RM 4 | 7004 |
