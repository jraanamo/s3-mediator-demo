

# Architecture 26 - Demo

A demonstrative project for detaching client communication from a backend server via a mediated, asynchronous architecture.

Architectural and system level decisions are recorded under @docs/decision/INDEX.md
Feature specifications are recorded under @docs/spec

## Background

A client application needs configuration data and a catalog of items to operate (consumes). As users interact with the application, it produces structured transaction records (receipts) that must be synchronized back to the server for reporting and analysis purposes (produces).

The goals this demo, by detaching the clients from direct communication with the server, is to demonstrate a model that:
- is highly fault tolerant
- is not impacted by peak work loads
- outsources critical uptime/load -sensitive parts
- is easy and cost efficient to scale
- is highly performant even under high loads
- uses trustworthy Nordic providers


## The Demonstrated Architecture

### Architectural Components

#### Backend

The server component that manages configuration, item catalog data, and processes incoming records from clients. This is also where the application monitors and coordinates fleet activity.

For the sake of this demo, the backend will be implemented as a Node application under the subdirectory: backend.

#### Client

An application that:
a) consumes configuration and item catalog data
b) produces transaction records (receipts) and logging data

For the sake of this demo, the client will be implemented as a Java application under the subdirectory: client.

#### Mediator Service

The mediator service is the component in this architecture that (mostly) detaches the direct communication between the client and the server. Some communication is still required to kick things off.

For the sake of this demo, this mediator service will be implmented by a S3 compatible object storage from UpCould.

## Demo development model

### SDD

This demo will be generated using AI agents (you) and using SDD (Spec Driven Development). The specs are written under docs/spec. 

Since this is a demo project, we never need to worry about existing clients or backwards compatibility. Everything can be deleted and rewritten.

#### Decisions

Every architectural *decision* we make during the process needs to be stored under /docs/decision/<decision-name>.md and an INDEX.md under /docs/decision needs to be updated

