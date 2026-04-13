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

For the full DVRMS stack in this repository, use a Java 8 runtime with CORBA support. If you have multiple JDKs installed, point `JAVA8_HOME` to your Java 8 installation before running the stack scripts.

### Compile
```bash
javac -d out src/main/java/com/dvrms/common/*.java src/main/java/com/dvrms/sequencer/*.java src/test/java/com/dvrms/sequencer/*.java
```

### Run the Full Stack
Use the helper script to compile the stack into `out/stack` and start:
- `orbd`
- Sequencer
- Replica Managers `RM1` to `RM4`
- replicas `R1` to `R4`
- Front End CORBA server

```bash
JAVA8_HOME="/path/to/java8" ./scripts/run-stack.sh
```

Example:
```bash
JAVA8_HOME="/Users/thachpham/Library/Java/JavaVirtualMachines/corretto-1.8.0_482/Contents/Home" ./scripts/run-stack.sh
```

Logs are written to:
```bash
out/stack-logs
```

The PID file used by the stop script is:
```bash
out/stack-logs/pids.txt
```

### Run the Interactive CLI
After the stack is running, start the interactive front-end CLI with:

```bash
java -Dorb.host=localhost -Dorb.port=1050 -cp out/stack com.dvrms.frontend.FrontEndCLI
```

If you are using a specific Java 8 installation, run:

```bash
"$JAVA8_HOME/bin/java" -Dorb.host=localhost -Dorb.port=1050 -cp out/stack com.dvrms.frontend.FrontEndCLI
```

### Stop the Full Stack
Use:

```bash
./scripts/stop-stack.sh
```

The stop script:
- kills PIDs recorded by `run-stack.sh`
- scans the known stack ports and kills those processes too
- checks known DVRMS Java process names
- escalates to `SIGKILL` if needed

### If Processes Are Still Left Running
If a process still holds a stack port after `./scripts/stop-stack.sh`, find it and kill it manually.

Check a specific port such as the Front End on `5001`:

```bash
lsof -nP -i :5001
```

Get only the PID:

```bash
lsof -ti :5001
```

Force-kill it:

```bash
kill -9 <PID>
```

You can use the same pattern for other ports, for example:

```bash
lsof -nP -i :5000
lsof -nP -i :6001
lsof -nP -i :7001
```

### Run the Sequencer
```bash
java -cp out com.dvrms.sequencer.Sequencer
```

### Run the Sequencer Test (in a separate terminal)
```bash
java -cp out com.dvrms.sequencer.SequencerTest
```

### Run the Sequencer Test with the helper script
```bash
./scripts/run-sequencer-test.sh
```

This script:
- compiles the Sequencer test harness into `out/sequencer-test`
- runs `com.dvrms.sequencer.SequencerTest`
- expects to bind local UDP ports `5000`, `6001`-`6004`, and `7001`-`7004`

If those ports are already in use, stop the conflicting process before rerunning the script.

## Communication Protocol

All inter-component communication uses **UDP** with pipe-delimited ASCII messages.

### Message Formats
| Message | Direction | Format |
|---|---|---|
| CLIENT_REQUEST | FE → Sequencer | `SEQ_REQ\|<msgID>\|<feHost>\|<fePort>\|<method>\|<args...>` |
| SEQUENCED_REQUEST | Sequencer → Replicas | `REQ\|<msgID>\|<seqNum>\|<feHost>\|<fePort>\|<method>\|<args...>` |
| ACK | Replica → Sequencer | `ACK\|<replicaID>\|<msgID>` |
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
